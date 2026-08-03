package main

// The session directory and its on-disk metadata.
//
// This is a straight port of the Session class and session.json schema from
// supervisor/mc-criu. The schema is kept byte-compatible on purpose: a session
// created by the Python supervisor must be readable here and vice versa, so the
// two can be run against each other while the port is being trusted.

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// FileRec is one open regular file, recorded so a restore can roll it back to
// the length criu wrote into the image.
type FileRec struct {
	Path      string   `json:"path"`
	Size      int64    `json:"size"`
	Deleted   bool     `json:"deleted"`
	Fds       []string `json:"fds,omitempty"`
	SizeAtScan *int64  `json:"size_at_scan,omitempty"`
	// Identity, so a file that was rotated away and recreated at the same path
	// is told apart from the one criu recorded. Absent (0) in images written
	// before this existed, and then treated as "unknown", not "mismatch".
	Ino uint64 `json:"ino,omitempty"`
	Dev uint64 `json:"dev,omitempty"`
	// Writable means at least one fd onto it can write, which decides how it is
	// archived: a hard link cannot preserve the contents of a file its writer
	// keeps changing, so those are copied instead.
	Writable    bool   `json:"writable,omitempty"`
	Archive     string `json:"archive,omitempty"`
	ArchiveKind string `json:"archive_kind,omitempty"`
}

// MapRec is one file-backed mapping. Unlike FileRec these cannot be reconciled
// by length: the workload may have unlinked the file entirely, so the contents
// are archived beside the image and put back.
type MapRec struct {
	Path        string `json:"path"`
	Size        int64  `json:"size"`
	Ino         uint64 `json:"ino"`
	Dev         uint64 `json:"dev"`
	MtimeNs     int64  `json:"mtime_ns"`
	Pids        []int  `json:"pids,omitempty"`
	Archive     string `json:"archive,omitempty"`
	ArchiveKind string `json:"archive_kind,omitempty"`
}

// Generation is one checkpoint image set.
type Generation struct {
	Status        string    `json:"status"` // "complete" | anything else = unusable
	Created       float64   `json:"created"`
	CreatedISO    string    `json:"created_iso"`
	ImageDir      string    `json:"image_dir"`
	CriuArgv      []string  `json:"criu_argv,omitempty"`
	LeaveRunning  bool      `json:"leave_running"`
	Bytes         int64     `json:"bytes"`
	Files         int       `json:"files"`
	ParkSeconds   float64   `json:"park_seconds"`
	DumpSeconds   float64   `json:"dump_seconds"`
	BootID        string    `json:"boot_id"`
	Argv          []string  `json:"argv,omitempty"`
	Cwd           string    `json:"cwd,omitempty"`
	MaxFd         int       `json:"max_fd"`
	OpenFiles     []FileRec `json:"open_files"`
	OpenFileProbs []string  `json:"open_file_scan_problems,omitempty"`
	MappedFiles   []MapRec  `json:"mapped_files"`
	MappedProbs   []string  `json:"mapped_file_scan_problems,omitempty"`
	// Fingerprint of the instance this image belongs to. Absent on images
	// written before fingerprinting existed, which is why a missing value is
	// treated as "unknown" rather than "mismatch".
	Fingerprint   *Fingerprint `json:"fingerprint,omitempty"`
	Phase         string       `json:"phase,omitempty"`
}

// Meta is session.json.
type Meta struct {
	Version         int                    `json:"version"`
	SessionDir      string                 `json:"session_dir"`
	Argv            []string               `json:"argv"`
	Cwd             string                 `json:"cwd"`
	EnvOverrides    map[string]string      `json:"env_overrides"`
	Mcinit          string                 `json:"mcinit,omitempty"`
	UnshareArgv     []string               `json:"unshare_argv,omitempty"`
	Criu            string                 `json:"criu"`
	UnsharePid      int                    `json:"unshare_pid"`
	HostPid         int                    `json:"host_pid"`
	HostPidStart    uint64                 `json:"host_pid_starttime"`
	BootID          string                 `json:"boot_id"`
	StartedAt       float64                `json:"started_at"`
	StartedAtISO    string                 `json:"started_at_iso"`
	Unprivileged    bool                   `json:"unprivileged"`
	State           string                 `json:"state"`
	Generations     map[string]*Generation `json:"generations"`
	NextGeneration  int                    `json:"next_generation"`
	LastRestore     map[string]any         `json:"last_restore"`
}

type Session struct {
	Root string
	Meta *Meta
}

func NewSession(root string) *Session {
	abs, err := filepath.Abs(root)
	if err != nil {
		abs = root
	}
	return &Session{Root: abs, Meta: &Meta{
		Generations: map[string]*Generation{}, NextGeneration: 1,
	}}
}

func (s *Session) Rendezvous() string  { return filepath.Join(s.Root, "rendezvous") }
func (s *Session) Images() string      { return filepath.Join(s.Root, "images") }
func (s *Session) JSONFile() string    { return filepath.Join(s.Root, "session.json") }
func (s *Session) WorkloadLog() string { return filepath.Join(s.Root, "workload.log") }
func (s *Session) NspidFile() string   { return filepath.Join(s.Root, "nspid") }
func (s *Session) ReportFile() string  { return filepath.Join(s.Rendezvous(), "report.json") }
func (s *Session) ImageDir(gen int) string {
	return filepath.Join(s.Images(), strconv.Itoa(gen))
}

func (s *Session) Load() error {
	b, err := os.ReadFile(s.JSONFile())
	if err != nil {
		if os.IsNotExist(err) {
			return nil // a fresh session directory is not an error
		}
		return err
	}
	m := &Meta{}
	if err := json.Unmarshal(b, m); err != nil {
		return fmt.Errorf("%s is not readable JSON: %w", s.JSONFile(), err)
	}
	if m.Generations == nil {
		m.Generations = map[string]*Generation{}
	}
	if m.NextGeneration == 0 {
		m.NextGeneration = 1
	}
	s.Meta = m
	return nil
}

func (s *Session) Save() error {
	if err := os.MkdirAll(s.Root, 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s.Meta, "", "  ")
	if err != nil {
		return err
	}
	return writeAtomic(s.JSONFile(), append(b, '\n'))
}

// CompleteGenerations returns the usable generation numbers, ascending.
//
// A generation whose image directory has been deleted is not usable and is not
// returned. Deleting `images/19` by hand should leave 18 as the newest
// checkpoint; instead every launch picked 19 out of the record, failed on a
// directory that is not there, and launched normally. The record outlives the
// images, so the record alone cannot answer "what can be restored".
//
// ONLY a missing directory is skipped, and that limit is the point. A dump that
// failed is already excluded by its status; an image that is present but
// unrestorable — a fingerprint mismatch, a truncated image, criu refusing it —
// must still fail loudly, because quietly restoring an older checkpoint than the
// one asked for is a worse outcome than an error.
func (s *Session) CompleteGenerations() []int {
	usable, _ := s.generations()
	return usable
}

// OrphanedGenerations returns the generations recorded complete whose image
// directory is gone, ascending. Callers say so out loud: a checkpoint vanishing
// from the list with no explanation is indistinguishable from this feature
// breaking.
func (s *Session) OrphanedGenerations() []int {
	_, orphaned := s.generations()
	return orphaned
}

func (s *Session) generations() (usable, orphaned []int) {
	for k, g := range s.Meta.Generations {
		if g == nil || g.Status != "complete" {
			continue
		}
		n, err := strconv.Atoi(k)
		if err != nil {
			continue
		}
		if fi, err := os.Stat(s.ImageDir(n)); err != nil || !fi.IsDir() {
			orphaned = append(orphaned, n)
			continue
		}
		usable = append(usable, n)
	}
	sort.Ints(usable)
	sort.Ints(orphaned)
	return usable, orphaned
}

func (s *Session) NewestComplete() (int, bool) {
	c := s.CompleteGenerations()
	if len(c) == 0 {
		return 0, false
	}
	return c[len(c)-1], true
}

// IsAlive reports whether the recorded host pid is still the process we started.
// The starttime check is what stops a recycled pid from being mistaken for the
// session: pids wrap, and a stale session.json pointing at somebody else's
// process would otherwise get signalled by `stop`.
func (s *Session) IsAlive() bool {
	pid := s.Meta.HostPid
	if pid <= 0 {
		return false
	}
	st, err := procStarttime(pid)
	if err != nil {
		return false
	}
	if s.Meta.HostPidStart != 0 && st != s.Meta.HostPidStart {
		return false
	}
	return true
}

func (s *Session) ClearResumeFiles() {
	d := s.Rendezvous()
	ents, err := os.ReadDir(d)
	if err != nil {
		return
	}
	for _, e := range ents {
		if strings.HasPrefix(e.Name(), "resume-") {
			os.Remove(filepath.Join(d, e.Name()))
		}
	}
	os.Remove(filepath.Join(d, "request"))
}

func iso(t float64) string {
	return time.Unix(int64(t), 0).UTC().Format("2006-01-02T15:04:05+00:00")
}
