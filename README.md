# mc-criu

Start a fully initialized Minecraft client in seconds by restoring a
[CRIU](https://criu.org/) checkpoint instead of loading the game and every mod
again.

mc-criu is designed for Minecraft 1.21.1 with NeoForge on Linux. It checkpoints
the client at the main menu, closes it, and restores the same client state the
next time you launch. The manager carries the required NeoForge mod inside its
own binary and installs or updates it automatically.

## Contents

- [Important safety information](#important-safety-information)
- [Requirements](#requirements)
- [Installation](#installation)
- [Launcher setup](#launcher-setup)
- [Creating and restoring a checkpoint](#creating-and-restoring-a-checkpoint)
- [Managing checkpoints](#managing-checkpoints)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [License](#license)

## Important safety information

> [!WARNING]
> **Do not use mc-criu with singleplayer worlds.** Restoring a process also
> restores its view of files that were open at checkpoint time. That is useful
> for logs, but unsafe for irreplaceable world data and may roll a world back.

> [!WARNING]
> **Only create checkpoints from the main menu.** In-world checkpointing is not
> supported. Disconnect from a multiplayer server before pressing the
> **Checkpoint** button.

Keep these points in mind:

- A checkpoint belongs to the mod set and launch configuration that created it.
  The manager detects mod changes and refuses incompatible checkpoints.
- After changing a configuration that only takes effect on restart, launch
  normally and create a new checkpoint. Configuration changes cannot all be
  detected automatically.
- Checkpoints contain the entire JVM memory image and commonly use 2–10+ GB of
  disk space each.
- A checkpoint is not a substitute for backups.

## Requirements

- Linux. The prebuilt release contains a Linux x86-64 manager binary.
- Minecraft 1.21.1 with NeoForge.
- [CRIU](https://criu.org/) installed and available on `PATH`.
- X11 or XWayland.
- A launcher with a wrapper-command setting, such as Prism Launcher, PolyMC or
  MultiMC. Flatpak launchers need the additional setup below.
- Enough free disk space for the checkpoint images.

CRIU is not bundled. Install the `criu` package supplied by your Linux
distribution before continuing.

## Installation

Download `mc-criu.zip` and `mc-criu.zip.sha256` from the latest GitHub Release,
place them in the same directory, and verify the download:

```bash
sha256sum -c mc-criu.zip.sha256
unzip mc-criu.zip
cd mc-criu
```

Install the manager somewhere permanent:

```bash
sudo install -m755 mc-criu-manager /usr/local/bin/mc-criu-manager
```

Do not run it from `/tmp`, a RAM disk, or another location that may disappear.
The manager is part of the checkpointed process tree, so moving or deleting the
binary can make an existing checkpoint unrestorable.

### Running as a normal user

Run the included setup script once as root:

```bash
sudo ./install-nonroot.sh
```

It grants the installed CRIU binary the two capabilities required to checkpoint
another process: `CAP_CHECKPOINT_RESTORE` and `CAP_SYS_PTRACE`. It does not make
the manager setuid and does not grant `CAP_SYS_ADMIN`.

A CRIU package upgrade may replace the binary and clear those capabilities. If
checkpointing stops working after an upgrade, run the script again.

### Optional JEI caching addon

If the instance uses JEI, copy the companion addon into its `mods` directory:

```bash
cp mc-criu-jei-addon.jar /path/to/instance/mods/
```

The addon keeps JEI's built index across a disconnect and restore. It is enabled
whenever the JAR is installed; remove the JAR to disable it.

You do **not** need to install `mc-criu-mod.jar` manually. The manager embeds
that mod and installs the matching copy into the instance automatically.

### Release contents

| File | Purpose |
| --- | --- |
| `mc-criu-manager` | Main executable; includes and installs the checkpoint mod |
| `mc-criu-mod.jar` | Standalone copy of the embedded mod for manual installation |
| `mc-criu-jei-addon.jar` | Optional JEI reconnect cache |
| `mc-criu-wrapper.jar` | Java-agent fallback for launchers without a wrapper-command setting |
| `install-nonroot.sh` | One-time CRIU capability setup |
| `MANIFEST.txt` | Build provenance and checksums for the archive contents |

## Launcher setup

### Native or otherwise unsandboxed launcher

Open the instance's custom-command settings and set its **Wrapper Command** to:

```text
/usr/local/bin/mc-criu-manager auto --
```

Use the actual path if you installed the manager somewhere else. The trailing
`--` is required.

### Flatpak launcher

CRIU cannot checkpoint the game inside the launcher's Flatpak sandbox. The
wrapper must start the manager on the host with `flatpak-spawn --host`.

> [!WARNING]
> Granting this permission lets the Flatpak launch arbitrary commands on the
> host, outside its sandbox. Only enable it for a launcher you trust.

Grant the permission using the launcher's Flatpak application ID:

```bash
flatpak override --user --talk-name=org.freedesktop.Flatpak <application-id>
```

Common IDs include:

```bash
# Prism Launcher
flatpak override --user --talk-name=org.freedesktop.Flatpak org.prismlauncher.PrismLauncher

# PolyMC
flatpak override --user --talk-name=org.freedesktop.Flatpak org.polymc.PolyMC
```

Then set the instance's **Wrapper Command** to an absolute host path:

```text
flatpak-spawn --host /usr/local/bin/mc-criu-manager auto --
```

#### Resolving a Flatpak path mismatch

If the manager reports that a path matches more than one Flatpak installation,
or that the game directory does not exist on the host, map the sandbox path to
its host location.

For example, if the launcher passes `/app/share/PrismLauncher/NewLaunch.jar`,
locate the host directory whose `share/PrismLauncher/` subdirectory contains
that file. It is commonly similar to:

```text
/var/lib/flatpak/app/org.prismlauncher.PrismLauncher/current/active/files/
```

Then add the mapping before the final `--`:

```text
flatpak-spawn --host /usr/local/bin/mc-criu-manager auto \
  --map /app=/var/lib/flatpak/app/org.prismlauncher.PrismLauncher/current/active/files \
  --
```

## Creating and restoring a checkpoint

On a fresh installation:

1. Press **Launch**. With no checkpoint available, the manager starts Minecraft
   normally.
2. Wait for the main menu. A **Checkpoint** button should appear in the upper
   right corner.
3. If you installed the JEI addon, join the multiplayer server you plan to use,
   wait for JEI to finish loading, and then disconnect back to the main menu.
   This warms the index before it is checkpointed.
4. Press **Checkpoint**. Minecraft freezes and its window closes; this is
   expected.
5. Keep the launcher log open. The dump continues after the window closes, and
   the log reports when it has completed.
6. Press **Launch** again.
7. Choose a compatible checkpoint in the manager window and select **Restore**,
   or skip it to perform a normal launch.
8. The restored Minecraft window should appear within a few seconds, depending
   on the machine and image size.

The same checkpoint can be restored repeatedly. Create a new one whenever the
saved state is no longer the state you want to resume from.

## Managing checkpoints

Images are stored inside the instance at:

```text
<game-directory>/.mc-criu/session/images/<generation>/
```

Higher generation numbers are newer. Checkpoints can be deleted from the picker
or by removing an entire numbered generation directory while Minecraft is not
running.

Deleting `.mc-criu/session/images/` removes all saved checkpoints. Deleting the
whole `.mc-criu/` directory also resets the per-instance mc-criu configuration.

## Troubleshooting

Start with the built-in environment check:

```bash
mc-criu-manager doctor
```

### NVIDIA checkpoint failure

The default is tested on AMD. On NVIDIA, a checkpoint may require this JVM flag:

```text
-Dmccriu.unloadGL=true
```

Only add it if checkpointing fails with the default. Do not use it on AMD; it is
known to break the restore path there.

### No Checkpoint button

- Confirm that the launcher is using the wrapper command, including its final
  `--`.
- Check the launcher log for the manager's `instance` and `mod installed` lines.
- Confirm that `mc-criu-mod.jar` appeared in the instance's `mods` directory.
- Make sure the instance is Minecraft 1.21.1 with NeoForge.

### A checkpoint is not offered

- Confirm that the launcher resolved the same game directory as before.
- Check whether mods or launch arguments changed. The manager deliberately
  refuses to restore incompatible images.
- For Flatpak launchers, verify the `--map` path translation.

### Reporting an issue

Include:

- CPU and GPU models and the GPU driver;
- Linux distribution, kernel and CRIU versions;
- launcher name and whether it is a Flatpak;
- wrapper command and JVM flags;
- the complete relevant launcher log; and
- the output of `mc-criu-manager doctor`.

For additional manager diagnostics, launch once with `MC_CRIU_DEBUG=1` in the
environment and include that output as well.

## Building from source

The public repository contains only the source and scripts required to compile
the release. Test modpacks, third-party mods, Minecraft files, CRIU images, logs
and the development experiment corpus are intentionally excluded.

### Build requirements

- Linux and Bash
- Python 3.10 or newer; the bootstrap uses only the standard library
- JDK 21, with `java`, `javac` and `jar` on `PATH`
- Go 1.24 or newer
- `zip`, `unzip`, `file` and `ldd`
- Internet access for the one-time dependency bootstrap

On Debian or Ubuntu, the non-language tools can be installed with:

```bash
sudo apt install openjdk-21-jdk golang-go python3 zip unzip file libc-bin
```

Distribution repositories may carry an older Go release. If `go version` is
older than 1.24, install a current toolchain from <https://go.dev/dl/>.

### Bootstrap the compiler dependencies

From the repository root, run:

```bash
python3 tools/bootstrap-build.py
```

The bootstrap downloads build inputs into the ignored `runtime/` directory. It
does not download game assets, a test modpack or runtime tests. Each direct
download is pinned and verified:

| Input | Version | Verification |
| --- | --- | --- |
| Minecraft client metadata | 1.21.1 | SHA-1 `6d257dcfa9d74cdd9a83b4f5984674004decfa81` |
| Minecraft client JAR | 1.21.1 | SHA-1 `30c73b1c5da787909b2f73340419fdf13b9def88` |
| NeoForge installer | 21.1.244 | SHA-256 `ac7bea8f5c8a1d64f8787d177cc890e3b9abf67ede800f999fe05386d46fcaa8` |
| JEI compile API | 19.27.0.343 | SHA-1 `de304e36e94ff54997d62ee881c904a4892fd6dc` |

The NeoForge installer obtains and validates its own transitive libraries.
Minecraft, NeoForge and JEI are downloaded as local build inputs and are not
redistributed in the source repository.

The bootstrap is idempotent. Delete `runtime/` and run it again to force a clean
dependency setup.

### Build the release

```bash
./package.sh
```

The result is `out/mc-criu.zip`. It contains the static manager with the mod
embedded, the standalone mod, wrapper, JEI addon, user documentation, license
and an internal checksum manifest.

Verify it with:

```bash
unzip -t out/mc-criu.zip
sha256sum out/mc-criu.zip
```

Individual components can also be built after bootstrapping:

```bash
./agent/build-mod.sh       # runtime/mc/mods/mc-criu-mod.jar
./manager/build.sh         # out/mc-criu-manager; also rebuilds and embeds the mod
./wrapper/build-jar.sh     # out/mc-criu-wrapper.jar
./jeiwarm/build-mod.sh     # runtime/build-deps/mods/mc-criu-jei-addon.jar
```

Set `JEIWARM_JEI_JAR=/path/to/jei.jar` to compile the addon against another
compatible JEI 19.x JAR. Set `JEIWARM_GAME_DIR=/path/to/game` to change where
the addon is written.

### Automated GitHub releases

`.github/workflows/release.yml` runs when a GitHub Release is published,
including a prerelease. It checks out the release tag, performs the same
bootstrap and package build, verifies the archive, and attaches:

- `mc-criu.zip`
- `mc-criu.zip.sha256`

The workflow can also be run manually from the repository's **Actions** tab with
an existing release tag. This can backfill a release created before the workflow
was added.

## License

mc-criu is available under the [BSD 3-Clause License](LICENSE).
