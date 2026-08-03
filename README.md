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

---

## Important Warnings

> [!WARNING]
> **Do not use on Singleplayer.**
> CRIU needs to revert some files to the state they were at the last checkpoint, which could roll back your worlds.

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

