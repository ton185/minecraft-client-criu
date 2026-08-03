package mccriu.core;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic: digest every live 2D texture's level 0, before teardown and again
 * after the rebuild, so a texture that replays "successfully" but comes back
 * empty is named instead of inferred.
 *
 * This exists because the GL replay reported 221 textures, 168 programs and
 * zero warnings on a client whose entire text layer was invisible. Counts and
 * warnings are not evidence that the pixels arrived.
 *
 * Off unless {@code -Dmccriu.dumpTextures=true}. It reads every texture back
 * through glGetTexImage, which costs roughly what a capture costs, so it is not
 * something to leave on.
 */
public final class TexDump {

    /** How far to scan the texture name space. Diagnostic only; capture uses its own bound. */
    private static final int NAME_LIMIT = 4096;

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("mccriu.dumpTextures", "false"));
    }

    /** Append a labelled dump of every live 2D texture to {@code out}. */
    public static void dump(Path out, String phase) {
        List<String> lines = new ArrayList<>();
        lines.add("=== " + phase + " ===");
        lines.add(contextState());
        int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevPack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        int scanned = 0;
        for (int name = 1; name <= NAME_LIMIT; name++) {
            if (!GL11.glIsTexture(name)) continue;
            scanned++;
            try {
                lines.add(describe(name));
            } catch (RuntimeException e) {
                lines.add(String.format("tex %-5d ERROR %s", name, e));
            }
        }
        lines.add("(" + scanned + " live texture names)");
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPack);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBinding);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain: diagnostics must not leak errors */ }
        try {
            Files.write(out, lines, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("mc-criu: cannot write texture dump: " + e);
        }
    }

    /**
     * The context state text rendering depends on that widget rendering does not.
     *
     * Minecraft's text shader multiplies by a texel fetched from the lightmap on
     * texture unit 1 and discards the fragment below alpha 0.1, so unit 1 is the
     * one piece of state that can make every glyph vanish while every button
     * still draws. Nothing else in a GUI samples a second unit.
     */
    private static String contextState() {
        StringBuilder sb = new StringBuilder("state: ");
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        sb.append(String.format("activeTexture=GL_TEXTURE%d program=%d",
                active - GL13.GL_TEXTURE0, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)));
        for (int unit = 0; unit < 4; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            sb.append(String.format(" unit%d=%d", unit,
                    GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)));
        }
        GL13.glActiveTexture(active);
        sb.append(String.format(" blend=%b depth=%b scissor=%b",
                GL11.glIsEnabled(GL11.GL_BLEND), GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)));
        int[] box = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        sb.append(String.format(" scissorBox=%d,%d,%dx%d viewport=%d,%d,%dx%d",
                box[0], box[1], box[2], box[3], vp[0], vp[1], vp[2], vp[3]));
        return sb.toString();
    }

    private static String describe(int name) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        if (GL11.glGetError() != GL11.GL_NO_ERROR || w <= 0)
            return String.format("tex %-5d (not a 2D texture, or no level 0)", name);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int ifmt = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        int minF = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
        if (!GlFormats.isKnown(ifmt))
            return String.format("tex %-5d %4dx%-4d ifmt=0x%04X (no transfer format)", name, w, h, ifmt);

        GlFormats.Transfer tr = GlFormats.transferFor(ifmt);
        long bytes = GlFormats.levelBytes(ifmt, w, h, 1);
        ByteBuffer b = MemoryUtil.memAlloc((int) bytes);
        try {
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, tr.format(), tr.type(), b);
            boolean allZero = true, alphaAllZero = true;
            for (int i = 0; i < bytes; i++) {
                if (b.get(i) != 0) { allZero = false; break; }
            }
            // Alpha matters on its own: rendertype_text discards on alpha < 0.1,
            // so an all-alpha-zero lightmap makes every glyph vanish while the
            // texture is not otherwise blank.
            if (tr.bytesPerPixel() == 4) {
                for (long i = 3; i < bytes; i += 4) {
                    if (b.get((int) i) != 0) { alphaAllZero = false; break; }
                }
            } else {
                alphaAllZero = false;
            }
            return String.format("tex %-5d %4dx%-4d %-16s minF=0x%04X sha=%s%s%s",
                    name, w, h, GlFormats.nameOf(ifmt), minF, sha(b, (int) bytes),
                    allZero ? "  ALL-ZERO" : "", alphaAllZero ? "  ALPHA-ALL-ZERO" : "");
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    private static String sha(ByteBuffer b, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            b.position(0).limit(len);
            md.update(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.substring(0, 16);
        } catch (Exception e) {
            return "digest-failed";
        }
    }

    private TexDump() {}
}
