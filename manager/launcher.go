package main

// Everything a real launcher needs that a bare `start` does not.
//
// The Python supervisor grew a separate `wrap` subcommand for this, and its
// version string said so: "capability-based mode detection, wrap, sandbox path
// mapping". The Go port shipped `auto` without those pieces, which broke the
// Prism-under-Flatpak setup outright — the game never received its parameters,
// and every classpath entry pointed inside a sandbox the host cannot see. This
// file is that behaviour, restored.

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"syscall"
	"time"
)

func flatpakAppRoots() []string {
	home, _ := os.UserHomeDir()
	return []string{"/var/lib/flatpak/app", filepath.Join(home, ".local/share/flatpak/app")}
}

func flatpakRuntimeRoots() []string {
	home, _ := os.UserHomeDir()
	return []string{"/var/lib/flatpak/runtime", filepath.Join(home, ".local/share/flatpak/runtime")}
}

var sdkExtRe = regexp.MustCompile(`^/usr/lib/sdk/([^/]+)/(.*)$`)

// flatpakCandidates gives host locations for a path that only exists inside a
// Flatpak sandbox.
//
// `flatpak-spawn --host` puts the manager (and therefore the game) on the host,
// which is what makes checkpointing possible at all — criu cannot dump a
// bubblewrap sandbox. But the launcher still hands over the paths *it* sees.
// Those files are on the host, just somewhere else: /app is the app's `files`
// directory, and an SDK extension mounted at /usr/lib/sdk/<name> is the
// corresponding runtime's `files`.
func flatpakCandidates(sandboxPath string) []string {
	var out []string
	if strings.HasPrefix(sandboxPath, "/app/") {
		rest := strings.TrimPrefix(sandboxPath, "/app/")
		for _, root := range flatpakAppRoots() {
			for _, pat := range []string{
				filepath.Join(root, "*/current/active/files", rest),
				filepath.Join(root, "*/*/current/active/files", rest),
			} {
				if m, err := filepath.Glob(pat); err == nil {
					out = append(out, m...)
				}
			}
		}
	}
	if m := sdkExtRe.FindStringSubmatch(sandboxPath); m != nil {
		name, rest := m[1], m[2]
		for _, root := range flatpakRuntimeRoots() {
			pat := filepath.Join(root, "org.freedesktop.Sdk.Extension."+name, "*/*/active/files", rest)
			if g, err := filepath.Glob(pat); err == nil {
				out = append(out, g...)
			}
		}
	}
	sort.Strings(out)
	return out
}

// mapSandboxPaths rewrites sandbox paths to their host equivalents, and only
// ever when the original does not exist and exactly one host candidate does.
// Anything ambiguous is left alone and reported: silently picking one of two
// Flatpak installs would be worse than failing.
func mapSandboxPaths(cmd []string, extra map[string]string) []string {
	rewrites := map[string]string{}
	var order []string
	var ambiguous []string

	resolve := func(path string) string {
		if !strings.HasPrefix(path, "/") || fileExists(path) {
			return path
		}
		if v, ok := rewrites[path]; ok {
			return v
		}
		for prefix, target := range extra {
			if strings.HasPrefix(path, prefix) {
				cand := strings.TrimRight(target, "/") + path[len(strings.TrimRight(prefix, "/")):]
				if fileExists(cand) {
					rewrites[path] = cand
					order = append(order, path)
					return cand
				}
			}
		}
		cands := flatpakCandidates(path)
		if len(cands) == 1 {
			rewrites[path] = cands[0]
			order = append(order, path)
			return cands[0]
		}
		if len(cands) > 1 {
			ambiguous = append(ambiguous, path)
		}
		return path
	}

	out := make([]string, 0, len(cmd))
	for i, token := range cmd {
		prev := ""
		if i > 0 {
			prev = cmd[i-1]
		}
		switch prev {
		case "-cp", "-classpath", "--class-path", "-p", "--module-path":
			parts := strings.Split(token, string(os.PathListSeparator))
			for j, e := range parts {
				parts[j] = resolve(e)
			}
			out = append(out, strings.Join(parts, string(os.PathListSeparator)))
		default:
			out = append(out, resolve(token))
		}
	}

	if len(order) > 0 {
		fmt.Printf("sandbox paths translated to their host equivalents (%d):\n", len(order))
		for i, src := range order {
			if i == 4 {
				fmt.Printf("    ... and %d more\n", len(order)-4)
				break
			}
			fmt.Printf("    %s\n      -> %s\n", src, rewrites[src])
		}
	}
	for _, a := range ambiguous {
		fmt.Printf("warning: %s matches more than one Flatpak install; not translating.\n"+
			"         Use --map /app=<host path> to say which.\n", a)
	}
	return out
}

// captureLauncherStdin drains the launcher's stdin into a file and returns it.
//
// Prism-family launchers write the launch configuration to the game's STDIN;
// passing /dev/null there means the game never receives it and dies without its
// parameters. But inheriting the launcher's pipe is not an option either: a pipe
// whose other end belongs to a process outside the dump set is exactly what criu
// refuses, so checkpointing would never work.
//
// So: drain it to a regular file, which criu serialises without complaint, and
// hand the workload that. Reads until EOF or `quiet` with nothing new, so a
// launcher that holds the pipe open does not hang the wrapper forever.
func captureLauncherStdin(sess *Session, quiet time.Duration) (*os.File, []byte) {
	target := filepath.Join(sess.Root, "stdin")
	os.MkdirAll(sess.Root, 0o755)
	var data []byte

	fd := int(os.Stdin.Fd())
	if err := syscall.SetNonblock(fd, true); err == nil {
		deadline := time.Now().Add(quiet)
		buf := make([]byte, 65536)
		for time.Now().Before(deadline) {
			n, err := syscall.Read(fd, buf)
			if n > 0 {
				data = append(data, buf[:n]...)
				deadline = time.Now().Add(quiet)
				continue
			}
			if n == 0 && err == nil {
				break // EOF: the launcher is done talking
			}
			if err == syscall.EAGAIN || err == syscall.EWOULDBLOCK {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			break
		}
		syscall.SetNonblock(fd, false)
	}

	os.WriteFile(target, data, 0o644)
	if len(data) > 0 {
		fmt.Printf("stdin        captured %d bytes from the launcher -> %s\n", len(data), target)
		fmt.Println("             (buffered to a file on purpose: an inherited pipe would be an")
		fmt.Println("              external fd and criu would refuse to dump it)")
	}
	f, err := os.Open(target)
	if err != nil {
		return nil, data
	}
	return f, data
}

// gameDirFromLauncherStdin digs --gameDir out of what Prism sent.
//
// Prism does not put the game arguments on the command line at all — it writes
// them to stdin as `param <value>` lines and its EntryPoint reads them back. So
// the instance identity that `auto` keys everything on is in there, not in argv.
func gameDirFromLauncherStdin(data []byte) string {
	lines := strings.Split(string(data), "\n")
	for i, l := range lines {
		f := strings.Fields(strings.TrimSpace(l))
		if len(f) != 2 || f[0] != "param" {
			continue
		}
		if f[1] == "--gameDir" && i+1 < len(lines) {
			nf := strings.Fields(strings.TrimSpace(lines[i+1]))
			if len(nf) == 2 && nf[0] == "param" {
				return nf[1]
			}
		}
		if strings.HasPrefix(f[1], "--gameDir=") {
			return strings.TrimPrefix(f[1], "--gameDir=")
		}
	}
	return ""
}

// checkWorkloadReachable explains a path problem before java turns it into a
// ClassNotFoundException for the launcher's own entry point.
func checkWorkloadReachable(cmd []string) error {
	if len(cmd) == 0 {
		return nil
	}
	exe := cmd[0]
	ok := false
	if strings.Contains(exe, "/") {
		ok = syscall.Access(exe, 1 /* X_OK */) == nil
	} else {
		ok = lookPathExists(exe)
	}
	if !ok {
		return stepErr("auto: workload binary",
			"%s does not exist or is not executable from where mc-criu-manager is running.\n"+
				"If the launcher is a Flatpak and the manager runs on the host (flatpak-spawn\n"+
				"--host), this path is inside the sandbox and the host cannot see it.\n"+
				"See docs/LAUNCHER.md.", exe)
	}
	var missing []string
	for i := 0; i+1 < len(cmd); i++ {
		switch cmd[i] {
		case "-cp", "-classpath", "--class-path", "-p", "--module-path":
			for _, e := range strings.Split(cmd[i+1], string(os.PathListSeparator)) {
				if e != "" && !fileExists(e) {
					missing = append(missing, e)
				}
			}
		}
	}
	if len(missing) > 0 {
		fmt.Printf("warning: %d classpath/module-path entries do not exist here.\n", len(missing))
		fmt.Println("         java will fail to find its main class. First few:")
		for i, e := range missing {
			if i == 5 {
				break
			}
			fmt.Printf("           %s\n", e)
		}
		fmt.Println("         These are paths the launcher resolved in its own filesystem view;")
		fmt.Println("         see docs/LAUNCHER.md if it is running inside a sandbox.")
	}
	return nil
}

func lookPathExists(name string) bool {
	for _, dir := range strings.Split(os.Getenv("PATH"), string(os.PathListSeparator)) {
		if dir == "" {
			continue
		}
		if syscall.Access(filepath.Join(dir, name), 1) == nil {
			return true
		}
	}
	return false
}

// injectAgentJvmArgs puts the agent's options onto a java command line we did
// not build.
//
// A launcher hands over the full `java … MainClass …` line, so the options have
// to be INSERTED rather than appended: a JVM option after the main class is a
// game argument, not a JVM option. Immediately after argv[0] is always correct.
func injectAgentJvmArgs(cmd []string, sess *Session) []string {
	if len(cmd) == 0 {
		return cmd
	}
	exe := filepath.Base(cmd[0])
	if exe != "java" && !strings.HasPrefix(exe, "java") {
		fmt.Printf("warning: %s does not look like a java binary, so the agent's options are\n", cmd[0])
		fmt.Printf("         not being injected. Add this to the instance's JVM arguments:\n")
		fmt.Printf("           -Dmccriu.session=%s\n", sess.Root)
		return cmd
	}
	tmpdir := filepath.Join(sess.Root, "tmp")
	os.MkdirAll(tmpdir, 0o755)
	want := []string{
		"-Dmccriu.session=" + sess.Root,
		// /tmp is usually tmpfs; a mapping there would not survive a reboot, and
		// both LWJGL and some mods extract native libraries into java.io.tmpdir.
		"-Djava.io.tmpdir=" + tmpdir,
		// hsperfdata is an mmap under /tmp for the same reason.
		"-XX:-UsePerfData",
	}
	already := map[string]bool{}
	for _, a := range cmd {
		if strings.HasPrefix(a, "-D") || strings.HasPrefix(a, "-XX:") {
			already[strings.SplitN(a, "=", 2)[0]] = true
		}
	}
	var injected []string
	for _, a := range want {
		if !already[strings.SplitN(a, "=", 2)[0]] {
			injected = append(injected, a)
		}
	}
	out := append([]string{cmd[0]}, injected...)
	return append(out, cmd[1:]...)
}

// debugEnabled turns on a dump of everything the manager was handed. Set
// MC_CRIU_DEBUG=1 in the launcher's environment when something needs reporting.
func debugEnabled() bool { return os.Getenv("MC_CRIU_DEBUG") != "" }

func debugDump(stage string, argv []string, extra map[string]string) {
	if !debugEnabled() {
		return
	}
	fmt.Printf("\n--- mc-criu debug: %s ---\n", stage)
	wd, _ := os.Getwd()
	fmt.Printf("cwd            %s\n", wd)
	self, _ := os.Executable()
	fmt.Printf("manager        %s\n", self)
	for k, v := range extra {
		fmt.Printf("%-14s %s\n", k, v)
	}
	fmt.Printf("argv (%d):\n", len(argv))
	for i, a := range argv {
		fmt.Printf("  [%2d] %s\n", i, a)
	}
	fmt.Println("--- end debug ---")
}
