package mccriu.mc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection helpers that fail with an explanation instead of a stack trace.
 *
 * Everything this integration reaches for is a private member of a Minecraft
 * class, so the interesting failure is not "it threw" but "which member moved,
 * and in which version". Each accessor names what it wanted and why, so a future
 * Minecraft that renames a field produces a message someone can act on.
 */
final class Reflect {

    static final class MissingMemberException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        MissingMemberException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** Look up a declared field and make it writable. */
    static Field field(Class<?> owner, String name, String why) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) {
            throw new MissingMemberException(
                    "mc-criu needs " + owner.getName() + "." + name + " (" + why + ") but it is "
                    + "not present or not accessible in this build. If this is a Minecraft "
                    + "version mc-criu has not been updated for, that field has probably been "
                    + "renamed. Underlying error: " + e, e);
        }
    }

    static Method method(Class<?> owner, String name, String why, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            throw new MissingMemberException(
                    "mc-criu needs " + owner.getName() + "." + name + "(...) (" + why + ") but it "
                    + "is not present or not accessible in this build. Underlying error: " + e, e);
        }
    }

    static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new MissingMemberException("cannot read " + f, e);
        }
    }

    static void setLong(Field f, Object target, long value) {
        try {
            f.setLong(target, value);
        } catch (IllegalAccessException e) {
            throw new MissingMemberException(
                    "cannot write " + f + ". Minecraft's window handle is a final field; writing "
                    + "it needs the module to be open. If Minecraft classes are in a named "
                    + "module here, add --add-opens for it.", e);
        }
    }

    static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new MissingMemberException("calling " + m.getName() + " failed: " + c, c);
        }
    }

    private Reflect() {}
}
