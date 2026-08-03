package mccriu.jeiwarm.mixin;

import mccriu.jeiwarm.WarmJei;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fingerprint what the SERVER sent, which is the only stable identity for
 * "this server's data".
 *
 * The first version of this mod fingerprinted the client's live recipe set
 * instead, and measurement killed that: on All the Mods 10 the live set is
 * replaced five times per join — 93518 -> 93527 -> 93531 -> 93535 -> 93649
 * entries — because mods add recipes progressively while handling
 * {@code RecipesUpdatedEvent}, and JEI builds its index part-way through that
 * sequence. Which intermediate value JEI sees depends on listener ordering, so
 * it differed between two joins of the identical world (93646 against 93649) and
 * reuse correctly refused every time.
 *
 * The server's payload does not have that problem: it is one packet, sent once
 * per join, before any mod has touched anything.
 *
 * Deliberately does not cancel. Letting the handler run means every mod still
 * gets its event and still applies its own recipe changes exactly as it always
 * does — which is also why the earlier idea of suppressing
 * {@code RecipeManager.replaceRecipes} had to go: keeping the previous
 * contents would leave those mod-added recipes in place and then let the mods
 * add them a second time.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleUpdateRecipes", at = @At("HEAD"))
    private void mccriu$fingerprintServerRecipes(ClientboundUpdateRecipesPacket packet,
                                                 CallbackInfo ci) {
        if (!WarmJei.enabled()) return;
        WarmJei.serverPayload(packet.getRecipes());
    }
}
