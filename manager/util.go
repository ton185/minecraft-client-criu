package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// StepError is a failure with a named step, so every abort says which part of
// the sequence gave up rather than just what went wrong.
type StepError struct {
	Step   string
	Detail string
	LogRef string
}

func (e *StepError) Error() string {
	s := fmt.Sprintf("%s: %s", e.Step, e.Detail)
	if e.LogRef != "" {
		s += "\n  see " + e.LogRef
	}
	return s
}

func stepErr(step, format string, a ...any) error {
	return &StepError{Step: step, Detail: fmt.Sprintf(format, a...)}
}

func readText(path string) (string, bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", false
	}
	return string(b), true
}

// writeAtomic writes via a temp file in the same directory and renames, so a
// reader never sees a half-written rendezvous file.
func writeAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".tmp-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	if _, err := f.Write(data); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Chmod(tmp, 0o644); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, path)
}

// procStarttime is field 22 of /proc/<pid>/stat, in clock ticks since boot.
// Paired with the pid it identifies a process across pid reuse.
func procStarttime(pid int) (uint64, error) {
	raw, ok := readText(fmt.Sprintf("/proc/%d/stat", pid))
	if !ok {
		return 0, fmt.Errorf("no such process %d", pid)
	}
	// comm can contain spaces and parentheses, so split after the LAST ')'.
	i := strings.LastIndex(raw, ")")
	if i < 0 {
		return 0, fmt.Errorf("unparseable /proc/%d/stat", pid)
	}
	fields := strings.Fields(raw[i+1:])
	// After comm the fields are state,ppid,...; starttime is field 22 overall,
	// which is index 19 here (state being index 0).
	if len(fields) < 20 {
		return 0, fmt.Errorf("short /proc/%d/stat", pid)
	}
	return strconv.ParseUint(fields[19], 10, 64)
}

func bootID() string {
	if s, ok := readText("/proc/sys/kernel/random/boot_id"); ok {
		return strings.TrimSpace(s)
	}
	return ""
}

// treePids returns the pid and every descendant, via /proc/<pid>/task/*/children.
func treePids(pid int) []int {
	var out []int
	seen := map[int]bool{}
	stack := []int{pid}
	for len(stack) > 0 {
		p := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if seen[p] {
			continue
		}
		seen[p] = true
		if _, err := os.Stat(fmt.Sprintf("/proc/%d", p)); err != nil {
			continue
		}
		out = append(out, p)
		tids, err := os.ReadDir(fmt.Sprintf("/proc/%d/task", p))
		if err != nil {
			continue
		}
		for _, t := range tids {
			kids, ok := readText(fmt.Sprintf("/proc/%d/task/%s/children", p, t.Name()))
			if !ok {
				continue
			}
			for _, f := range strings.Fields(kids) {
				if n, err := strconv.Atoi(f); err == nil {
					stack = append(stack, n)
				}
			}
		}
	}
	return out
}

// waitUntil polls fn until it returns a value, or gives up with a named step.
func waitUntil[T any](fn func() (T, bool), timeout time.Duration, what, step, logRef string) (T, error) {
	var zero T
	deadline := time.Now().Add(timeout)
	for {
		if v, ok := fn(); ok {
			return v, nil
		}
		if time.Now().After(deadline) {
			return zero, &StepError{
				Step:   step,
				Detail: fmt.Sprintf("timed out after %s waiting for %s", timeout, what),
				LogRef: logRef,
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// tailFile returns the last n lines, for error messages that point at a log.
func tailFile(path string, n int) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	var lines []string
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		lines = append(lines, sc.Text())
		if len(lines) > n*4 {
			lines = lines[len(lines)-n:]
		}
	}
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

// dirSize is bytes and files under root, counting each inode once. The archived
// open files and mappings beside an image are hard links to files that already
// exist elsewhere on the disk, and one of them can be linked into several
// images; summing them per name would report an image as costing gigabytes of
// disk it never took.
func dirSize(root string) (int64, int) {
	var total int64
	var count int
	seen := map[[2]uint64]bool{}
	filepath.Walk(root, func(p string, fi os.FileInfo, err error) error {
		if err != nil || fi == nil || fi.IsDir() {
			return nil
		}
		// Keyed on device as well as inode: inode numbers are only unique within
		// one filesystem, and a walk can cross a mount point.
		if sys, ok := fi.Sys().(*syscall.Stat_t); ok && sys.Nlink > 1 {
			key := [2]uint64{uint64(sys.Dev), sys.Ino}
			if seen[key] {
				return nil
			}
			seen[key] = true
		}
		total += fi.Size()
		count++
		return nil
	})
	return total, count
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	fi, err := in.Stat()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, fi.Mode().Perm())
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
