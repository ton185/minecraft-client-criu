package mccriu.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/**
 * The agent's half of the supervisor protocol in docs/PROTOCOL.md.
 *
 * Everything goes through files that are opened, read or written, and closed
 * again. Nothing stays open across the checkpoint — an fd held onto the
 * supervisor would be the very "external socket" the whole teardown exists to
 * eliminate, and a pipe would leave buffered bytes CRIU has to reason about.
 * A stat every 50 ms is cheaper than one frame's worth of anything.
 */
public final class Rendezvous {

    public static final String STARTING = "STARTING";
    public static final String RUNNING = "RUNNING";
    public static final String PREPARING = "PREPARING";
    public static final String PARKED = "PARKED";
    public static final String RESUMING = "RESUMING";

    private final Path dir;

    public Rendezvous(Path sessionDir) {
        this.dir = sessionDir.resolve("rendezvous");
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException2("cannot create rendezvous dir " + this.dir, e);
        }
    }

    public static final class UncheckedIOException2 extends RuntimeException {
        private static final long serialVersionUID = 1L;
        UncheckedIOException2(String m, Throwable c) { super(m, c); }
    }

    public Path dir() { return dir; }

    /** Publish the agent's state. Written atomically so a reader never sees half a word. */
    public void setState(String state) {
        writeAtomic(dir.resolve("state"), state);
    }

    public void setFailed(String reason) {
        setState("FAILED:" + reason.replace('\n', ' '));
    }

    /**
     * Publish which screen the game is showing. The checkpoint guarantee only
     * covers the main menu, so a caller needs a way to know it is there rather
     * than inferring it from log lines.
     */
    public void setScreen(String screenClassName) {
        writeAtomic(dir.resolve("screen"), screenClassName);
    }

    /**
     * Returns the requested generation if the supervisor has asked for a
     * checkpoint, otherwise -1.
     */
    public int pendingRequest() {
        Path p = dir.resolve("request");
        if (!Files.exists(p)) return -1;
        try {
            String s = Files.readString(p).trim();
            return s.isEmpty() ? -1 : Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    public void clearRequest() {
        try {
            Files.deleteIfExists(dir.resolve("request"));
        } catch (IOException ignored) {
            // The supervisor also removes it; a leftover only costs one retry.
        }
    }

    /** True once the supervisor has restored us and released this generation. */
    public boolean resumeSignalled(int generation) {
        return Files.exists(dir.resolve("resume-" + generation));
    }

    /**
     * Block until the supervisor says we have been restored.
     *
     * This is the one wait with no timeout, and deliberately so: between
     * {@code criu dump} and {@code criu restore} the process does not execute at
     * all, and there is no meaningful deadline on how long an image may sit on
     * disk. Waking every 50 ms costs nothing while parked and bounds how long
     * the rebuild is delayed after restore.
     */
    public void parkUntilResumed(int generation) {
        while (!resumeSignalled(generation)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while parked for checkpoint", e);
            }
        }
    }

    public void writeReport(Map<String, Object> report) {
        writeAtomic(dir.resolve("report.json"), toJson(report));
    }

    private void writeAtomic(Path target, String content) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException2("cannot write " + target, e);
        }
    }

    // A tiny serialiser: the report is a flat map of primitives and lists of
    // strings, and pulling in a JSON library would put another jar on the
    // classpath of every modded instance for no benefit.
    static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, o, 0);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object o, int indent) {
        String pad = "  ".repeat(indent + 1);
        String padEnd = "  ".repeat(indent);
        if (o instanceof Map<?, ?> m) {
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(pad).append(quote(String.valueOf(e.getKey()))).append(": ");
                writeJson(sb, e.getValue(), indent + 1);
                if (++i < m.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append(padEnd).append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append("[\n");
            java.util.Iterator<?> iter = it.iterator();
            while (iter.hasNext()) {
                sb.append(pad);
                writeJson(sb, iter.next(), indent + 1);
                if (iter.hasNext()) sb.append(',');
                sb.append('\n');
            }
            sb.append(padEnd).append(']');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o == null) {
            sb.append("null");
        } else {
            sb.append(quote(o.toString()));
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
