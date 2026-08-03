package mccriu.core;

import org.lwjgl.opengl.GL;
import org.lwjgl.openal.ALC;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives one checkpoint: snapshot GL, tear the native stack down until CRIU will
 * accept the process, park, and rebuild everything on the other side.
 *
 * The host application supplies the four operations this class cannot know how
 * to do — destroying and recreating its window, stopping and starting its audio.
 * Everything else (GL object capture, driver unloading, the fd audit, the
 * rendezvous with the supervisor) is the same whether the host is a fifty-line
 * test harness or a modded Minecraft.
 */
public final class CheckpointCoordinator {

    /** Control-flow marker for a deliberately skipped unload. */
    private static final class SkipUnload extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** What the embedding application must provide. All calls are on the render thread. */
    public interface Host {
        /** Quiesce before anything is torn down: stop sounds, finish the frame. */
        default void beforeTeardown() {}

        /** Close the audio device and context. After this, no AL calls. */
        void shutdownAudio();

        /**
         * Destroy the GL context and the window, and terminate GLFW, so the X11
         * connection and the driver's device fds are released.
         */
        void destroyWindow();

        /** Recreate the window and a current GL context with the same properties. */
        void recreateWindow();

        /** Reopen the audio device. */
        void restartAudio();

        /** Anything the host wants to do once GL objects are back. */
        default void afterRebuild() {}

        /** Free-text description of where the game is, recorded in the report. */
        default String describeState() { return "unknown"; }
    }

    private final Rendezvous rdv;
    private final Host host;
    private final GlSnapshot.Config glConfig;
    private final System.Logger log = System.getLogger("mc-criu");

    private volatile int requestedGeneration = -1;
    private Thread watcher;
    private boolean glUnloaded, alcUnloaded;
    /** GLFW clock reading from just before teardown; negative means "nothing to restore". */
    private double glfwTimeAtTeardown = -1;
    private final java.util.concurrent.Executor renderThreadExecutor;
    private String lastPublishedScreen = "";

    /** Diagnostics from the most recent attempt, surfaced through report.json. */
    private final Map<String, Object> report = new LinkedHashMap<>();

    public CheckpointCoordinator(Path sessionDir, Host host) {
        this(sessionDir, host, new GlSnapshot.Config());
    }

    public CheckpointCoordinator(Path sessionDir, Host host, GlSnapshot.Config glConfig) {
        this(sessionDir, host, glConfig, null);
    }

    /**
     * @param renderThreadExecutor if non-null, the watcher submits {@link #tick()}
     *        here instead of expecting the host to poll. Minecraft already drains
     *        such a queue once per frame, before rendering starts, which is
     *        exactly the safe point we need — and it costs nothing per frame.
     */
    public CheckpointCoordinator(Path sessionDir, Host host, GlSnapshot.Config glConfig,
                                 java.util.concurrent.Executor renderThreadExecutor) {
        this.rdv = new Rendezvous(sessionDir);
        this.host = host;
        this.glConfig = glConfig;
        this.renderThreadExecutor = renderThreadExecutor;
    }

    /** Start watching for checkpoint requests. Call once the game is rendering. */
    public void start() {
        rdv.setState(Rendezvous.RUNNING);
        watcher = new Thread(this::watchLoop, "mc-criu-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watchLoop() {
        while (true) {
            try {
                int gen = rdv.pendingRequest();
                if (gen >= 0 && requestedGeneration < 0) {
                    requestedGeneration = gen;
                    if (renderThreadExecutor != null) renderThreadExecutor.execute(this::tick);
                }
                // Publish where the game is, so the supervisor can wait for the
                // title screen instead of guessing from log output. Only written
                // when it changes; a checkpoint is only *guaranteed* at the main
                // menu, and this is how a caller can be sure it is there.
                String screen = safeDescribe();
                if (!screen.equals(lastPublishedScreen)) {
                    rdv.setScreen(screen);
                    lastPublishedScreen = screen;
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.log(System.Logger.Level.WARNING, "mc-criu watcher: " + e);
            }
        }
    }

    /**
     * Call once per frame from the render thread, at a frame boundary. Returns
     * immediately unless a checkpoint has been requested, in which case it does
     * not return until the process has been dumped, restored and rebuilt.
     */
    public void tick() {
        int gen = requestedGeneration;
        if (gen < 0) return;
        requestedGeneration = -1;
        rdv.clearRequest();
        runCheckpoint(gen);
    }

    /** True if a checkpoint is pending, so the host can reach a quiet frame first. */
    public boolean checkpointPending() { return requestedGeneration >= 0; }

    private void runCheckpoint(int generation) {
        report.clear();
        report.put("generation", generation);
        report.put("gameState", safeDescribe());
        // Stale from a previous attempt would set the clock to the wrong value
        // on an abort that never got as far as destroying the window.
        glfwTimeAtTeardown = -1;

        // Check before touching anything: if we cannot repair LWJGL's OpenAL
        // dispatch cache after reloading the library, the rebuild would segfault
        // rather than throw, and there would be no game left to report it to.
        String blocker = AlcDispatchReset.probe();
        if (blocker != null) {
            log.log(System.Logger.Level.ERROR, "mc-criu: refusing to checkpoint: " + blocker);
            report.put("refused", blocker);
            rdv.writeReport(report);
            rdv.setFailed(blocker);
            rdv.setState(Rendezvous.RUNNING);
            return;
        }

        GlSnapshot snapshot = null;
        try {
            rdv.setState(Rendezvous.PREPARING);
            List<Map<String, Object>> stages = new ArrayList<>();

            host.beforeTeardown();
            stages.add(stage("beforeTeardown"));

            if (TexDump.enabled()) TexDump.dump(rdv.dir().resolve("textures.txt"), "before teardown");

            long t0 = System.nanoTime();
            snapshot = GlSnapshot.capture(glConfig);
            report.put("gl", snapshot.toReport());
            log.log(System.Logger.Level.INFO, "mc-criu: captured GL state: " + snapshot.summary());
            stages.add(stage("captureGl"));

            host.shutdownAudio();
            stages.add(stage("shutdownAudio"));

            glfwTimeAtTeardown = readGlfwTime();
            host.destroyWindow();
            stages.add(stage("destroyWindow"));

            unloadNativeDrivers();
            stages.add(stage("unloadDrivers"));

            report.put("teardownStages", stages);
            report.put("teardownMillis", (System.nanoTime() - t0) / 1_000_000L);

            FdAudit.Result audit = FdAudit.audit();
            report.put("auditClean", audit.clean());
            report.put("auditFatal", audit.fatal().stream()
                    .map(e -> e.detail() + " -- " + e.reason()).toList());
            report.put("auditSuspicious", audit.suspicious().stream()
                    .map(e -> e.detail() + " -- " + e.reason()).toList());

            if (!audit.clean()) {
                // Abort while the game is still rebuildable. A broken checkpoint
                // is bad; a broken game is worse.
                String why = audit.fatal().stream().map(FdAudit.Entry::detail)
                        .reduce((a, b) -> a + "; " + b).orElse("unknown");
                log.log(System.Logger.Level.ERROR,
                        "mc-criu: refusing to checkpoint, process is not CRIU-clean:\n"
                        + FdAudit.format(audit));
                rdv.writeReport(report);
                rebuild(snapshot);
                snapshot = null;
                rdv.setFailed("not CRIU-clean: " + why);
                rdv.setState(Rendezvous.RUNNING);
                return;
            }

            rdv.writeReport(report);
            rdv.setState(Rendezvous.PARKED);

            // ---- the process is dumped and later restored inside this call ----
            rdv.parkUntilResumed(generation);

            rdv.setState(Rendezvous.RESUMING);
            rebuild(snapshot);
            snapshot = null;
            rdv.setState(Rendezvous.RUNNING);

            // Republish the screen unconditionally, even though it has not changed.
            //
            // rendezvous/screen is a file, and the watch loop below only writes it
            // when the name differs from lastPublishedScreen. A restored process
            // resumes on the screen it was checkpointed on, so nothing looks
            // changed and it would never rewrite the file -- leaving whatever a
            // PREVIOUS incarnation of this image last wrote there. With one restore
            // that is invisible because the stale value happens to be right; across
            // repeated restores of one image it is a lie that outlives its writer,
            // and it sent a test harness clicking into the options menu.
            lastPublishedScreen = null;
            String resumed = safeDescribe();
            rdv.setScreen(resumed);
            lastPublishedScreen = resumed;

            log.log(System.Logger.Level.INFO, "mc-criu: resumed from generation " + generation);

        } catch (Throwable fatal) {
            log.log(System.Logger.Level.ERROR, "mc-criu: checkpoint failed", fatal);
            rdv.setFailed(fatal.toString());
            try {
                if (snapshot != null) rebuild(snapshot);
                rdv.setState(Rendezvous.RUNNING);
            } catch (Throwable rebuildFailure) {
                // Now the game genuinely cannot continue: there is no window and
                // no GL context. Surfacing this as a crash is the honest outcome.
                log.log(System.Logger.Level.ERROR,
                        "mc-criu: could not rebuild after a failed checkpoint", rebuildFailure);
                throw new IllegalStateException(
                        "mc-criu could not restore the graphics stack after a failed checkpoint. "
                        + "The original failure was: " + fatal, rebuildFailure);
            }
        }
    }

    private void rebuild(GlSnapshot snapshot) {
        reloadNativeDrivers();
        host.recreateWindow();
        // The Java heap still contains wrappers for opaque objects owned by the
        // old context. Named resources are reclaimed below; GLsync pointers
        // cannot be. Advance their epoch only once the replacement context is
        // current, and prove a newly-created fence works before proceeding.
        GlContextEpoch.contextRecreated();
        restoreGlfwClock();
        if (snapshot != null) {
            snapshot.restore();
            log.log(System.Logger.Level.INFO, "mc-criu: replayed GL state: " + snapshot.summary());
            for (String w : snapshot.warnings())
                log.log(System.Logger.Level.WARNING, "mc-criu: " + w);
            snapshot.free();
        }
        host.restartAudio();
        host.afterRebuild();
        if (TexDump.enabled()) TexDump.dump(rdv.dir().resolve("textures.txt"), "after rebuild");
    }

    /**
     * Unload the driver shared libraries themselves.
     *
     * Closing the AL device and terminating GLFW is not enough: measured on this
     * machine, a JVM that had opened OpenAL Soft's PipeWire backend still held a
     * connected socketpair and its eventfds afterwards, and CRIU rejected the
     * dump with "External socket is used". Unloading the library runs its
     * destructors and releases them. The same lever is what should release
     * NVIDIA's /dev/nvidia* fds on a machine that has them.
     *
     * GLFW is deliberately NOT unloaded: LWJGL binds its function pointers at
     * class-initialisation time, so unloading it would leave the next glfwInit()
     * jumping into freed memory. It holds nothing after glfwTerminate anyway.
     */
    /**
     * Whether to unload the driver libraries. Measured on Mesa, unloading libGL
     * is NOT what makes the process CRIU-clean — closing the AL device and
     * unloading libopenal is. The GL unload exists for GPU drivers that hold
     * device fds past window destruction, so on a driver that refuses a second
     * context afterwards it can be turned off; the fd audit then reports
     * whether the process is still clean, so nothing is silently degraded.
     *
     * unloadGL therefore defaults to FALSE. It used to default to true, which
     * made a working install depend on the caller knowing to pass
     * -Dmccriu.unloadGL=false: with it on, glfwCreateWindow fails on radeonsi
     * with "GLX: No GLXFBConfigs returned" and the restored client has no window.
     * Costing every user a broken restore to serve a driver we have never been
     * able to reproduce on is the wrong default. Turn it back on with
     * -Dmccriu.unloadGL=true if a driver is found that needs it.
     */
    private static boolean flag(String name, boolean dflt) {
        String v = System.getProperty(name);
        return v == null ? dflt : Boolean.parseBoolean(v);
    }

    private void unloadNativeDrivers() {
        if (!flag("mccriu.unloadAL", true) && !flag("mccriu.unloadGL", false)) {
            log.log(System.Logger.Level.INFO,
                    "mc-criu: driver unloading disabled by -Dmccriu.unload*=false");
            return;
        }
        try {
            if (!flag("mccriu.unloadAL", true)) throw new SkipUnload();
            ALC.destroy();
            alcUnloaded = true;
        } catch (SkipUnload skip) {
            log.log(System.Logger.Level.INFO,
                    "mc-criu: not unloading libopenal (-Dmccriu.unloadAL=false)");
        } catch (Throwable t) {
            log.log(System.Logger.Level.WARNING, "mc-criu: ALC.destroy() failed: " + t);
        }
        try {
            if (!flag("mccriu.unloadGL", false)) throw new SkipUnload();
            GL.destroy();
            glUnloaded = true;
        } catch (SkipUnload skip) {
            log.log(System.Logger.Level.INFO,
                    "mc-criu: not unloading libGL (-Dmccriu.unloadGL=false)");
        } catch (Throwable t) {
            log.log(System.Logger.Level.WARNING, "mc-criu: GL.destroy() failed: " + t);
        }
        System.gc();
    }

    /**
     * Reload what {@link #unloadNativeDrivers()} unloaded.
     *
     * LWJGL loads these libraries from a static initialiser, so the *first* use
     * needs no help — but after an explicit destroy() the function pointers are
     * dangling and the next call segfaults inside JNI rather than throwing.
     * Unload and reload are therefore both owned here, tracked by a flag, rather
     * than inferred by asking LWJGL whether a provider exists.
     */
    private void reloadNativeDrivers() {
        if (glUnloaded) {
            GL.create();
            glUnloaded = false;
        }
        if (alcUnloaded) {
            ALC.create();
            // Must happen between create() and the first alcOpenDevice().
            AlcDispatchReset.apply();
            alcUnloaded = false;
        }
    }

    /**
     * Carry GLFW's clock across the teardown.
     *
     * {@code glfwInit()} sets the timer's zero point, so the host's
     * {@code glfwTerminate()} / {@code glfwInit()} pair silently rewinds
     * {@code glfwGetTime()} to 0 — while every Java field that remembers a
     * reading from it survives CRIU byte-perfect. Anything that compares a
     * stored timestamp against the clock therefore sees the clock jump
     * backwards by the process's whole uptime.
     *
     * That is not hypothetical: it is what the frozen display was. Minecraft's
     * frame limiter is
     *
     *     double d = lastDrawTime + 1.0 / fps;
     *     for (double e = glfwGetTime(); e < d; e = glfwGetTime())
     *         glfwWaitEventsTimeout(d - e);
     *
     * with {@code lastDrawTime} a private static double. After a rebuild it
     * holds the pre-checkpoint reading while the clock restarts at zero, so a
     * limiter meant to wait 8 ms waits out the entire elapsed session —
     * measured at 183 seconds on a three-minute-old client, and predicted to
     * within 1.4 s on a five-minute-old one. The window is up, events still
     * pump (which is why input worked and sounds played), and not one frame is
     * drawn. It recovers by itself once the new clock catches up, so on a
     * client that has been running for an hour it is an hour: indistinguishable
     * from permanent.
     *
     * Restoring the reading rather than letting it run means the time spent
     * parked and dumped is not counted — the same choice the supervisor already
     * makes for CLOCK_MONOTONIC with a time namespace: continue, do not jump.
     *
     * Done here rather than in a host because every GLFW host has this hazard
     * the moment it terminates and re-initialises, whether or not it happens to
     * read the clock today.
     */
    private void restoreGlfwClock() {
        if (glfwTimeAtTeardown < 0) return;
        double before = glfwTimeAtTeardown;
        glfwTimeAtTeardown = -1;
        try {
            org.lwjgl.glfw.GLFW.glfwSetTime(before);
            log.log(System.Logger.Level.INFO, String.format(
                    "mc-criu: GLFW clock carried across the rebuild: %.3fs "
                    + "(a fresh glfwInit would have restarted it at 0)", before));
        } catch (Throwable t) {
            // Never fail a rebuild over the clock: a stalled frame limiter is
            // recoverable, no window is not.
            log.log(System.Logger.Level.WARNING, "mc-criu: could not restore the GLFW clock: " + t);
        }
    }

    /** The GLFW clock, or -1 if it cannot be read (no GLFW, or not initialised). */
    private double readGlfwTime() {
        try {
            double t = org.lwjgl.glfw.GLFW.glfwGetTime();
            return t > 0 ? t : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Snapshot the fd/map counts at a teardown stage, so doctor can show progress. */
    private Map<String, Object> stage(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", name);
        FdAudit.Result r = FdAudit.audit();
        m.put("fatal", r.fatal().size());
        m.put("suspicious", r.suspicious().size());
        if (!r.fatal().isEmpty())
            m.put("fatalDetail", r.fatal().stream().map(FdAudit.Entry::detail).limit(20).toList());
        return m;
    }

    private String safeDescribe() {
        try {
            return host.describeState();
        } catch (Throwable t) {
            return "describeState() threw: " + t;
        }
    }

    public Rendezvous rendezvous() { return rdv; }
}
