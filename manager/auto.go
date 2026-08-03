package main

// `mc-criu-manager auto -- <java> <args…>`
//
// The launcher's wrapper-command slot. Everything after `--` is the java command
// line the launcher built, untouched except for the mod injection.

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync/atomic"
	"syscall"
	"time"
)

func cmdAuto(argv []string, mappings map[string]string) error {
	if len(argv) == 0 {
		return fmt.Errorf("auto: nothing to run; expected `auto -- <java> <args…>`")
	}
	debugDump("auto entry", argv, nil)

	// A launcher hands over paths it resolved in ITS filesystem view. Under
	// `flatpak-spawn --host` those are sandbox paths the host cannot see, and
	// java fails with ClassNotFoundException for the launcher's own entry point
	// rather than anything obviously path-related.
	argv = mapSandboxPaths(argv, mappings)
	if err := checkWorkloadReachable(argv); err != nil {
		return err
	}

	// Prism-family launchers do not put the game arguments on the command line
	// at all: they write them to STDIN and their EntryPoint reads them back. So
	// this has to happen before the instance can even be identified, and the
	// drained copy is what the game is given later.
	tmpSess := NewSession(filepath.Join(os.TempDir(), "mc-criu-stdin"))
	stdinFile, stdinData := captureLauncherStdin(tmpSess, 2*time.Second)

	gameDir := gameDirFromArgs(argv)
	source := "--gameDir on the command line"
	if gameDir == "" {
		gameDir = gameDirFromLauncherStdin(stdinData)
		source = "--gameDir in the launcher's stdin"
	}
	if gameDir == "" {
		// Prism runs the game with its working directory set to the instance's
		// minecraft folder, so that is the last honest signal before giving up.
		if wd, err := os.Getwd(); err == nil && fileExists(filepath.Join(wd, "mods")) ||
			func() bool { wd, _ := os.Getwd(); return fileExists(filepath.Join(wd, "options.txt")) }() {
			gameDir, _ = os.Getwd()
			source = "the working directory"
		}
	}
	if gameDir == "" {
		fmt.Fprintln(os.Stderr,
			"mc-criu: could not identify this instance (no --gameDir in the command line, "+
				"none in the launcher's stdin, and the working directory does not look like "+
				"a game directory); launching without checkpoint support.")
		if stdinFile != nil {
			return execWithStdin(argv, stdinFile)
		}
		return execPassthrough(argv)
	}
	abs, err := filepath.Abs(gameDir)
	if err == nil {
		gameDir = abs
	}
	fmt.Printf("instance     %s (from %s)\n", gameDir, source)

	// The game directory has to EXIST here, in this filesystem view.
	//
	// Under `flatpak-spawn --host` the launcher's instance path is a sandbox
	// path, and the host may have it somewhere else entirely. Without this check
	// MkdirAll would happily create a phantom .mc-criu tree under a directory the
	// game never uses: checkpoints would appear to succeed and then never be
	// found again, which is indistinguishable from the feature not working.
	if fi, err := os.Stat(gameDir); err != nil || !fi.IsDir() {
		return fmt.Errorf(
			"auto: the game directory %s does not exist from where mc-criu-manager is\n"+
				"running. If the launcher is a Flatpak and the manager runs on the host\n"+
				"(flatpak-spawn --host), that path is inside the sandbox. Point --gameDir at\n"+
				"the host path, or use --map to translate it:\n"+
				"    mc-criu-manager auto --map %s=<host path> --\n"+
				"Nothing has been created; refusing rather than checkpointing into a\n"+
				"directory the game does not read.", gameDir, gameDir)
	}

	cfg, err := LoadConfig(gameDir)
	if err != nil {
		return fmt.Errorf("auto: %w", err)
	}

	sess := NewSession(filepath.Join(instanceDir(gameDir), "session"))
	if err := sess.Load(); err != nil {
		return err
	}

	jar, err := InstallMod(gameDir)
	if err != nil {
		return fmt.Errorf("auto: %w", err)
	}
	// After the session is known: the agent's options, INSERTED after argv[0] --
	// a JVM option placed after the main class is a game argument, not a JVM
	// option.
	argv = injectMod(argv, sess.Root)
	argv = injectAgentJvmArgs(argv, sess)

	// Move the drained stdin into the real session directory now that it is known.
	if stdinFile != nil {
		stdinFile.Close()
		os.MkdirAll(sess.Root, 0o755)
		if b, err := os.ReadFile(filepath.Join(tmpSess.Root, "stdin")); err == nil {
			os.WriteFile(filepath.Join(sess.Root, "stdin"), b, 0o644)
		}
	}
	debugDump("after injection", argv, map[string]string{
		"session": sess.Root, "modjar": jar, "stdin bytes": fmt.Sprint(len(stdinData)),
	})
	fp := FingerprintInstance(gameDir, argv)
	pendingFingerprint = &fp

	// A restore into a running session would give two copies of one process.
	if sess.IsAlive() {
		return fmt.Errorf("auto: a session for %s is already running (host pid %d)",
			gameDir, sess.Meta.HostPid)
	}

	choice, err := decide(sess, &cfg, fp)
	if err != nil {
		return err
	}

	switch choice.Action {
	case actionLaunch:
		return startAndWait(sess, argv, gameDir)
	case actionRestore:
		fmt.Printf("mc-criu: restoring checkpoint %d\n", choice.Generation)
		err := cmdRestore(sess, restoreOpts{
			Generation: choice.Generation, Timeout: tResume, CriuTimeout: tCriu,
		})
		if err != nil {
			// Do not fall back to a normal launch: the whole point of this is to
			// catch a failed restore so the user can see it and debug it.
			fmt.Fprintf(os.Stderr, "mc-criu: restore failed:\n%v\n", err)
			return err
		}
		return waitForSession(sess)
	default:
		return nil
	}
}

type action int

const (
	actionLaunch action = iota
	actionRestore
	actionQuit
)

type decision struct {
	Action     action
	Generation int
}

// decide picks between restoring, launching and quitting, consulting the user
// unless the config says not to.
func decide(sess *Session, cfg *Config, fp Fingerprint) (decision, error) {
	gens := sess.CompleteGenerations()
	// Always say what was found. Silence here is indistinguishable from the
	// feature not working: a user whose checkpoint is not offered has no way to
	// tell whether none exists, none matches, or the manager never looked.
	fmt.Printf("checkpoints  %d found for this instance (%s)\n", len(gens), sess.Images())
	// Deleting an image directory by hand is a supported way to free the disk it
	// takes, so the record pointing at one that is gone is expected rather than
	// broken — but it is still said out loud, because a checkpoint quietly
	// missing from this list looks exactly like the feature failing.
	if orphans := sess.OrphanedGenerations(); len(orphans) > 0 {
		fmt.Printf("             ignoring %v: their image directories are gone\n", orphans)
	}
	if len(gens) == 0 {
		fmt.Println("             none yet -- launching the game. Use the Checkpoint button")
		fmt.Println("             on the main menu to save one.")
		return decision{Action: actionLaunch}, nil
	}

	// Filter to images this instance may actually restore.
	var usable []int
	var mismatch string
	for _, n := range gens {
		g := sess.Meta.Generations[strconv.Itoa(n)]
		ok, why := fp.Matches(g.Fingerprint)
		if ok {
			usable = append(usable, n)
		} else if mismatch == "" {
			mismatch = why
		}
	}

	if len(usable) == 0 {
		// Every image belongs to a different mod set. The picker is still shown,
		// listing them as unrestorable: saying nothing would make the user's
		// checkpoints look like they had silently stopped working, and hiding
		// them would leave several GB each that cannot be reclaimed from the UI.
		fmt.Fprintf(os.Stderr, "mc-criu: %d checkpoint(s) exist but none match this instance — %s\n",
			len(gens), mismatch)
	}

	if len(usable) > 0 && cfg.AlwaysLatest && len(usable) == len(gens) {
		return decision{Action: actionRestore, Generation: usable[len(usable)-1]}, nil
	}
	if cfg.AlwaysLatest {
		// alwaysLatest is set, but something no longer matches. Force the picker
		// back and explain — this is the one case that overrides the setting.
		mismatch = "some checkpoints no longer match this instance, so the menu is being " +
			"shown even though \"always load latest\" is on: " + mismatch
	}

	items := make([]PickItem, 0, len(gens))
	for _, n := range gens {
		g := sess.Meta.Generations[strconv.Itoa(n)]
		ok, why := fp.Matches(g.Fingerprint)
		items = append(items, PickItem{
			Generation: n,
			When:       g.CreatedISO,
			Size:       humanBytes(g.Bytes),
			Restorable: ok,
			Why:        why,
		})
	}

	res, err := RunPicker(PickerInput{Items: items, Notice: mismatch})
	if err != nil {
		// No display, no X server, or the dialog could not be drawn. Launching
		// normally is the safe outcome, but it must not be silent.
		fmt.Fprintf(os.Stderr, "mc-criu: cannot show the checkpoint menu (%v); launching normally.\n", err)
		return decision{Action: actionLaunch}, nil
	}
	switch res.Kind {
	case PickRestore:
		if res.AlwaysLatest {
			if err := cfg.SaveAlwaysLatest(true); err != nil {
				fmt.Fprintf(os.Stderr, "mc-criu: could not save the setting: %v\n", err)
			}
		}
		return decision{Action: actionRestore, Generation: res.Generation}, nil
	case PickDelete:
		if err := deleteGeneration(sess, res.Generation); err != nil {
			fmt.Fprintf(os.Stderr, "mc-criu: %v\n", err)
		}
		return decide(sess, cfg, fp) // show the menu again with it gone
	case PickSkip:
		return decision{Action: actionLaunch}, nil
	default:
		return decision{Action: actionQuit}, nil
	}
}

// deleteGeneration removes an image, its archived open files and mappings, and
// its metadata.
func deleteGeneration(sess *Session, gen int) error {
	key := strconv.Itoa(gen)
	g := sess.Meta.Generations[key]
	if g == nil {
		return fmt.Errorf("checkpoint %d is not on record", gen)
	}
	dir := sess.ImageDir(gen)
	if err := os.RemoveAll(dir); err != nil {
		return fmt.Errorf("cannot delete %s: %w", dir, err)
	}
	delete(sess.Meta.Generations, key)
	if err := sess.Save(); err != nil {
		return err
	}
	fmt.Printf("mc-criu: deleted checkpoint %d (%s)\n", gen, dir)
	return nil
}

func startAndWait(sess *Session, argv []string, gameDir string) error {
	// Hand the game the launcher's parameters, as a regular file.
	var stdin *os.File
	if f, err := os.Open(filepath.Join(sess.Root, "stdin")); err == nil {
		stdin = f
		defer stdin.Close()
	}
	if err := cmdStart(sess, startOpts{Cwd: gameDir, Cmd: argv, Stdin: stdin}); err != nil {
		return err
	}
	return waitForSession(sess)
}

// execWithStdin replaces this process with the given command, having already
// drained the launcher's stdin to a file and reopened it as fd 0.
func execWithStdin(argv []string, stdin *os.File) error {
	if err := syscall.Dup2(int(stdin.Fd()), 0); err != nil {
		return execPassthrough(argv)
	}
	return execPassthrough(argv)
}

// pendingFingerprint is what a checkpoint taken during this run will record. Set
// once in cmdAuto so it is present on both the launch and the restore path — a
// checkpoint taken after a restore has to be stamped just the same.
var pendingFingerprint *Fingerprint

// checkpointsInFlight is how many checkpoints are mid-flight.
//
// It exists because a dump-and-stop checkpoint KILLS the tree it is dumping, so
// "the game exited" and "a checkpoint is still being written" are true at the
// same moment. Without this, waitForSession saw the tree die, returned, and the
// process exited -- killing the checkpoint goroutine after criu had written the
// image but before the generation was recorded as complete. The result on disk
// was images/7 present, next_generation at 8, and zero complete generations, so
// every later launch reported "checkpoints 0 found" and offered nothing.
//
// This machine happened to win that race every time; the reporter's lost it
// every time. Timing is not a fix, so the wait is now explicit.
var checkpointsInFlight atomic.Int32

// waitForSession blocks until the game exits, so the launcher's process tree
// stays alive for as long as the game does and it can tell when play ended.
func waitForSession(sess *Session) error {
	// Also service checkpoint requests raised from inside the game (the main
	// menu button), which arrive as a rendezvous request file.
	go serveCheckpointRequests(sess)
	go warnIfAgentNeverReports(sess)
	for {
		if !sess.IsAlive() {
			// The game is gone, but a checkpoint may be the reason it is gone
			// and may still be finishing. Leaving now would discard it.
			for i := 0; checkpointsInFlight.Load() > 0 && i < 2400; i++ {
				time.Sleep(250 * time.Millisecond)
			}
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// serveCheckpointRequests watches for the in-game checkpoint button.
//
// The mod cannot dump its own process, so it asks: it writes `checkpoint-please`
// into the rendezvous directory and this side runs the real checkpoint, which
// then drives the agent through the usual request/park/resume handshake.
func serveCheckpointRequests(sess *Session) {
	trigger := filepath.Join(sess.Rendezvous(), "checkpoint-please")
	for {
		time.Sleep(400 * time.Millisecond)
		if !sess.IsAlive() {
			return
		}
		if !fileExists(trigger) {
			continue
		}
		os.Remove(trigger)
		fmt.Println("mc-criu: checkpoint requested from the game")
		// Claimed before the dump, released only after the result is written:
		// the dump is what kills the tree, so from here until the generation is
		// recorded this process must not be allowed to exit.
		checkpointsInFlight.Add(1)
		// Dump-and-stop, not --keep-running.
		//
		// criu reads every open file's size while the tree is frozen.
		// --leave-running unfreezes it again as part of the dump, so the game
		// carries on writing to its logs and the sizes recorded afterwards are
		// past what criu wrote -- which is what made restores fail with
		// "debug.log has bad size N (expect N-151)". Letting the tree die means
		// the sizes are exactly criu's, with nothing to approximate.
		//
		// It also matches what the checkpoint is for: you save the loaded state
		// at the main menu and stop playing; the next launch restores it.
		err := cmdCheckpoint(sess, checkpointOpts{
			KeepRunning: false, Timeout: tPark, CriuTimeout: tCriu,
			Fingerprint: pendingFingerprint,
		})
		if err != nil {
			fmt.Fprintf(os.Stderr, "mc-criu: checkpoint failed: %v\n", err)
			writeAtomic(filepath.Join(sess.Rendezvous(), "checkpoint-result"),
				[]byte("failed: "+err.Error()+"\n"))
			checkpointsInFlight.Add(-1)
			continue
		}
		writeAtomic(filepath.Join(sess.Rendezvous(), "checkpoint-result"), []byte("ok\n"))
		fmt.Printf("mc-criu: checkpoint saved; %d now available for this instance\n",
			len(sess.CompleteGenerations()))
		checkpointsInFlight.Add(-1)
	}
}

// warnIfAgentNeverReports says so when the mod never came up.
//
// Silence here used to look exactly like success: the game launched, the manager
// printed nothing, and there was simply no checkpoint button and no explanation.
// The mod publishes its state as soon as it is rendering, so if nothing has
// appeared long after even a heavy pack should have loaded, something is wrong
// with the mod rather than with the game.
func warnIfAgentNeverReports(sess *Session) {
	const grace = 5 * time.Minute
	deadline := time.Now().Add(grace)
	for time.Now().Before(deadline) {
		time.Sleep(5 * time.Second)
		if !sess.IsAlive() {
			return // the game exited on its own; not our problem to report
		}
		if sess.Rdv().State() != "" {
			return // it came up
		}
	}
	if !sess.IsAlive() {
		return
	}
	fmt.Fprintf(os.Stderr,
		"\nmc-criu: the in-game mod has not reported in after %s, so there will be no\n"+
			"         checkpoint button and checkpointing will not work.\n"+
			"         Check that %s/mods/%s is present and that the game log mentions\n"+
			"         \"mc-criu\". Run with MC_CRIU_DEBUG=1 for the full command line.\n",
		grace, filepath.Dir(filepath.Dir(sess.Root)), modJarName)
}

// execPassthrough replaces this process with the given command, so the launcher
// keeps tracking the same pid.
func execPassthrough(argv []string) error {
	path, err := exec.LookPath(argv[0])
	if err != nil {
		return fmt.Errorf("cannot find %s: %w", argv[0], err)
	}
	return syscall.Exec(path, argv, os.Environ())
}
