package main

// mc-criu-manager — one static binary for launching, checkpointing and restoring
// a modded Minecraft client. Replaces the mc-criu Python supervisor and the
// mcinit C helper.
//
// criu itself is a hard prerequisite and is NOT bundled: it is a separate C
// program with its own dependency chain and its own kernel-compatibility
// surface. "Static binary that runs anywhere" describes this program, not the
// system it needs.

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func usage() {
	fmt.Fprint(os.Stderr, `mc-criu-manager `+managerVersion+`

  mc-criu-manager auto [--map SANDBOX=HOST]... -- <java> <args...>
        The launcher's wrapper command. Offers any saved checkpoint, then
        restores it or launches the game. --map translates a sandbox path
        prefix to its host equivalent when the automatic Flatpak lookup is
        ambiguous.

  MC_CRIU_DEBUG=1 dumps the command line, working directory and everything
  the manager derived from them. Set it when reporting a problem.

  mc-criu-manager start --session DIR [--cwd DIR] [--env K=V]... -- <cmd...>
  mc-criu-manager checkpoint --session DIR [--keep-running]
  mc-criu-manager restore --session DIR [--generation N] [--no-file-rollback]
  mc-criu-manager stop --session DIR
  mc-criu-manager status --session DIR
  mc-criu-manager doctor --session DIR [--brief]
        The low-level session commands, as mc-criu had them.

  mc-criu-manager extract-mod [PATH]
        Write the embedded mod jar out (default: ./mc-criu-mod.jar). Only needed
        to install it by hand; auto puts it in the instance mods folder.

Configuration:
  <gameDir>/.mc-criu/config    per instance
  <binary dir>/mc-criu.conf    global defaults (actAsJava, realJavaPath)
`)
}

func main() { os.Exit(run()) }

func run() int {
	// actAsJava: the binary is installed as the launcher's "java". This must be
	// decided before any flag parsing, because the argv belongs to the JVM.
	if code, handled := maybeActAsJava(); handled {
		return code
	}

	if len(os.Args) < 2 {
		usage()
		return 2
	}
	cmd := os.Args[1]
	args := os.Args[2:]

	switch cmd {
	case "__picker-demo":
		return runPickerDemo()

	case "__init":
		return runInit(args)

	case "auto":
		// Flags before `--`; everything after it is the launcher's java line.
		fs := flag.NewFlagSet("auto", flag.ExitOnError)
		var maps multiFlag
		fs.Var(&maps, "map", "SANDBOX=HOST path prefix (repeatable), for a Flatpak launcher")
		var rest []string
		for i, a := range args {
			if a == "--" {
				fs.Parse(args[:i])
				rest = args[i+1:]
				break
			}
		}
		if rest == nil { // no `--`: treat everything as the command
			rest = stripLeadingDashDash(args)
		}
		return report(cmdAuto(rest, parseMappings(maps)))

	case "extract-mod":
		// A convenience for installing the mod by hand. The normal flow does not
		// use it: `auto` writes the jar straight into the instance's mods folder.
		dest := modJarName
		if len(args) > 0 {
			dest = args[0]
		}
		if err := WriteModTo(dest); err != nil {
			return report(err)
		}
		abs, _ := filepath.Abs(dest)
		fmt.Println(abs)
		return 0

	case "start":
		fs := flag.NewFlagSet("start", flag.ExitOnError)
		session := fs.String("session", defaultSession(), "session directory")
		cwd := fs.String("cwd", "", "working directory for the workload")
		wait := fs.Float64("wait-running", 0, "seconds to wait for the agent to report RUNNING")
		var envs multiFlag
		fs.Var(&envs, "env", "KEY=VALUE (repeatable)")
		fs.Parse(args)
		return report(cmdStart(NewSession(*session), startOpts{
			Cwd: *cwd, Env: envs, WaitRunning: secs(*wait), Cmd: stripLeadingDashDash(fs.Args()),
		}))

	case "checkpoint":
		fs := flag.NewFlagSet("checkpoint", flag.ExitOnError)
		session := fs.String("session", defaultSession(), "session directory")
		keep := fs.Bool("keep-running", false, "leave the workload running after the dump")
		timeout := fs.Float64("timeout", tPark.Seconds(), "agent park timeout")
		criuTimeout := fs.Float64("criu-timeout", tCriu.Seconds(), "criu wall-clock timeout")
		fs.Parse(args)
		sess := NewSession(*session)
		if err := sess.Load(); err != nil {
			return report(err)
		}
		var fp *Fingerprint
		if gd := gameDirFromArgs(sess.Meta.Argv); gd != "" {
			f := FingerprintInstance(gd, sess.Meta.Argv)
			fp = &f
		}
		return report(cmdCheckpoint(sess, checkpointOpts{
			KeepRunning: *keep, Timeout: secs(*timeout), CriuTimeout: secs(*criuTimeout),
			Fingerprint: fp,
		}))

	case "restore":
		fs := flag.NewFlagSet("restore", flag.ExitOnError)
		session := fs.String("session", defaultSession(), "session directory")
		gen := fs.Int("generation", 0, "generation to restore (default: newest complete)")
		fs.IntVar(gen, "g", 0, "generation to restore")
		noRollback := fs.Bool("no-file-rollback", false, "do not roll files back to their dump-time state")
		timeout := fs.Float64("timeout", tResume.Seconds(), "agent resume timeout")
		criuTimeout := fs.Float64("criu-timeout", tCriu.Seconds(), "criu wall-clock timeout")
		fs.Parse(args)
		return report(cmdRestore(NewSession(*session), restoreOpts{
			Generation: *gen, NoFileRollback: *noRollback,
			Timeout: secs(*timeout), CriuTimeout: secs(*criuTimeout),
		}))

	case "stop":
		fs := flag.NewFlagSet("stop", flag.ExitOnError)
		session := fs.String("session", defaultSession(), "session directory")
		timeout := fs.Float64("timeout", 30, "seconds to wait for a clean exit")
		fs.Parse(args)
		return report(cmdStop(NewSession(*session), secs(*timeout)))

	case "doctor":
		fs := flag.NewFlagSet("doctor", flag.ExitOnError)
		session := fs.String("session", defaultSession(), "session directory")
		brief := fs.Bool("brief", false, "collapse the OK entries in the fd/map scan")
		fs.Parse(args)
		return cmdDoctor(NewSession(*session), *brief)

	case "status":
		fs := flag.NewFlagSet("status", flag.ExitOnError)
		session := fs.String("session", defaultSession(), "session directory")
		fs.Parse(args)
		return report(cmdStatus(NewSession(*session)))

	case "--version", "-v", "version":
		fmt.Println("mc-criu-manager " + managerVersion)
		return 0

	case "--help", "-h", "help":
		usage()
		return 0
	}

	fmt.Fprintf(os.Stderr, "unknown command %q\n\n", cmd)
	usage()
	return 2
}

// maybeActAsJava handles the case where the launcher's Java path points here.
//
// The launcher probes the JVM before it will use it (`java -version`,
// `-XshowSettings:properties`, Prism's JavaCheck.jar). If those do not reach the
// real JVM the launcher decides the JVM is broken and refuses to launch
// anything, so anything that is not a game launch is exec'd straight through.
func maybeActAsJava() (int, bool) {
	// A real subcommand always wins: it is how the binary is driven deliberately.
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "auto", "start", "checkpoint", "restore", "stop", "status", "doctor",
			"__init", "extract-mod", "--version", "-v", "version", "--help", "-h", "help":
			return 0, false
		}
	}
	cfg, err := LoadConfig("")
	if err != nil {
		fmt.Fprintf(os.Stderr, "mc-criu: %v\n", err)
		return 1, true
	}
	if !cfg.ActAsJava {
		return 0, false
	}
	if cfg.RealJavaPath == "" {
		fmt.Fprintln(os.Stderr,
			"mc-criu: actAsJava is set but realJavaPath is empty.\n"+
				"Set realJavaPath to the real java binary in "+globalConfigPath()+".\n"+
				"Neither value is auto-detected on purpose.")
		return 1, true
	}
	argv := append([]string{cfg.RealJavaPath}, os.Args[1:]...)
	if !looksLikeGameLaunch(argv) {
		if err := execPassthrough(argv); err != nil {
			fmt.Fprintf(os.Stderr, "mc-criu: %v\n", err)
			return 1, true
		}
		return 0, true
	}
	return report(cmdAuto(argv, nil)), true
}

func report(err error) int {
	if err == nil {
		return 0
	}
	fmt.Fprintf(os.Stderr, "\nmc-criu-manager: %v\n", err)
	return 1
}

func defaultSession() string {
	if s := os.Getenv("MC_CRIU_SESSION"); s != "" {
		return s
	}
	wd, _ := os.Getwd()
	return filepath.Join(wd, "runtime", "session")
}

func secs(f float64) time.Duration { return time.Duration(f * float64(time.Second)) }

// stripLeadingDashDash drops the `--` separator a launcher leaves in place.
func stripLeadingDashDash(a []string) []string {
	if len(a) > 0 && a[0] == "--" {
		return a[1:]
	}
	return a
}

// parseMappings turns repeated --map SANDBOX=HOST into a prefix table.
func parseMappings(vals []string) map[string]string {
	if len(vals) == 0 {
		return nil
	}
	out := map[string]string{}
	for _, v := range vals {
		if i := strings.IndexByte(v, '='); i > 0 {
			out[v[:i]] = v[i+1:]
		} else {
			fmt.Fprintf(os.Stderr, "mc-criu: ignoring --map %q: expected SANDBOX=HOST\n", v)
		}
	}
	return out
}

type multiFlag []string

func (m *multiFlag) String() string { return strings.Join(*m, ",") }
func (m *multiFlag) Set(v string) error {
	*m = append(*m, v)
	return nil
}
