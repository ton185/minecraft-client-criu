# Caveats/Warnings
1. **Do not use on Singleplayer**. CRIU needs to revert some files to the state they were at the last checkpoint, which could rollback your worlds.
2. If you have changed a config that needs a reboot to register, or installed/removed a mod, you will need to generate a new checkpoint. The manager automatically detects mod changes, but if you changed a config you should do a normal launch next time and checkpoint again
3. Checkpoint files can be large (2-10+ GB) since they are literally writing the entire memory of the process to disk. It is recommended to clean up any older/unused images. They live within the minecraft folder at `.mc-criu/session/images`, with the newest one being numbered highest. It is also safe to delete this entire directory, but note that doing this will reset your mc-criu config.

# Usage Guide
## Setup
Run `install-nonroot.sh`, note that this will add some capabilities to the criu binary (`setcap cap_checkpoint_restore,cap_sys_ptrace+eip "$CRIU"`)

It is recommended to also install the companion mod that provides JEI caching (`mc-criu-jei-addon.jar`)

## Expected behavior on fresh install
1. You press launch
2. The game will launch normally. On the top right of the main menu you should see a "Checkpoint" button
3. Upon pressing this button, the game should freeze and then close (if it does not close, refer to troubleshooting)
3.5 If you have the JEI addon installed, it is also recommended to first join the server you will be playing on, and then disconnect and checkpoint there. This will allow JEI to be cached, massively boosting world load time.
5. The checkpoint will still take some time after the window closes, you can see exactly when the checkpoint is done in the launcher's log window
6. Once the checkpoint is done, you press launch
7. The manager will pop up a window asking you to choose a checkpoint to restore, or skip and load normally
8. Once you choose restore, the Minecraft window should pop up within a couple seconds (depends on hardware)
9. You can then checkpoint again whenever you want or continue to restore from the existing checkpoint


## Normal/Unsandboxed
In your launcher, set the wrapper command to `<path to mc-criu> auto --`, for example `/home/user/mc-criu-manager auto --`

## Flatpak
The app can't work directly in Flatpak due to permission issues with CRIU, so you will need to start the Minecraft process unsandboxed

Note that the following command will allow your launcher to use `flatpak-spawn --host` which can spawn any command/process unsandboxed
```bash
flatpak override --user --talk-name=org.freedesktop.Flatpak <launcher flatpak id>
# Examples for popular launchers

# Prism
flatpak override --user --talk-name=org.freedesktop.Flatpak org.prismlauncher.PrismLauncher

# PolyMC
flatpak override --user --talk-name=org.freedesktop.Flatpak org.polymc.PolyMC
```

Once you have done that, go to your instance settings and find the `Custom Commands` tab. In the `Wrapper Command`, enter this:
`flatpak-spawn --host <full path to mc-criu-manager binary> auto --`

For example:
`flatpak-spawn --host /home/user/Downloads/mc-criu-manager auto --`

**If you get a warning about "Matches more than one Flatpak install; not translating":**
Find the path which would get you the same root as the one mentioned. For example if it says /app/share/PrismLauncher/NewLaunch.jar, you need to find the directory on your host which has `/share` inside it.
Usually this should be something like `/var/lib/flatpak/app/org.prismlauncher.PrismLauncher/current/active/files/`, under which you should see `share/PrismLauncher/NewLaunch.jar`
so you can then complete the map argument like this: `--map /app=/var/lib/flatpak/app/org.prismlauncher.PrismLauncher/current/active/files/`, resulting in the final wrapper command of:
`flatpak-spawn --host <mc-criu-manager full path> auto --map /app=/var/lib/flatpak/app/org.prismlauncher.PrismLauncher/current/active/files/ --`

## Troubleshooting
If you are on nvidia, you will most likely need to add `-Dmccriu.unloadGL=true` to your JVM flags. From my testing this flag will break completely on AMD, so don't add it unless you encountered an issue.
If your issue is not resolved after trying this flag, check what your launcher log window says and create an issue stating your hardware, the copy-paste from the launcher log window, your wrapper command and JVM flags used.
