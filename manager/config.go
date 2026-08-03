package main

// Configuration, in two layers.
//
//   <gameDir>/.mc-criu/config   per instance
//   <binary dir>/mc-criu.conf   global defaults
//
// Per-instance wins where both set a key. Keying instance state on the game
// directory makes it per-instance for free, survives copying an instance, and
// puts multi-GB images on the same disk as the instance they belong to. The
// global file exists because actAsJava and realJavaPath are properties of the
// install, not of any one instance.
//
// The format is deliberately dull: `key = value`, # comments, no sections.

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type Config struct {
	// AlwaysLatest hides the picker and restores the newest image. A
	// fingerprint mismatch overrides it — see auto.go.
	AlwaysLatest bool
	// ActAsJava makes the binary usable as the launcher's "java" path.
	ActAsJava bool
	// RealJavaPath is the JVM to actually run. Never auto-detected.
	RealJavaPath string

	path string // where the per-instance file lives, for writing back
}

func defaultConfig() Config { return Config{} }

func parseConfigFile(path string, c *Config) error {
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	ln := 0
	for sc.Scan() {
		ln++
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		i := strings.IndexByte(line, '=')
		if i < 0 {
			return fmt.Errorf("%s:%d: expected `key = value`, got %q", path, ln, line)
		}
		key := strings.TrimSpace(line[:i])
		val := strings.TrimSpace(line[i+1:])
		switch key {
		case "alwaysLatest":
			b, err := strconv.ParseBool(val)
			if err != nil {
				return fmt.Errorf("%s:%d: alwaysLatest wants true or false, got %q", path, ln, val)
			}
			c.AlwaysLatest = b
		case "actAsJava":
			b, err := strconv.ParseBool(val)
			if err != nil {
				return fmt.Errorf("%s:%d: actAsJava wants true or false, got %q", path, ln, val)
			}
			c.ActAsJava = b
		case "realJavaPath":
			c.RealJavaPath = val
		default:
			// Unknown keys are an error, not a shrug: a typo'd key that is
			// silently ignored looks exactly like a setting that does not work.
			return fmt.Errorf("%s:%d: unknown setting %q", path, ln, key)
		}
	}
	return sc.Err()
}

func globalConfigPath() string {
	self, err := os.Executable()
	if err != nil {
		return ""
	}
	return filepath.Join(filepath.Dir(self), "mc-criu.conf")
}

func instanceDir(gameDir string) string { return filepath.Join(gameDir, ".mc-criu") }

func instanceConfigPath(gameDir string) string {
	return filepath.Join(instanceDir(gameDir), "config")
}

// LoadConfig reads the global file then the per-instance one, so per-instance
// wins. gameDir may be empty, in which case only the global file is read.
func LoadConfig(gameDir string) (Config, error) {
	c := defaultConfig()
	if g := globalConfigPath(); g != "" {
		if err := parseConfigFile(g, &c); err != nil {
			return c, err
		}
	}
	if gameDir != "" {
		c.path = instanceConfigPath(gameDir)
		if err := parseConfigFile(c.path, &c); err != nil {
			return c, err
		}
	}
	return c, nil
}

// SaveAlwaysLatest persists just that one setting to the per-instance file,
// preserving anything else already in it. Written when the user ticks the box in
// the picker, which is the only setting the UI can change.
func (c *Config) SaveAlwaysLatest(v bool) error {
	if c.path == "" {
		return fmt.Errorf("no per-instance config path is known")
	}
	if err := os.MkdirAll(filepath.Dir(c.path), 0o755); err != nil {
		return err
	}
	var kept []string
	if raw, ok := readText(c.path); ok {
		for _, line := range strings.Split(raw, "\n") {
			t := strings.TrimSpace(line)
			if t == "" {
				continue
			}
			if !strings.HasPrefix(t, "#") {
				if i := strings.IndexByte(t, '='); i > 0 &&
					strings.TrimSpace(t[:i]) == "alwaysLatest" {
					continue
				}
			}
			kept = append(kept, line)
		}
	}
	kept = append(kept, fmt.Sprintf("alwaysLatest = %t", v))
	c.AlwaysLatest = v
	return os.WriteFile(c.path, []byte(strings.Join(kept, "\n")+"\n"), 0o644)
}
