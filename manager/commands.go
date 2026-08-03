package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Every wait in this program has a timeout and a name. The one documented
// exception is the agent's park, which the agent performs and we only observe.
const (
	tPidDiscovery = 10 * time.Second
	tPark         = 120 * time.Second // agent RUNNING -> PARKED (GL teardown, dlclose, audit)
	tResume       = 120 * time.Second // resume-<N> written -> agent back to RUNNING
	tCriu         = 600 * time.Second // criu dump / criu restore wall clock
	tProcGone     = 15 * time.Second  // dump-and-stop -> tree actually gone
)

const managerVersion = "1.0.0 (go, single binary)"

func criuPath() string {
	if p := os.Getenv("MC_CRIU_CRIU"); p != "" {
		return p
	}
	if p, err := exec.LookPath("criu"); err == nil {
		return p
	}
	return "/usr/bin/criu"
}

// hasCapSysAdmin decides the mode. What matters is whether we can create
// namespaces, not our uid: euid 0 inside a container routinely has CAP_SYS_ADMIN
// dropped, and testing the uid there picks the privileged path and dies with
// "unshare failed: Operation not permitted". criu accepts --unprivileged when
// euid is 0, so treating such a session as unprivileged is correct and safe.
func hasCapSysAdmin() bool {
	raw, ok := readText("/proc/self/status")
	if !ok {
		return false
	}
	for _, line := range strings.Split(raw, "\n") {
		if strings.HasPrefix(line, "CapEff:") {
			v, err := strconv.ParseUint(strings.TrimSpace(strings.TrimPrefix(line, "CapEff:")), 16, 64)
			if err != nil {
				return false
			}
			const capSysAdmin = 21
			return v&(1<<capSysAdmin) != 0
		}
	}
	return false
}

func unprivileged() bool { return !hasCapSysAdmin() }

// ------------------------------------------------------------------- start

type startOpts struct {
	Cwd         string
	Env         []string
	WaitRunning time.Duration
	Cmd         []string
	// Stdin is what the workload gets on fd 0. Prism-family launchers write the
	// launch configuration there, so /dev/null leaves the game unconfigured --
	// but it must be a regular FILE, never the launcher's pipe: a pipe whose
	// other end is outside the dump set is exactly what criu refuses.
	Stdin *os.File
}

func cmdStart(sess *Session, o startOpts) error {
	// Every other command loads the session first; this one did not, and both
	// things it reads out of Meta below were therefore always zero.
	//
	// The generations were the visible half: the carry-over further down says
	// "keep older images restorable", but with an empty Meta there was nothing
	// to carry, so starting on a directory that already held checkpoints forgot
	// them and reset the numbering to 1. The images stayed on disk, unreferenced.
	//
	// IsAlive was the quiet half: it reads Meta.HostPid, so the guard right
	// underneath could never fire from the CLI and `start` would cheerfully put
	// a second copy of the game on top of a live session.
	//
	// A missing session.json is not an error (Load says so); an unreadable one
	// is, here as everywhere else.
	if err := sess.Load(); err != nil {
		return err
	}
	if sess.IsAlive() {
		return stepErr("start: preflight",
			"session %s is already running (host pid %d). Stop it first.",
			sess.Root, sess.Meta.HostPid)
	}
	if len(o.Cmd) == 0 {
		return stepErr("start: preflight", "no command given")
	}
	cwd := o.Cwd
	if cwd == "" {
		cwd, _ = os.Getwd()
	}
	if fi, err := os.Stat(cwd); err != nil || !fi.IsDir() {
		return stepErr("start: preflight", "working directory %s does not exist", cwd)
	}

	os.MkdirAll(sess.Rendezvous(), 0o755)
	os.MkdirAll(sess.Images(), 0o755)
	sess.ClearResumeFiles()
	os.Remove(sess.NspidFile())
	os.Remove(filepath.Join(sess.Rendezvous(), "state"))

	envOverrides := map[string]string{}
	env := os.Environ()
	for _, kv := range o.Env {
		env = append(env, kv)
		if i := strings.IndexByte(kv, '='); i > 0 {
			envOverrides[kv[:i]] = kv[i+1:]
		}
	}
	env = append(env,
		"MC_CRIU_SESSION="+sess.Root,
		"MC_CRIU_RENDEZVOUS="+sess.Rendezvous())

	unpriv := unprivileged()
	self, err := os.Executable()
	if err != nil {
		return stepErr("start: preflight", "cannot find my own path: %v", err)
	}
	initArgv := append([]string{self, "__init", sess.NspidFile()}, o.Cmd...)

	var sysProc *syscall.SysProcAttr
	if unpriv {
		// No namespaces at all. An ordinary user cannot create a PID namespace
		// without first creating a user namespace, and criu's parasite refuses
		// to dump a non-root task whose /proc does not match its PID namespace —
		// which is exactly what a PID namespace over the host's /proc produces.
		// See docs/NONROOT.md for the trade-off.
		env = append(env, "MC_CRIU_NO_PIDNS=1")
		sysProc = &syscall.SysProcAttr{Setsid: true}
	} else {
		// A time namespace so criu can shift CLOCK_MONOTONIC on restore rather
		// than letting the JVM watch the clock jump forward by however long the
		// image sat on disk.
		//
		// It has to be unshared HERE, by the parent, because unshare(CLONE_NEWTIME)
		// does not move the caller — only its future children. Doing it inside
		// __init would leave PID 1 in the old namespace and the JVM in a new one,
		// which criu rejects with "Can't dump nested time namespace".
		//
		// LockOSThread because namespace membership is a property of the thread:
		// Go may otherwise fork from a different thread than the one that
		// unshared, and the child would silently land in the original namespace.
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		if _, _, errno := syscall.Syscall(syscall.SYS_UNSHARE, cloneNewTime, 0, 0); errno != 0 {
			return stepErr("start: unshare time namespace",
				"%v (the kernel may lack CONFIG_TIME_NS)", errno)
		}
		sysProc = &syscall.SysProcAttr{Setsid: true, Cloneflags: syscall.CLONE_NEWPID}
	}

	// O_APPEND: an image can be restored many times, and criu restores the file
	// offset it dumped. Without O_APPEND the second restore overwrites the
	// first restore's output.
	logfd, err := os.OpenFile(sess.WorkloadLog(), os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o644)
	if err != nil {
		return stepErr("start: open workload log", "%v", err)
	}
	defer logfd.Close()
	stdin := o.Stdin
	if stdin == nil {
		devnull, err := os.Open(os.DevNull)
		if err != nil {
			return stepErr("start: open /dev/null", "%v", err)
		}
		defer devnull.Close()
		stdin = devnull
	}

	proc := exec.Command(initArgv[0], initArgv[1:]...)
	proc.Dir = cwd
	proc.Env = env
	proc.Stdin, proc.Stdout, proc.Stderr = stdin, logfd, logfd
	proc.SysProcAttr = sysProc
	if err := proc.Start(); err != nil {
		if !unpriv {
			// We believed we were privileged but cannot actually unshare — a
			// container, a seccomp filter, a restrictive LSM. Ground truth wins.
			fmt.Println("note: this process has CAP_SYS_ADMIN but a PID namespace cannot be created here;")
			fmt.Println("      falling back to the unprivileged layout (no namespaces).")
			unpriv = true
			env = append(env, "MC_CRIU_NO_PIDNS=1")
			proc = exec.Command(initArgv[0], initArgv[1:]...)
			proc.Dir, proc.Env = cwd, env
			proc.Stdin, proc.Stdout, proc.Stderr = stdin, logfd, logfd
			proc.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
			if err := proc.Start(); err != nil {
				return stepErr("start: spawn namespace", "%v", err)
			}
		} else {
			return stepErr("start: spawn namespace", "%v", err)
		}
	}
	// Go spawns __init directly, so its host pid is known without discovering a
	// grandchild through /proc the way an external `unshare` required.
	initPid := proc.Process.Pid
	go proc.Wait() // reap the host-side child; the session is tracked by pid+starttime

	starttime, _ := procStarttime(initPid)
	gens := sess.Meta.Generations
	if gens == nil {
		gens = map[string]*Generation{}
	}
	next := sess.Meta.NextGeneration
	if next == 0 {
		next = 1
	}
	last := sess.Meta.LastRestore
	sess.Meta = &Meta{
		Version:        1,
		SessionDir:     sess.Root,
		Argv:           o.Cmd,
		Cwd:            cwd,
		EnvOverrides:   envOverrides,
		UnshareArgv:    initArgv,
		Criu:           criuPath(),
		UnsharePid:     initPid,
		HostPid:        initPid,
		HostPidStart:   starttime,
		BootID:         bootID(),
		StartedAt:      float64(time.Now().Unix()),
		StartedAtISO:   iso(float64(time.Now().Unix())),
		Unprivileged:   unpriv,
		State:          "running",
		Generations:    gens, // keep older images restorable
		NextGeneration: next,
		LastRestore:    last,
	}
	if err := sess.Save(); err != nil {
		return err
	}

	fmt.Printf("mc-criu-manager %s\n", managerVersion)
	mode := "root - pid+time namespace"
	if unpriv {
		mode = "unprivileged - no namespaces, criu --unprivileged"
	}
	fmt.Printf("mode         %s\n", mode)
	fmt.Printf("session      %s\n", sess.Root)
	full := strings.Join(o.Cmd, " ")
	os.WriteFile(filepath.Join(sess.Root, "command.txt"), []byte(full+"\n"), 0o644)
	if len(full) > 160 {
		fmt.Printf("command      %s ... (%d args, full text in %s)\n",
			o.Cmd[0], len(o.Cmd), filepath.Join(sess.Root, "command.txt"))
	} else {
		fmt.Printf("command      %s\n", full)
	}
	fmt.Printf("host pid     %d\n", initPid)

	if o.WaitRunning > 0 {
		if _, err := sess.Rdv().WaitState([]string{StateRunning}, o.WaitRunning,
			"start: wait for agent RUNNING", sess.WorkloadLog()); err != nil {
			return err
		}
		fmt.Println("agent        RUNNING")
	}
	return nil
}

// -------------------------------------------------------------- checkpoint

type checkpointOpts struct {
	KeepRunning bool
	Timeout     time.Duration
	CriuTimeout time.Duration
	Fingerprint *Fingerprint
}

func cmdCheckpoint(sess *Session, o checkpointOpts) error {
	if err := sess.Load(); err != nil {
		return err
	}
	if !sess.IsAlive() {
		return stepErr("checkpoint: preflight",
			"session %s is not running", sess.Root)
	}
	// PROTOCOL step 1: only a RUNNING agent is safe to interrupt.
	if st := sess.Rdv().State(); st != StateRunning {
		return stepErr("checkpoint: preflight",
			"agent state is %s, expected %q.\nOnly a RUNNING agent can be asked to "+
				"prepare a checkpoint (PROTOCOL.md step 1).", quoteOrNone(st), StateRunning)
	}
	gen := sess.Meta.NextGeneration
	if gen == 0 {
		gen = 1
	}
	// Burn the number before anything can fail, so a failed attempt never reuses
	// a generation and a later image cannot collide with an abandoned one.
	sess.Meta.NextGeneration = gen + 1
	if err := sess.Save(); err != nil {
		return err
	}
	// Stale resume-* would release the agent the instant it parks and criu would
	// dump a half-rebuilt GL context.
	sess.ClearResumeFiles()
	imgdir := sess.ImageDir(gen)
	if err := os.MkdirAll(imgdir, 0o755); err != nil {
		return stepErr("checkpoint: create image dir", "%v", err)
	}
	rdv := sess.Rdv()
	pid := sess.Meta.HostPid

	// PROTOCOL step 1: ask, and wait for the agent to park itself.
	rdv.ClearResume(gen)
	if err := rdv.Request(gen); err != nil {
		return stepErr("checkpoint: write request", "%v", err)
	}
	t0 := time.Now()
	if _, err := rdv.WaitStateAlive([]string{StateParked}, o.Timeout,
		"checkpoint: wait for agent PARKED", sess.WorkloadLog(), sess.IsAlive); err != nil {
		rdv.ClearRequest()
		sess.Meta.Generations[strconv.Itoa(gen)] = &Generation{
			Status: "failed", Phase: "park",
			Created: float64(time.Now().Unix()), CreatedISO: iso(float64(time.Now().Unix())),
			ImageDir: imgdir,
		}
		sess.Save()
		return err
	}
	// The agent has committed; the request has served its purpose. Clearing it
	// now means the dumped image contains no pending request on disk, so a
	// restore of this image cannot re-trigger the prepare it already completed.
	rdv.ClearRequest()
	parkS := time.Since(t0).Seconds()
	fmt.Printf("agent parked in %.2fs\n", parkS)

	if rep, ok := readText(sess.ReportFile()); ok {
		fmt.Printf("agent report %s (%d bytes)\n", sess.ReportFile(), len(rep))
	}

	// Which regular files the tree holds open, and which files it maps. Both
	// need the tree to still exist, so they happen before the dump.
	pids := treePids(pid)
	fileRecs, fileProbs := scanOpenRegularFiles(pids)
	mapRecs, mapProbs := scanMappedFiles(pids)
	maxFd := highestOpenFd(pids)
	fmt.Printf("recorded %d open regular file(s) for restore-time size reconciliation"+
		"; highest fd in the tree is %d\n", len(fileRecs), maxFd)
	fmt.Printf("recorded %d file-backed mapping(s) for restore-time replacement\n", len(mapRecs))
	for _, p := range fileProbs {
		fmt.Fprintf(os.Stderr, "  WARNING: incomplete open-file scan: %s\n", p)
	}
	for _, p := range mapProbs {
		fmt.Fprintf(os.Stderr, "  WARNING: incomplete mapped-file scan: %s\n", p)
	}
	// criu needs fd numbers above maxFd for its own service descriptors, and
	// more of a margin than is obvious: a restore that needed 1074 was refused
	// under a limit of 1024 for a tree whose highest fd was ~960. The margin is
	// only what makes the "your hard limit is the problem" error trigger — the
	// limit actually asked for is raiseNofile's own floor of 65536.
	if err := raiseNofile(uint64(maxFd)+1024, "checkpoint: raise fd limit"); err != nil {
		rdv.Resume(gen) // the agent is parked; do not strand it
		return err
	}

	argv := []string{criuPath(), "dump", "-t", strconv.Itoa(pid), "-D", imgdir,
		"-v4", "-o", "dump.log", "--tcp-close"}
	// The session's own mode, not this process's: starting as root and
	// checkpointing as a user (or the reverse) must not change the flags.
	if sess.Meta.Unprivileged {
		argv = append(argv, "--unprivileged")
		// Minecraft watches directories with inotify. Dumping a watch normally
		// means turning its inode back into a path with open_by_handle_at(),
		// which needs CAP_DAC_READ_SEARCH — a capability that would let anyone
		// read any file on the system through criu. irmap makes criu find the
		// path by scanning instead; point it at the game directory so the scan
		// is cheap and actually covers the watched paths.
		argv = append(argv, "--force-irmap")
		if sess.Meta.Cwd != "" {
			argv = append(argv, "--irmap-scan-path", sess.Meta.Cwd)
		}
	}
	if o.KeepRunning {
		argv = append(argv, "--leave-running")
	}
	fmt.Printf("running: %s\n", strings.Join(argv, " "))

	t0 = time.Now()
	rc, out := runCriu(argv, o.CriuTimeout)
	dumpS := time.Since(t0).Seconds()

	if rc != 0 {
		// A failed checkpoint must leave the game RUNNING. The agent is parked
		// waiting for resume-<N>; release it before reporting anything.
		fmt.Fprintf(os.Stderr, "\ncriu dump failed (rc=%d); releasing the parked agent ...\n", rc)
		rdv.Resume(gen)
		if _, err := rdv.WaitState([]string{StateRunning}, o.Timeout,
			"checkpoint: release agent after dump failure", sess.WorkloadLog()); err == nil {
			fmt.Fprintln(os.Stderr, "agent released and back to RUNNING - the game is still running.")
		}
		sess.Meta.Generations[strconv.Itoa(gen)] = &Generation{
			Status: "failed", Phase: "dump",
			Created: float64(time.Now().Unix()), CreatedISO: iso(float64(time.Now().Unix())),
			ImageDir: imgdir, CriuArgv: argv,
		}
		sess.Save()
		return &StepError{Step: "checkpoint: criu dump",
			Detail: fmt.Sprintf("criu exited %d\n%s", rc, lastLines(out, 25)),
			LogRef: filepath.Join(imgdir, "dump.log")}
	}

	if !o.KeepRunning {
		// dump-and-stop: wait for the tree to actually be gone before stat'ing
		// the files, so the sizes recorded are the ones criu wrote.
		waitUntil(func() (bool, bool) {
			if _, err := os.Stat(fmt.Sprintf("/proc/%d", pid)); err != nil {
				return true, true
			}
			return false, false
		}, tProcGone, "the dumped tree to exit", "checkpoint: wait for tree to exit", sess.WorkloadLog())
	}

	// Refresh sizes ONLY on a dump-and-stop.
	//
	// criu reads each file's size while the tree is frozen. On a dump-and-stop
	// the tree is dead by the time criu returns and has written nothing since the
	// freeze, so a stat here reproduces criu's number exactly — which a stat taken
	// *before* the dump would not, because a parked JVM's logging threads keep
	// running through criu's few milliseconds of startup.
	//
	// With --leave-running the opposite is true: criu unfreezes the tree as part
	// of the dump, so by the time it returns the game has been logging again for
	// the whole duration. Stat'ing then records a size PAST what criu wrote, and
	// restore-time reconciliation would truncate to that larger number and still
	// be wrong — measured on All the Mods 10 as
	//     File .../logs/debug.log has bad size 12832529 (expect 12832378)
	// and the restore died inside criu. The pre-dump scan, taken while the agent
	// was parked and before criu started, is the closer number, so keep it.
	if !o.KeepRunning {
		restatProbs := restatOpenFiles(fileRecs)
		for _, p := range restatProbs {
			fmt.Fprintf(os.Stderr, "  WARNING: %s\n", p)
		}
		fileProbs = append(fileProbs, restatProbs...)
	} else {
		fmt.Println("note: --keep-running unfroze the tree during the dump, so recorded sizes " +
			"are the pre-dump ones rather than a post-dump stat")
	}

	// Archive both sets now: the tree is frozen or dead, so what is on disk is
	// what criu recorded, and the open-file sizes have just been refreshed.
	// Hard links wherever nothing is writing, so this is free even for a pack
	// whose mod jars are all open and all mapped.
	archiveProbs := archiveMappedFiles(sess, gen, mapRecs)
	mapProbs = append(mapProbs, archiveProbs...)
	fileArchiveProbs := archiveOpenFiles(sess, gen, fileRecs)
	fileProbs = append(fileProbs, fileArchiveProbs...)
	for _, p := range append(archiveProbs, fileArchiveProbs...) {
		fmt.Fprintf(os.Stderr, "  WARNING: could not archive: %s\n", p)
	}

	size, count := dirSize(imgdir)
	fmt.Printf("criu dump OK in %.2fs - %s across %d files\n", dumpS, humanBytes(size), count)

	sess.Meta.Generations[strconv.Itoa(gen)] = &Generation{
		Status: "complete",
		Created: float64(time.Now().Unix()), CreatedISO: iso(float64(time.Now().Unix())),
		ImageDir: imgdir, CriuArgv: argv, LeaveRunning: o.KeepRunning,
		Bytes: size, Files: count, ParkSeconds: parkS, DumpSeconds: dumpS,
		BootID: bootID(), Argv: sess.Meta.Argv, Cwd: sess.Meta.Cwd, MaxFd: maxFd,
		OpenFiles: sortedFileRecs(fileRecs), OpenFileProbs: fileProbs,
		MappedFiles: sortedMapRecs(mapRecs), MappedProbs: mapProbs,
		Fingerprint: o.Fingerprint,
	}
	if o.KeepRunning {
		// --leave-running: the tree is still there and the agent is still parked
		// waiting for its resume token. Without this the game would sit frozen
		// forever having successfully checkpointed itself.
		if !sess.IsAlive() {
			sess.Save()
			return &StepError{Step: "checkpoint: verify --keep-running",
				Detail: fmt.Sprintf("criu dump reported success with --leave-running but host "+
					"pid %d is gone. The image exists but the game is dead.", pid),
				LogRef: filepath.Join(imgdir, "dump.log")}
		}
		if err := rdv.Resume(gen); err != nil {
			return stepErr("checkpoint: release agent", "%v", err)
		}
		if _, err := rdv.WaitStateAlive([]string{StateRunning}, o.Timeout,
			"checkpoint: wait for agent RUNNING after --keep-running",
			sess.WorkloadLog(), sess.IsAlive); err != nil {
			sess.Save()
			return err
		}
		sess.Meta.State = "running"
	} else {
		sess.Meta.State = "checkpointed"
		sess.Meta.HostPid = 0
		sess.Meta.HostPidStart = 0
	}
	if err := sess.Save(); err != nil {
		return err
	}
	fmt.Printf("generation   %d written to %s\n", gen, imgdir)
	return nil
}

// ----------------------------------------------------------------- restore

type restoreOpts struct {
	Generation    int // 0 = newest complete
	NoFileRollback bool
	Timeout       time.Duration
	CriuTimeout   time.Duration
}

func cmdRestore(sess *Session, o restoreOpts) error {
	if err := sess.Load(); err != nil {
		return err
	}
	if sess.IsAlive() {
		return stepErr("restore: preflight",
			"session is still running (host pid %d). Stop it first, or you will have "+
				"two copies of the same process.", sess.Meta.HostPid)
	}
	gen := o.Generation
	if gen == 0 {
		g, ok := sess.NewestComplete()
		if !ok {
			return stepErr("restore: preflight",
				"no complete generation under %s; run `checkpoint` first", sess.Images())
		}
		gen = g
	}
	g := sess.Meta.Generations[strconv.Itoa(gen)]
	if g == nil {
		return stepErr("restore: preflight", "generation %d is not on record. Available: %v",
			gen, sess.CompleteGenerations())
	}
	if g.Status != "complete" {
		return stepErr("restore: preflight",
			"generation %d is marked %q (phase %q) - it is not a usable image. Available: %v",
			gen, g.Status, g.Phase, sess.CompleteGenerations())
	}
	imgdir := sess.ImageDir(gen)
	if fi, err := os.Stat(imgdir); err != nil || !fi.IsDir() {
		// Only reachable for an EXPLICIT --generation: without one, the newest
		// generation whose images still exist is chosen, so a deleted image
		// falls through to the one below it. Asked for by number, though, a
		// deleted image is an error rather than a silent substitution.
		return stepErr("restore: preflight",
			"image directory is missing: %s\nGeneration %d was deleted. Still restorable: %v",
			imgdir, gen, sess.CompleteGenerations())
	}
	if !fileExists(filepath.Join(imgdir, "inventory.img")) {
		return stepErr("restore: preflight",
			"%s has no inventory.img - it is not a complete criu image set", imgdir)
	}
	if sess.Meta.Cwd != "" {
		if fi, err := os.Stat(sess.Meta.Cwd); err != nil || !fi.IsDir() {
			return stepErr("restore: preflight",
				"the workload's recorded cwd %s no longer exists; criu restores cwd by path and will fail",
				sess.Meta.Cwd)
		}
	}
	if bootID() != g.BootID && g.BootID != "" {
		fmt.Println("note: host rebooted since this image was written; restoring from disk.")
	}

	// Before anything is mutated: put every file the tree held open back to its
	// dump-time length, and every mapping back to the contents criu recorded, or
	// refuse. This is what makes "restore the same image repeatedly" work.
	rollback := !o.NoFileRollback
	if err := reconcileOpenFiles(sess, gen, g, rollback); err != nil {
		return err
	}
	if err := reconcileMappedFiles(sess, gen, g, rollback); err != nil {
		return err
	}

	// The same fd ceiling applies to restore: criu chooses service_fd_base from
	// the fd numbers in the image, so an image taken by a process holding ~1000
	// fds cannot be restored under the usual soft limit of 1024.
	if err := raiseNofile(uint64(g.MaxFd)+1024, "restore: raise fd limit"); err != nil {
		return err
	}

	rdv := sess.Rdv()
	sess.ClearResumeFiles()
	// The restored agent is guaranteed to rewrite state, but a stale RUNNING from
	// the previous incarnation would satisfy the wait below instantly — before
	// anything had been rebuilt.
	rdv.ClearState()
	pidfile := filepath.Join(sess.Root, "restore.pid")
	os.Remove(pidfile)

	argv := []string{criuPath(), "restore", "-D", imgdir, "-d", "-v4", "-o", "restore.log"}
	if sess.Meta.Unprivileged {
		argv = append(argv, "--unprivileged")
	}
	argv = append(argv, "--tcp-close", "--pidfile", pidfile)
	fmt.Printf("generation   %d  (%s)\n", gen, humanBytes(g.Bytes))
	fmt.Printf("running: %s\n", strings.Join(argv, " "))

	t0 := time.Now()
	rc, out := runCriu(argv, o.CriuTimeout)
	restoreS := time.Since(t0).Seconds()
	if rc != 0 {
		return &StepError{Step: "restore: criu restore",
			Detail: fmt.Sprintf("criu exited %d\n%s", rc, lastLines(out, 25)),
			LogRef: filepath.Join(imgdir, "restore.log")}
	}
	raw, ok := readText(pidfile)
	if !ok || strings.TrimSpace(raw) == "" {
		return &StepError{Step: "restore: read pidfile",
			Detail:  fmt.Sprintf("criu restore succeeded but wrote no pid to %s", pidfile),
			LogRef:  filepath.Join(imgdir, "restore.log")}
	}
	newPid, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil {
		return stepErr("restore: read pidfile", "unparseable pid %q", strings.TrimSpace(raw))
	}
	if _, err := os.Stat(fmt.Sprintf("/proc/%d", newPid)); err != nil {
		return &StepError{Step: "restore: verify restored tree",
			Detail: fmt.Sprintf("criu restore reported success but pid %d is not alive", newPid),
			LogRef: filepath.Join(imgdir, "restore.log")}
	}
	st, _ := procStarttime(newPid)
	sess.Meta.HostPid = newPid
	sess.Meta.HostPidStart = st
	sess.Meta.BootID = bootID()
	sess.Meta.State = "running"
	sess.Save()
	fmt.Printf("criu restore OK in %.2fs - new host pid %d\n", restoreS, newPid)

	// Release the parked agent and wait for it to finish rebuilding.
	if err := rdv.Resume(gen); err != nil {
		return stepErr("restore: release agent", "%v", err)
	}
	t0 = time.Now()
	if _, err := rdv.WaitStateAlive([]string{StateRunning}, o.Timeout,
		"restore: wait for agent RUNNING after resume", sess.WorkloadLog(), sess.IsAlive); err != nil {
		return err
	}
	rebuildS := time.Since(t0).Seconds()
	sess.Meta.LastRestore = map[string]any{
		"generation": gen, "restore_seconds": restoreS, "rebuild_seconds": rebuildS,
		"at": float64(time.Now().Unix()), "at_iso": iso(float64(time.Now().Unix())),
	}
	sess.Save()
	fmt.Printf("resume-%d written; agent rebuilt and RUNNING after %.2fs (total %.2fs)\n",
		gen, rebuildS, restoreS+rebuildS)
	return nil
}

// -------------------------------------------------------------------- stop

func cmdStop(sess *Session, timeout time.Duration) error {
	if err := sess.Load(); err != nil {
		return err
	}
	if !sess.IsAlive() {
		fmt.Println("not running.")
		sess.Meta.State = "stopped"
		sess.Save()
		return nil
	}
	pid := sess.Meta.HostPid
	fmt.Printf("sending SIGTERM to host pid %d ...\n", pid)
	syscall.Kill(pid, syscall.SIGTERM)
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if !sess.IsAlive() {
			fmt.Println("stopped.")
			sess.Meta.State = "stopped"
			sess.Save()
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	fmt.Fprintf(os.Stderr, "still alive after %s; sending SIGKILL\n", timeout)
	syscall.Kill(pid, syscall.SIGKILL)
	// SIGKILL to PID 1 of a PID namespace tears down every process in it, which
	// is a kernel guarantee. If it does not work something is deeply wrong and
	// papering over it with a retry loop would hide that.
	killDeadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(killDeadline) {
		if !sess.IsAlive() {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if sess.IsAlive() {
		return stepErr("stop", "host pid %d survived SIGKILL after 10s", pid)
	}
	sess.Meta.State = "stopped"
	sess.Meta.HostPid = 0
	sess.Meta.HostPidStart = 0
	sess.Save()
	fmt.Println("stopped (killed).")
	return nil
}

// ------------------------------------------------------------------ status

func cmdStatus(sess *Session) error {
	if err := sess.Load(); err != nil {
		return err
	}
	fmt.Printf("mc-criu-manager %s\n", managerVersion)
	fmt.Printf("session      %s\n", sess.Root)
	mode := "root - pid+time namespace"
	if sess.Meta.Unprivileged {
		mode = "unprivileged - no namespaces"
	}
	fmt.Printf("mode         %s\n", mode)
	alive := sess.IsAlive()
	fmt.Printf("running      %v", alive)
	if alive {
		fmt.Printf("  (host pid %d)", sess.Meta.HostPid)
	}
	fmt.Println()
	if st := sess.Rdv().State(); st != "" {
		fmt.Printf("agent        %s\n", st)
	}
	if sc := sess.Rdv().Screen(); sc != "" {
		fmt.Printf("screen       %s\n", sc)
	}
	gens := sess.CompleteGenerations()
	if len(gens) == 0 {
		fmt.Println("generations  none")
		return nil
	}
	fmt.Println("generations")
	for _, n := range gens {
		g := sess.Meta.Generations[strconv.Itoa(n)]
		fmt.Printf("  %-3d %s  %-10s  %s\n", n, g.CreatedISO, humanBytes(g.Bytes), g.ImageDir)
	}
	return nil
}

// ------------------------------------------------------------------- criu

func runCriu(argv []string, timeout time.Duration) (int, string) {
	cmd := exec.Command(argv[0], argv[1:]...)
	var sb strings.Builder
	cmd.Stdout, cmd.Stderr = &sb, &sb
	if err := cmd.Start(); err != nil {
		return 127, fmt.Sprintf("cannot run %s: %v", argv[0], err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case <-done:
	case <-time.After(timeout):
		cmd.Process.Kill()
		<-done
		return 124, sb.String() + fmt.Sprintf("\n(timed out after %s)", timeout)
	}
	return cmd.ProcessState.ExitCode(), sb.String()
}

func lastLines(s string, n int) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}
