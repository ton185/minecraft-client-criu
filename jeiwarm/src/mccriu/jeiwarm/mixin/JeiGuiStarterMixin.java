package mccriu.jeiwarm.mixin;

import mccriu.jeiwarm.WarmJei;

import mezz.jei.api.registration.IRuntimeRegistration;
import mezz.jei.gui.startup.JeiEventHandlers;
import mezz.jei.gui.startup.JeiGuiStarter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reuse the ingredient filter and the GUI handlers instead of rebuilding them.
 *
 * This is the most expensive single phase — ~37% of JEI's startup, and 90% of
 * that is the tooltip search index, which calls {@code getTooltipLines} once per
 * ingredient and so runs every mod's tooltip listener 86,177 times.
 *
 * The first attempt at this mod cancelled {@code JeiStarter.start()} outright,
 * which skipped the phase but left the item-list overlay dead: the overlay's
 * event subscriptions are created by {@code NeoForgeGuiPlugin.registerRuntime}
 * calling this method, and cleared again on disconnect. Skipping the call meant
 * nothing re-subscribed.
 *
 * The separation that makes it work: this method does two things, and only one of
 * them is expensive. It builds the filter and the overlays (expensive, and
 * identical on a static server), and it returns a plain three-field record of
 * event handlers, which {@code EventRegistration.registerEvents} then subscribes
 * — pure event-bus work. So caching the products and returning them lets JEI's
 * own code do all the wiring, exactly as it does on a cold start. The overlay is
 * live because it was registered the normal way, not because this mod tried to
 * reproduce the registration.
 */
@Mixin(JeiGuiStarter.class)
public class JeiGuiStarterMixin {

    @Inject(method = "start", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mccriu$reuseGui(IRuntimeRegistration registration,
                                       CallbackInfoReturnable<JeiEventHandlers> cir) {
        if (!WarmJei.enabled()) return;
        JeiEventHandlers cached = WarmJei.cachedGuiHandlers();
        if (cached == null || !WarmJei.canReuse()) return;
        // The real method leaves these four on the registration, and
        // JeiStarter.start() reads them straight afterwards to build the
        // JeiRuntime. Skipping the call without putting them back would hand
        // JEI a runtime with no overlay and no filter.
        WarmJei.applyCachedGui(registration);
        WarmJei.recordGuiReuse();
        cir.setReturnValue(cached);
    }

    @Inject(method = "start", at = @At("RETURN"), remap = false)
    private static void mccriu$cacheGui(IRuntimeRegistration registration,
                                        CallbackInfoReturnable<JeiEventHandlers> cir) {
        if (!WarmJei.enabled()) return;
        WarmJei.cacheGui(cir.getReturnValue(), registration);
    }
}
