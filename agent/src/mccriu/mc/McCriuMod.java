package mccriu.mc;

import mccriu.core.CheckpointCoordinator;
import mccriu.core.GlSnapshot;
import mccriu.core.Rendezvous;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.common.Mod;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for the checkpoint agent inside a NeoForge client.
 *
 * Being a mod rather than a {@code -javaagent} is deliberate: the snapshot layer
 * must talk to the <em>same</em> LWJGL classes the game does. A java agent is
 * loaded by the system class loader, while NeoForge loads the game and its
 * libraries through its own transforming loader, so an agent could easily end up
 * driving a second, unrelated copy of LWJGL and capture GL state from a context
 * nobody is rendering to.
 *
 * The mod does nothing at all unless {@code -Dmccriu.session=<dir>} is set, so a
 * modpack can ship it permanently and only pay for it when the supervisor asks.
 */
@Mod("mccriu")
public final class McCriuMod {

    public static final String SESSION_PROPERTY = "mccriu.session";
    private static final System.Logger LOG = System.getLogger("mc-criu");

    private static volatile CheckpointCoordinator coordinator;

    public McCriuMod() {
        String session = System.getProperty(SESSION_PROPERTY);
        if (session == null || session.isBlank()) {
            LOG.log(System.Logger.Level.INFO,
                    "mc-criu: inactive (-D" + SESSION_PROPERTY + " not set)");
            return;
        }
        CheckpointButton.install(Paths.get(session));
        Thread t = new Thread(() -> waitForGameThenInstall(Paths.get(session)), "mc-criu-install");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Wait until the client is actually rendering before hooking in.
     *
     * A checkpoint taken before the window exists has nothing to snapshot, and
     * the interesting moment is the title screen anyway — the point where a
     * heavy modpack has finished loading and the image is worth keeping.
     */
    private static void waitForGameThenInstall(Path sessionDir) {
        Rendezvous rdv = new Rendezvous(sessionDir);
        rdv.setState(Rendezvous.STARTING);
        try {
            Minecraft mc = null;
            for (int i = 0; i < 6000; i++) { // up to ten minutes; big packs are slow
                mc = Minecraft.getInstance();
                if (mc != null && mc.getWindow() != null && mc.screen != null) break;
                Thread.sleep(100);
            }
            if (mc == null || mc.getWindow() == null) {
                rdv.setFailed("Minecraft did not reach a rendering state within 10 minutes");
                return;
            }

            String problem = MinecraftHost.selfCheck(mc);
            if (problem != null) {
                // Refuse now, loudly, while the game is perfectly healthy —
                // rather than discovering it halfway through a teardown.
                LOG.log(System.Logger.Level.ERROR, "mc-criu: cannot install: " + problem);
                rdv.setFailed(problem);
                return;
            }

            final Minecraft client = mc;
            MinecraftHost host = new MinecraftHost(client);
            GlSnapshot.Config cfg = new GlSnapshot.Config();
            // Minecraft's own tasks run on the render thread once per frame,
            // before rendering starts. That is exactly the safe point for
            // destroying a GL context, and it costs nothing between checkpoints.
            coordinator = new CheckpointCoordinator(sessionDir, host, cfg, client::execute);
            coordinator.start();
            LOG.log(System.Logger.Level.INFO,
                    "mc-criu: armed; session=" + sessionDir + " screen=" + host.describeState());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "mc-criu: install failed", t);
            rdv.setFailed("install failed: " + t);
        }
    }

    public static CheckpointCoordinator coordinator() { return coordinator; }
}
