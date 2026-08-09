# mc-criu

Minecraft checkpoint/restore tool powered by [CRIU](https://criu.org/).

## 📌 Table of Contents
- [Important Warnings](#important-warnings)
- [Usage Guide](#usage-guide)
  - [Setup](#setup)
  - [Expected Behaviour on Fresh Install](#expected-behaviour-on-fresh-install)
  - [Normal / Unsandboxed](#normal--unsandboxed)
  - [Flatpak](#flatpak)
- [Troubleshooting](#troubleshooting)
- [Build Guide](#building-mc-criu)

---

## Important Warnings

> [!WARNING]
> **Do not use on Singleplayer.**
> CRIU needs to revert some files to the state they were at the last checkpoint, which could roll back your worlds.

> [!NOTE]
> **Vibecoded**
>
> This project was vibecoded. I tested and personally use it on ATM10.

> [!NOTE]
> If you have changed a config that requires a reboot to register, or installed/removed a mod, you will need to generate a new checkpoint.
> The manager automatically detects mod changes, but if you changed a config, do a normal launch next time and create a checkpoint again.

> [!NOTE]
> Checkpoint files can be large (2–10+ GB) since they literally write the entire process memory to disk.
> It is recommended to clean up older/unused images. They live inside the Minecraft folder at `.mc-criu/session/images` — the newest one has the highest number.
> It is safe to delete the entire directory, but note that doing this will reset your mc-criu config.

---

## Usage Guide

### Setup

Run `install-nonroot.sh`.
This will add the necessary capabilities to the CRIU binary:

```bash
setcap cap_checkpoint_restore,cap_sys_ptrace+eip "$CRIU"
```

> [!TIP]
> It is recommended to also install the companion mod that provides JEI caching: `mc-criu-jei-addon.jar`.

---

### Expected Behaviour on Fresh Install

1. Press **Launch**.
2. The game will launch normally. On the top right of the main menu you should see a **Checkpoint** button.
3. If you do not have the JEI addon, you can just press the button now. **If you do have the JEI addon**, join the server you will be playing on, then disconnect and checkpoint. This allows JEI to be cached, which massively boosts world load time.
4. Once the button is pressed, the game will freeze and then close.
   *If it does not close, refer to the [Troubleshooting](#troubleshooting) section.*
6. The checkpoint will still take some time after the window closes – you can see exactly when it finishes in the launcher's log window.
7. Once the checkpoint is done, press **Launch** again.
8. The manager will pop up a window asking you to choose a checkpoint to restore, or skip and load normally.
9. After choosing *Restore*, the Minecraft window should appear within a couple of seconds (depending on your hardware).
10. You can then create a new checkpoint whenever you want, or continue restoring from the existing one.

---

### Normal / Unsandboxed

In your launcher, set the wrapper command to:

```bash
<path to mc-criu> auto --
```

Example:

```bash
/home/user/mc-criu-manager auto --
```

---

### Flatpak

The app can't work directly inside Flatpak due to permission issues with CRIU, so you need to start the Minecraft process unsandboxed.

> [!WARNING]
> The following command will allow your launcher to use `flatpak-spawn --host`, which can spawn **any** command/process unsandboxed. Proceed with caution.

First, grant the Flatpak permission:

```bash
flatpak override --user --talk-name=org.freedesktop.Flatpak <launcher flatpak id>
```

Examples for popular launchers:

```bash
# Prism
flatpak override --user --talk-name=org.freedesktop.Flatpak org.prismlauncher.PrismLauncher

# PolyMC
flatpak override --user --talk-name=org.freedesktop.Flatpak org.polymc.PolyMC
```

Once done, go to your instance settings and find the **Custom Commands** tab.
In the **Wrapper Command**, enter:


```bash
flatpak-spawn --host <full path to mc-criu-manager binary> auto --
```

Example:

```bash
flatpak-spawn --host /home/user/Downloads/mc-criu-manager auto --
```

---

#### If you get a warning about "Matches more than one Flatpak install; not translating":

Find the path which would give you the same root as the one mentioned.  
For example, if it says `/app/share/PrismLauncher/NewLaunch.jar`, you need to find the directory on your host which contains `share` inside it.

Usually this is something like:

```
/var/lib/flatpak/app/org.prismlauncher.PrismLauncher/current/active/files/
```

Under that path you should see `share/PrismLauncher/NewLaunch.jar`.

Then complete the `--map` argument like this:

```bash
--map /app=/var/lib/flatpak/app/org.prismlauncher.PrismLauncher/current/active/files/
```

Resulting in the final wrapper command:

```bash
flatpak-spawn --host <mc-criu-manager full path> auto --map /app=/var/lib/flatpak/app/org.prismlauncher.PrismLauncher/current/active/files/ --
```

---

## Troubleshooting

> [!WARNING]
> If you are on **Nvidia**, you will most likely need to add `-Dmccriu.unloadGL=true` to your JVM flags.  
> From my testing, this flag will break completely on **AMD** – don't add it unless you actually encounter an issue.

If your issue is not resolved after trying the flag:

1. Check what your launcher log window says.
2. Create an issue including:
   - Your hardware
   - The copy-paste from the launcher log window
   - Your wrapper command
   - The JVM flags you used


# Building mc-criu

The public source tree contains only the source and scripts needed to build the
release. Test modpacks, third-party mods, Minecraft files, CRIU images, logs and
the development experiment corpus are intentionally not included (to avoid license/size issues)

## Supported build target

The scripts build mc-criu for Linux and compile the client mods for Minecraft
1.21.1 with NeoForge 21.1.244. The release manager binary is static, but it is
built for the architecture of the installed Go toolchain.

Required tools:

- Linux and Bash
- Python 3.10 or newer (standard library only)
- JDK 21 (`java`, `javac` and `jar` on `PATH`)
- Go 1.24 or newer
- `zip`, `unzip`, `file` and `ldd`
- Internet access during the one-time dependency bootstrap

On Debian or Ubuntu, the non-language tools can be installed with:

```bash
sudo apt install openjdk-21-jdk golang-go python3 zip unzip file libc-bin
```

On Arch:
```bash
sudo pacman -Sy jdk21-openjdk go python3 zip unzip file
```

Distribution repositories may carry an older Go release. If `go version` is
older than 1.24, install a current Go toolchain from <https://go.dev/dl/>.

## One-time bootstrap

From the repository root, run:

```bash
python3 tools/bootstrap-build.py
chmod +x ./package.sh ./manager/build.sh ./agent/build-mod.sh ./wrapper/build-jar.sh ./jeiwarm/build-mod.sh
```

The bootstrap downloads build inputs into the ignored `runtime/` directory. It
does not download game assets, a test modpack or any runtime tests. Downloads
are pinned and checked before use:

| Input | Pinned version | Verification |
| --- | --- | --- |
| Minecraft client metadata | 1.21.1 | SHA-1 `6d257dcfa9d74cdd9a83b4f5984674004decfa81` |
| Minecraft client JAR | 1.21.1 | SHA-1 from the verified metadata (`30c73b1c5da787909b2f73340419fdf13b9def88`) |
| NeoForge installer | 21.1.244 | SHA-256 `ac7bea8f5c8a1d64f8787d177cc890e3b9abf67ede800f999fe05386d46fcaa8` |
| JEI compile API | 19.27.0.343 for NeoForge 1.21.1 | SHA-1 `de304e36e94ff54997d62ee881c904a4892fd6dc` |

The NeoForge installer obtains its own transitive libraries and validates them
using the checksums in its install profile. Minecraft, NeoForge and JEI remain
third-party build inputs; they are downloaded locally and are not included in
this source repository.

The bootstrap is idempotent. To force a completely clean dependency setup,
delete `runtime/` and run it again.

## Build the release archive

```bash
./package.sh
```

The result is `out/mc-criu.zip`. The command builds:

- the NeoForge checkpoint mod;
- the static Go manager with that mod embedded;
- the optional launcher javaagent;
- the optional JEI caching addon; and
- the user-facing release archive and checksum manifest.

Check the completed archive with:

```bash
unzip -t out/mc-criu.zip
sha256sum out/mc-criu.zip
```

## Build individual components

After bootstrapping, the component scripts can also be run directly:

```bash
./agent/build-mod.sh       # runtime/mc/mods/mc-criu-mod.jar
./manager/build.sh         # out/mc-criu-manager (also rebuilds/embeds the mod)
./wrapper/build-jar.sh     # out/mc-criu-wrapper.jar
./jeiwarm/build-mod.sh     # runtime/build-deps/mods/mc-criu-jei-addon.jar
```

`JEIWARM_JEI_JAR=/path/to/jei.jar` can be set to compile the JEI addon against a
different compatible JEI 19.x JAR. `JEIWARM_GAME_DIR=/path/to/game` changes
where that addon is written; by default it stays under `runtime/build-deps/`.

## What is deliberately absent

The public repository does not ship the private test harness or its fixtures.
Those fixtures depend on large third-party mod collections and captured runtime
state, neither of which is needed to inspect or compile mc-criu. Release builds
are verified on the project test machines before publication.
