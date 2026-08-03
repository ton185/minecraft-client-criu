package mccriu.core;

import org.lwjgl.openal.ALC;

import java.lang.reflect.Field;

/**
 * Bridges a gap in LWJGL's OpenAL lifecycle that only appears once you unload
 * and reload the library across a checkpoint.
 *
 * <p>{@code ALC.getICD()} resolves the alc* entry points from a static {@code icd}
 * cache, falling back to a {@code router} field only when that cache is empty.
 * {@code ALC.destroy()} rebuilds {@code router} on the next {@code create()} but
 * never clears {@code icd}, and the cache it leaves behind is an {@code ICDStatic}
 * whose {@code get()} reads a write-once holder — a {@code static final} frozen at
 * class-initialisation time. So after a reload every alc* call dispatches through
 * function pointers into the <em>previous</em> mapping of libopenal.
 *
 * <p>Without CRIU in the picture this is invisible: {@code dlclose} followed by
 * {@code dlopen} usually hands the library back at the same address, so the stale
 * pointers still happen to land on the right code. A restored process has a
 * different memory layout, the reloaded library lands elsewhere, and the first
 * {@code alcOpenDevice} after restore segfaults inside JNI.
 *
 * <p>Clearing {@code icd} restores the documented fallback: dispatch goes through
 * {@code router}, which {@code create()} has just rebuilt against the new mapping.
 * The very next {@code ALC.createCapabilities(device)} reinstalls an
 * {@code ICDStatic}, notices the addresses have moved and downgrades itself to
 * thread/process lookup — LWJGL's own recovery path. All this does is bridge the
 * window between {@code create()} and that first {@code createCapabilities()},
 * which is precisely where {@code alcOpenDevice} lives.
 */
final class AlcDispatchReset {

    private static final String FIELD = "icd";

    /**
     * Checks whether the reset can be performed, without performing it.
     * Returns null when it can, or a human-readable reason when it cannot, so
     * the coordinator can refuse a checkpoint up front instead of tearing the
     * graphics stack down and discovering the problem with a segfault.
     */
    static String probe() {
        try {
            Field f = ALC.class.getDeclaredField(FIELD);
            f.setAccessible(true);
            f.get(null);
            return null;
        } catch (NoSuchFieldException e) {
            return "this LWJGL build has no ALC." + FIELD + " field, so its stale OpenAL "
                    + "dispatch cache cannot be cleared after the library is reloaded. "
                    + "Audio would crash the JVM on restore.";
        } catch (RuntimeException | IllegalAccessException e) {
            return "cannot access ALC." + FIELD + " (" + e + "). If LWJGL is on the module "
                    + "path, add: --add-opens org.lwjgl.openal/org.lwjgl.openal=ALL-UNNAMED";
        }
    }

    /** Clear the cache. Call immediately after {@link ALC#create()}. */
    static void apply() {
        try {
            Field f = ALC.class.getDeclaredField(FIELD);
            f.setAccessible(true);
            f.set(null, null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "could not clear LWJGL's stale OpenAL dispatch cache: " + e
                    + ". Continuing would segfault on the next alcOpenDevice.", e);
        }
    }

    private AlcDispatchReset() {}
}
