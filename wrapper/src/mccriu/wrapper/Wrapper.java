package mccriu.wrapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * A java agent for launchers with no wrapper-command option.
 *
 * It does one thing: relaunch this JVM's exact command line under
 * {@code mc-criu-manager auto -- …} and get out of the way. That is enough for a
 * launcher that lets you add JVM arguments but not a wrapper command.
 *
 * <p>It spawns and exits rather than exec'ing in place, because pure Java cannot
 * execve. A launcher that reacts to the original process exiting by killing the
 * process group will therefore not work with this route — use the manager's
 * {@code actAsJava} mode instead, where the launcher starts the manager directly
 * and tracks the right pid. This is deliberately the simple version.
 */
public final class Wrapper {

    /** Set on the relaunched process so it does not wrap itself again, forever. */
    private static final String GUARD = "MC_CRIU_WRAPPED";

    public static void premain(String args) { run(); }
    public static void agentmain(String args) { run(); }

    private static void run() {
        if (System.getenv(GUARD) != null) return;   // this IS the relaunched process

        List<String> argv;
        try {
            argv = selfCommandLine();
        } catch (IOException e) {
            System.err.println("mc-criu-wrapper: cannot read /proc/self/cmdline: " + e
                    + " -- continuing without checkpoint support");
            return;
        }
        if (argv.isEmpty()) return;

        File manager = findManager();
        if (manager == null) {
            System.err.println("mc-criu-wrapper: mc-criu-manager not found. Set MC_CRIU_MANAGER "
                    + "to its path, or put it next to this jar. Continuing without checkpoint support.");
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(manager.getAbsolutePath());
        cmd.add("auto");
        cmd.add("--");
        cmd.addAll(argv);

        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().put(GUARD, "1");
        try {
            pb.start();
        } catch (IOException e) {
            System.err.println("mc-criu-wrapper: cannot start " + manager + ": " + e
                    + " -- continuing without checkpoint support");
            return;
        }
        // The relaunch owns the game now. Anything this JVM does from here on is
        // a second copy of it.
        Runtime.getRuntime().halt(0);
    }

    /** This JVM's argv, exactly as the kernel has it: NUL-separated. */
    private static List<String> selfCommandLine() throws IOException {
        byte[] raw = Files.readAllBytes(Paths.get("/proc/self/cmdline"));
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == 0) {
                if (i > start) out.add(new String(raw, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start < raw.length) out.add(new String(raw, start, raw.length - start, StandardCharsets.UTF_8));
        return out;
    }

    /** MC_CRIU_MANAGER, then beside this jar, then $PATH. No deeper search. */
    private static File findManager() {
        String env = System.getenv("MC_CRIU_MANAGER");
        if (env != null && !env.isEmpty()) {
            File f = new File(env);
            if (f.canExecute()) return f;
        }
        try {
            Path jar = Paths.get(Wrapper.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File beside = jar.getParent().resolve("mc-criu-manager").toFile();
            if (beside.canExecute()) return beside;
        } catch (Exception ignored) {
            // No code source, or an unusual URL. Fall through to $PATH.
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, "mc-criu-manager");
                if (f.canExecute()) return f;
            }
        }
        return null;
    }

    private Wrapper() {}
}
