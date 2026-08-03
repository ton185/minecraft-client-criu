package main

// Reconciling the filesystem so an image can be restored more than once.
//
// Two separate discoveries, because they fail in different ways:
//
//   OPEN FILES (fd table). criu writes each open regular file's size into the
//   image and refuses to restore unless the on-disk file is that length exactly:
//       Error (criu/files-reg.c:2175): File …/latest.log has bad size 3923 (expect 1354)
//   It skips that check for O_WRONLY|O_APPEND only — every other mode, including
//   the O_RDWR|O_APPEND that log4j's RandomAccessFileAppender uses, is checked.
//   The size mismatch goes both ways and needs two different repairs:
//     LONGER  — the workload appended after the dump. Truncate back; the bytes
//               past the recorded length belong to a discarded timeline.
//     SHORTER, or the path now holds a different inode — the file was ROTATED
//               away, not appended to. Minecraft's log4j rolls latest.log and
//               debug.log on every startup, so one ordinary launch between two
//               restores is enough, and truncation cannot help: the recorded
//               bytes are in a file that no longer has that name. So the
//               contents are archived at checkpoint and put back, exactly as
//               mappings are.
//
//   MAPPINGS (/proc/<pid>/maps). A file that is mmap'd and then closed holds no
//   fd, so the scan above cannot see it — and criu opens every file-backed VMA by
//   path on restore. Ars Nouveau's Lucene index on All the Mods 10 is exactly
//   that: MMapDirectory maps the segment files and closes the fds, and a commit
//   writes _15.* and UNLINKS _14.*, which the image still maps. The second
//   restore then dies with "Can't open vma". Size reconciliation cannot help — the
//   file is gone, not merely longer — so the contents are archived instead.

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
)

// scanOpenRegularFiles records every regular file the tree holds open.
// problems is never swallowed: an fd table we could not read means the record
// may be incomplete, and both checkpoint and restore say so out loud.
func scanOpenRegularFiles(pids []int) (map[string]*FileRec, []string) {
	byPath := map[string]*FileRec{}
	var problems []string
	for _, pid := range pids {
		tids, err := os.ReadDir(fmt.Sprintf("/proc/%d/task", pid))
		if err != nil {
			problems = append(problems, fmt.Sprintf("cannot list tasks of pid %d: %v", pid, err))
			continue
		}
		// The threads of one process share its fd table, so every task lists the
		// same descriptors: a 200-thread JVM holding 600 files walks 120 000
		// entries for 600 distinct ones. The loop below still visits them all,
		// because that is what fills in Fds — but the open mode is a property of
		// the descriptor, so it is read once and reused.
		//
		// Keyed on fd number AND target, not the number alone. The tree is parked,
		// not frozen: its logging threads keep opening and closing files during a
		// scan this long, and the kernel reissues the lowest free fd number. Fd
		// 250 seen as a read-only jar by the first task and as a freshly opened
		// O_RDWR file by the hundredth is one key with two answers — and the wrong
		// answer, "not writable", is the silent one: it archives a file that is
		// still being written by hard link, which preserves nothing.
		mode := map[string]bool{}
		for _, t := range tids {
			fddir := fmt.Sprintf("/proc/%d/task/%s/fd", pid, t.Name())
			fds, err := os.ReadDir(fddir)
			if err != nil {
				problems = append(problems, fmt.Sprintf("cannot list %s: %v", fddir, err))
				continue
			}
			for _, fd := range fds {
				link := filepath.Join(fddir, fd.Name())
				target, err := os.Readlink(link)
				if err != nil {
					problems = append(problems, fmt.Sprintf("cannot inspect %s: %v", link, err))
					continue
				}
				// Only things with a real path can be reconciled by path later.
				// Stat'ing the fd symlink reports anon inodes (eventfd, eventpoll,
				// pidfd) as regular files, and they would then be recorded under
				// targets like "anon_inode:[eventfd]" — which no restore can ever
				// find again, turning every restore into a hard failure.
				if !strings.HasPrefix(target, "/") {
					continue
				}
				st, err := os.Stat(link)
				if err != nil {
					problems = append(problems, fmt.Sprintf("cannot inspect %s: %v", link, err))
					continue
				}
				if !st.Mode().IsRegular() {
					continue
				}
				deleted := strings.HasSuffix(target, " (deleted)")
				path := strings.TrimSuffix(target, " (deleted)")
				ref := fmt.Sprintf("%d/%s:%s", pid, t.Name(), fd.Name())
				key := fd.Name() + "\x00" + target
				writable, seen := mode[key]
				if !seen {
					var why string
					writable, why = fdIsWritable(pid, t.Name(), fd.Name())
					if why != "" {
						// Not knowing the mode means not knowing whether a hard
						// link is enough to archive it, so say so and take the
						// safe side.
						problems = append(problems, why)
					}
					mode[key] = writable
				}
				if rec, ok := byPath[path]; ok {
					rec.Fds = append(rec.Fds, ref)
					rec.Writable = rec.Writable || writable
					if rec.Size != st.Size() {
						problems = append(problems, fmt.Sprintf(
							"%s changed size during the scan (%d then %d); keeping the larger",
							path, rec.Size, st.Size()))
						if st.Size() > rec.Size {
							rec.Size = st.Size()
						}
					}
					continue
				}
				rec := &FileRec{Path: path, Size: st.Size(), Deleted: deleted,
					Writable: writable, Fds: []string{ref}}
				if sys, okc := st.Sys().(*syscall.Stat_t); okc {
					rec.Ino, rec.Dev = sys.Ino, uint64(sys.Dev)
				}
				byPath[path] = rec
			}
		}
	}
	return byPath, problems
}

// fdIsWritable reports whether this descriptor can write to its file, from the
// open flags in /proc/<pid>/task/<tid>/fdinfo/<fd>. A descriptor whose mode
// cannot be read is reported as writable *and* named in the returned reason:
// treating an unknown as read-only would archive it with a hard link, which
// preserves nothing if it turns out the workload is still writing to it.
func fdIsWritable(pid int, tid, fd string) (bool, string) {
	p := fmt.Sprintf("/proc/%d/task/%s/fdinfo/%s", pid, tid, fd)
	raw, ok := readText(p)
	if !ok {
		return true, fmt.Sprintf("cannot read %s; assuming it is open for writing", p)
	}
	for _, line := range strings.Split(raw, "\n") {
		rest, found := strings.CutPrefix(line, "flags:")
		if !found {
			continue
		}
		flags, err := strconv.ParseUint(strings.TrimSpace(rest), 8, 64)
		if err != nil {
			return true, fmt.Sprintf("cannot parse the open flags in %s (%q); assuming it is open for writing", p, line)
		}
		return flags&syscall.O_ACCMODE != syscall.O_RDONLY, ""
	}
	return true, fmt.Sprintf("%s has no flags line; assuming it is open for writing", p)
}

// restatOpenFiles refreshes sizes after criu has finished.
//
// criu reads each file's size while the tree is frozen. On a plain dump-and-stop
// the tree is dead by the time criu returns and has written nothing since the
// freeze, so a stat here reproduces criu's number exactly — which a stat taken
// *before* the dump would not, because a parked JVM's logging threads keep
// running during criu's few milliseconds of startup.
func restatOpenFiles(recs map[string]*FileRec) []string {
	var problems []string
	for path, rec := range recs {
		st, err := os.Stat(path)
		if err != nil {
			if rec.Deleted {
				continue // expected: it was already unlinked when we scanned
			}
			problems = append(problems, fmt.Sprintf("%s vanished between the scan and the dump: %v", path, err))
			continue
		}
		if st.Size() != rec.Size {
			was := rec.Size
			rec.SizeAtScan = &was
			rec.Size = st.Size()
		}
	}
	return problems
}

func highestOpenFd(pids []int) int {
	top := 0
	for _, pid := range pids {
		tids, err := os.ReadDir(fmt.Sprintf("/proc/%d/task", pid))
		if err != nil {
			continue
		}
		for _, t := range tids {
			fds, err := os.ReadDir(fmt.Sprintf("/proc/%d/task/%s/fd", pid, t.Name()))
			if err != nil {
				continue
			}
			for _, fd := range fds {
				if n, err := strconv.Atoi(fd.Name()); err == nil && n > top {
					top = n
				}
			}
		}
	}
	return top
}

// raiseNofile lifts RLIMIT_NOFILE so criu can place its service descriptors
// above the highest fd the dumped process used.
//
//	Error (criu/servicefd.c:282): sfd: Can't chose service_fd_base: 1024 1074
//
// A heavily modded client holds one fd per open jar and goes straight past the
// usual soft limit of 1024. The soft limit is ours to raise up to the hard limit
// without any privilege, so raise it rather than making the user do it — and
// always to at least 65536, not merely to what this particular image needs,
// because the cost is nil and the failure is opaque.
//
// It is needed on RESTORE as much as on dump: criu chooses service_fd_base from
// the fd numbers recorded in the image. Raising it only for the dump is what
// made a checkpoint taken after joining a server restore-fail while one taken at
// a fresh title screen worked — the second image simply held more fds.
//
// THE LIMIT THAT MATTERS IS CRIU'S, NOT OURS, and Go goes out of its way to make
// those different. Since 1.19 the runtime raises this process's soft NOFILE to
// the hard limit at startup, and os/exec RESTORES THE ORIGINAL SOFT LIMIT in
// every child it spawns — unless the program has set the limit itself. So a
// manager that only *checks* the limit finds a comfortable 524287, skips the
// raise, and hands criu the shell's original 1024. Measured, under a login
// shell's usual `ulimit -Sn 1024`:
//
//	at startup                  parent soft=524287   CHILD soft=1024
//	after an explicit Setrlimit parent soft=65536    CHILD soft=65536
//
// which is exactly the reported failure — the manager saw no problem and criu
// died on `service_fd_base: 1024 1074`. Hence: always call Setrlimit, never
// return early because our own limit already looks fine. The explicit call is
// what makes the value stick across exec, and it is the only reason any of this
// reaches criu at all. This box's own shell starts at 524288/524288, so nothing
// here reproduces it — see manager/files_test.go, which runs the helper under a
// lowered soft limit on purpose.
func raiseNofile(minimum uint64, step string) error {
	want := minimum
	if want < 65536 {
		want = 65536
	}
	var lim syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &lim); err != nil {
		return stepErr(step, "cannot read RLIMIT_NOFILE: %v", err)
	}
	target := want
	if lim.Max != 0 && target > lim.Max {
		target = lim.Max
	}
	if lim.Cur > target {
		target = lim.Cur // never lower what we already have
	}
	syscall.Setrlimit(syscall.RLIMIT_NOFILE, &syscall.Rlimit{Cur: target, Max: lim.Max})

	// Verify, and if the HARD limit is the obstacle say so with the command that
	// fixes it: silently continuing here means criu fails later with a message
	// that names neither the limit nor how to raise it.
	syscall.Getrlimit(syscall.RLIMIT_NOFILE, &lim)
	if lim.Cur < minimum {
		return stepErr(step,
			"this image needs RLIMIT_NOFILE of at least %d but the hard limit caps it at %d.\n"+
				"criu places its own service descriptors above the highest fd the dumped\n"+
				"process used, and cannot start below that. Raise the hard limit and retry:\n"+
				"    ulimit -Hn %d", minimum, lim.Cur, want)
	}
	// Reaching `minimum` is not the same as reaching a limit criu is happy with.
	// How far above the highest fd criu needs its service_fd_base is criu's
	// business, and it has been measured 114 above it — so a limit that clears
	// `minimum` by a hair can still lose. Not fatal (a small image may be fine),
	// but never silent, because the failure it turns into names neither cause.
	if lim.Cur < want {
		fmt.Fprintf(os.Stderr,
			"  WARNING: could not raise RLIMIT_NOFILE to %d; the hard limit caps it at %d.\n"+
				"           criu may still fail with \"Can't chose service_fd_base\". Raise it with:\n"+
				"               ulimit -Hn %d\n", want, lim.Cur, want)
	}
	return nil
}

// rollbackBackupPath is `<path>.mccriu-<gen>-<n>.bak`, beside the original when
// that directory is writable and under the session dir when it is not. n is the
// first free index, so a previous restore's archive is never overwritten.
func rollbackBackupPath(sess *Session, path string, gen int) string {
	base := path
	if syscall.Access(filepath.Dir(path), 2 /* W_OK */) != nil {
		d := filepath.Join(sess.Root, "file-rollback")
		os.MkdirAll(d, 0o755)
		base = filepath.Join(d, filepath.Base(path))
	}
	for n := 0; ; n++ {
		cand := fmt.Sprintf("%s.mccriu-%d-%d.bak", base, gen, n)
		if !fileExists(cand) {
			return cand
		}
	}
}

// archiveOpenFiles preserves the contents of every open regular file beside the
// image, so one the workload later rotates away can be put back.
//
// Hard link for a file nobody is writing: free in space and time however large
// it is, and it keeps the INODE alive when the path is rotated away, which is
// the failure this guards against. A file the tree holds open for WRITING is
// copied instead — a link would share the inode with the writer and go on
// changing with it, so it would archive nothing at all. Measured on All the Mods
// 10 at the title screen: 587 open files worth 1.5 GB, of which 580 linked and 7
// copied for 11.3 MB — the logs.
//
// What gets copied is therefore whatever the tree holds open for writing, which
// is small at a menu and is NOT small everywhere: in a loaded world Minecraft
// keeps region files open O_RDWR and caches up to 256 of them, so a checkpoint
// taken in-world copies that set too. A file on another filesystem cannot be
// linked and is copied whatever its mode, so a JRE on a separate mount from the
// instance is copied every checkpoint as well. Both are why the copied byte
// count is printed rather than assumed negligible.
//
// An already-unlinked file is skipped: there is no path to link from, and criu
// carries an unlinked file's contents inside the image itself.
func archiveOpenFiles(sess *Session, gen int, recs map[string]*FileRec) []string {
	var problems []string
	linked, copied := 0, 0
	var copiedBytes int64
	for _, rec := range recs {
		if rec.Deleted {
			continue
		}
		dest := openFileArchive(sess, gen, rec.Path)
		rec.Archive = dest
		if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
			problems = append(problems, fmt.Sprintf("cannot create %s: %v", filepath.Dir(dest), err))
			rec.Archive = ""
			continue
		}
		// Clear the way first. If an archive is already there — a generation
		// number reused after a session directory was restarted, two checkpoints
		// racing — then it is very likely a HARD LINK TO THE FILE WE ARE ABOUT TO
		// ARCHIVE, and copyFile opens its destination O_TRUNC before reading the
		// source. That truncates the live file through the other link and then
		// copies the zero bytes that are left, reporting success. Measured: a
		// 14-byte file and its archive both ended at 0 bytes with a nil error.
		// Unlinking one name of a hard-linked pair cannot lose data; leaving it
		// in place can.
		if err := os.Remove(dest); err != nil && !os.IsNotExist(err) {
			problems = append(problems, fmt.Sprintf("cannot clear the previous archive %s: %v", dest, err))
			rec.Archive = ""
			continue
		}
		if !rec.Writable {
			if err := os.Link(rec.Path, dest); err == nil {
				linked++
				rec.ArchiveKind = "link"
				continue
			}
		}
		if err := copyFile(rec.Path, dest); err != nil {
			problems = append(problems, fmt.Sprintf("cannot archive open file %s: %v", rec.Path, err))
			rec.Archive = ""
			continue
		}
		copied++
		// What landed, not what was recorded: with --keep-running the tree is
		// writing throughout, so the copy is bigger than the size in the record.
		if fi, err := os.Stat(dest); err == nil {
			copiedBytes += fi.Size()
		}
		rec.ArchiveKind = "copy"
	}
	fmt.Printf("archived %d open file(s) beside the image (%d hard-linked, %d copied = %s)\n",
		linked+copied, linked, copied, humanBytes(copiedBytes))
	return problems
}

// openFileArchive is where an open file's contents live beside the image. It is
// DERIVED, not read back from the record, so reconciliation works even when the
// record has lost its pointer: session.json is rewritten by every command, and
// an older mc-criu-manager parsing it into its own struct silently drops the
// fields it does not know — observed stripping ino/writable/archive from a
// generation a newer build had just written. The bytes beside the image are the
// durable part; the pointer is only a convenience.
func openFileArchive(sess *Session, gen int, path string) string {
	return filepath.Join(sess.ImageDir(gen), "open-files", strings.TrimPrefix(path, "/"))
}

// putBackOpenFile restores one archived file to its path.
//
// A linked archive IS the original inode, so linking it back restores the file
// itself. A copied archive has to be copied back: linking it would hand the
// restored workload the archive to write into, and the next restore of this
// same generation would then find it already spoiled. An unknown kind is copied,
// which is right either way and only costs time.
func putBackOpenFile(rec *FileRec, archive string) error {
	if rec.ArchiveKind == "link" {
		if err := os.Link(archive, rec.Path); err == nil {
			return nil
		}
	}
	return copyFile(archive, rec.Path)
}

// reconcileOpenFiles puts every open regular file back to what criu recorded:
// first the same file, then the same length. This is what makes "restore the
// same image repeatedly" work, and what survives a log rotation in between.
func reconcileOpenFiles(sess *Session, gen int, g *Generation, enabled bool) error {
	const step = "restore: reconcile open files"
	if len(g.OpenFiles) == 0 {
		return nil
	}
	if !enabled {
		fmt.Printf("open files: %d recorded, rollback disabled by --no-file-rollback\n", len(g.OpenFiles))
		return nil
	}
	type pending struct {
		rec *FileRec
		why string
		now int64
	}

	// 1. IDENTITY. A path that is gone, holds a different inode, or is shorter
	//    than the image expects was not appended to — it was rotated away or
	//    rewritten, and no amount of truncation brings the recorded bytes back.
	//    Classify everything before touching anything: a half-repaired tree is
	//    worse than an unrepaired one.
	var toReplace []pending
	var noArchive []string
	archiveOf := map[string]string{}
	for i := range g.OpenFiles {
		rec := &g.OpenFiles[i]
		if rec.Deleted {
			continue // criu carries an unlinked file's contents inside the image
		}
		why := ""
		st, err := os.Stat(rec.Path)
		switch {
		case err != nil:
			why = fmt.Sprintf("is gone (%v)", err)
		case rec.Ino != 0 && inodeOf(st) != 0 && inodeOf(st) != rec.Ino:
			why = fmt.Sprintf("was rotated away or replaced (a different inode is at that path now; "+
				"%d bytes on disk, %d in the image)", st.Size(), rec.Size)
		case st.Size() < rec.Size:
			why = fmt.Sprintf("is SHORTER than the image expects (%d, want %d)", st.Size(), rec.Size)
		default:
			continue // same file, right length or longer: step 2 deals with it
		}
		archive := openFileArchive(sess, gen, rec.Path)
		if !fileExists(archive) {
			noArchive = append(noArchive, fmt.Sprintf("%s %s, and no archive of it was kept", rec.Path, why))
			continue
		}
		archiveOf[rec.Path] = archive
		toReplace = append(toReplace, pending{rec, why, 0})
	}
	if len(noArchive) > 0 {
		return &StepError{Step: step, Detail: "criu reopens every file the tree held open and refuses " +
			"unless it is exactly the length it recorded, so these cannot be rolled back:\n  " +
			strings.Join(noArchive, "\n  ") +
			"\nAn image written before open files were archived cannot survive a log rotation, and " +
			"Minecraft's log4j rotates logs/latest.log and logs/debug.log on every startup. " +
			"Re-checkpoint, or restore a generation whose files are still intact."}
	}
	if len(toReplace) > 0 {
		fmt.Printf("open files: %d were rotated away or replaced since the dump and must be put back "+
			"(what is on disk now belongs to a discarded timeline):\n", len(toReplace))
		for _, p := range toReplace {
			if fileExists(p.rec.Path) {
				backup := rollbackBackupPath(sess, p.rec.Path, gen)
				if err := os.Rename(p.rec.Path, backup); err != nil {
					if err2 := copyFile(p.rec.Path, backup); err2 != nil {
						return &StepError{Step: step, Detail: fmt.Sprintf(
							"cannot move %s aside to %s: %v\nRefusing to overwrite a file we could not archive first.",
							p.rec.Path, backup, err)}
					}
					if err2 := os.Remove(p.rec.Path); err2 != nil {
						return &StepError{Step: step, Detail: fmt.Sprintf(
							"archived %s to %s but could not remove the original: %v", p.rec.Path, backup, err2)}
					}
				}
				fmt.Printf("    %s\n      %s; moved aside -> %s\n", p.rec.Path, p.why, backup)
			} else {
				fmt.Printf("    %s\n      %s\n", p.rec.Path, p.why)
			}
			// The path must be free before the archive goes back. Left in place it
			// would defeat os.Link and fall through to copyFile, which truncates
			// its destination first — and when the archive is a hard link to the
			// very file still sitting at that path, that destroys both.
			if fileExists(p.rec.Path) {
				return &StepError{Step: step, Detail: fmt.Sprintf(
					"%s is still there after being moved aside; refusing to write over it", p.rec.Path)}
			}
			os.MkdirAll(filepath.Dir(p.rec.Path), 0o755)
			archive := archiveOf[p.rec.Path]
			if err := putBackOpenFile(p.rec, archive); err != nil {
				return &StepError{Step: step, Detail: fmt.Sprintf(
					"cannot restore %s from %s: %v", p.rec.Path, archive, err)}
			}
			fmt.Printf("      restored from %s\n", archive)
		}
	}

	// 2. LENGTH. Fresh stats, because step 1 changed some of these files: an
	//    archive kept by hard link went on growing until the rotation unlinked
	//    it, so it comes back longer than the image expects.
	var toTruncate []pending
	matched := 0
	for i := range g.OpenFiles {
		rec := &g.OpenFiles[i]
		if rec.Deleted {
			continue
		}
		st, err := os.Stat(rec.Path)
		if err != nil {
			continue // step 3 reports it
		}
		switch {
		case st.Size() == rec.Size:
			matched++
		case st.Size() > rec.Size:
			toTruncate = append(toTruncate, pending{rec, "", st.Size()})
		}
	}
	if len(toTruncate) == 0 {
		fmt.Printf("open files: all %d match their recorded size; nothing to roll back\n", matched)
	} else {
		fmt.Printf("open files: %d match, %d have grown since the dump and must be rolled back "+
			"(bytes written after the checkpoint belong to a discarded timeline):\n",
			matched, len(toTruncate))
		for _, p := range toTruncate {
			backup := rollbackBackupPath(sess, p.rec.Path, gen)
			if err := copyFile(p.rec.Path, backup); err != nil {
				return &StepError{Step: step, Detail: fmt.Sprintf(
					"cannot archive %s to %s: %v\nRefusing to truncate a file we could not archive first.",
					p.rec.Path, backup, err)}
			}
			if err := os.Truncate(p.rec.Path, p.rec.Size); err != nil {
				return &StepError{Step: step, Detail: fmt.Sprintf(
					"archived %s to %s but could not truncate it: %v", p.rec.Path, backup, err)}
			}
			fmt.Printf("    %s\n      archived %d bytes -> %s\n      truncated to %d bytes "+
				"(its length when generation %d was dumped)\n",
				p.rec.Path, p.now, backup, p.rec.Size, gen)
		}
	}

	// 3. VERIFY, because the repairs above can silently fall short: a file the
	//    workload truncated IN PLACE takes its hard-linked archive down with it,
	//    so putting that archive back restores a file that is still too short.
	//    criu would then fail with its own message about a file we claimed to
	//    have reconciled.
	var stillWrong []string
	for i := range g.OpenFiles {
		rec := &g.OpenFiles[i]
		if rec.Deleted {
			continue
		}
		st, err := os.Stat(rec.Path)
		if err != nil {
			stillWrong = append(stillWrong, fmt.Sprintf("%s is gone (%v)", rec.Path, err))
			continue
		}
		if st.Size() != rec.Size {
			stillWrong = append(stillWrong, fmt.Sprintf("%s is %d bytes, the image expects %d",
				rec.Path, st.Size(), rec.Size))
		}
	}
	if len(stillWrong) > 0 {
		return &StepError{Step: step, Detail: "after rolling back, these files are still not the " +
			"length criu recorded and it would refuse to restore:\n  " + strings.Join(stillWrong, "\n  ") +
			"\nAn archive kept by hard link shares the file's inode, so it cannot help when that file " +
			"was truncated in place — either by the workload, or by rolling back an OLDER generation " +
			"that recorded a shorter length for it. Restore a different generation, or re-checkpoint."}
	}
	return nil
}

func inodeOf(fi os.FileInfo) uint64 {
	if sys, ok := fi.Sys().(*syscall.Stat_t); ok {
		return sys.Ino
	}
	return 0
}

// ---------------------------------------------------------------- mappings

// scanMappedFiles records every file-backed mapping the tree holds.
// Skips anonymous mappings, pseudo-filesystems and anything already deleted: a
// mapping whose file is gone before we start cannot be preserved, and criu has
// its own ghost-file handling for that case.
func scanMappedFiles(pids []int) (map[string]*MapRec, []string) {
	skipPrefixes := []string{"/proc/", "/sys/", "/dev/", "/run/"}
	byPath := map[string]*MapRec{}
	var problems []string
	for _, pid := range pids {
		raw, ok := readText(fmt.Sprintf("/proc/%d/maps", pid))
		if !ok {
			problems = append(problems, fmt.Sprintf("cannot read /proc/%d/maps (process gone, or not ours)", pid))
			continue
		}
		for _, line := range strings.Split(raw, "\n") {
			parts := strings.SplitN(line, " ", 6)
			if len(parts) < 6 {
				continue // anonymous mapping: no path
			}
			path := strings.TrimSpace(parts[5])
			if !strings.HasPrefix(path, "/") || strings.HasSuffix(path, " (deleted)") {
				continue // [heap], [stack], anon_inode:…, or already unlinked
			}
			skip := false
			for _, p := range skipPrefixes {
				if strings.HasPrefix(path, p) {
					skip = true
					break
				}
			}
			if skip {
				continue
			}
			if rec, ok := byPath[path]; ok {
				found := false
				for _, p := range rec.Pids {
					if p == pid {
						found = true
						break
					}
				}
				if !found {
					rec.Pids = append(rec.Pids, pid)
				}
				continue
			}
			fi, err := os.Stat(path)
			if err != nil {
				problems = append(problems, fmt.Sprintf("mapped file %s cannot be stat'd: %v", path, err))
				continue
			}
			if !fi.Mode().IsRegular() {
				continue
			}
			st, _ := fi.Sys().(*syscall.Stat_t)
			rec := &MapRec{Path: path, Size: fi.Size(), MtimeNs: fi.ModTime().UnixNano(), Pids: []int{pid}}
			if st != nil {
				rec.Ino, rec.Dev = st.Ino, uint64(st.Dev)
			}
			byPath[path] = rec
		}
	}
	return byPath, problems
}

// archiveMappedFiles preserves the contents of every mapped file beside the image.
//
// Hard link first, copy only as a fallback. A link costs no space and no time no
// matter how large the file, and it keeps the INODE alive when the workload
// unlinks the path — exactly the failure this guards against. It deliberately
// does NOT protect against a file rewritten in place, because both links would
// then see the new bytes; that case is detected at restore and reported.
//
// Copying instead would mean copying every mod jar and shared library the JVM has
// mapped, gigabytes per generation on a 500-mod pack. Linking makes archiving the
// whole set affordable, so no heuristic is needed about which files look mutable.
func archiveMappedFiles(sess *Session, gen int, recs map[string]*MapRec) []string {
	var problems []string
	store := filepath.Join(sess.ImageDir(gen), "mapped-files")
	linked, copied := 0, 0
	for _, rec := range recs {
		dest := filepath.Join(store, strings.TrimPrefix(rec.Path, "/"))
		rec.Archive = dest
		if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
			problems = append(problems, fmt.Sprintf("cannot create %s: %v", filepath.Dir(dest), err))
			rec.Archive = ""
			continue
		}
		if fileExists(dest) {
			continue
		}
		if err := os.Link(rec.Path, dest); err == nil {
			linked++
			rec.ArchiveKind = "link"
			continue
		}
		if err := copyFile(rec.Path, dest); err != nil {
			problems = append(problems, fmt.Sprintf("cannot archive mapped file %s: %v", rec.Path, err))
			rec.Archive = ""
			continue
		}
		copied++
		rec.ArchiveKind = "copy"
	}
	fmt.Printf("archived %d mapped file(s) beside the image (%d hard-linked, %d copied)\n",
		linked+copied, linked, copied)
	return problems
}

// reconcileMappedFiles puts back any mapped file the workload deleted or replaced.
// Files are compared by inode: an unlinked-and-recreated file has a different
// inode even when the path is back.
func reconcileMappedFiles(sess *Session, gen int, g *Generation, enabled bool) error {
	const step = "restore: reconcile mapped files"
	if len(g.MappedFiles) == 0 {
		return nil
	}
	if !enabled {
		fmt.Printf("mapped files: %d recorded, rollback disabled by --no-file-rollback\n", len(g.MappedFiles))
		return nil
	}
	type pending struct {
		rec *MapRec
		why string
	}
	var toRestore []pending
	var noArchive []string
	ok := 0
	for i := range g.MappedFiles {
		rec := &g.MappedFiles[i]
		why := ""
		fi, err := os.Stat(rec.Path)
		if err != nil {
			why = "missing"
		} else {
			st, _ := fi.Sys().(*syscall.Stat_t)
			switch {
			case st != nil && st.Ino == rec.Ino && fi.Size() == rec.Size:
				ok++
				continue
			case st != nil && st.Ino != rec.Ino:
				why = "replaced (different inode)"
			default:
				why = fmt.Sprintf("size %d, was %d", fi.Size(), rec.Size)
			}
		}
		if rec.Archive == "" || !fileExists(rec.Archive) {
			noArchive = append(noArchive, fmt.Sprintf("%s is %s and no archive of it was kept", rec.Path, why))
			continue
		}
		toRestore = append(toRestore, pending{rec, why})
	}
	if len(noArchive) > 0 {
		return &StepError{Step: step, Detail: "criu opens every mapped file by path on restore, " +
			"and these cannot be put back:\n  " + strings.Join(noArchive, "\n  ") +
			"\nThe restore would fail inside criu with 'Can't open vma'. Re-checkpoint, or " +
			"restore a generation whose files still exist."}
	}
	if len(toRestore) == 0 {
		fmt.Printf("mapped files: all %d still match the image; nothing to put back\n", ok)
		return nil
	}
	fmt.Printf("mapped files: %d match, %d were deleted or replaced since the dump and must be "+
		"put back (the workload's newer copies belong to a discarded timeline):\n", ok, len(toRestore))
	for _, p := range toRestore {
		if fileExists(p.rec.Path) {
			backup := rollbackBackupPath(sess, p.rec.Path, gen)
			if err := os.Rename(p.rec.Path, backup); err != nil {
				if err2 := copyFile(p.rec.Path, backup); err2 != nil {
					return &StepError{Step: step, Detail: fmt.Sprintf(
						"cannot move %s aside to %s: %v\nRefusing to overwrite a file we could not archive first.",
						p.rec.Path, backup, err)}
				}
				os.Remove(p.rec.Path)
			}
			fmt.Printf("    %s\n      %s; moved aside -> %s\n", p.rec.Path, p.why, backup)
		} else {
			fmt.Printf("    %s\n      %s\n", p.rec.Path, p.why)
		}
		os.MkdirAll(filepath.Dir(p.rec.Path), 0o755)
		if err := os.Link(p.rec.Archive, p.rec.Path); err != nil {
			if err := copyFile(p.rec.Archive, p.rec.Path); err != nil {
				return &StepError{Step: step, Detail: fmt.Sprintf(
					"cannot restore %s from %s: %v", p.rec.Path, p.rec.Archive, err)}
			}
		}
		fmt.Printf("      restored from %s\n", p.rec.Archive)
	}
	return nil
}

func sortedFileRecs(m map[string]*FileRec) []FileRec {
	out := make([]FileRec, 0, len(m))
	for _, v := range m {
		out = append(out, *v)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}

func sortedMapRecs(m map[string]*MapRec) []MapRec {
	out := make([]MapRec, 0, len(m))
	for _, v := range m {
		out = append(out, *v)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}
