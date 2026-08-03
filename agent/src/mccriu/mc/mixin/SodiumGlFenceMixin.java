package mccriu.mc.mixin;

import mccriu.core.GlContextEpoch;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL32C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes Sodium's opaque fence wrappers obey the GL-context lifecycle.
 *
 * <p>Sodium 0.8.12 stores the native {@code GLsync} pointer in {@code GlFence.id}.
 * CRIU preserves that Java {@code long}, but destroying the context destroys the
 * object it names. The old GPU work is already complete from the rebuilt
 * application's point of view: GlSnapshot read the destination buffers back and
 * replayed those final bytes. Treating an old wrapper as completed is therefore
 * the synchronization-preserving operation; querying or deleting its driver
 * pointer is never valid. Sodium's separate raw render-ahead fence queue is
 * retired by MinecraftHost before teardown.
 *
 * <p>{@link Pseudo} keeps Sodium optional. With no Sodium target this mixin is
 * ignored; when it is present, the descriptors deliberately pin the 0.8.12 API
 * instead of broadly changing an unrelated future class.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.sync.GlFence", remap = false)
abstract class SodiumGlFenceMixin {

    @Shadow private boolean disposed;

    @Unique private long mccriu$contextEpoch;
    @Unique private boolean mccriu$reportedStale;
    @Unique private long mccriu$nativeId;
    @Unique private String mccriu$creationDiagnostic;
    @Unique private int mccriu$deleteCalls;

    @Inject(method = "<init>(J)V", at = @At("RETURN"))
    private void mccriu$rememberContext(long id, CallbackInfo ci) {
        this.mccriu$contextEpoch = GlContextEpoch.current();
        this.mccriu$nativeId = id;

        // Retain enough post-rebuild evidence to distinguish a stale wrapper,
        // an externally deleted current fence, and a changed context/dispatch
        // if this fails again. Cold-start rendering remains byte-for-byte
        // Sodium behaviour.
        if (this.mccriu$contextEpoch > 0) {
            String errorsAfterCreate = mccriu$drainGlErrors();
            boolean validAtCreation = id != 0 && GL32C.glIsSync(id);
            String errorsAfterValidation = mccriu$drainGlErrors();
            this.mccriu$creationDiagnostic = String.format(
                    "id=0x%X bornEpoch=%d validAtCreation=%s "
                    + "errorsAfterGlFenceSync=%s errorsAfterGlIsSync=%s %s",
                    id, this.mccriu$contextEpoch, validAtCreation,
                    errorsAfterCreate, errorsAfterValidation,
                    GlContextEpoch.describeCurrentContext());
        }
    }

    @Inject(method = "isCompleted()Z", at = @At("HEAD"), cancellable = true)
    private void mccriu$completeFenceFromOldContext(CallbackInfoReturnable<Boolean> cir) {
        if (mccriu$isStale()) cir.setReturnValue(true);
    }

    @Inject(method = "sync()V", at = @At("HEAD"), cancellable = true)
    private void mccriu$skipUnboundedWaitOnOldContext(CallbackInfo ci) {
        if (mccriu$isStale()) ci.cancel();
    }

    @Inject(method = "sync(J)V", at = @At("HEAD"), cancellable = true)
    private void mccriu$skipWaitOnOldContext(long timeout, CallbackInfo ci) {
        if (mccriu$isStale()) ci.cancel();
    }

    @Inject(method = "delete()V", at = @At("HEAD"), cancellable = true)
    private void mccriu$forgetFenceFromOldContext(CallbackInfo ci) {
        if (!mccriu$isStale()) {
            this.mccriu$deleteCalls++;
            return;
        }
        // Preserve GlFence's own lifecycle contract while deliberately avoiding
        // glDeleteSync(id): id belongs to a context that no longer exists.
        this.disposed = true;
        ci.cancel();
    }

    /**
     * Run Sodium's one real query, but replace its ambiguous count check with a
     * complete observation if it fails. A redirect avoids querying twice and
     * therefore keeps the successful path equivalent to Sodium's original code.
     */
    @Redirect(
            method = "isCompleted()Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL32C;glGetSynci(JILjava/nio/IntBuffer;)I"))
    private int mccriu$guardSyncQuery(long id, int pname, IntBuffer count) {
        if (GlContextEpoch.current() == 0) return GL32C.glGetSynci(id, pname, count);

        // Clear and record any older error so errorsAfterQuery belongs to the
        // glGetSynciv call under test rather than an unrelated render command.
        String errorsBeforeQuery = mccriu$drainGlErrors();
        boolean validBeforeQuery = id != 0 && GL32C.glIsSync(id);
        String errorsAfterValidation = mccriu$drainGlErrors();

        int result = GL32C.glGetSynci(id, pname, count);
        int returned = count.get(0);
        String errorsAfterQuery = mccriu$drainGlErrors();
        if (returned == 1) return result;

        String message = String.join("\n",
                "mc-criu: Sodium's current-epoch fence failed glGetSynciv",
                "  wrapper: id=0x" + Long.toHexString(this.mccriu$nativeId).toUpperCase()
                        + " callId=0x" + Long.toHexString(id).toUpperCase()
                        + " bornEpoch=" + this.mccriu$contextEpoch
                        + " currentEpoch=" + GlContextEpoch.current()
                        + " disposed=" + this.disposed
                        + " priorDeleteCalls=" + this.mccriu$deleteCalls,
                "  creation: " + String.valueOf(this.mccriu$creationDiagnostic),
                "  query: validBeforeQuery=" + validBeforeQuery
                        + " pname=0x" + Integer.toHexString(pname).toUpperCase()
                        + " result=0x" + Integer.toHexString(result).toUpperCase()
                        + " returned=" + returned
                        + " errorsBeforeQuery=" + errorsBeforeQuery
                        + " errorsAfterGlIsSync=" + errorsAfterValidation
                        + " errorsAfterQuery=" + errorsAfterQuery,
                "  query context: " + GlContextEpoch.describeCurrentContext(),
                "  post-rebuild control: " + GlContextEpoch.lastVerifiedSync());
        throw new IllegalStateException(message);
    }

    @Unique
    private boolean mccriu$isStale() {
        if (GlContextEpoch.isCurrent(this.mccriu$contextEpoch)) return false;
        if (!this.mccriu$reportedStale) {
            this.mccriu$reportedStale = true;
            GlContextEpoch.reportRetiredStaleSync("Sodium GlFence", this.mccriu$contextEpoch);
        }
        return true;
    }

    @Unique
    private static String mccriu$drainGlErrors() {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            int error = GL11C.glGetError();
            if (error == GL11C.GL_NO_ERROR) break;
            errors.add(mccriu$errorName(error));
        }
        return errors.isEmpty() ? "none" : errors.toString();
    }

    @Unique
    private static String mccriu$errorName(int error) {
        return switch (error) {
            case GL11C.GL_INVALID_ENUM -> "GL_INVALID_ENUM(0x0500)";
            case GL11C.GL_INVALID_VALUE -> "GL_INVALID_VALUE(0x0501)";
            case GL11C.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION(0x0502)";
            case GL11C.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY(0x0505)";
            default -> String.format("0x%04X", error);
        };
    }
}
