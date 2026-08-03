package mccriu.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A "Checkpoint" button on the main menu.
 *
 * The main menu is the only state a checkpoint is guaranteed from, and every
 * existing refusal points here: in-world is refused because of Sodium's mapped
 * staging buffer, and the multiplayer server list crashes the client outright
 * because ServerStatusPinger calls the GLFW clock from a Netty thread after
 * glfwTerminate. Putting the button here makes the safe state the obvious one.
 *
 * The game cannot dump its own process, so the button only ASKS: it drops a
 * marker file in the rendezvous directory, and the manager supervising this
 * process runs the real checkpoint, which comes back through the normal
 * request/park/resume handshake this mod already implements.
 *
 * TOP RIGHT, not bottom left. It started bottom-left, and All the Mods 10's
 * FancyMenu title screen draws its version text over exactly that corner: the
 * button was still there and still hoverable, but sat under three lines of
 * overlapping text, which is not a state anyone should have to click into.
 *
 * FEEDBACK MATTERS MORE HERE THAN ON AN ORDINARY BUTTON. A checkpoint parks the
 * render thread for several seconds — the game *freezes* — so a button that gives
 * nothing back is indistinguishable from one that never registered the click.
 * The label changes before the freeze, and a toast reports the outcome after it.
 */
final class CheckpointButton {

    private static final System.Logger LOG = System.getLogger("mc-criu");

    private static final Component IDLE    = Component.literal("Checkpoint");
    private static final Component WORKING = Component.literal("Checkpointing...");

    static void install(Path sessionDir) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (ScreenEvent.Init.Post e) -> {
                    if (!(e.getScreen() instanceof TitleScreen screen)) return;
                    Button b = Button.builder(IDLE, btn -> request(sessionDir, btn))
                            .bounds(screen.width - 104, 6, 98, 20)
                            .build();
                    e.addListener(b);
                });
    }

    private static void request(Path sessionDir, Button btn) {
        Path rdv = sessionDir.resolve("rendezvous");
        Path marker = rdv.resolve("checkpoint-please");
        Path result = rdv.resolve("checkpoint-result");
        Minecraft mc = Minecraft.getInstance();

        try {
            Files.createDirectories(rdv);
            Files.deleteIfExists(result);   // a stale result would be read as ours
            Files.writeString(marker, "please\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ex) {
            LOG.log(System.Logger.Level.ERROR, "mc-criu: could not request a checkpoint: " + ex);
            toast(mc, "Checkpoint failed", "Could not reach mc-criu-manager. Is the game "
                    + "running under `mc-criu-manager auto`?");
            return;
        }

        // Say something immediately: the client is about to stop rendering for
        // several seconds, and that must not look like a hang.
        btn.setMessage(WORKING);
        btn.active = false;
        toast(mc, "Checkpoint requested",
                "The game will freeze, then close. Relaunch to restore it.");
        LOG.log(System.Logger.Level.INFO, "mc-criu: checkpoint requested from the main menu");

        Thread watcher = new Thread(() -> {
            String outcome = null;
            // Generous: a 500-mod image is ~10 GB and the dump alone takes seconds.
            for (int i = 0; i < 600 && outcome == null; i++) {
                try {
                    Thread.sleep(500);
                    if (Files.exists(result)) outcome = Files.readString(result).trim();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // A partially written file; try again on the next pass.
                }
            }
            // A SUCCESSFUL checkpoint never gets here: the dump stops the tree,
            // so this process is gone before there is anything to report. That
            // is why the request toast above says the game will close. What this
            // watcher is really for is the failure case, where the manager
            // releases the agent and the game keeps running -- and then the user
            // needs to be told why nothing happened.
            final String what = outcome;
            mc.execute(() -> {
                btn.setMessage(IDLE);
                btn.active = true;
                if (what == null) {
                    toast(mc, "Checkpoint status unknown",
                            "No result after five minutes. Check the manager's output.");
                } else if (!what.startsWith("ok")) {
                    toast(mc, "Checkpoint failed", what);
                }
            });
        }, "mc-criu-checkpoint-feedback");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void toast(Minecraft mc, String title, String body) {
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(body));
    }

    private CheckpointButton() {}
}
