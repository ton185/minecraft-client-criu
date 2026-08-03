package mccriu.core;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Capture and replay the default-block uniform values of a linked program.
 *
 * Needed on <em>both</em> restore paths. {@code glProgramBinary} carries the
 * compiled and linked program but not the values written into its uniforms: the
 * spec resets every uniform to its initial value, exactly as a re-link does. A
 * program restored without this links successfully, reports no error, and
 * renders black — the kind of silent wrongness that is worse than a crash.
 *
 * Uniforms backed by a uniform block are deliberately skipped: their storage
 * lives in a buffer object, which the snapshot captures as a buffer.
 */
final class GlUniforms {

    /** One default-block uniform: its name, type, and raw values. */
    static final class Uniform {
        String name;
        int type;
        int arraySize;
        float[] floats;
        int[] ints;
        // Only one of floats/ints is populated, per the GL type.
    }

    static List<Uniform> capture(int program) {
        List<Uniform> out = new ArrayList<>();
        int count = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
        if (count <= 0) return out;

        // Uniforms belonging to a block have a non-negative block index; their
        // data lives in a UBO we capture separately.
        int[] blockIndex = new int[count];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer indices = stack.mallocInt(count);
            for (int i = 0; i < count; i++) indices.put(i, i);
            java.nio.IntBuffer params = stack.mallocInt(count);
            GL31.glGetActiveUniformsiv(program, indices, GL31.GL_UNIFORM_BLOCK_INDEX, params);
            for (int i = 0; i < count; i++) blockIndex[i] = params.get(i);
        }

        for (int i = 0; i < count; i++) {
            if (blockIndex[i] >= 0) continue;

            String name;
            int type, size;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer sizeBuf = stack.mallocInt(1);
                java.nio.IntBuffer typeBuf = stack.mallocInt(1);
                name = GL20.glGetActiveUniform(program, i, sizeBuf, typeBuf);
                size = sizeBuf.get(0);
                type = typeBuf.get(0);
            }
            if (name == null || name.isEmpty()) continue;

            // "thing[0]" is how drivers report arrays; index each element by name.
            String base = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;

            int components = componentsOf(type);
            if (components == 0) continue; // opaque type we do not know; program binary covers it

            Uniform u = new Uniform();
            u.name = base;
            u.type = type;
            u.arraySize = size;

            boolean intLike = isIntLike(type);
            if (intLike) u.ints = new int[components * size];
            else u.floats = new float[components * size];

            for (int e = 0; e < size; e++) {
                String elemName = size > 1 ? base + "[" + e + "]" : base;
                int loc = GL20.glGetUniformLocation(program, elemName);
                if (loc < 0) continue;
                if (intLike) {
                    int[] tmp = new int[components];
                    GL20.glGetUniformiv(program, loc, tmp);
                    System.arraycopy(tmp, 0, u.ints, e * components, components);
                } else {
                    float[] tmp = new float[components];
                    GL20.glGetUniformfv(program, loc, tmp);
                    System.arraycopy(tmp, 0, u.floats, e * components, components);
                }
            }
            out.add(u);
        }
        return out;
    }

    /** Re-apply captured uniforms. The program must already be in use. */
    static void restore(int program, List<Uniform> uniforms) {
        for (Uniform u : uniforms) {
            int components = componentsOf(u.type);
            for (int e = 0; e < u.arraySize; e++) {
                String elemName = u.arraySize > 1 ? u.name + "[" + e + "]" : u.name;
                int loc = GL20.glGetUniformLocation(program, elemName);
                if (loc < 0) continue;
                int off = e * components;
                if (u.ints != null) {
                    int[] v = slice(u.ints, off, components);
                    switch (components) {
                        case 1 -> GL20.glUniform1iv(loc, v);
                        case 2 -> GL20.glUniform2iv(loc, v);
                        case 3 -> GL20.glUniform3iv(loc, v);
                        case 4 -> GL20.glUniform4iv(loc, v);
                        default -> { }
                    }
                } else {
                    float[] v = slice(u.floats, off, components);
                    if (isMatrix(u.type)) {
                        switch (u.type) {
                            case GL20.GL_FLOAT_MAT2 -> GL20.glUniformMatrix2fv(loc, false, v);
                            case GL20.GL_FLOAT_MAT3 -> GL20.glUniformMatrix3fv(loc, false, v);
                            case GL20.GL_FLOAT_MAT4 -> GL20.glUniformMatrix4fv(loc, false, v);
                            case GL21.GL_FLOAT_MAT2x3 -> GL21.glUniformMatrix2x3fv(loc, false, v);
                            case GL21.GL_FLOAT_MAT2x4 -> GL21.glUniformMatrix2x4fv(loc, false, v);
                            case GL21.GL_FLOAT_MAT3x2 -> GL21.glUniformMatrix3x2fv(loc, false, v);
                            case GL21.GL_FLOAT_MAT3x4 -> GL21.glUniformMatrix3x4fv(loc, false, v);
                            case GL21.GL_FLOAT_MAT4x2 -> GL21.glUniformMatrix4x2fv(loc, false, v);
                            case GL21.GL_FLOAT_MAT4x3 -> GL21.glUniformMatrix4x3fv(loc, false, v);
                            default -> { }
                        }
                    } else {
                        switch (components) {
                            case 1 -> GL20.glUniform1fv(loc, v);
                            case 2 -> GL20.glUniform2fv(loc, v);
                            case 3 -> GL20.glUniform3fv(loc, v);
                            case 4 -> GL20.glUniform4fv(loc, v);
                            default -> { }
                        }
                    }
                }
            }
        }
    }

    private static int[] slice(int[] a, int off, int n) {
        int[] r = new int[n];
        System.arraycopy(a, off, r, 0, n);
        return r;
    }

    private static float[] slice(float[] a, int off, int n) {
        float[] r = new float[n];
        System.arraycopy(a, off, r, 0, n);
        return r;
    }

    private static boolean isMatrix(int type) {
        return switch (type) {
            case GL20.GL_FLOAT_MAT2, GL20.GL_FLOAT_MAT3, GL20.GL_FLOAT_MAT4,
                 GL21.GL_FLOAT_MAT2x3, GL21.GL_FLOAT_MAT2x4, GL21.GL_FLOAT_MAT3x2,
                 GL21.GL_FLOAT_MAT3x4, GL21.GL_FLOAT_MAT4x2, GL21.GL_FLOAT_MAT4x3 -> true;
            default -> false;
        };
    }

    private static boolean isIntLike(int type) {
        return switch (type) {
            case GL11.GL_INT, GL20.GL_INT_VEC2, GL20.GL_INT_VEC3, GL20.GL_INT_VEC4,
                 GL11.GL_UNSIGNED_INT, GL30.GL_UNSIGNED_INT_VEC2, GL30.GL_UNSIGNED_INT_VEC3,
                 GL30.GL_UNSIGNED_INT_VEC4, GL20.GL_BOOL, GL20.GL_BOOL_VEC2,
                 GL20.GL_BOOL_VEC3, GL20.GL_BOOL_VEC4 -> true;
            default -> isSampler(type);
        };
    }

    /** Sampler and image uniforms hold an integer texture unit. */
    private static boolean isSampler(int type) {
        // The sampler/image enums are not contiguous, but every one of them is
        // set with glUniform1i, so a name-based test is both simplest and
        // future-proof against enums this LWJGL build does not expose.
        return SAMPLER_TYPES.contains(type);
    }

    private static final java.util.Set<Integer> SAMPLER_TYPES = java.util.Set.of(
            GL20.GL_SAMPLER_1D, GL20.GL_SAMPLER_2D, GL20.GL_SAMPLER_3D, GL20.GL_SAMPLER_CUBE,
            GL20.GL_SAMPLER_1D_SHADOW, GL20.GL_SAMPLER_2D_SHADOW,
            GL30.GL_SAMPLER_1D_ARRAY, GL30.GL_SAMPLER_2D_ARRAY,
            GL30.GL_SAMPLER_1D_ARRAY_SHADOW, GL30.GL_SAMPLER_2D_ARRAY_SHADOW,
            GL30.GL_SAMPLER_CUBE_SHADOW, GL31.GL_SAMPLER_2D_RECT,
            GL31.GL_SAMPLER_2D_RECT_SHADOW, GL31.GL_SAMPLER_BUFFER,
            GL32.GL_SAMPLER_2D_MULTISAMPLE, GL32.GL_SAMPLER_2D_MULTISAMPLE_ARRAY,
            GL30.GL_INT_SAMPLER_1D, GL30.GL_INT_SAMPLER_2D, GL30.GL_INT_SAMPLER_3D,
            GL30.GL_INT_SAMPLER_CUBE, GL30.GL_INT_SAMPLER_1D_ARRAY,
            GL30.GL_INT_SAMPLER_2D_ARRAY, GL31.GL_INT_SAMPLER_2D_RECT,
            GL31.GL_INT_SAMPLER_BUFFER, GL32.GL_INT_SAMPLER_2D_MULTISAMPLE,
            GL32.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
            GL30.GL_UNSIGNED_INT_SAMPLER_1D, GL30.GL_UNSIGNED_INT_SAMPLER_2D,
            GL30.GL_UNSIGNED_INT_SAMPLER_3D, GL30.GL_UNSIGNED_INT_SAMPLER_CUBE,
            GL30.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY, GL30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY,
            GL31.GL_UNSIGNED_INT_SAMPLER_2D_RECT, GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER,
            GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE,
            GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY);

    /** Number of scalar components; 0 means "we do not handle this type". */
    private static int componentsOf(int type) {
        return switch (type) {
            case GL11.GL_FLOAT, GL11.GL_INT, GL11.GL_UNSIGNED_INT, GL20.GL_BOOL -> 1;
            case GL20.GL_FLOAT_VEC2, GL20.GL_INT_VEC2, GL30.GL_UNSIGNED_INT_VEC2,
                 GL20.GL_BOOL_VEC2 -> 2;
            case GL20.GL_FLOAT_VEC3, GL20.GL_INT_VEC3, GL30.GL_UNSIGNED_INT_VEC3,
                 GL20.GL_BOOL_VEC3 -> 3;
            case GL20.GL_FLOAT_VEC4, GL20.GL_INT_VEC4, GL30.GL_UNSIGNED_INT_VEC4,
                 GL20.GL_BOOL_VEC4 -> 4;
            case GL20.GL_FLOAT_MAT2 -> 4;
            case GL20.GL_FLOAT_MAT3 -> 9;
            case GL20.GL_FLOAT_MAT4 -> 16;
            case GL21.GL_FLOAT_MAT2x3, GL21.GL_FLOAT_MAT3x2 -> 6;
            case GL21.GL_FLOAT_MAT2x4, GL21.GL_FLOAT_MAT4x2 -> 8;
            case GL21.GL_FLOAT_MAT3x4, GL21.GL_FLOAT_MAT4x3 -> 12;
            default -> isSampler(type) ? 1 : 0;
        };
    }

    private GlUniforms() {}
}
