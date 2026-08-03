package mccriu.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;

/**
 * Captures every live OpenGL object in the current context, and replays it into
 * a freshly created context <em>under the same object names</em>.
 *
 * <p>Name preservation is the whole trick. Minecraft, and every mod loaded into
 * it, stores GL objects as bare {@code int} handles scattered across the Java
 * heap — {@code AbstractTexture.id}, {@code ShaderInstance.programId}, Sodium's
 * arena buffers, a shader pack's framebuffer set. CRIU preserves that heap
 * perfectly. So if the rebuilt context hands back the same names, every one of
 * those handles is still valid and nothing above this layer needs to know the
 * context was ever destroyed. No resource-pack reload, no reload listeners, no
 * per-mod knowledge.
 *
 * <p>If a name cannot be reclaimed, this class throws. It does not renumber and
 * hope: a texture handle pointing at the wrong texture renders wrong instead of
 * crashing, and wrong-but-running is the one outcome worth avoiding.
 *
 * <p>Contents live in off-heap buffers so a multi-hundred-megabyte texture set
 * does not have to fit through the Java heap. CRIU dumps anonymous memory either
 * way; malloc just keeps GC out of it.
 */
public final class GlSnapshot {

    // ---------------------------------------------------------------- config

    public static final class Config {
        /**
         * Highest GL name probed in any single name space.
         *
         * The whole space up to here is scanned; there is no early exit on a
         * gap, because a gap does not mean the end of the name space and
         * assuming it did lost objects silently. See {@link #scanNames}.
         */
        public int nameCeiling = Integer.getInteger("mccriu.nameCeiling", 1 << 18);
        /** Read renderbuffer contents back by blitting through a scratch FBO. */
        public boolean captureRenderbufferContents = false;
    }

    // --------------------------------------------------------------- records

    static final class TexLevel {
        int level, width, height, depth, internalFormat;
        boolean compressed;
        int compressedSize;
        ByteBuffer data;
    }

    static final class TextureRec {
        int name, target;
        boolean immutable;
        int immutableLevels;
        final List<TexLevel> levels = new ArrayList<>();
        final Map<Integer, Integer> iparams = new LinkedHashMap<>();
        float[] borderColor;
        float[] fparams; // MIN_LOD, MAX_LOD, LOD_BIAS
    }

    static final class BufferRec {
        int name;
        long size;
        int usage;
        boolean immutable;
        int storageFlags;
        ByteBuffer data;
    }

    static final class ShaderRec {
        int name, type;
        String source;
    }

    static final class ProgramRec {
        int name;
        boolean linked;
        int binaryFormat;
        ByteBuffer binary;
        final List<Integer> attachedShaders = new ArrayList<>();
        final Map<String, Integer> attribLocations = new LinkedHashMap<>();
        final Map<String, Integer> uniformBlockBindings = new LinkedHashMap<>();
        List<GlUniforms.Uniform> uniforms = List.of();
        boolean separable;
        boolean binaryRetrievable;
    }

    static final class RenderbufferRec {
        int name, width, height, internalFormat, samples;
    }

    static final class FbAttachment {
        int point, objectType, objectName, textureLevel, textureLayer, cubeFace;
    }

    static final class FramebufferRec {
        int name;
        final List<FbAttachment> attachments = new ArrayList<>();
        int[] drawBuffers;
        int readBuffer;
    }

    static final class VaoAttrib {
        int index;
        boolean enabled, normalized, integer;
        int size, type, stride, bufferBinding, divisor;
        long pointer;
    }

    static final class VaoRec {
        int name;
        int elementArrayBuffer;
        final List<VaoAttrib> attribs = new ArrayList<>();
    }

    static final class SamplerRec {
        int name;
        final Map<Integer, Integer> iparams = new LinkedHashMap<>();
        float[] borderColor;
    }

    /** The subset of context-wide state that Minecraft's GlStateManager shadows. */
    static final class GlobalState {
        int[] viewport = new int[4];
        int[] scissorBox = new int[4];
        float[] clearColor = new float[4];
        double clearDepth;
        int clearStencil;
        int activeTexture;
        int currentProgram, boundVao, drawFbo, readFbo;
        int arrayBuffer, elementArrayBuffer, uniformBuffer, pixelPackBuffer, pixelUnpackBuffer;
        int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, blendEqRgb, blendEqAlpha;
        float[] blendColor = new float[4];
        int depthFunc;
        boolean depthMask;
        double[] depthRange = new double[2];
        int cullFaceMode, frontFace;
        boolean[] colorMask = new boolean[4];
        int stencilFunc, stencilRef, stencilValueMask, stencilWriteMask;
        int stencilFail, stencilPassDepthFail, stencilPassDepthPass;
        int polygonModeFront;
        float polygonOffsetFactor, polygonOffsetUnits;
        float lineWidth;
        int packAlignment, unpackAlignment;
        final Map<Integer, Boolean> caps = new LinkedHashMap<>();
        int[] textureBindings2D;
        int[] samplerBindings;
    }

    // ------------------------------------------------------------ the object

    /** LWJGL 3.3.3 does not expose the BLEND_COLOR query token, only glBlendColor. */
    private static final int GL_BLEND_COLOR = 0x8005;

    private final Config cfg;
    private final GLCapabilities caps;
    private final List<String> warnings = new ArrayList<>();
    /** Who rendered the state being captured. Compared against the rebuilt context. */
    private ContextId capturedFrom;
    /**
     * Capabilities of the context being replayed INTO, which is not the one this
     * object was constructed against: `caps` belongs to the context that was torn
     * down. Set by checkRebuiltContext before any replay call looks at it.
     */
    private GLCapabilities restoreCaps;
    /** Highest live name seen per name space, so a report shows how far the scan got. */
    private final Map<String, Integer> scanHighest = new LinkedHashMap<>();

    private final List<TextureRec> textures = new ArrayList<>();
    private final List<BufferRec> buffers = new ArrayList<>();
    private final List<ShaderRec> shaders = new ArrayList<>();
    private final List<ProgramRec> programs = new ArrayList<>();
    private final List<RenderbufferRec> renderbuffers = new ArrayList<>();
    private final List<FramebufferRec> framebuffers = new ArrayList<>();
    private final List<VaoRec> vaos = new ArrayList<>();
    private final List<SamplerRec> samplers = new ArrayList<>();
    private GlobalState global;

    private long capturedBytes;
    private long captureMillis, restoreMillis;
    private boolean usedProgramBinary;

    private GlSnapshot(Config cfg, GLCapabilities caps) {
        this.cfg = cfg;
        this.caps = caps;
    }

    // ---------------------------------------------------------------- public

    /** Must be called on the thread owning the context, with the context current. */
    public static GlSnapshot capture(Config cfg) {
        GLCapabilities caps = GL.getCapabilities();
        Objects.requireNonNull(caps, "no current GL context on this thread");
        GlSnapshot s = new GlSnapshot(cfg, caps);
        long t0 = System.nanoTime();
        s.doCapture();
        s.captureMillis = (System.nanoTime() - t0) / 1_000_000L;
        return s;
    }

    /** Must be called on a freshly created context, on its owning thread. */
    public void restore() {
        long t0 = System.nanoTime();
        checkRebuiltContext();
        doRestore();
        restoreMillis = (System.nanoTime() - t0) / 1_000_000L;
    }

    /**
     * Refuse to replay into a context that cannot take the state back.
     *
     * Without this the first missing entry point is a JNI FatalError from inside
     * LWJGL — "No context is current or a function that is not available in the
     * current context was called" — which aborts the JVM from native code. No
     * catch block runs, the coordinator's "a broken checkpoint is bad, a broken
     * game is worse" recovery never happens, and what the user sees is the game
     * vanishing with a native stack. Reported from an NVIDIA machine, at
     * glVertexAttribDivisor, after a REFUSED checkpoint: the audit had done its
     * job and declined, and the rebuild that was supposed to hand the game back
     * killed it instead.
     *
     * The pointers are read out of GLCapabilities rather than called, because
     * calling is the thing that cannot be recovered from. Every entry point the
     * replay uses is listed: LWJGL resolves each independently, so "GL 3.3 is
     * supported" is not the same claim as "these functions resolved" -- the
     * report above had glGenSamplers and glSamplerParameteri working in the very
     * same context that had no glVertexAttribDivisor.
     */
    private void checkRebuiltContext() {
        // Capabilities FIRST, and through the Java side, which throws something
        // catchable when none are installed. glGetString is itself a native
        // entry point on the same thread-local table, so reading the identity
        // before this check would abort the JVM in precisely the case this
        // method exists to report.
        GLCapabilities c;
        try {
            c = GL.getCapabilities();
        } catch (Throwable t) {
            throw new IllegalStateException("no GL capabilities are installed on this thread, so "
                    + "the rebuilt context is not usable: " + t
                    + "\nGL.createCapabilities() must run on the render thread after "
                    + "glfwMakeContextCurrent and before any GL call.", t);
        }
        if (c == null)
            throw new IllegalStateException("GL.getCapabilities() returned null on the render "
                    + "thread; the rebuilt context was never made current.");

        restoreCaps = c;
        ContextId now = ContextId.read(c);
        List<String> missing = new ArrayList<>();
        // name -> address, for exactly the calls doRestore() makes.
        if (c.glGenTextures == 0) missing.add("glGenTextures");
        if (c.glGenBuffers == 0) missing.add("glGenBuffers");
        if (c.glGenRenderbuffers == 0) missing.add("glGenRenderbuffers");
        if (c.glGenFramebuffers == 0) missing.add("glGenFramebuffers");
        if (c.glGenVertexArrays == 0) missing.add("glGenVertexArrays");
        if (c.glGenSamplers == 0) missing.add("glGenSamplers");
        if (c.glSamplerParameteri == 0) missing.add("glSamplerParameteri");
        if (c.glCreateShader == 0) missing.add("glCreateShader");
        if (c.glCreateProgram == 0) missing.add("glCreateProgram");
        if (c.glBindVertexArray == 0) missing.add("glBindVertexArray");
        if (c.glVertexAttribPointer == 0) missing.add("glVertexAttribPointer");
        if (c.glVertexAttribIPointer == 0) missing.add("glVertexAttribIPointer");
        // The one entry point with a documented alternative: ARB_instanced_arrays
        // is where glVertexAttribDivisor came from before 3.3 promoted it, and
        // LWJGL resolves the two names independently. Fatal only if BOTH are
        // absent -- see setVertexAttribDivisor.
        if (c.glVertexAttribDivisor == 0 && c.glVertexAttribDivisorARB == 0)
            missing.add("glVertexAttribDivisor (and its ARB_instanced_arrays alternative)");
        if (c.glEnableVertexAttribArray == 0) missing.add("glEnableVertexAttribArray");
        if (c.glBindFramebuffer == 0) missing.add("glBindFramebuffer");
        if (c.glFramebufferTexture2D == 0) missing.add("glFramebufferTexture2D");
        if (missing.isEmpty()) {
            if (capturedFrom != null && !capturedFrom.sameStack(now)) {
                warnings.add("the rebuilt context is not the one the state came from:\n"
                        + "        captured from " + capturedFrom + "\n"
                        + "        replaying into " + now);
            }
            return;
        }

        throw new IllegalStateException(
                "the rebuilt GL context is missing " + missing.size() + " entry point(s) the "
                + "replay needs, so the graphics state cannot be put back: " + String.join(", ", missing)
                + "\n  captured from:  " + capturedFrom
                + "\n  replaying into: " + now
                + "\n  OpenGL33=" + c.OpenGL33 + " OpenGL45=" + c.OpenGL45 + " OpenGL46=" + c.OpenGL46
                + "\n  (glVertexAttribDivisorARB=" + (c.glVertexAttribDivisorARB != 0 ? "present" : "absent")
                + ", ARB_instanced_arrays=" + c.GL_ARB_instanced_arrays + ")"
                + "\nThis is reported instead of called: calling a null entry point is a JNI "
                + "FatalError that kills the JVM outright, with no chance to say why.");
    }

    /** Vendor, renderer and version of a GL context: enough to tell two apart. */
    record ContextId(String vendor, String renderer, String version, String glsl) {
        /** Reads nothing unless glGetString itself resolved: it is a native call too. */
        static ContextId read(GLCapabilities c) {
            if (c == null || c.glGetString == 0)
                return new ContextId("?", "?", "unreadable (glGetString did not resolve)", "?");
            try {
                return new ContextId(
                        String.valueOf(GL11.glGetString(GL11.GL_VENDOR)),
                        String.valueOf(GL11.glGetString(GL11.GL_RENDERER)),
                        String.valueOf(GL11.glGetString(GL11.GL_VERSION)),
                        String.valueOf(GL20.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION)));
            } catch (Throwable t) {
                // Reading the identity must never be what breaks the rebuild.
                return new ContextId("?", "?", "unreadable (" + t + ")", "?");
            }
        }

        /** Same driver and version — the renderer string can differ by window. */
        boolean sameStack(ContextId o) {
            return o != null && Objects.equals(vendor, o.vendor) && Objects.equals(version, o.version);
        }

        @Override public String toString() {
            return vendor + " | " + renderer + " | GL " + version + " | GLSL " + glsl;
        }
    }

    /** Release the off-heap contents. Call after {@link #restore()}. */
    public void free() {
        for (TextureRec t : textures)
            for (TexLevel l : t.levels) if (l.data != null) MemoryUtil.memFree(l.data);
        for (BufferRec b : buffers) if (b.data != null) MemoryUtil.memFree(b.data);
        for (ProgramRec p : programs) if (p.binary != null) MemoryUtil.memFree(p.binary);
        textures.clear(); buffers.clear(); programs.clear();
        shaders.clear(); renderbuffers.clear(); framebuffers.clear();
        vaos.clear(); samplers.clear();
    }

    public List<String> warnings() { return warnings; }
    public long capturedBytes() { return capturedBytes; }
    public long captureMillis() { return captureMillis; }
    public long restoreMillis() { return restoreMillis; }

    public String summary() {
        return String.format(
                "textures=%d buffers=%d programs=%d shaders=%d fbos=%d rbos=%d vaos=%d samplers=%d "
                + "bytes=%.1fMiB captureMs=%d restoreMs=%d programBinary=%s warnings=%d",
                textures.size(), buffers.size(), programs.size(), shaders.size(),
                framebuffers.size(), renderbuffers.size(), vaos.size(), samplers.size(),
                capturedBytes / 1048576.0, captureMillis, restoreMillis,
                usedProgramBinary, warnings.size());
    }

    public Map<String, Object> toReport() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("textures", textures.size());
        m.put("buffers", buffers.size());
        m.put("programs", programs.size());
        m.put("shaders", shaders.size());
        m.put("framebuffers", framebuffers.size());
        m.put("renderbuffers", renderbuffers.size());
        m.put("vaos", vaos.size());
        m.put("samplers", samplers.size());
        // How far up each name space live objects were actually found. The
        // counts above say how many were captured; this says whether the scan
        // had to reach far to find them, which is what a silent truncation
        // would have hidden.
        m.put("highestName", scanHighest);
        m.put("nameCeiling", cfg.nameCeiling);
        m.put("capturedBytes", capturedBytes);
        m.put("captureMillis", captureMillis);
        m.put("restoreMillis", restoreMillis);
        m.put("usedProgramBinary", usedProgramBinary);
        // Which stack rendered this. A GL failure that only happens on one
        // driver is unreadable without it, and it was not recorded anywhere.
        if (capturedFrom != null) {
            m.put("glVendor", capturedFrom.vendor());
            m.put("glRenderer", capturedFrom.renderer());
            m.put("glVersion", capturedFrom.version());
            m.put("glslVersion", capturedFrom.glsl());
        }
        m.put("warnings", warnings);
        return m;
    }

    // --------------------------------------------------------------- capture

    private void doCapture() {
        capturedFrom = ContextId.read(caps);
        global = captureGlobalState();
        neutralisePixelStore();

        // Check after each phase rather than once at the end: a stray
        // GL_INVALID_ENUM tells us a query token is wrong, and knowing which
        // phase raised it is the difference between a one-line fix and a hunt.
        captureTextures();      checkGlError("capturing textures");
        captureBuffers();       checkGlError("capturing buffers");
        captureShaders();       checkGlError("capturing shaders");
        capturePrograms();      checkGlError("capturing programs");
        captureRenderbuffers(); checkGlError("capturing renderbuffers");
        captureFramebuffers();  checkGlError("capturing framebuffers");
        captureVaos();          checkGlError("capturing vertex arrays");
        captureSamplers();      checkGlError("capturing samplers");

        // Last, because it needs every captured set to exist.
        verifyBoundObjectsCaptured();
    }

    /**
     * glGetTexImage and glTexImage honour the pixel-store state and the pixel
     * buffer bindings. Leaving the application's values in place would silently
     * skew every readback, so both are neutralised for the duration and the
     * originals put back by the global-state restore.
     */
    private void neutralisePixelStore() {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL12.GL_PACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_PACK_SKIP_IMAGES, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
    }

    private interface LiveTest { boolean isLive(int name); }

    /**
     * Scan a GL name space for live objects.
     *
     * There is no "enumerate objects" call in OpenGL, so names are probed with
     * glIs* from 1 up to {@link Config#nameCeiling}.
     *
     * This used to stop early after a run of consecutive misses, on the theory
     * that a gap meant the end of the name space. It does not. A modpack that
     * creates and deletes enough objects leaves live names far above the first
     * gap — and stopping there dropped them **silently**, because the warning
     * below only fired in the opposite case, when the scan ran all the way to
     * the ceiling. Measured on All the Mods 10 (521 mods): live buffers at
     * 1-12, then nothing until 5311, which was the bound GL_ARRAY_BUFFER. The
     * old scan gave up around 4108, never captured it, and after the rebuild
     * its Java-side handle referred to a buffer that did not exist. Vertex data
     * written into it went nowhere, which is what made every string in the game
     * invisible while the widgets around them still drew.
     *
     * The heuristic is deleted rather than retuned: any gap size is a guess,
     * and a wrong guess is silent data loss. Probing the whole ceiling is a
     * client-side hash lookup per name and costs nothing measurable — capturing
     * that same 221-texture, 1 GiB client took 755 ms scanning in full against
     * 946 ms with the early exit.
     */
    private List<Integer> scanNames(LiveTest test, String what) {
        List<Integer> live = new ArrayList<>();
        int highest = 0;
        for (int n = 1; n <= cfg.nameCeiling; n++) {
            if (test.isLive(n)) { live.add(n); highest = n; }
        }
        scanHighest.put(what, highest);
        if (highest == cfg.nameCeiling) {
            warnings.add(what + ": a live object sits at the name ceiling " + cfg.nameCeiling
                    + ", so there may be more above it that were NOT captured and would come "
                    + "back empty. Raise -Dmccriu.nameCeiling.");
        }
        return live;
    }

    /**
     * Every object the context is *currently using* must have been captured.
     *
     * The scan is a probe, not an enumeration, so on its own it can only ever be
     * trusted as far as its ceiling. This turns that into something checkable:
     * the context state records the names actually bound at capture time, and a
     * bound name missing from the captured set is a **provable** miss — no
     * heuristic, no judgement. It is what turns "the scan found everything" from
     * an assumption into an assertion, and it is the check that would have
     * caught the ATM10 buffer immediately instead of after a day of bisecting.
     */
    private void verifyBoundObjectsCaptured() {
        Set<Integer> tex = names(textures.stream().map(t -> t.name).toList());
        Set<Integer> buf = names(buffers.stream().map(b -> b.name).toList());
        Set<Integer> fbo = names(framebuffers.stream().map(f -> f.name).toList());
        Set<Integer> prog = names(programs.stream().map(p -> p.name).toList());
        Set<Integer> vao = names(vaos.stream().map(v -> v.name).toList());
        Set<Integer> smp = names(samplers.stream().map(s -> s.name).toList());

        boundOk("buffer", "GL_ARRAY_BUFFER", global.arrayBuffer, buf);
        boundOk("buffer", "GL_ELEMENT_ARRAY_BUFFER", global.elementArrayBuffer, buf);
        boundOk("buffer", "GL_UNIFORM_BUFFER", global.uniformBuffer, buf);
        boundOk("buffer", "GL_PIXEL_PACK_BUFFER", global.pixelPackBuffer, buf);
        boundOk("buffer", "GL_PIXEL_UNPACK_BUFFER", global.pixelUnpackBuffer, buf);
        boundOk("program", "GL_CURRENT_PROGRAM", global.currentProgram, prog);
        boundOk("vertex array", "GL_VERTEX_ARRAY_BINDING", global.boundVao, vao);
        boundOk("framebuffer", "GL_DRAW_FRAMEBUFFER", global.drawFbo, fbo);
        boundOk("framebuffer", "GL_READ_FRAMEBUFFER", global.readFbo, fbo);
        for (int i = 0; i < global.textureBindings2D.length; i++) {
            boundOk("texture", "GL_TEXTURE" + i + " GL_TEXTURE_2D", global.textureBindings2D[i], tex);
            boundOk("sampler", "sampler on unit " + i, global.samplerBindings[i], smp);
        }
    }

    private static Set<Integer> names(List<Integer> l) { return new java.util.HashSet<>(l); }

    private void boundOk(String kind, String where, int name, Set<Integer> captured) {
        if (name == 0 || captured.contains(name)) return;
        warnings.add(String.format(
                "%s %d is bound at %s but was NOT captured, so after restore that handle refers "
                + "to an object that does not exist and everything drawn through it silently "
                + "produces nothing. The name scan did not reach it (highest %s name seen: %d, "
                + "ceiling %d). Raise -Dmccriu.nameCeiling.",
                kind, name, where, kind, scanHighest.getOrDefault(pluralFor(kind), 0),
                cfg.nameCeiling));
    }

    /** scanNames() labels; kept in one place so the warning can quote the right one. */
    private static String pluralFor(String kind) {
        return switch (kind) {
            case "buffer" -> "buffers";
            case "texture" -> "textures";
            case "program" -> "programs";
            case "framebuffer" -> "framebuffers";
            case "vertex array" -> "vertex arrays";
            case "sampler" -> "samplers";
            default -> kind;
        };
    }

    private void captureTextures() {
        boolean dsa = caps.OpenGL45 || caps.GL_ARB_direct_state_access;
        for (int name : scanNames(GL11::glIsTexture, "textures")) {
            TextureRec r = new TextureRec();
            r.name = name;
            r.target = dsa ? GL45.glGetTextureParameteri(name, GL45.GL_TEXTURE_TARGET)
                           : probeTextureTarget(name);
            if (r.target == 0) {
                warnings.add("texture " + name + ": could not determine target; skipped");
                continue;
            }
            GL11.glBindTexture(r.target, name);

            // IMMUTABLE_FORMAT is a texture parameter, not a level parameter;
            // asking glGetTexLevelParameteri for it raises GL_INVALID_ENUM.
            r.immutable = GL11.glGetTexParameteri(r.target,
                    GL42.GL_TEXTURE_IMMUTABLE_FORMAT) != 0;
            if (r.immutable)
                r.immutableLevels = GL11.glGetTexParameteri(r.target, GL43.GL_TEXTURE_IMMUTABLE_LEVELS);

            for (int pname : new int[]{
                    GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_TEXTURE_MAG_FILTER,
                    GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T, GL12.GL_TEXTURE_WRAP_R,
                    GL12.GL_TEXTURE_BASE_LEVEL, GL12.GL_TEXTURE_MAX_LEVEL,
                    GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_TEXTURE_COMPARE_FUNC,
                    GL33.GL_TEXTURE_SWIZZLE_R, GL33.GL_TEXTURE_SWIZZLE_G,
                    GL33.GL_TEXTURE_SWIZZLE_B, GL33.GL_TEXTURE_SWIZZLE_A}) {
                r.iparams.put(pname, GL11.glGetTexParameteri(r.target, pname));
            }
            r.fparams = new float[]{
                    GL11.glGetTexParameterf(r.target, GL12.GL_TEXTURE_MIN_LOD),
                    GL11.glGetTexParameterf(r.target, GL12.GL_TEXTURE_MAX_LOD),
                    GL11.glGetTexParameterf(r.target, GL14.GL_TEXTURE_LOD_BIAS)};
            r.borderColor = new float[4];
            GL11.glGetTexParameterfv(r.target, GL11.GL_TEXTURE_BORDER_COLOR, r.borderColor);

            captureTextureLevels(r);

            // Attribute GL errors to the texture that caused them. An
            // unattributed error at the end of the whole phase is nearly
            // useless; knowing it was texture 47, a 2D_ARRAY, is actionable —
            // especially on hardware I cannot test here.
            int err;
            while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR) {
                warnings.add(String.format(
                        "GL error 0x%04X while capturing texture %d (target 0x%04X, %d level(s))",
                        err, r.name, r.target, r.levels.size()));
            }
            if (r.levels.isEmpty()) {
                warnings.add("texture " + r.name + " (target 0x"
                        + Integer.toHexString(r.target) + ") yielded no levels; it will be "
                        + "restored with no contents. Report this — it means a live texture's "
                        + "pixels were not read back.");
            }
            textures.add(r);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void captureTextureLevels(TextureRec r) {
        if (r.target == GL31.GL_TEXTURE_BUFFER) {
            warnings.add("texture " + r.name + ": TEXTURE_BUFFER; storage lives in its buffer object");
            return;
        }
        boolean multisample = r.target == GL32.GL_TEXTURE_2D_MULTISAMPLE
                || r.target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
        if (multisample) {
            warnings.add("texture " + r.name
                    + ": multisample texture contents cannot be read back with glGetTexImage; "
                    + "storage recreated, contents NOT preserved");
        }

        // Start at BASE_LEVEL, not 0: a texture whose base level is non-zero has
        // no image at level 0, so starting from 0 would see width 0, stop
        // immediately, and restore the texture with no contents at all — black,
        // silently. Stop at log2(MAX_TEXTURE_SIZE), beyond which the level
        // queries themselves raise GL_INVALID_VALUE.
        int baseLevel = GL11.glGetTexParameteri(r.target, GL12.GL_TEXTURE_BASE_LEVEL);
        int maxLevel = GL11.glGetTexParameteri(r.target, GL12.GL_TEXTURE_MAX_LEVEL);
        int maxTexSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        int levelCeiling = Math.min(maxLevel, (int) (Math.log(maxTexSize) / Math.log(2)));
        for (int level = baseLevel; level <= levelCeiling; level++) {
            int queryTarget = r.target == GL13.GL_TEXTURE_CUBE_MAP
                    ? GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X : r.target;
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* start each level clean */ }
            int w = GL11.glGetTexLevelParameteri(queryTarget, level, GL11.GL_TEXTURE_WIDTH);
            int queryErr = GL11.glGetError();
            if (queryErr != GL11.GL_NO_ERROR) {
                // The level does not exist; the query, not the readback, failed.
                // Harmless — but it must not be mistaken for a failed readback.
                break;
            }
            if (w <= 0) break;
            int h = GL11.glGetTexLevelParameteri(queryTarget, level, GL11.GL_TEXTURE_HEIGHT);
            int d = GL11.glGetTexLevelParameteri(queryTarget, level, GL12.GL_TEXTURE_DEPTH);
            int ifmt = GL11.glGetTexLevelParameteri(queryTarget, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            boolean compressed = GL11.glGetTexLevelParameteri(queryTarget, level,
                    GL13.GL_TEXTURE_COMPRESSED) != 0;

            TexLevel l = new TexLevel();
            l.level = level; l.width = w; l.height = h; l.depth = d;
            l.internalFormat = ifmt; l.compressed = compressed;

            if (multisample) { r.levels.add(l); continue; }

            if (compressed) {
                l.compressedSize = GL11.glGetTexLevelParameteri(queryTarget, level,
                        GL13.GL_TEXTURE_COMPRESSED_IMAGE_SIZE);
                if (r.target == GL13.GL_TEXTURE_CUBE_MAP) {
                    warnings.add("texture " + r.name + ": compressed cube map faces beyond +X "
                            + "are not captured");
                }
                l.data = MemoryUtil.memAlloc(Math.max(l.compressedSize, 1));
                GL13.glGetCompressedTexImage(queryTarget, level, l.data);
                capturedBytes += l.compressedSize;
            } else {
                GlFormats.Transfer tr = GlFormats.transferFor(ifmt);
                long bytes = GlFormats.levelBytes(ifmt, w, h, Math.max(d, 1));
                if (r.target == GL13.GL_TEXTURE_CUBE_MAP) bytes *= 6;
                if (bytes > Integer.MAX_VALUE)
                    throw new IllegalStateException("texture " + r.name + " level " + level
                            + " is " + bytes + " bytes, too large for a single ByteBuffer");
                l.data = MemoryUtil.memAlloc((int) bytes);
                if (r.target == GL13.GL_TEXTURE_CUBE_MAP) {
                    long faceBytes = bytes / 6;
                    for (int f = 0; f < 6; f++) {
                        l.data.position((int) (f * faceBytes));
                        ByteBuffer slice = l.data.slice();
                        slice.limit((int) faceBytes);
                        GL11.glGetTexImage(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, level,
                                tr.format(), tr.type(), slice);
                    }
                    l.data.position(0);
                } else {
                    GL11.glGetTexImage(r.target, level, tr.format(), tr.type(), l.data);
                }
                // A failed readback leaves the buffer holding whatever malloc
                // returned, and the texture would come back as garbage with no
                // other symptom. That must be loud.
                int readErr = GL11.glGetError();
                if (readErr != GL11.GL_NO_ERROR) {
                    warnings.add(String.format(
                            "READBACK FAILED: texture %d level %d (%dx%dx%d, %s) returned GL "
                            + "error 0x%04X. Its contents are NOT captured and will be garbage "
                            + "after restore.",
                            r.name, level, w, h, Math.max(d, 1),
                            GlFormats.nameOf(ifmt), readErr));
                }
                capturedBytes += bytes;
            }
            r.levels.add(l);
        }
    }

    private int probeTextureTarget(int name) {
        int[] candidates = {
                GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_3D,
                GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_1D, GL30.GL_TEXTURE_1D_ARRAY,
                GL31.GL_TEXTURE_RECTANGLE, GL32.GL_TEXTURE_2D_MULTISAMPLE,
                GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY, GL31.GL_TEXTURE_BUFFER,
                GL40.GL_TEXTURE_CUBE_MAP_ARRAY};
        for (int t : candidates) {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
            GL11.glBindTexture(t, name);
            if (GL11.glGetError() == GL11.GL_NO_ERROR) return t;
        }
        return 0;
    }

    private void captureBuffers() {
        for (int name : scanNames(GL15::glIsBuffer, "buffers")) {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, name);
            BufferRec r = new BufferRec();
            r.name = name;
            r.size = GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER, GL15.GL_BUFFER_SIZE);
            r.usage = GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER, GL15.GL_BUFFER_USAGE);
            r.immutable = GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER,
                    GL44.GL_BUFFER_IMMUTABLE_STORAGE) != 0;
            r.storageFlags = GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER,
                    GL44.GL_BUFFER_STORAGE_FLAGS);

            boolean mapped = GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER,
                    GL15.GL_BUFFER_MAPPED) != 0;
            if (mapped) {
                // A persistently mapped buffer puts driver-owned pages in our
                // address space; CRIU cannot serialise those, and unmapping
                // behind the owner's back would dangle a pointer it still holds.
                throw new IllegalStateException("buffer " + name + " (" + r.size + " bytes) is "
                        + "currently mapped. A mapped buffer cannot be checkpointed: its pages "
                        + "belong to the GPU driver. Checkpoint from a state with no mapped "
                        + "buffers (the main menu has none).");
            }
            if (r.size > 0) {
                if (r.size > Integer.MAX_VALUE)
                    throw new IllegalStateException("buffer " + name + " is " + r.size + " bytes");
                r.data = MemoryUtil.memAlloc((int) r.size);
                GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, 0, r.data);
                capturedBytes += r.size;
            }
            buffers.add(r);
        }
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
    }

    private void captureShaders() {
        for (int name : scanNames(GL20::glIsShader, "shaders")) {
            ShaderRec r = new ShaderRec();
            r.name = name;
            r.type = GL20.glGetShaderi(name, GL20.GL_SHADER_TYPE);
            r.source = GL20.glGetShaderSource(name);
            shaders.add(r);
        }
    }

    private void capturePrograms() {
        boolean canBinary = (caps.OpenGL41 || caps.GL_ARB_get_program_binary)
                && GL11.glGetInteger(GL41.GL_NUM_PROGRAM_BINARY_FORMATS) > 0;
        if (!canBinary) {
            warnings.add("driver exposes no program binary formats; programs will be recompiled "
                    + "from source on restore");
        }
        for (int name : scanNames(GL20::glIsProgram, "programs")) {
            ProgramRec r = new ProgramRec();
            r.name = name;
            r.linked = GL20.glGetProgrami(name, GL20.GL_LINK_STATUS) != 0;
            r.separable = GL20.glGetProgrami(name, GL41.GL_PROGRAM_SEPARABLE) != 0;

            int[] shaderNames = new int[GL20.glGetProgrami(name, GL20.GL_ATTACHED_SHADERS)];
            if (shaderNames.length > 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer count = stack.mallocInt(1);
                    IntBuffer out = stack.mallocInt(shaderNames.length);
                    GL20.glGetAttachedShaders(name, count, out);
                    for (int i = 0; i < count.get(0); i++) r.attachedShaders.add(out.get(i));
                }
            }

            if (r.linked) {
                captureAttribLocations(name, r);
                captureUniformBlockBindings(name, r);
                r.uniforms = GlUniforms.capture(name);

                if (canBinary) {
                    GL41.glProgramParameteri(name, GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT,
                            GL11.GL_TRUE);
                    int len = GL20.glGetProgrami(name, GL41.GL_PROGRAM_BINARY_LENGTH);
                    if (len > 0) {
                        r.binary = MemoryUtil.memAlloc(len);
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            IntBuffer lenOut = stack.mallocInt(1);
                            IntBuffer fmtOut = stack.mallocInt(1);
                            GL41.glGetProgramBinary(name, lenOut, fmtOut, r.binary);
                            r.binaryFormat = fmtOut.get(0);
                            r.binary.limit(lenOut.get(0));
                        }
                        r.binaryRetrievable = true;
                        capturedBytes += len;
                        usedProgramBinary = true;
                    } else {
                        warnings.add("program " + name + ": PROGRAM_BINARY_LENGTH is 0 even though "
                                + "the driver advertises binary formats; will recompile from source");
                    }
                }
            }
            programs.add(r);
        }
    }

    private void captureAttribLocations(int program, ProgramRec r) {
        int n = GL20.glGetProgrami(program, GL20.GL_ACTIVE_ATTRIBUTES);
        for (int i = 0; i < n; i++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer size = stack.mallocInt(1);
                IntBuffer type = stack.mallocInt(1);
                String nm = GL20.glGetActiveAttrib(program, i, size, type);
                if (nm == null || nm.startsWith("gl_")) continue;
                int loc = GL20.glGetAttribLocation(program, nm);
                if (loc >= 0) r.attribLocations.put(nm, loc);
            }
        }
    }

    private void captureUniformBlockBindings(int program, ProgramRec r) {
        int n = GL20.glGetProgrami(program, GL31.GL_ACTIVE_UNIFORM_BLOCKS);
        for (int i = 0; i < n; i++) {
            String nm = GL31.glGetActiveUniformBlockName(program, i);
            int binding = GL31.glGetActiveUniformBlocki(program, i, GL31.GL_UNIFORM_BLOCK_BINDING);
            if (nm != null) r.uniformBlockBindings.put(nm, binding);
        }
    }

    private void captureRenderbuffers() {
        for (int name : scanNames(GL30::glIsRenderbuffer, "renderbuffers")) {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
            RenderbufferRec r = new RenderbufferRec();
            r.name = name;
            r.width = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
            r.height = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
            r.internalFormat = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER,
                    GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            r.samples = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES);
            renderbuffers.add(r);
        }
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        if (!renderbuffers.isEmpty()) {
            warnings.add(renderbuffers.size() + " renderbuffer(s): storage is recreated but pixel "
                    + "contents are NOT preserved (there is no glGetRenderbufferImage). These hold "
                    + "the previous frame's depth/colour, which is redrawn immediately.");
        }
    }

    private void captureFramebuffers() {
        int maxColour = GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS);
        for (int name : scanNames(GL30::glIsFramebuffer, "framebuffers")) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, name);
            FramebufferRec r = new FramebufferRec();
            r.name = name;

            List<Integer> points = new ArrayList<>();
            for (int i = 0; i < maxColour; i++) points.add(GL30.GL_COLOR_ATTACHMENT0 + i);
            points.add(GL30.GL_DEPTH_ATTACHMENT);
            points.add(GL30.GL_STENCIL_ATTACHMENT);

            for (int point : points) {
                int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, point,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                if (type == GL11.GL_NONE) continue;
                FbAttachment a = new FbAttachment();
                a.point = point;
                a.objectType = type;
                a.objectName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, point,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                if (type == GL11.GL_TEXTURE) {
                    a.textureLevel = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                            point, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
                    a.textureLayer = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                            point, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER);
                    a.cubeFace = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                            point, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE);
                }
                r.attachments.add(a);
            }

            int maxDraw = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
            r.drawBuffers = new int[maxDraw];
            for (int i = 0; i < maxDraw; i++)
                r.drawBuffers[i] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + i);
            r.readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            framebuffers.add(r);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void captureVaos() {
        int maxAttribs = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
        for (int name : scanNames(GL30::glIsVertexArray, "vertex arrays")) {
            GL30.glBindVertexArray(name);
            VaoRec r = new VaoRec();
            r.name = name;
            r.elementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            for (int i = 0; i < maxAttribs; i++) {
                VaoAttrib a = new VaoAttrib();
                a.index = i;
                a.enabled = GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0;
                a.bufferBinding = GL20.glGetVertexAttribi(i, GL15.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
                if (!a.enabled && a.bufferBinding == 0) continue;
                a.size = GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE);
                a.type = GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE);
                a.stride = GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE);
                a.normalized = GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED) != 0;
                a.integer = GL30.glGetVertexAttribi(i, GL30.GL_VERTEX_ATTRIB_ARRAY_INTEGER) != 0;
                a.divisor = GL33.glGetVertexAttribi(i, GL33.GL_VERTEX_ATTRIB_ARRAY_DIVISOR);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer pb = stack.mallocPointer(1);
                    GL20.glGetVertexAttribPointerv(i, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER, pb);
                    a.pointer = pb.get(0);
                }
                r.attribs.add(a);
            }
            vaos.add(r);
        }
        GL30.glBindVertexArray(0);
    }

    private void captureSamplers() {
        for (int name : scanNames(GL33::glIsSampler, "samplers")) {
            SamplerRec r = new SamplerRec();
            r.name = name;
            for (int pname : new int[]{
                    GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_TEXTURE_MAG_FILTER,
                    GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T, GL12.GL_TEXTURE_WRAP_R,
                    GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_TEXTURE_COMPARE_FUNC}) {
                r.iparams.put(pname, GL33.glGetSamplerParameteri(name, pname));
            }
            r.borderColor = new float[4];
            GL33.glGetSamplerParameterfv(name, GL11.GL_TEXTURE_BORDER_COLOR, r.borderColor);
            samplers.add(r);
        }
    }

    private static final int[] TRACKED_CAPS = {
            GL11.GL_BLEND, GL11.GL_CULL_FACE, GL11.GL_DEPTH_TEST, GL11.GL_DITHER,
            GL11.GL_POLYGON_OFFSET_FILL, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST,
            GL13.GL_MULTISAMPLE, GL13.GL_SAMPLE_ALPHA_TO_COVERAGE,
            GL30.GL_FRAMEBUFFER_SRGB, GL30.GL_RASTERIZER_DISCARD,
            GL31.GL_PRIMITIVE_RESTART, GL32.GL_DEPTH_CLAMP,
            GL11.GL_LINE_SMOOTH, GL11.GL_POLYGON_SMOOTH};

    private GlobalState captureGlobalState() {
        GlobalState g = new GlobalState();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, g.viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, g.scissorBox);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, g.clearColor);
        g.clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        g.clearStencil = GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE);
        g.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        g.currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        g.boundVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        g.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        g.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        g.arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        g.elementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        g.uniformBuffer = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
        g.pixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        g.pixelUnpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        g.blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        g.blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        g.blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        g.blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        g.blendEqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        g.blendEqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        GL11.glGetFloatv(GL_BLEND_COLOR, g.blendColor);
        g.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        g.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, g.depthRange);
        g.cullFaceMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        g.frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, bb);
            for (int i = 0; i < 4; i++) g.colorMask[i] = bb.get(i) != 0;
        }
        g.stencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        g.stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        g.stencilValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        g.stencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        g.stencilFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
        g.stencilPassDepthFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        g.stencilPassDepthPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        g.polygonModeFront = GL11.glGetInteger(GL11.GL_POLYGON_MODE);
        g.polygonOffsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        g.polygonOffsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        g.lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        g.packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        g.unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        for (int cap : TRACKED_CAPS) g.caps.put(cap, GL11.glIsEnabled(cap));

        int units = Math.min(GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS), 32);
        g.textureBindings2D = new int[units];
        g.samplerBindings = new int[units];
        for (int i = 0; i < units; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            g.textureBindings2D[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            g.samplerBindings[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        GL13.glActiveTexture(g.activeTexture);
        return g;
    }

    // --------------------------------------------------------------- restore

    private void doRestore() {
        neutralisePixelStore();

        reclaimNames("textures", textures.stream().map(t -> t.name).toList(),
                GL11::glGenTextures);
        reclaimNames("buffers", buffers.stream().map(b -> b.name).toList(),
                GL15::glGenBuffers);
        reclaimNames("renderbuffers", renderbuffers.stream().map(r -> r.name).toList(),
                GL30::glGenRenderbuffers);
        reclaimNames("framebuffers", framebuffers.stream().map(f -> f.name).toList(),
                GL30::glGenFramebuffers);
        reclaimNames("vertex arrays", vaos.stream().map(v -> v.name).toList(),
                GL30::glGenVertexArrays);
        reclaimNames("samplers", samplers.stream().map(s -> s.name).toList(),
                GL33::glGenSamplers);

        // Shader and program names come from a single shared name space that has
        // no glGen: glCreateShader/glCreateProgram allocate implicitly. Restore
        // them in ascending name order and check what we got.
        restoreShadersAndPrograms();

        restoreTextures();
        restoreBuffers();
        restoreRenderbuffers();
        restoreSamplers();
        restoreVaos();
        restoreFramebuffers();
        restoreGlobalState();

        checkGlError("after restore");
    }

    private interface NameGen { void gen(int[] out); }

    /**
     * Reserve exactly the names we had. Drivers hand out the lowest free names in
     * ascending order from a fresh context, so asking for {@code max} names gets
     * us {@code 1..max}. Names we do not need are kept reserved rather than
     * deleted: that reproduces the "generated but never bound" state the original
     * context was in, so a handle the game generated and has not bound yet stays
     * valid too.
     */
    private void reclaimNames(String what, List<Integer> wanted, NameGen gen) {
        if (wanted.isEmpty()) return;
        int max = Collections.max(wanted);
        Set<Integer> needed = new HashSet<>(wanted);
        Set<Integer> got = new HashSet<>();

        int guard = 0;
        while (!got.containsAll(needed)) {
            if (++guard > 64)
                throw new IllegalStateException(nameFailure(what, wanted, got));
            int chunk = Math.max(max, 1);
            int[] names = new int[chunk];
            gen.gen(names);
            for (int n : names) got.add(n);
            int highest = Arrays.stream(names).max().orElse(0);
            if (highest > max && !got.containsAll(needed))
                throw new IllegalStateException(nameFailure(what, wanted, got));
        }
    }

    private String nameFailure(String what, List<Integer> wanted, Set<Integer> got) {
        List<Integer> missing = wanted.stream().filter(n -> !got.contains(n)).sorted().toList();
        return "cannot reclaim original GL " + what + " names on the rebuilt context: "
                + missing.size() + " missing, first few " + missing.stream().limit(10).toList()
                + ". This driver does not allocate names from 1 in ascending order, so handles "
                + "already stored in the game's heap would point at the wrong objects. Refusing "
                + "to continue rather than render silently-wrong output.";
    }

    private void restoreTextures() {
        for (TextureRec r : textures) {
            GL11.glBindTexture(r.target, r.name);
            for (Map.Entry<Integer, Integer> e : r.iparams.entrySet())
                GL11.glTexParameteri(r.target, e.getKey(), e.getValue());
            GL11.glTexParameterf(r.target, GL12.GL_TEXTURE_MIN_LOD, r.fparams[0]);
            GL11.glTexParameterf(r.target, GL12.GL_TEXTURE_MAX_LOD, r.fparams[1]);
            GL11.glTexParameterf(r.target, GL14.GL_TEXTURE_LOD_BIAS, r.fparams[2]);
            GL11.glTexParameterfv(r.target, GL11.GL_TEXTURE_BORDER_COLOR, r.borderColor);

            for (TexLevel l : r.levels) {
                if (l.data == null) continue; // multisample / buffer texture
                l.data.position(0);
                if (l.compressed) {
                    l.data.limit(Math.max(l.compressedSize, 1));
                    uploadCompressed(r, l);
                } else {
                    GlFormats.Transfer tr = GlFormats.transferFor(l.internalFormat);
                    l.data.limit(l.data.capacity());
                    upload(r, l, tr);
                }
            }
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void upload(TextureRec r, TexLevel l, GlFormats.Transfer tr) {
        switch (r.target) {
            case GL11.GL_TEXTURE_1D ->
                    GL11.glTexImage1D(r.target, l.level, l.internalFormat, l.width, 0,
                            tr.format(), tr.type(), l.data);
            case GL12.GL_TEXTURE_3D, GL30.GL_TEXTURE_2D_ARRAY, GL40.GL_TEXTURE_CUBE_MAP_ARRAY ->
                    GL12.glTexImage3D(r.target, l.level, l.internalFormat, l.width, l.height,
                            Math.max(l.depth, 1), 0, tr.format(), tr.type(), l.data);
            case GL30.GL_TEXTURE_1D_ARRAY ->
                    GL11.glTexImage2D(r.target, l.level, l.internalFormat, l.width, l.height, 0,
                            tr.format(), tr.type(), l.data);
            case GL13.GL_TEXTURE_CUBE_MAP -> {
                long faceBytes = (long) l.data.capacity() / 6;
                for (int f = 0; f < 6; f++) {
                    l.data.position((int) (f * faceBytes));
                    ByteBuffer slice = l.data.slice();
                    slice.limit((int) faceBytes);
                    GL11.glTexImage2D(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, l.level,
                            l.internalFormat, l.width, l.height, 0, tr.format(), tr.type(), slice);
                }
                l.data.position(0);
            }
            default ->
                    GL11.glTexImage2D(r.target, l.level, l.internalFormat, l.width, l.height, 0,
                            tr.format(), tr.type(), l.data);
        }
    }

    private void uploadCompressed(TextureRec r, TexLevel l) {
        switch (r.target) {
            case GL12.GL_TEXTURE_3D, GL30.GL_TEXTURE_2D_ARRAY ->
                    GL13.glCompressedTexImage3D(r.target, l.level, l.internalFormat, l.width,
                            l.height, Math.max(l.depth, 1), 0, l.data);
            case GL11.GL_TEXTURE_1D ->
                    GL13.glCompressedTexImage1D(r.target, l.level, l.internalFormat, l.width, 0, l.data);
            default ->
                    GL13.glCompressedTexImage2D(r.target, l.level, l.internalFormat, l.width,
                            l.height, 0, l.data);
        }
    }

    private void restoreBuffers() {
        for (BufferRec r : buffers) {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, r.name);
            if (r.size == 0) {
                GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, 0L, r.usage);
                continue;
            }
            r.data.position(0).limit((int) r.size);
            if (r.immutable) GL44.glBufferStorage(GL31.GL_COPY_WRITE_BUFFER, r.data, r.storageFlags);
            else GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, r.data, r.usage);
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    private void restoreRenderbuffers() {
        for (RenderbufferRec r : renderbuffers) {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, r.name);
            if (r.samples > 0)
                GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, r.samples,
                        r.internalFormat, r.width, r.height);
            else
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, r.internalFormat, r.width, r.height);
        }
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
    }

    private void restoreSamplers() {
        for (SamplerRec r : samplers) {
            for (Map.Entry<Integer, Integer> e : r.iparams.entrySet())
                GL33.glSamplerParameteri(r.name, e.getKey(), e.getValue());
            GL33.glSamplerParameterfv(r.name, GL11.GL_TEXTURE_BORDER_COLOR, r.borderColor);
        }
    }

    /**
     * The same GL function under whichever name this context resolved.
     *
     * Reported from NVIDIA: a rebuilt context in which glGenSamplers and
     * glSamplerParameteri (both 3.3) worked and glProgramBinary (4.1) worked,
     * but the core glVertexAttribDivisor pointer was null — so the replay died
     * on a JNI FatalError with the JVM aborting from native code. LWJGL resolves
     * the core name and the ARB_instanced_arrays name separately, and
     * ARB_instanced_arrays is where this function was promoted from, so the two
     * are the same entry point in the driver.
     *
     * Taking whichever resolved cannot be worse than the alternative: the branch
     * is only reached when calling the core one would have killed the process.
     * It is noted as a warning rather than done quietly, because a context that
     * is missing a core 3.3 pointer is telling us something, and the next
     * failure will be easier to read if this one is on the record.
     */
    private void setVertexAttribDivisor(int index, int divisor) {
        GLCapabilities rc = restoreCaps != null ? restoreCaps : caps;
        if (rc.glVertexAttribDivisor != 0) {
            GL33.glVertexAttribDivisor(index, divisor);
            return;
        }
        if (!warnedAboutDivisorFallback) {
            warnedAboutDivisorFallback = true;
            warnings.add("this context did not resolve the core glVertexAttribDivisor; "
                    + "using ARB_instanced_arrays' glVertexAttribDivisorARB instead");
        }
        ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
    }

    private boolean warnedAboutDivisorFallback;

    private void restoreVaos() {
        for (VaoRec r : vaos) {
            GL30.glBindVertexArray(r.name);
            for (VaoAttrib a : r.attribs) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, a.bufferBinding);
                if (a.integer)
                    GL30.glVertexAttribIPointer(a.index, a.size, a.type, a.stride, a.pointer);
                else
                    GL20.glVertexAttribPointer(a.index, a.size, a.type, a.normalized, a.stride, a.pointer);
                setVertexAttribDivisor(a.index, a.divisor);
                if (a.enabled) GL20.glEnableVertexAttribArray(a.index);
                else GL20.glDisableVertexAttribArray(a.index);
            }
            // Element array binding is VAO state, so it must be set while bound.
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, r.elementArrayBuffer);
        }
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private void restoreFramebuffers() {
        for (FramebufferRec r : framebuffers) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, r.name);
            for (FbAttachment a : r.attachments) {
                if (a.objectType == GL30.GL_RENDERBUFFER) {
                    GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, a.point,
                            GL30.GL_RENDERBUFFER, a.objectName);
                } else if (a.objectType == GL11.GL_TEXTURE) {
                    if (a.cubeFace != 0)
                        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, a.point, a.cubeFace,
                                a.objectName, a.textureLevel);
                    else if (a.textureLayer != 0)
                        GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, a.point, a.objectName,
                                a.textureLevel, a.textureLayer);
                    else
                        GL32.glFramebufferTexture(GL30.GL_FRAMEBUFFER, a.point, a.objectName,
                                a.textureLevel);
                }
            }
            if (r.drawBuffers != null && r.drawBuffers.length > 0)
                GL20.glDrawBuffers(r.drawBuffers);
            GL11.glReadBuffer(r.readBuffer);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
                warnings.add(String.format("framebuffer %d is incomplete after restore (0x%04X)",
                        r.name, status));
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void restoreShadersAndPrograms() {
        // Shaders and programs share one name space and are allocated implicitly,
        // so we must recreate them in ascending name order and verify as we go.
        record Item(int name, boolean isProgram) {}
        List<Item> items = new ArrayList<>();
        for (ShaderRec s : shaders) items.add(new Item(s.name, false));
        for (ProgramRec p : programs) items.add(new Item(p.name, true));
        items.sort(Comparator.comparingInt(Item::name));

        Map<Integer, ShaderRec> shaderByName = new HashMap<>();
        for (ShaderRec s : shaders) shaderByName.put(s.name, s);
        Map<Integer, ProgramRec> programByName = new HashMap<>();
        for (ProgramRec p : programs) programByName.put(p.name, p);

        int expected = 1;
        List<Integer> filler = new ArrayList<>();
        for (Item item : items) {
            // Burn names until the allocator reaches the one we need. Burned
            // names stay allocated, mirroring objects the original context had
            // that we did not capture.
            while (expected < item.name()) {
                int burned = GL20.glCreateProgram();
                if (burned == 0)
                    throw new IllegalStateException("glCreateProgram returned 0 while reclaiming "
                            + "GL program/shader name " + item.name());
                if (burned > item.name())
                    throw new IllegalStateException("GL shader/program name space skipped past "
                            + item.name() + " (got " + burned + "); cannot reclaim original names");
                filler.add(burned);
                expected = burned + 1;
            }

            int got;
            if (item.isProgram()) {
                got = GL20.glCreateProgram();
            } else {
                got = GL20.glCreateShader(shaderByName.get(item.name()).type);
            }
            if (got != item.name())
                throw new IllegalStateException("wanted GL " + (item.isProgram() ? "program" : "shader")
                        + " name " + item.name() + " but the driver gave " + got
                        + "; handles stored in the game's heap would be wrong");
            expected = got + 1;
        }

        for (ShaderRec s : shaders) {
            GL20.glShaderSource(s.name, s.source == null ? "" : s.source);
            GL20.glCompileShader(s.name);
            if (GL20.glGetShaderi(s.name, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
                warnings.add("shader " + s.name + " failed to recompile: "
                        + GL20.glGetShaderInfoLog(s.name));
        }

        for (ProgramRec p : programs) {
            if (!p.linked) continue;
            boolean ok = false;
            if (p.binary != null && p.binary.limit() > 0) {
                p.binary.position(0);
                GL41.glProgramBinary(p.name, p.binaryFormat, p.binary);
                ok = GL20.glGetProgrami(p.name, GL20.GL_LINK_STATUS) != 0;
                if (ok) {
                    // ProgramBinary resets every uniform to its initial value,
                    // exactly as a re-link does — the binary carries the compiled
                    // program, not the values written into it. Skipping this
                    // gives back a program that links fine and renders black.
                    applyUniformState(p);
                } else {
                    warnings.add("program " + p.name + ": driver rejected the cached binary "
                            + "(likely a driver update); recompiling from source");
                }
            }
            if (!ok) {
                relinkFromSource(p);
            }
        }
    }

    private void relinkFromSource(ProgramRec p) {
        for (int sh : p.attachedShaders) {
            if (GL20.glIsShader(sh)) GL20.glAttachShader(p.name, sh);
        }
        for (Map.Entry<String, Integer> e : p.attribLocations.entrySet())
            GL20.glBindAttribLocation(p.name, e.getValue(), e.getKey());
        GL20.glLinkProgram(p.name);
        if (GL20.glGetProgrami(p.name, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            warnings.add("program " + p.name + " failed to relink from source: "
                    + GL20.glGetProgramInfoLog(p.name));
            return;
        }
        applyUniformState(p);
    }

    /** Re-apply everything a link (or a ProgramBinary) resets. */
    private void applyUniformState(ProgramRec p) {
        int prev = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(p.name);
        GlUniforms.restore(p.name, p.uniforms);
        for (Map.Entry<String, Integer> e : p.uniformBlockBindings.entrySet()) {
            int idx = GL31.glGetUniformBlockIndex(p.name, e.getKey());
            if (idx != GL31.GL_INVALID_INDEX)
                GL31.glUniformBlockBinding(p.name, idx, e.getValue());
        }
        GL20.glUseProgram(prev);
    }

    private void restoreGlobalState() {
        GlobalState g = global;
        GL11.glViewport(g.viewport[0], g.viewport[1], g.viewport[2], g.viewport[3]);
        GL11.glScissor(g.scissorBox[0], g.scissorBox[1], g.scissorBox[2], g.scissorBox[3]);
        GL11.glClearColor(g.clearColor[0], g.clearColor[1], g.clearColor[2], g.clearColor[3]);
        GL11.glClearDepth(g.clearDepth);
        GL11.glClearStencil(g.clearStencil);
        GL14.glBlendFuncSeparate(g.blendSrcRgb, g.blendDstRgb, g.blendSrcAlpha, g.blendDstAlpha);
        GL20.glBlendEquationSeparate(g.blendEqRgb, g.blendEqAlpha);
        GL14.glBlendColor(g.blendColor[0], g.blendColor[1], g.blendColor[2], g.blendColor[3]);
        GL11.glDepthFunc(g.depthFunc);
        GL11.glDepthMask(g.depthMask);
        GL11.glDepthRange(g.depthRange[0], g.depthRange[1]);
        GL11.glCullFace(g.cullFaceMode);
        GL11.glFrontFace(g.frontFace);
        GL11.glColorMask(g.colorMask[0], g.colorMask[1], g.colorMask[2], g.colorMask[3]);
        GL11.glStencilFunc(g.stencilFunc, g.stencilRef, g.stencilValueMask);
        GL11.glStencilMask(g.stencilWriteMask);
        GL11.glStencilOp(g.stencilFail, g.stencilPassDepthFail, g.stencilPassDepthPass);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, g.polygonModeFront);
        GL11.glPolygonOffset(g.polygonOffsetFactor, g.polygonOffsetUnits);
        GL11.glLineWidth(g.lineWidth);

        for (Map.Entry<Integer, Boolean> e : g.caps.entrySet()) {
            if (e.getValue()) GL11.glEnable(e.getKey());
            else GL11.glDisable(e.getKey());
        }

        for (int i = 0; i < g.textureBindings2D.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, g.textureBindings2D[i]);
            if (g.samplerBindings[i] != 0) GL33.glBindSampler(i, g.samplerBindings[i]);
        }
        GL13.glActiveTexture(g.activeTexture);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, g.arrayBuffer);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, g.uniformBuffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, g.pixelPackBuffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, g.pixelUnpackBuffer);
        GL30.glBindVertexArray(g.boundVao);
        // ELEMENT_ARRAY binding belongs to whichever VAO is bound, so it goes last.
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, g.elementArrayBuffer);
        GL20.glUseProgram(g.currentProgram);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, g.drawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, g.readFbo);

        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, g.packAlignment);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, g.unpackAlignment);
    }

    private void checkGlError(String where) {
        int err;
        List<String> errs = new ArrayList<>();
        while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR)
            errs.add(String.format("0x%04X", err));
        if (!errs.isEmpty()) warnings.add("GL errors " + where + ": " + errs);
    }
}
