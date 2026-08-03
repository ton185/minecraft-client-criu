package main

// `mc-criu-manager __init` — PID 1 of a session namespace. Replaces supervisor/mcinit.
//
// CRIU refuses to dump a process tree whose session leader lives outside the PID
// namespace being dumped ("A session leader of N is outside of its pid
// namespace"). So PID 1 calls setsid() before spawning anything: the session, the
// process group and the PID namespace then share a root and the tree is
// self-contained. It also reaps, because orphans in a PID namespace reparent to
// PID 1 and would otherwise pile up as zombies across a long session.
//
// Two things differ from the C version it replaces, both simplifications:
//
//   * The PID namespace is created by the PARENT via Cloneflags rather than by
//     an external `unshare`, so this process is spawned directly and its host pid
//     is known without having to discover a grandchild through /proc.
//   * The time namespace is unshared here rather than by `unshare --time`.
//     CLONE_NEWTIME cannot be passed to clone(); it only affects children created
//     after the unshare, which is exactly the shape we need — unshare, then spawn
//     the workload.

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"syscall"
)

// Not in package syscall. Only valid for unshare(2), never for clone(2).
const cloneNewTime = 0x00000080

// runInit is `__init <pidfile> <cmd> [args...]`.
func runInit(args []string) int {
	if len(args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: __init <pidfile> <cmd> [args...]")
		return 2
	}
	pidfile, argv := args[0], args[1:]

	// Unprivileged sessions deliberately run without a PID namespace, so not
	// being PID 1 is expected there and must not look like a fault.
	if os.Getpid() != 1 && os.Getenv("MC_CRIU_NO_PIDNS") == "" {
		fmt.Fprintf(os.Stderr, "__init: warning: pid is %d, expected 1 "+
			"(not in a fresh PID namespace?)\n", os.Getpid())
	}

	// The whole point: put the session leader inside this namespace.
	if _, err := syscall.Setsid(); err != nil && err != syscall.EPERM {
		fmt.Fprintf(os.Stderr, "__init: setsid: %v\n", err)
		return 1
	}

	// The time namespace is NOT unshared here, and that is load-bearing.
	//
	// unshare(CLONE_NEWTIME) deliberately does not move the caller into the new
	// namespace — only children created afterwards go there. Calling it at this
	// point would leave PID 1 in the old time namespace and the JVM below it in a
	// new one, and criu refuses a tree that spans two:
	//
	//     Error (criu/namespaces.c:739): Can't dump nested time namespace
	//
	// So the unshare happens one level up, in the process that spawns this one
	// (see cmdStart), which puts this process and everything under it in a single
	// time namespace.

	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	// Own process group so job-control signals do not leak between the workload
	// and init.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		fmt.Fprintf(os.Stderr, "__init: exec %s: %v\n", argv[0], err)
		return 127
	}
	childPid := cmd.Process.Pid

	if err := os.WriteFile(pidfile, []byte(strconv.Itoa(childPid)+"\n"), 0o644); err != nil {
		fmt.Fprintf(os.Stderr, "__init: cannot write pidfile %s: %v\n", pidfile, err)
	}

	// Forward signals to the workload so `stop` behaves like a normal kill.
	sigs := make(chan os.Signal, 8)
	signal.Notify(sigs, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	go func() {
		for s := range sigs {
			if ss, ok := s.(syscall.Signal); ok {
				syscall.Kill(childPid, ss)
			}
		}
	}()

	// Reap everything; exit with the workload's status when it goes.
	for {
		var ws syscall.WaitStatus
		p, err := syscall.Wait4(-1, &ws, 0, nil)
		if err == syscall.EINTR {
			continue
		}
		if err != nil {
			return 0 // ECHILD: nothing left to wait for
		}
		if p == childPid {
			switch {
			case ws.Exited():
				return ws.ExitStatus()
			case ws.Signaled():
				return 128 + int(ws.Signal())
			default:
				return 1
			}
		}
	}
}
