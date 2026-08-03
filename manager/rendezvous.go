package main

// The supervisor's half of the rendezvous protocol with the in-game agent.
//
// The agent owns `state` and `screen`; the supervisor owns `request` and
// `resume-<gen>`. Everything is a small file written atomically, because the two
// sides are separate processes with no channel between them and a half-written
// state name read as a valid one would be worse than no state at all.

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Agent states, as written by mccriu.core.Rendezvous.
const (
	StateRunning   = "RUNNING"
	StatePreparing = "PREPARING"
	StateParked    = "PARKED"
	StateResuming  = "RESUMING"
	// FAILED is a prefix: the reason follows a colon.
	StateFailedPrefix = "FAILED:"
)

type Rdv struct{ dir string }

func (s *Session) Rdv() *Rdv { return &Rdv{dir: s.Rendezvous()} }

func (r *Rdv) path(n string) string { return filepath.Join(r.dir, n) }

func (r *Rdv) State() string {
	s, _ := readText(r.path("state"))
	return strings.TrimSpace(s)
}

func (r *Rdv) Screen() string {
	s, _ := readText(r.path("screen"))
	return strings.TrimSpace(s)
}

// Request asks the agent to checkpoint at generation gen. The agent picks this
// up on its watch thread and runs the teardown on the render thread.
func (r *Rdv) Request(gen int) error {
	return writeAtomic(r.path("request"), []byte(strconv.Itoa(gen)+"\n"))
}

func (r *Rdv) ClearRequest() { os.Remove(r.path("request")) }

// Resume releases a parked agent. The agent is blocked in parkUntilResumed(gen)
// waiting for exactly this file, which is why it is named per generation: a
// stale resume file from an abandoned attempt must not release a later park.
func (r *Rdv) Resume(gen int) error {
	return writeAtomic(r.path("resume-"+strconv.Itoa(gen)), []byte("go\n"))
}

func (r *Rdv) ClearResume(gen int) { os.Remove(r.path("resume-" + strconv.Itoa(gen))) }

// ClearState removes the agent's published state.
//
// Called before a restore. Every image captures a PARKED agent, so the restored
// agent is guaranteed to rewrite state (RESUMING then RUNNING) — but a stale
// RUNNING left on disk from the previous incarnation would make the wait after
// the restore succeed instantly, before the agent had rebuilt anything.
func (r *Rdv) ClearState() { os.Remove(r.path("state")) }

// WaitState polls until the agent reports one of want, or it reports a FAILED:
// state, or the deadline passes. A FAILED state is returned as an error with the
// agent's own reason, because the agent knows why and the supervisor does not.
// The per-iteration check order is load-bearing: exact match, then FAILED, then
// liveness, then the deadline. If the agent reports FAILED on the same poll that
// the deadline expires, the useful error is the agent's reason, not a timeout.
func (r *Rdv) WaitState(want []string, timeout time.Duration, step string, logRef string) (string, error) {
	return r.waitState(want, timeout, step, logRef, nil)
}

// WaitStateAlive is WaitState plus a liveness check, so a workload that dies
// mid-wait is reported as dead instead of hanging until the timeout.
func (r *Rdv) WaitStateAlive(want []string, timeout time.Duration, step, logRef string, alive func() bool) (string, error) {
	return r.waitState(want, timeout, step, logRef, alive)
}

func (r *Rdv) waitState(want []string, timeout time.Duration, step, logRef string, alive func() bool) (string, error) {
	deadline := time.Now().Add(timeout)
	var last string
	for {
		st := r.State()
		last = st
		for _, w := range want {
			if st == w {
				return st, nil
			}
		}
		if strings.HasPrefix(st, StateFailedPrefix) {
			return st, &StepError{
				Step: step,
				Detail: "the agent aborted and reported: " + st +
					"\n(per PROTOCOL.md the agent has rebuilt itself and is back to RUNNING; " +
					"the game is still running)",
				LogRef: logRef,
			}
		}
		if alive != nil && !alive() {
			return st, &StepError{
				Step: step,
				Detail: "the workload died while waiting for agent state " +
					strings.Join(want, " or ") + " (last state seen: " + quoteOrNone(last) + ")",
				LogRef: logRef,
			}
		}
		if time.Now().After(deadline) {
			return st, &StepError{
				Step: step,
				Detail: "timed out after " + timeout.String() + " waiting for agent state " +
					strings.Join(want, " or ") + "; it is currently " + quoteOrNone(last),
				LogRef: logRef,
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func quoteOrNone(s string) string {
	if s == "" {
		return "(nothing published)"
	}
	return "\"" + s + "\""
}
