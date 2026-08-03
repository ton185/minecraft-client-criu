package mccriu.mc;

import com.mojang.blaze3d.platform.Window;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import mccriu.core.CheckpointCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Teaches {@link CheckpointCoordinator} how to take Minecraft's window and audio
 * apart and put them back together.
 *
 * The window genuinely has to die: GLFW owns the X11 display connection, and
 * that connection is a socket whose peer — the X server — is outside the dump
 * set, which CRIU refuses. So we destroy it, terminate GLFW, and afterwards
 * build an identical window and point Minecraft's {@code Window} object at the
 * new handle. Every GL object inside it is restored under its original name by
 * the snapshot layer, so nothing above this class notices.
 *
 * Notably absent: any resource-pack reload. Reloading resources is what makes a
 * big modpack take minutes to start, and re-running it on restore would give
 * back most of the time the checkpoint was meant to save.
 */
final class MinecraftHost implements CheckpointCoordinator.Host {

    private static final System.Logger LOG = System.getLogger("mc-criu");

    private final Minecraft mc;

    // Window internals we have to drive by hand, resolved once and up front so a
    // version mismatch is reported before anything is torn down.
    private final Field windowHandleField;
    private final Field defaultErrorCallbackField;
    private final Method onMove, onFramebufferResize, onResize, onFocus, onEnter;
    private final Field soundEngineField;
    private final Method loadLibrary;
    /** Sodium's raw CPU-render-ahead GLsync queue, absent when Sodium is absent. */
    private final Field sodiumFrameFencesField;

    /** Window geometry captured before teardown so the new window matches. */
    private int savedWidth, savedHeight;
    private boolean savedFullscreen;
    private String savedTitle = "Minecraft";
    /** Recorded while the context is still current, for failure reports. */
    private String glVendor = "?", glRenderer = "?", glVersion = "?";
    /** Set by recreateWindow, consumed by afterRebuild once GL is usable. */
    private long rebuiltHandle;

    MinecraftHost(Minecraft mc) {
        this.mc = mc;
        this.windowHandleField = Reflect.field(Window.class, "window",
                "the GLFW handle, which must be repointed at the rebuilt window");
        this.defaultErrorCallbackField = Reflect.field(Window.class, "defaultErrorCallback",
                "Window.close() closes it, so a fresh one is needed after rebuild");
        this.onMove = Reflect.method(Window.class, "onMove",
                "window position callback", long.class, int.class, int.class);
        this.onFramebufferResize = Reflect.method(Window.class, "onFramebufferResize",
                "framebuffer size callback", long.class, int.class, int.class);
        this.onResize = Reflect.method(Window.class, "onResize",
                "window size callback", long.class, int.class, int.class);
        this.onFocus = Reflect.method(Window.class, "onFocus",
                "focus callback", long.class, boolean.class);
        this.onEnter = Reflect.method(Window.class, "onEnter",
                "cursor enter callback", long.class, boolean.class);
        this.soundEngineField = Reflect.field(SoundManager.class, "soundEngine",
                "the OpenAL engine, which owns the PipeWire connection");
        this.loadLibrary = Reflect.method(SoundEngine.class, "loadLibrary",
                "reopens the audio device after restore");
        this.sodiumFrameFencesField = findSodiumFrameFencesField(mc.getClass());
    }

    /** Verify every reflective hook resolves. Called at install time. */
    static String selfCheck(Minecraft mc) {
        try {
            new MinecraftHost(mc);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    @Override
    public String describeState() {
        Object screen = mc.screen;
        return screen == null ? "in-world (no screen)" : screen.getClass().getName();
    }

    // ----------------------------------------------------------------- audio

    @Override
    public void shutdownAudio() {
        SoundManager sm = mc.getSoundManager();
        SoundEngine engine = (SoundEngine) Reflect.get(soundEngineField, sm);
        // destroy() stops every source, destroys the AL context and closes the
        // device; that is what releases OpenAL Soft's PipeWire socket.
        engine.destroy();
    }

    @Override
    public void restartAudio() {
        SoundManager sm = mc.getSoundManager();
        SoundEngine engine = (SoundEngine) Reflect.get(soundEngineField, sm);
        // loadLibrary() reopens the device and rebuilds the channel pool without
        // going near the resource manager. SoundEngine.reload() would also work
        // but starts by destroying an engine we have already destroyed.
        Reflect.invoke(loadLibrary, engine);
    }

    // ---------------------------------------------------------------- window

    @Override
    public void destroyWindow() {
        Window w = mc.getWindow();
        savedWidth = w.getWidth();
        savedHeight = w.getHeight();
        savedFullscreen = w.isFullscreen();

        GL.setCapabilities(null);
        GLFW.glfwMakeContextCurrent(0);
        // Window.close() frees the callbacks, destroys the window and calls
        // glfwTerminate() — which is what actually closes the X11 connection.
        w.close();
    }

    @Override
    public void recreateWindow() {
        // Defensively forget any raw handles that survived an interrupted
        // teardown before the replacement context allocates anything. NVIDIA
        // starts its new GLsync namespace at 1, so an old value can otherwise
        // alias and later delete a completely unrelated new fence.
        retireSodiumFrameFences(false, "before allocating replacement GLsync objects");

        // Record GLFW's own diagnostics rather than only printing them. When
        // window creation fails, GLFW always says why, and that message is the
        // difference between a fixable report and "returned NULL".
        StringBuilder glfwErrors = new StringBuilder();
        GLFWErrorCallback errorCallback = GLFWErrorCallback.create((code, description) -> {
            String msg = String.format("GLFW error 0x%X: %s",
                    code, GLFWErrorCallback.getDescription(description));
            glfwErrors.append(msg).append('\n');
            System.err.println("mc-criu: " + msg);
        });
        GLFW.glfwSetErrorCallback(errorCallback);
        if (!GLFW.glfwInit())
            throw new IllegalStateException("glfwInit() failed while rebuilding the window "
                    + "after restore; the X server at DISPLAY may be gone.\n" + glfwErrors);

        // The same hints Minecraft's Window constructor uses, so the rebuilt
        // context is the one the restored GL objects were captured from.
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE);

        long handle = GLFW.glfwCreateWindow(Math.max(savedWidth, 1), Math.max(savedHeight, 1),
                savedTitle, 0L, 0L);
        if (handle == 0) throw windowCreationFailed(glfwErrors);

        Window w = mc.getWindow();
        Reflect.setLong(windowHandleField, w, handle);
        try {
            defaultErrorCallbackField.set(w, errorCallback);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot install a fresh GLFW error callback", e);
        }

        // Minecraft's input handlers begin with
        //     if (window != this.minecraft.getWindow().getWindow()) return;
        // so if the Window object is still holding the old handle, every key and
        // click is dropped silently and the game looks alive but dead. Verify
        // the write landed rather than discovering it as "no clicks work".
        long asMinecraftSeesIt = w.getWindow();
        if (asMinecraftSeesIt != handle)
            throw new IllegalStateException(String.format(
                    "Window.window still reads 0x%X after being set to 0x%X. Minecraft's "
                    + "input handlers compare against it and would discard every event, so "
                    + "the window would render but ignore all input.",
                    asMinecraftSeesIt, handle));

        reinstallWindowCallbacks(w, handle);
        // These two re-register every key, char, mouse-button, cursor and scroll
        // callback against the new handle.
        mc.keyboardHandler.setup(handle);
        mc.mouseHandler.setup(handle);

        rebuiltHandle = handle;
        GLFW.glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        w.updateVsync(mc.options.enableVsync().get());
        w.setTitle(savedTitle);
        if (savedFullscreen) w.toggleFullScreen();

        // Deliberately NOT calling RenderSystem.setErrorCallback here: it swaps
        // the GLFW error callback and frees the previous one — which is the
        // object we just stored in Window.defaultErrorCallback. The next
        // Window.close() would then free it a second time and segfault inside
        // DeleteGlobalRef. Learned the hard way on the second checkpoint.
    }

    @Override
    public void afterRebuild() {
        // Deliberately here and not in recreateWindow(): replaying the size
        // event makes Minecraft call resizeDisplay(), which resizes the main
        // render target — GL calls. Those need the context current, and they
        // need to happen after the GL objects have been replayed, or the
        // snapshot would overwrite the resized target. Doing it too early
        // leaves the main render target broken, which shows up as a window that
        // takes input but never redraws.
        if (rebuiltHandle != 0) {
            syncWindowStateIntoMinecraft(mc.getWindow(), rebuiltHandle);
            rebuiltHandle = 0;
        }
    }

    /**
     * Feed Minecraft the window events it missed.
     *
     * A freshly created window reports its real size, position and focus only
     * through callbacks, which are attached a moment after creation. Minecraft's
     * {@code Window} caches all of it — {@code framebufferWidth/Height} drive the
     * main render target and the GUI scale — so without this the game runs with
     * whatever those fields held before the checkpoint.
     */
    private void syncWindowStateIntoMinecraft(Window w, long handle) {
        int[] a = new int[1], b = new int[1];

        GLFW.glfwGetFramebufferSize(handle, a, b);
        Reflect.invoke(onFramebufferResize, w, handle, a[0], b[0]);

        GLFW.glfwGetWindowSize(handle, a, b);
        Reflect.invoke(onResize, w, handle, a[0], b[0]);

        GLFW.glfwGetWindowPos(handle, a, b);
        Reflect.invoke(onMove, w, handle, a[0], b[0]);

        // Ask for focus, then tell Minecraft whatever the answer actually was.
        // It gates parts of its input handling on believing the window is active.
        GLFW.glfwShowWindow(handle);
        GLFW.glfwFocusWindow(handle);
        boolean focused = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) != 0;
        Reflect.invoke(onFocus, w, handle, focused);
    }

    /**
     * Turn "returned NULL" into something actionable.
     *
     * A probe with default hints separates the two possible causes: if a plain
     * window can be created then the display connection is fine and it is the
     * OpenGL context request being refused, which points at the driver rather
     * than at X.
     */
    private IllegalStateException windowCreationFailed(CharSequence glfwErrors) {
        String probe;
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long probeWindow = GLFW.glfwCreateWindow(64, 64, "mc-criu probe", 0L, 0L);
        if (probeWindow != 0) {
            GLFW.glfwDestroyWindow(probeWindow);
            probe = "a window with DEFAULT hints WAS created, so the display connection is "
                  + "healthy and it is the OpenGL context request that is refused. The likely "
                  + "cause is the graphics driver declining a second GL context in this process "
                  + "after the first was destroyed and libGL was unloaded and reloaded. Try "
                  + "-Dmccriu.unloadGL=false (see below).";
        } else {
            probe = "even a window with DEFAULT hints could not be created, so this is not about "
                  + "the OpenGL context request — the display connection itself is unusable.";
        }

        String errors = glfwErrors.length() == 0
                ? "  (nothing — GLFW's error callback did not fire)"
                : "  " + glfwErrors.toString().trim().replace("\n", "\n  ");

        return new IllegalStateException(String.join("\n",
            "glfwCreateWindow() returned NULL while rebuilding the window after restore.",
            "",
            "GLFW reported:",
            errors,
            "",
            "Probe: " + probe,
            "",
            "GL context before the checkpoint: " + glVendor + " | " + glRenderer + " | " + glVersion,
            "requested now: OpenGL 3.2 core, forward-compatible, native (GLX) context, "
                + Math.max(savedWidth, 1) + "x" + Math.max(savedHeight, 1),
            "DISPLAY=" + System.getenv("DISPLAY")
                + "  WAYLAND_DISPLAY=" + System.getenv("WAYLAND_DISPLAY"),
            "",
            "If the probe says the display is healthy, the most likely fix is to stop",
            "unloading libGL at checkpoint time:  -Dmccriu.unloadGL=false",
            "That unload is not load-bearing on Mesa (measured); it exists to make a GPU",
            "driver release its device fds. If your driver already releases them when the",
            "window is destroyed, skipping it costs nothing — and if it does not, the",
            "checkpoint will refuse with the exact fds still held, rather than corrupting",
            "anything. See docs/NVIDIA.md."));
    }

    /**
     * Re-register the callbacks Window installs in its constructor. There is no
     * public way to re-run that registration, so the private handlers are bound
     * directly — the alternative would be reimplementing their bodies, which
     * would silently drift from Minecraft's.
     */
    private void reinstallWindowCallbacks(Window w, long handle) {
        GLFW.glfwSetWindowPosCallback(handle,
                (win, x, y) -> Reflect.invoke(onMove, w, win, x, y));
        GLFW.glfwSetFramebufferSizeCallback(handle,
                (win, cx, cy) -> Reflect.invoke(onFramebufferResize, w, win, cx, cy));
        GLFW.glfwSetWindowSizeCallback(handle,
                (win, cx, cy) -> Reflect.invoke(onResize, w, win, cx, cy));
        GLFW.glfwSetWindowFocusCallback(handle,
                (win, focused) -> Reflect.invoke(onFocus, w, win, focused));
        GLFW.glfwSetCursorEnterCallback(handle,
                (win, entered) -> Reflect.invoke(onEnter, w, win, entered));
    }

    @Override
    public void beforeTeardown() {
        Window w = mc.getWindow();
        savedTitle = "Minecraft";
        try {
            glVendor = String.valueOf(GL11.glGetString(GL11.GL_VENDOR));
            glRenderer = String.valueOf(GL11.glGetString(GL11.GL_RENDERER));
            glVersion = String.valueOf(GL11.glGetString(GL11.GL_VERSION));
        } catch (RuntimeException ignored) {
            // Diagnostics only; never block a checkpoint over them.
        }

        // Sodium's MinecraftMixin stores frame-pacing fences as raw longs in a
        // Java queue. Unlike Sodium's GlFence objects, those values carry no
        // owner or context epoch. Empty the queue while its context is still
        // current so no old value survives CRIU and aliases a new native handle.
        retireSodiumFrameFences(true, "before GL context teardown");

        // Stop every playing sound first so the AL context is quiet when it goes.
        try {
            mc.getSoundManager().stop();
        } catch (RuntimeException ignored) {
            // A sound engine that is already unhappy must not block the checkpoint.
        }
        savedWidth = w.getWidth();
        savedHeight = w.getHeight();
    }

    /**
     * Locate the private {@code @Unique fences} field contributed to Minecraft
     * by Sodium 0.8's core MinecraftMixin. Looking it up by type as well as name
     * tolerates Mixin renaming the unique member, while keeping Sodium optional.
     */
    private static Field findSodiumFrameFencesField(Class<?> minecraftClass) {
        Field candidate = null;
        for (Field field : minecraftClass.getDeclaredFields()) {
            if (field.getType() != LongArrayFIFOQueue.class) continue;
            if (field.getName().equals("fences")) {
                candidate = field;
                break;
            }
            if (candidate != null) {
                throw new Reflect.MissingMemberException(
                        "found more than one LongArrayFIFOQueue on " + minecraftClass.getName()
                        + "; cannot safely identify Sodium's raw GLsync queue", null);
            }
            candidate = field;
        }

        if (candidate == null) return null;
        try {
            candidate.setAccessible(true);
            return candidate;
        } catch (RuntimeException e) {
            throw new Reflect.MissingMemberException(
                    "cannot access Sodium's raw GLsync queue " + candidate, e);
        }
    }

    /**
     * Retire every raw frame fence without ever presenting a stale value to a
     * replacement context. When the owning context is still current, delete
     * valid native objects too; after teardown, only discard the Java values.
     */
    private void retireSodiumFrameFences(boolean deleteNative, String phase) {
        if (sodiumFrameFencesField == null) return;

        LongArrayFIFOQueue fences = (LongArrayFIFOQueue) Reflect.get(
                sodiumFrameFencesField, mc);
        int count = fences.size();
        while (fences.size() != 0) {
            long fence = fences.dequeueLong();
            if (deleteNative && fence != 0 && GL32C.glIsSync(fence)) {
                GL32C.glDeleteSync(fence);
            }
        }

        if (count != 0) {
            LOG.log(System.Logger.Level.INFO,
                    "mc-criu: retired " + count + " Sodium render-ahead GLsync handle(s) "
                    + phase);
        }
    }

    /**
     * The rebuilt window carries GLFW's default icon: Minecraft sets its icon
     * once at startup from the resource pack, and re-reading it would mean
     * touching the resource manager. Cosmetic, and reported rather than hidden.
     */
    static final String KNOWN_COSMETIC_LOSS =
            "window icon is not restored (it would require a resource-pack lookup)";
}
