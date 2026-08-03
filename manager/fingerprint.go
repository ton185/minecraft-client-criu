package main

// What an image belongs to.
//
// An image is a frozen JVM: its heap holds the mod set that was loaded when it
// was dumped. Restore it into an instance whose mods have since changed and the
// running game disagrees with the disk — and because a restore now reconciles
// file-backed mappings, it would also faithfully put the OLD jars back over the
// new ones. So each image records what it was taken from, and a mismatch is
// refused rather than restored.
//
// Cheap on purpose: names and sizes, not content hashes. Reading 482 mod jars to
// decide whether to show a menu would cost more than the menu saves, and the
// case that actually happens — a mod added, removed or updated — changes the set
// or a size.

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type Fingerprint struct {
	// Digest over the sorted mod set. The single value a mismatch is decided on.
	Mods string `json:"mods"`
	// Human-readable context, so a mismatch can say what changed.
	ModCount int    `json:"mod_count"`
	MainClass string `json:"main_class,omitempty"`
	GameDir   string `json:"game_dir,omitempty"`
}

// FingerprintInstance digests the mods directory and the launch command.
//
// A missing mods directory is not an error: a vanilla instance legitimately has
// none, and it fingerprints as an empty set that will keep matching.
func FingerprintInstance(gameDir string, argv []string) Fingerprint {
	fp := Fingerprint{GameDir: gameDir, MainClass: mainClassOf(argv)}
	modsDir := filepath.Join(gameDir, "mods")
	ents, err := os.ReadDir(modsDir)
	if err != nil {
		fp.Mods = digestStrings(nil)
		return fp
	}
	var keys []string
	for _, e := range ents {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !strings.HasSuffix(strings.ToLower(name), ".jar") {
			continue
		}
		// NOT our own mod. The manager installs and silently updates
		// mc-criu-mod.jar itself, so including it means every upgrade of the
		// manager changes the fingerprint and orphans every checkpoint the user
		// had -- which is exactly what happened: a new build shipped, the jar
		// size changed, and their existing checkpoint stopped being offered with
		// no obvious reason. The fingerprint is meant to catch the USER changing
		// their mods, not us changing ours.
		if name == modJarName {
			continue
		}
		fi, err := e.Info()
		if err != nil {
			continue
		}
		keys = append(keys, fmt.Sprintf("%s:%d", name, fi.Size()))
	}
	sort.Strings(keys)
	fp.ModCount = len(keys)
	fp.Mods = digestStrings(keys)
	return fp
}

func digestStrings(keys []string) string {
	h := sha256.New()
	for _, k := range keys {
		h.Write([]byte(k))
		h.Write([]byte{0})
	}
	return hex.EncodeToString(h.Sum(nil))[:24]
}

// Matches reports whether an image may be restored into this instance.
//
// An image with NO recorded fingerprint does not match. "Unknown" is not
// "safe": an image is a frozen JVM holding whatever mod set was loaded when it
// was dumped, and restoring one whose provenance cannot be established is
// exactly the case this check exists to prevent — the more so since a restore
// reconciles file-backed mappings and would put that image's jars back over
// whatever is on disk now.
//
// This deliberately makes unfingerprinted images unrestorable rather than
// silently trusted. It also means that if fingerprints ever stop being recorded,
// restores break loudly instead of quietly losing the check.
func (fp Fingerprint) Matches(other *Fingerprint) (bool, string) {
	if other == nil {
		return false, "this checkpoint has no recorded fingerprint, so there is no way to " +
			"tell which mod set it belongs to"
	}
	if other.Mods == fp.Mods {
		return true, ""
	}
	return false, fmt.Sprintf(
		"the mods have changed since this checkpoint was taken (%d jars then, %d now)",
		other.ModCount, fp.ModCount)
}

// mainClassOf is the first argument that looks like a Java main class rather
// than a flag or a path — enough to tell a game launch from `java -version`.
func mainClassOf(argv []string) string {
	skipNext := false
	for i, a := range argv {
		if i == 0 {
			continue // the java binary itself
		}
		if skipNext {
			skipNext = false
			continue
		}
		if strings.HasPrefix(a, "-") {
			switch a {
			case "-cp", "-classpath", "--class-path", "-p", "--module-path",
				"--add-modules", "--add-opens", "--add-exports", "-jar":
				skipNext = true
			}
			continue
		}
		if strings.Contains(a, ".") && !strings.ContainsAny(a, "/\\") {
			return a
		}
		return a
	}
	return ""
}

// gameDirFromArgs pulls --gameDir out of the launch command. That is the
// instance identity everything else is keyed on.
func gameDirFromArgs(argv []string) string {
	for i, a := range argv {
		if a == "--gameDir" && i+1 < len(argv) {
			return argv[i+1]
		}
		if strings.HasPrefix(a, "--gameDir=") {
			return strings.TrimPrefix(a, "--gameDir=")
		}
	}
	return ""
}

// looksLikeGameLaunch distinguishes a real launch from the probing every
// launcher does before it will use a JVM at all: `java -version`,
// `-XshowSettings:properties -version`, and Prism's JavaCheck.jar. If those do
// not reach the real JVM the launcher decides the JVM is broken and refuses to
// launch anything, so actAsJava must pass them straight through.
func looksLikeGameLaunch(argv []string) bool {
	for _, a := range argv {
		if a == "-version" || a == "--version" || a == "-showversion" {
			return false
		}
		if strings.HasPrefix(a, "-XshowSettings") {
			return false
		}
	}
	mc := mainClassOf(argv)
	if mc == "" {
		return false
	}
	// A jar-based probe (JavaCheck.jar) is not a game launch either.
	for i, a := range argv {
		if a == "-jar" && i+1 < len(argv) {
			return false
		}
	}
	return true
}
