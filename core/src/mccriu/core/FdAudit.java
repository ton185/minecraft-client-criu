package mccriu.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Decides whether this process is in a state CRIU can actually dump.
 *
 * Running {@code criu dump} against a process that still holds a GPU device node
 * fails with a terse error after the process has already been frozen. Worse, some
 * things dump "successfully" and then misbehave on restore. So the agent checks
 * itself first, and a failed check aborts the checkpoint while the game is still
 * healthy enough to rebuild and carry on.
 *
 * This is also what {@code mc-criu doctor} reports on a machine I cannot test —
 * an NVIDIA box, where the interesting question is precisely which driver fds
 * survive teardown.
 */
public final class FdAudit {

    public enum Verdict { OK, SUSPICIOUS, FATAL }

    public record Entry(String kind, String detail, Verdict verdict, String reason) {}

    public record Result(List<Entry> entries, boolean clean) {
        public List<Entry> fatal() {
            return entries.stream().filter(e -> e.verdict() == Verdict.FATAL).toList();
        }
        public List<Entry> suspicious() {
            return entries.stream().filter(e -> e.verdict() == Verdict.SUSPICIOUS).toList();
        }
    }

    /** Character devices CRIU knows how to recreate. Anything else is fatal. */
    private static final Set<String> BENIGN_DEVICES = Set.of(
            "/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom",
            "/dev/tty", "/dev/console", "/dev/ptmx");

    /**
     * Device prefixes that mean a driver still owns part of our address space.
     * These are the ones that matter: NVIDIA's are listed alongside DRM because
     * the whole teardown design exists to get rid of them.
     */
    private static final String[] FATAL_DEVICE_PREFIXES = {
            "/dev/dri/", "/dev/nvidia", "/dev/snd/", "/dev/udmabuf", "/dev/dma_heap/",
            "/dev/video", "/dev/input/", "/dev/kfd", "/dev/mali", "/dev/kgsl"};

    public static Result audit() {
        List<Entry> entries = new ArrayList<>();
        auditFds(entries);
        auditMaps(entries);
        boolean clean = entries.stream().noneMatch(e -> e.verdict() == Verdict.FATAL);
        return new Result(entries, clean);
    }

    private static void auditFds(List<Entry> out) {
        Path dir = Paths.get("/proc/self/fd");
        List<String> targets = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String fd = p.getFileName().toString();
                String target;
                try {
                    target = Files.readSymbolicLink(p).toString();
                } catch (IOException e) {
                    continue; // the fd we opened to read the directory, racing shut
                }
                targets.add("fd " + fd + " -> " + target);
                classifyFd(fd, target, out);
            }
        } catch (IOException e) {
            out.add(new Entry("fd", "/proc/self/fd", Verdict.FATAL,
                    "cannot read /proc/self/fd: " + e));
        }
    }

    private static void classifyFd(String fd, String target, List<Entry> out) {
        String what = "fd " + fd + " -> " + target;

        for (String prefix : FATAL_DEVICE_PREFIXES) {
            if (target.startsWith(prefix)) {
                out.add(new Entry("fd", what, Verdict.FATAL,
                        "device node still open; the driver has not released this process. "
                        + "CRIU cannot serialise a character device."));
                return;
            }
        }
        if (target.startsWith("/dev/")) {
            if (!BENIGN_DEVICES.contains(target))
                out.add(new Entry("fd", what, Verdict.SUSPICIOUS,
                        "unrecognised device node; CRIU may or may not handle it"));
            return;
        }
        if (target.startsWith("anon_inode:")) {
            String kind = target.substring("anon_inode:".length());
            switch (kind) {
                case "sync_file", "dmabuf", "[userfaultfd]" -> out.add(new Entry("fd", what,
                        Verdict.FATAL, "GPU synchronisation/sharing object; not dumpable"));
                case "[eventpoll]", "[eventfd]", "[timerfd]", "[signalfd]", "inotify" -> { }
                default -> out.add(new Entry("fd", what, Verdict.SUSPICIOUS,
                        "unrecognised anon_inode"));
            }
            return;
        }
        if (target.startsWith("socket:")) {
            String inode = target.substring(target.indexOf('[') + 1, target.length() - 1);
            classifySocket(what, inode, out);
        }
    }

    /**
     * Connected unix sockets are reported but never treated as fatal.
     *
     * The interesting distinction is whether the peer is inside the dump set: a
     * socketpair with both ends in this process (the JVM and Netty create
     * several) dumps perfectly, while a connection to the X server or PipeWire
     * produces "External socket is used". Telling those apart needs the peer
     * inode, which is only available through sock_diag netlink — not through
     * /proc, and not from inside the JVM without shelling out.
     *
     * Guessing here is worse than not guessing: an early version called every
     * connected unix socket fatal and refused perfectly good checkpoints of a
     * modded client whose only remaining sockets were internal socketpairs. So
     * the agent catches what it can be certain about — device nodes and GPU
     * sharing objects, which is exactly what teardown exists to remove — and
     * leaves the socket verdict to CRIU itself, whose refusal is handled by
     * releasing the agent and reporting. `mc-criu doctor` runs outside the JVM
     * and does resolve peers.
     */
    private static void classifySocket(String what, String inode, List<Entry> out) {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/net/unix"))) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 7 || !f[6].equals(inode)) continue;
                int state = Integer.parseInt(f[5]);
                String path = f.length > 7 ? f[7] : "";
                boolean connected = state == 3; // SS_CONNECTED
                if (connected) {
                    out.add(new Entry("socket",
                            what + " (unix" + (path.isEmpty() ? ", unnamed" : " " + path) + ")",
                            Verdict.SUSPICIOUS,
                            "connected unix socket. Harmless if the peer is inside the dump set "
                            + "(an in-process socketpair); fatal if it reaches the X server, "
                            + "PipeWire or dbus. Peer resolution needs sock_diag, so CRIU decides."));
                } else if (!path.isEmpty()) {
                    out.add(new Entry("socket", what + " (unix " + path + ")", Verdict.SUSPICIOUS,
                            "named but unconnected unix socket"));
                }
                return;
            }
            // Not a unix socket: check the IP tables.
            for (String proto : new String[]{"tcp", "tcp6"}) {
                for (String line : Files.readAllLines(Paths.get("/proc/net/" + proto))) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 10 || !f[9].equals(inode)) continue;
                    int st = Integer.parseInt(f[3], 16);
                    if (st != 0x07 && st != 0x0A) // not CLOSE / LISTEN
                        out.add(new Entry("socket", what + " (" + proto + " state 0x"
                                + Integer.toHexString(st) + ")", Verdict.SUSPICIOUS,
                                "established TCP connection; it will be closed by --tcp-close and "
                                + "the peer will see a reset"));
                    return;
                }
            }
        } catch (IOException | NumberFormatException e) {
            out.add(new Entry("socket", what, Verdict.SUSPICIOUS,
                    "could not classify socket: " + e));
        }
    }

    private static void auditMaps(List<Entry> out) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get("/proc/self/maps"));
        } catch (IOException e) {
            out.add(new Entry("map", "/proc/self/maps", Verdict.FATAL, "cannot read: " + e));
            return;
        }
        Set<String> reported = new HashSet<>();
        for (String line : lines) {
            int slash = line.indexOf('/');
            if (slash < 0) continue;
            String path = line.substring(slash).trim();
            if (!reported.add(path)) continue;

            for (String prefix : FATAL_DEVICE_PREFIXES) {
                if (path.startsWith(prefix)) {
                    out.add(new Entry("map", path, Verdict.FATAL,
                            "device memory is mapped into this process; CRIU cannot dump it"));
                }
            }
            if (path.startsWith("/memfd:") && path.endsWith("(deleted)")) {
                // CRIU handles memfd, but a driver-owned one (lp_dma_buf,
                // "allocation fd") means the GL stack is still live.
                if (path.contains("dma_buf") || path.contains("allocation"))
                    out.add(new Entry("map", path, Verdict.FATAL,
                            "graphics allocation still mapped; the GL driver has not been released"));
            }
            if (path.startsWith("/tmp/") && !path.contains("(deleted)")) {
                out.add(new Entry("map", path, Verdict.SUSPICIOUS,
                        "mapped file lives under /tmp, which is tmpfs here and is wiped by a "
                        + "reboot; restoring after a reboot would fail. Relocate it (for LWJGL "
                        + "natives, set -Dorg.lwjgl.librarypath to a persistent directory)."));
            }
        }
    }

    /** Render a report suitable for a log or the doctor command. */
    public static String format(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.clean() ? "CLEAN: nothing fatal to CRIU\n" : "NOT CLEAN\n");
        for (Entry e : r.entries()) {
            if (e.verdict() == Verdict.OK) continue;
            sb.append(String.format("  [%-10s] %s%n              %s%n",
                    e.verdict(), e.detail(), e.reason()));
        }
        return sb.toString();
    }

    private FdAudit() {}
}
