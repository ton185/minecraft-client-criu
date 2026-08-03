package main

// The mod travels inside the binary.
//
// So the minimal install is one file (plus criu). The jar is written out next to
// the binary — its own directory, not the cwd, which belongs to the launcher and
// changes between instances.

import (
	"bytes"
	_ "embed"
	"fmt"
	"os"
	"path/filepath"
)

//go:embed embedded/mc-criu-mod.jar
var embeddedMod []byte

const modJarName = "mc-criu-mod.jar"

// InstallMod writes the embedded mod into the instance's own mods folder.
//
// Straight from the bytes compiled into this binary, with no intermediate copy
// beside the executable. There used to be one, because the mod was injected with
// -Dfml.modFolders=<path> and that needed a stable path to point at. That
// injection does not work (FML parses the property into an ExplodedModPath, for
// directories of loose classes, not jars), so the mod is copied into mods/ where
// the loader actually reads it -- which makes the staging copy pure overhead, and
// one more thing to go stale.
//
// Dropping it also means this binary's own directory never has to be writable,
// so it can live in /usr/local/bin, a Nix store, or a read-only Flatpak data
// directory.
//
// Keyed on content, not mere existence: "install if absent" silently keeps an old
// jar after an upgrade.
func InstallMod(gameDir string) (string, error) {
	modsDir := filepath.Join(gameDir, "mods")
	if err := os.MkdirAll(modsDir, 0o755); err != nil {
		return "", fmt.Errorf("cannot create %s: %w", modsDir, err)
	}
	dest := filepath.Join(modsDir, modJarName)
	if have, err := os.ReadFile(dest); err == nil && bytes.Equal(have, embeddedMod) {
		return dest, nil
	}
	if err := os.WriteFile(dest, embeddedMod, 0o644); err != nil {
		return "", fmt.Errorf("cannot install the mod into %s: %w\n"+
			"The game loads it from there; without it nothing else in mc-criu works.", dest, err)
	}
	fmt.Printf("mod          installed %s (%d bytes)\n", dest, len(embeddedMod))
	return dest, nil
}

// WriteModTo writes the embedded jar to an explicit path, for `extract-mod`.
// Only a convenience for installing it by hand; nothing in the normal flow uses it.
func WriteModTo(path string) error {
	return os.WriteFile(path, embeddedMod, 0o644)
}

// injectAgentSession sets -Dmccriu.session, without which the mod loads and then
// does nothing: that property is the mod's entire on-switch, and it is how the
// mod finds the rendezvous directory to talk back through.
func injectMod(argv []string, sessionDir string) []string {
	if len(argv) == 0 {
		return argv
	}
	ses := "-Dmccriu.session=" + sessionDir
	for _, a := range argv {
		if a == ses {
			return argv // already injected
		}
	}
	argv = append([]string{argv[0], ses}, argv[1:]...)
	return argv
}
