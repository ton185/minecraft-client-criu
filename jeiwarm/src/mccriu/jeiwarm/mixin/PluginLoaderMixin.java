package mccriu.jeiwarm.mixin;

import mccriu.jeiwarm.WarmJei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.library.config.RecipeCategorySortingConfig;
import mezz.jei.library.load.PluginLoader;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import mezz.jei.library.recipes.RecipeManager;
import mezz.jei.library.runtime.JeiHelpers;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Reuse the two other expensive products of a JEI start: the ingredient manager
 * (~19% of the build) and JEI's recipe index (~38%).
 *
 * Redirecting the individual producers rather than cancelling
 * {@code JeiStarter.start()} wholesale is the difference between this working and
 * the first attempt's dead overlay. Everything JEI does with these objects —
 * building helpers, the runtime, the GUI, notifying plugins — still runs exactly
 * as on a cold start; only the construction of two well-defined objects is
 * skipped. There are no side effects to reproduce by hand and nothing to keep in
 * sync with JEI's internals.
 *
 * The known unsoundness lives here, and it is the one the caller accepted: the
 * reused recipe index was built from the previous connection's recipe objects.
 * On a server whose data has not changed those are content-identical, but they
 * are not the same objects the live {@code RecipeManager} now holds.
 */
@Mixin(PluginLoader.class)
public class PluginLoaderMixin {

    @Inject(method = "registerIngredients", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mccriu$reuseIngredients(CallbackInfoReturnable<IIngredientManager> cir) {
        if (!WarmJei.enabled() || !WarmJei.canReuse()) return;
        IIngredientManager cached = WarmJei.cachedIngredientManager();
        if (cached == null) return;
        WarmJei.recordPhaseReuse("ingredient manager");
        cir.setReturnValue(cached);
    }

    @Inject(method = "registerIngredients", at = @At("RETURN"), remap = false)
    private static void mccriu$cacheIngredients(CallbackInfoReturnable<IIngredientManager> cir) {
        if (WarmJei.enabled()) WarmJei.cacheIngredientManager(cir.getReturnValue());
    }

    @Inject(method = "createRecipeManager", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mccriu$reuseRecipes(CallbackInfoReturnable<RecipeManager> cir) {
        if (!WarmJei.enabled() || !WarmJei.canReuse()) return;
        RecipeManager cached = WarmJei.cachedRecipeManager();
        if (cached == null) return;
        WarmJei.recordPhaseReuse("recipe index");
        cir.setReturnValue(cached);
    }

    @Inject(method = "createRecipeManager", at = @At("RETURN"), remap = false)
    private static void mccriu$cacheRecipes(CallbackInfoReturnable<RecipeManager> cir) {
        if (WarmJei.enabled()) WarmJei.cacheRecipeManager(cir.getReturnValue());
    }
}
