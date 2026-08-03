package mccriu.core;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeGLX;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Identity of the native OpenGL context behind the Java object graph.
 *
 * <p>Named GL objects can be replayed under their old integer names, which is
 * what {@link GlSnapshot} does. A {@code GLsync} is different: its handle is an
 * opaque driver pointer and there is no API for recreating one under the same
 * value. Java wrappers around a fence survive CRIU, but the fence itself dies
 * with the old context. Code must therefore recognize wrappers born in an older
 * epoch instead of passing their stale pointers to the replacement driver.
 */
public final class GlContextEpoch {

    private static final AtomicLong CURRENT = new AtomicLong();
    private static final AtomicLong LAST_REPORTED_STALE = new AtomicLong(Long.MIN_VALUE);
    private static final System.Logger LOG = System.getLogger("mc-criu");
    private static volatile String lastVerifiedSync = "no rebuilt-context fence has been verified";

    /** Epoch to store in a newly-created context-owned Java wrapper. */
    public static long current() { return CURRENT.get(); }

    /** Whether a wrapper belongs to the context that is current now. */
    public static boolean isCurrent(long bornIn) { return bornIn == CURRENT.get(); }

    /** The successful control observation from immediately after the rebuild. */
    public static String lastVerifiedSync() { return lastVerifiedSync; }

    /**
     * Context and LWJGL dispatch identity, safe to include in a later failure.
     * GLFW itself is not unloaded, so querying the current window through it
     * does not call the stale GLX function table that an explicit GL unload may
     * have invalidated.
     */
    public static String describeCurrentContext() {
        String thread = Thread.currentThread().getName() + "#" + Thread.currentThread().threadId();
        long window = 0;
        long glxContext = 0;
        try {
            window = GLFW.glfwGetCurrentContext();
            if (window != 0) glxContext = GLFWNativeGLX.glfwGetGLXContext(window);
        } catch (Throwable ignored) {
            // The function pointers below are the more important half. Keep a
            // diagnostic useful even on a non-GLX GLFW build.
        }

        try {
            GLCapabilities c = GL.getCapabilities();
            return String.format(
                    "thread=%s glfwWindow=0x%X glxContext=0x%X capabilities@%X "
                    + "glFenceSync=0x%X glIsSync=0x%X glGetSynciv=0x%X glDeleteSync=0x%X",
                    thread, window, glxContext, System.identityHashCode(c),
                    c.glFenceSync, c.glIsSync, c.glGetSynciv, c.glDeleteSync);
        } catch (Throwable t) {
            return String.format("thread=%s glfwWindow=0x%X glxContext=0x%X capabilities=%s",
                    thread, window, glxContext, t);
        }
    }

    /**
     * Mark a successfully recreated context as current and verify its sync API.
     * Called with the new context current, before any captured state is replayed.
     */
    static void contextRecreated() {
        long epoch = CURRENT.incrementAndGet();
        verifyFreshFence(epoch);
    }

    /**
     * Log once per rebuilt context when an integration retires an old wrapper.
     * The log makes the compatibility path observable without spamming once per
     * staging region.
     */
    public static void reportRetiredStaleSync(String owner, long bornIn) {
        long now = CURRENT.get();
        if (LAST_REPORTED_STALE.getAndSet(now) == now) return;
        LOG.log(System.Logger.Level.INFO,
                "mc-criu: retired a " + owner + " from GL context epoch " + bornIn
                + " after rebuilding epoch " + now + "; opaque GLsync handles cannot be "
                + "reused across contexts");
    }

    /**
     * Exercise the exact operation Sodium uses before allowing the rebuild to
     * proceed. This proves the new context can create and query a fence at the
     * rebuild boundary. A later failure is therefore a lifecycle event after
     * this point (such as another owner deleting an aliased raw handle), or a
     * subsequent context/dispatch change, rather than a baseline API failure.
     */
    private static void verifyFreshFence(long epoch) {
        long fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (fence == 0) {
            throw new IllegalStateException("glFenceSync returned NULL in freshly rebuilt GL "
                    + "context epoch " + epoch + ". The replacement context cannot provide "
                    + "the sync objects used by Sodium.");
        }

        boolean valid = false;
        try {
            valid = GL32C.glIsSync(fence);
            if (!valid) {
                throw new IllegalStateException("a GLsync created in freshly rebuilt context "
                        + "epoch " + epoch + " was immediately invalid (glIsSync=false). "
                        + "This is a new-context/driver dispatch failure, not a stale Sodium fence.");
            }

            int status;
            int returned;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.callocInt(1);
                status = GL32C.glGetSynci(fence, GL32C.GL_SYNC_STATUS, count);
                returned = count.get(0);
                if (returned != 1) {
                    throw new IllegalStateException("glGetSynciv returned " + returned
                            + " value(s) for a fence created in freshly rebuilt GL context epoch "
                            + epoch + ". Sodium would later fail with its misleading "
                            + "'glGetSync returned more than one value' exception.");
                }
            }
            lastVerifiedSync = String.format(
                    "epoch=%d id=0x%X glIsSync=true status=0x%X returned=%d %s",
                    epoch, fence, status, returned, describeCurrentContext());
        } finally {
            if (valid) GL32C.glDeleteSync(fence);
        }
    }

    private GlContextEpoch() {}
}
