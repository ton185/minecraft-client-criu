package main

// `doctor` — why a checkpoint would fail, before one is attempted.
//
// The central idea, and the reason the verdict is not a simple count: a RENDERING
// client is *supposed* to hold GPU, audio and X handles. Those are exactly what
// the agent releases when it parks. So the findings are read against the agent's
// state rather than in the abstract — calling a healthy rendering client FATAL
// would cry wolf every single time and teach people to ignore the tool.
//
// The handle list is still printed while rendering, because that list is the
// "before" picture that report.json's teardownStages is read against, and on an
// untested driver it is the most useful thing this command does.

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"

	"strconv"
	"strings"
	"time"
)

const (
	vOK    = "OK"
	vSUS   = "SUSPICIOUS"
	vFATAL = "FATAL"
)

func rank(v string) int {
	switch v {
	case vFATAL:
		return 2
	case vSUS:
		return 1
	}
	return 0
}

type finding struct {
	verdict string
	detail  string
	reason  string
}

func (f finding) String() string {
	return fmt.Sprintf("  [%-10s] %s -- %s", f.verdict, f.detail, f.reason)
}

// criu ghost-copies a deleted file's contents up to this size; past it a dump
// fails. Matches criu's own --ghost-limit default.
const criuGhostLimit = 1 << 20

var (
	safeChardevs = map[string]bool{
		"/dev/null": true, "/dev/zero": true, "/dev/full": true,
		"/dev/random": true, "/dev/urandom": true,
	}
	ttyish = regexp.MustCompile(`^/dev/(tty.*|pts/\d+|ptmx|console)$`)
	gpuish = regexp.MustCompile(`^/dev/(dri/|nvidia|nvidiactl|nvidia-|kfd|udmabuf|snd/|input/|video\d|dvb/|fb\d)`)

	safeAnon = map[string]bool{
		"[eventfd]": true, "[eventpoll]": true, "[timerfd]": true, "[signalfd]": true,
		"inotify": true, "[fanotify]": true, "[pidfd]": true, "[userfaultfd]": true,
	}
	fatalAnon = map[string]bool{
		"sync_file": true, "dmabuf": true, "[io_uring]": true, "[perf_event]": true,
		"dma_fence": true, "[vfio-device]": true,
	}
	tcpStates = map[string]string{
		"01": "ESTABLISHED", "02": "SYN_SENT", "03": "SYN_RECV", "04": "FIN_WAIT1",
		"05": "FIN_WAIT2", "06": "TIME_WAIT", "07": "CLOSE", "08": "CLOSE_WAIT",
		"09": "LAST_ACK", "0A": "LISTEN", "0B": "CLOSING",
	}
)

type doctorRun struct {
	findings []finding
	worst    string
}

func (d *doctorRun) note(verdict, detail, reason string) {
	if rank(verdict) > rank(d.worst) {
		d.worst = verdict
	}
	if verdict != vOK {
		d.findings = append(d.findings, finding{verdict, detail, reason})
	}
}

func cmdDoctor(sess *Session, brief bool) int {
	d := &doctorRun{worst: vOK}
	line := strings.Repeat("=", 78)

	fmt.Println(line)
	fmt.Println("mc-criu doctor - environment")
	fmt.Println(line)

	criu := criuPath()
	fmt.Printf("%-17s%s\n", "criu binary", criu)
	ver := "(absent)"
	if fi, err := os.Stat(criu); err == nil && fi.Mode()&0o111 != 0 {
		out, _ := exec.Command(criu, "--version").Output()
		ver = strings.TrimSpace(string(out))
	} else {
		d.note(vFATAL, "criu is not installed or not executable: "+criu,
			"mc-criu-manager cannot checkpoint anything without it (it is a hard prerequisite "+
				"and is deliberately not bundled)")
	}
	fmt.Printf("%-17s%s\n", "criu version", strings.ReplaceAll(ver, "\n", " | "))

	mode := "root (PID+time namespace)"
	if unprivileged() {
		if os.Geteuid() == 0 {
			mode = "uid 0 but no CAP_SYS_ADMIN - a sandbox or container has dropped it, " +
				"so the unprivileged path is used"
		} else {
			mode = fmt.Sprintf("unprivileged (uid %d)", os.Geteuid())
		}
	}
	fmt.Printf("%-17s%s\n", "privilege", mode)

	if ver != "(absent)" {
		for _, c := range []struct {
			extra   []string
			fatal   bool
		}{{nil, true}, {[]string{"--all"}, false}} {
			argv := append([]string{"check"}, c.extra...)
			if unprivileged() {
				argv = append(argv, "--unprivileged")
			}
			cmd := exec.Command(criu, argv...)
			out, _ := cmd.CombinedOutput()
			rc := cmd.ProcessState.ExitCode()
			name := "criu check"
			if len(c.extra) > 0 {
				name += " " + strings.Join(c.extra, " ")
			}
			fmt.Printf("\n--- %s (rc=%d) ---\n%s\n", name, rc, strings.TrimRight(string(out), "\n"))
			if rc != 0 {
				if c.fatal {
					d.note(vFATAL, name+" failed", "criu itself says this kernel cannot do it")
				} else {
					d.note(vSUS, name+" failed",
						"some optional criu features are host-specific; often harmless")
				}
			}
		}
	}

	fmt.Printf("\n%-17s%s\n", "kernel release", unameRelease())
	cfg := kernelConfig()
	for _, k := range []string{"CONFIG_CHECKPOINT_RESTORE", "CONFIG_PID_NS", "CONFIG_TIME_NS",
		"CONFIG_NAMESPACES", "CONFIG_UNIX_DIAG", "CONFIG_INET_DIAG", "CONFIG_PROC_CHILDREN"} {
		v := cfg[k]
		if v == "" {
			v = "not set"
		}
		fmt.Printf("%-17s%s = %s\n", "", k, v)
	}
	for _, must := range []string{"CONFIG_CHECKPOINT_RESTORE", "CONFIG_PID_NS",
		"CONFIG_TIME_NS", "CONFIG_NAMESPACES"} {
		v := cfg[must]
		if v == "y" || v == "m" {
			continue
		}
		if strings.HasPrefix(v, "unknown") || v == "" {
			d.note(vSUS, must+" is "+orNotSet(v), "no kernel config was readable to confirm it")
		} else {
			d.note(vFATAL, must+" is "+v,
				"mc-criu cannot work without it (PID+time namespaces and CRIU support)")
		}
	}

	fmt.Printf("\n%-17s%s\n", "manager", selfPath())
	if _, err := exec.LookPath("ss"); err != nil {
		d.note(vSUS, "`ss` (iproute2) is not installed",
			"doctor cannot resolve unix-socket peers without it, so connected unix "+
				"sockets are reported as SUSPICIOUS instead of being decided")
	}

	// ---------------------------------------------------------------- session
	fmt.Println()
	fmt.Println(line)
	fmt.Printf("mc-criu doctor - session %s\n", sess.Root)
	fmt.Println(line)

	_ = sess.Load() // a missing session is not an error here
	agentState := ""
	switch {
	case sess.Meta.HostPid == 0 && sess.Meta.SessionDir == "":
		fmt.Println("no session.json here - environment-only checks were performed.")
		fmt.Println("(start one with `mc-criu-manager start --session " + sess.Root + " -- <cmd>`)")
	case !sess.IsAlive():
		fmt.Printf("session is NOT RUNNING (recorded state %q, pid %d).\n",
			sess.Meta.State, sess.Meta.HostPid)
		fmt.Println("fd/maps analysis needs a live process; environment checks above still apply.")
		fmt.Printf("generations on disk: %v\n", sess.CompleteGenerations())
	default:
		pid := sess.Meta.HostPid
		pids := treePids(pid)
		agentState = sess.Rdv().State()
		fmt.Printf("%-17s%d\n", "host pid", pid)
		fmt.Printf("%-17s%s\n", "agent state", "'"+agentState+"'")
		fmt.Printf("%-17s%v\n", "tree", pids)
		d.scanTree(pids, brief)
	}

	// ----------------------------------------------------------- agent report
	fmt.Println("\n--- agent report.json ---")
	if rep, ok := readText(sess.ReportFile()); ok {
		fmt.Printf("%s (%d bytes)\n", sess.ReportFile(), len(rep))
		fmt.Println(rep)
	} else {
		fmt.Printf("none at %s (the agent writes it just before it parks)\n", sess.ReportFile())
	}

	// ----------------------------------------------------------------- verdict
	var fatals, suspects []finding
	for _, f := range d.findings {
		if f.verdict == vFATAL {
			fatals = append(fatals, f)
		} else {
			suspects = append(suspects, f)
		}
	}
	fmt.Println()
	fmt.Println(line)
	rendering := agentState != "" && agentState != StateParked && len(fatals) > 0
	if rendering {
		fmt.Printf("verdict: OK   (agent %s; %d handle(s) awaiting teardown, %d suspicious)\n",
			agentState, len(fatals), len(suspects))
	} else {
		fmt.Printf("verdict: %s   (%d fatal, %d suspicious)\n", d.worst, len(fatals), len(suspects))
	}

	if len(suspects) > 0 && len(fatals) == 0 {
		fmt.Println("\nSuspicious (worth a look, not necessarily blocking):")
		for i, f := range suspects {
			if i >= 60 {
				fmt.Printf("  ... and %d more\n", len(suspects)-60)
				break
			}
			fmt.Println(f)
		}
	}

	if rendering {
		fmt.Println("\nThe game is rendering, so of course it holds a GPU handle, an audio device")
		fmt.Println("and a socket to the X server. They are what teardown has to release before a dump:")
		for _, f := range fatals {
			fmt.Println(f)
		}
		fmt.Printf("\nCompare this list with the \"after\" picture the agent writes to\n%s\n",
			sess.ReportFile())
		return 0
	}
	if len(fatals) > 0 {
		fmt.Println("\nFATAL - a checkpoint attempted now would fail. What must be released:")
		for _, f := range fatals {
			fmt.Println(f)
		}
		fmt.Println("\nThe agent's teardown (PROTOCOL.md step 3) is what has to let go of these.")
		return 1
	}
	if d.worst == vSUS {
		fmt.Println("\nNothing fatal. The suspicious entries above are not blocking, but if a dump")
		fmt.Println("fails, they are the first place to look.")
	} else {
		fmt.Println("\nNo blocking problems found.")
	}
	return 0
}

// scanTree classifies every fd and every mapping the tree holds.
func (d *doctorRun) scanTree(pids []int, brief bool) {
	treeSockets := map[uint64]bool{}
	treePipes := map[uint64]int{}
	for _, p := range pids {
		fds, err := os.ReadDir(fmt.Sprintf("/proc/%d/fd", p))
		if err != nil {
			d.note(vSUS, fmt.Sprintf("cannot list /proc/%d/fd", p), err.Error())
			continue
		}
		for _, fd := range fds {
			t, err := os.Readlink(fmt.Sprintf("/proc/%d/fd/%s", p, fd.Name()))
			if err != nil {
				continue
			}
			if ino, ok := anonInode(t, "socket:["); ok {
				treeSockets[ino] = true
			}
			if ino, ok := anonInode(t, "pipe:["); ok {
				treePipes[ino]++
			}
		}
	}
	peers := unixPeerMap()
	tcp := map[uint64]string{}
	unixPaths := map[uint64]string{}
	if len(pids) > 0 {
		for _, proto := range []string{"tcp", "tcp6"} {
			for k, v := range parseNetTable(pids[0], proto) {
				tcp[k] = v
			}
		}
		unixPaths = parseUnixTable(pids[0])
	}

	var fdFindings []finding
	nfds := 0
	for _, p := range pids {
		fds, err := os.ReadDir(fmt.Sprintf("/proc/%d/fd", p))
		if err != nil {
			continue
		}
		for _, fd := range fds {
			link := fmt.Sprintf("/proc/%d/fd/%s", p, fd.Name())
			t, err := os.Readlink(link)
			if err != nil {
				continue
			}
			nfds++
			v, reason := classifyFd(link, t, tcp, unixPaths, peers, treeSockets, treePipes)
			fdFindings = append(fdFindings, finding{v, fmt.Sprintf("%d/%s -> %s", p, fd.Name(), t), reason})
		}
	}
	fmt.Printf("\n--- open files (%d fds across the tree) ---\n", nfds)
	d.emit(fdFindings, brief)

	var mapFindings []finding
	nlines := 0
	for _, p := range pids {
		raw, ok := readText(fmt.Sprintf("/proc/%d/maps", p))
		if !ok {
			continue
		}
		for _, l := range strings.Split(raw, "\n") {
			if strings.TrimSpace(l) == "" {
				continue
			}
			nlines++
			parts := strings.SplitN(l, " ", 6)
			path := ""
			if len(parts) >= 6 {
				path = strings.TrimSpace(parts[5])
			}
			v, reason := classifyMap(path)
			if v == vOK && path == "" {
				continue // anonymous memory: nothing to say, and there is a lot of it
			}
			mapFindings = append(mapFindings, finding{v, fmt.Sprintf("%d %s", p, path), reason})
		}
	}
	fmt.Printf("\n--- memory mappings (%d lines across the tree) ---\n", nlines)
	d.emit(mapFindings, brief)
}

// emit prints findings and folds them into the run's verdict. --brief collapses
// only the OK lines: it must never change the counts the verdict is computed
// from, or a "quieter" flag would quietly change the answer.
func (d *doctorRun) emit(fs []finding, brief bool) {
	nok, nsus, nfat := 0, 0, 0
	for _, f := range fs {
		switch f.verdict {
		case vOK:
			nok++
		case vSUS:
			nsus++
		default:
			nfat++
		}
		if rank(f.verdict) > rank(d.worst) {
			d.worst = f.verdict
		}
		if f.verdict != vOK {
			d.findings = append(d.findings, f)
		}
		if f.verdict != vOK || !brief {
			fmt.Println(f)
		}
	}
	if brief && nok > 0 {
		fmt.Printf("  (%d OK entries hidden by --brief)\n", nok)
	}
	fmt.Printf("  summary: %d OK, %d SUSPICIOUS, %d FATAL\n", nok, nsus, nfat)
}

func classifyFd(link, target string, tcp map[uint64]string, unixPaths map[uint64]string,
	peers map[uint64]uint64, treeSockets map[uint64]bool, treePipes map[uint64]int) (string, string) {

	if ino, ok := anonInode(target, "socket:["); ok {
		if st, isTCP := tcp[ino]; isTCP {
			if st == "CLOSE" {
				return vOK, "TCP socket already CLOSE"
			}
			return vFATAL, "live TCP socket (" + st + "); --tcp-close would silently drop it"
		}
		if _, isUnix := unixPaths[ino]; isUnix {
			peer, has := peers[ino]
			switch {
			case has && peer == 0:
				return vOK, "unconnected unix socket"
			case has && treeSockets[peer]:
				return vOK, "unix socket whose peer is inside the tree"
			case has:
				return vFATAL, "unix socket connected outside the tree (compositor, PipeWire or D-Bus)"
			default:
				return vSUS, "unix socket whose peer could not be resolved"
			}
		}
		return vSUS, "socket of unknown family"
	}
	if ino, ok := anonInode(target, "pipe:["); ok {
		if treePipes[ino] >= 2 {
			return vOK, "pipe with both ends inside the tree"
		}
		return vSUS, "pipe with only one end inside the tree"
	}
	if strings.HasPrefix(target, "anon_inode:") {
		kind := strings.TrimPrefix(target, "anon_inode:")
		if fatalAnon[kind] {
			return vFATAL, "anon inode criu cannot dump: " + kind
		}
		if safeAnon[kind] {
			return vOK, "anon inode criu handles: " + kind
		}
		return vSUS, "unrecognised anon inode: " + kind
	}
	if strings.HasPrefix(target, "/memfd:") {
		return vOK, "memfd"
	}
	if gpuish.MatchString(target) {
		return vFATAL, "graphics/audio device fd; the agent must destroy the GL context and " +
			"window, shut down audio and dlclose the drivers before parking"
	}
	if fi, err := os.Stat(link); err == nil {
		m := fi.Mode()
		if m&os.ModeCharDevice != 0 {
			if safeChardevs[target] {
				return vOK, "harmless character device"
			}
			if ttyish.MatchString(target) {
				return vSUS, "a terminal; init calls setsid() so the tree should have none"
			}
			return vFATAL, "character device criu cannot restore"
		}
		if m&os.ModeDevice != 0 {
			return vSUS, "block device; restorable only if the same device exists"
		}
		if strings.HasSuffix(target, " (deleted)") {
			if fi.Size() <= criuGhostLimit {
				return vOK, "deleted file, ghost-copied into the image"
			}
			return vFATAL, fmt.Sprintf("deleted file of %s exceeds criu's --ghost-limit",
				humanBytes(fi.Size()))
		}
	}
	return vOK, "regular file or directory"
}

func classifyMap(path string) (string, string) {
	switch {
	case path == "":
		return vOK, "anonymous"
	case strings.HasPrefix(path, "["), strings.HasPrefix(path, "anon_inode:"),
		path == "/dev/zero (deleted)":
		return vOK, "kernel-provided mapping"
	case strings.HasPrefix(path, "/memfd:"):
		return vOK, "memfd mapping"
	case gpuish.MatchString(path):
		return vFATAL, "mapped GPU/audio device node - a live driver mapping cannot be dumped"
	case strings.HasPrefix(path, "/SYSV"):
		return vSUS, "SysV shared memory segment"
	case strings.HasSuffix(path, " (deleted)"):
		return vOK, "deleted file, ghost-copied into the image"
	}
	return vOK, "file-backed mapping"
}

func anonInode(target, prefix string) (uint64, bool) {
	if !strings.HasPrefix(target, prefix) || !strings.HasSuffix(target, "]") {
		return 0, false
	}
	n, err := strconv.ParseUint(target[len(prefix):len(target)-1], 10, 64)
	return n, err == nil
}

// unixPeerMap asks `ss` which unix socket is connected to which. Without it a
// connected unix socket cannot be decided, only suspected.
func unixPeerMap() map[uint64]uint64 {
	out := map[uint64]uint64{}
	cmd := exec.Command("ss", "-x", "-a", "-n", "-H")
	done := make(chan struct{})
	var buf []byte
	go func() { buf, _ = cmd.Output(); close(done) }()
	select {
	case <-done:
	case <-time.After(20 * time.Second):
		if cmd.Process != nil {
			cmd.Process.Kill()
		}
		return out
	}
	for _, l := range strings.Split(string(buf), "\n") {
		f := strings.Fields(l)
		if len(f) < 3 {
			continue
		}
		local, err1 := strconv.ParseUint(f[len(f)-3], 10, 64)
		peer, err2 := strconv.ParseUint(f[len(f)-1], 10, 64)
		if err1 == nil && err2 == nil {
			out[local] = peer
		}
	}
	return out
}

func parseNetTable(pid int, proto string) map[uint64]string {
	out := map[uint64]string{}
	raw, ok := readText(fmt.Sprintf("/proc/%d/net/%s", pid, proto))
	if !ok {
		return out
	}
	for i, l := range strings.Split(raw, "\n") {
		if i == 0 {
			continue
		}
		f := strings.Fields(l)
		if len(f) < 10 {
			continue
		}
		ino, err := strconv.ParseUint(f[9], 10, 64)
		if err != nil {
			continue
		}
		st := tcpStates[f[3]]
		if st == "" {
			st = "state " + f[3]
		}
		out[ino] = st
	}
	return out
}

func parseUnixTable(pid int) map[uint64]string {
	out := map[uint64]string{}
	raw, ok := readText(fmt.Sprintf("/proc/%d/net/unix", pid))
	if !ok {
		return out
	}
	for i, l := range strings.Split(raw, "\n") {
		if i == 0 {
			continue
		}
		f := strings.Fields(l)
		if len(f) < 7 {
			continue
		}
		ino, err := strconv.ParseUint(f[6], 10, 64)
		if err != nil {
			continue
		}
		path := ""
		if len(f) >= 8 {
			path = f[7]
		}
		out[ino] = path
	}
	return out
}

func kernelConfig() map[string]string {
	wanted := map[string]bool{
		"CONFIG_CHECKPOINT_RESTORE": true, "CONFIG_PID_NS": true, "CONFIG_TIME_NS": true,
		"CONFIG_NAMESPACES": true, "CONFIG_UNIX_DIAG": true, "CONFIG_INET_DIAG": true,
		"CONFIG_PROC_CHILDREN": true,
	}
	out := map[string]string{}
	var text string
	if b, err := exec.Command("sh", "-c", "zcat /proc/config.gz 2>/dev/null").Output(); err == nil && len(b) > 0 {
		text = string(b)
	} else {
		for _, p := range []string{
			"/boot/config-" + unameRelease(),
			"/lib/modules/" + unameRelease() + "/build/.config",
		} {
			if s, ok := readText(p); ok {
				text = s
				break
			}
		}
	}
	if text == "" {
		for k := range wanted {
			out[k] = "unknown (no kernel config found)"
		}
		return out
	}
	for _, l := range strings.Split(text, "\n") {
		l = strings.TrimSpace(l)
		i := strings.IndexByte(l, '=')
		if i < 0 || strings.HasPrefix(l, "#") {
			continue
		}
		if k := l[:i]; wanted[k] {
			out[k] = l[i+1:]
		}
	}
	return out
}

func unameRelease() string {
	if s, ok := readText("/proc/sys/kernel/osrelease"); ok {
		return strings.TrimSpace(s)
	}
	return "unknown"
}

func selfPath() string {
	p, err := os.Executable()
	if err != nil {
		return "(unknown)"
	}
	return filepath.Clean(p)
}

func orNotSet(v string) string {
	if v == "" {
		return "not set"
	}
	return v
}


