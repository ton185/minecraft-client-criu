package mccriu.core;

import org.lwjgl.opengl.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps a texture's sized internal format to a (format, type) pair that
 * round-trips it losslessly through {@code glGetTexImage} / {@code glTexImage*}.
 *
 * Getting this wrong does not throw — it silently returns wrong pixels, which is
 * the worst possible failure mode for this project. So the table is explicit and
 * anything not in it raises {@link UnsupportedFormatException} rather than
 * guessing at a "close enough" transfer format.
 */
public final class GlFormats {

    /** Transfer description for one sized internal format. */
    public record Transfer(int format, int type, int bytesPerPixel, String name) {}

    public static final class UnsupportedFormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int internalFormat;
        UnsupportedFormatException(int internalFormat) {
            super(String.format(
                    "no known lossless transfer format for internal format 0x%04X. "
                    + "Refusing to guess: reading it back with the wrong format/type would "
                    + "silently corrupt the texture. Add it to GlFormats.TABLE.",
                    internalFormat));
            this.internalFormat = internalFormat;
        }
    }

    private static final Map<Integer, Transfer> TABLE = new HashMap<>();

    private static void put(int internalFormat, int format, int type, int bpp, String name) {
        TABLE.put(internalFormat, new Transfer(format, type, bpp, name));
    }

    static {
        final int UB = GL11.GL_UNSIGNED_BYTE;
        final int US = GL11.GL_UNSIGNED_SHORT;
        final int B  = GL11.GL_BYTE;
        final int S  = GL11.GL_SHORT;
        final int F  = GL11.GL_FLOAT;
        final int UI = GL11.GL_UNSIGNED_INT;
        final int I  = GL11.GL_INT;

        final int RED = GL11.GL_RED, RG = GL30.GL_RG, RGB = GL11.GL_RGB, RGBA = GL11.GL_RGBA;
        final int RED_I = GL30.GL_RED_INTEGER, RG_I = GL30.GL_RG_INTEGER;
        final int RGB_I = GL30.GL_RGB_INTEGER, RGBA_I = GL30.GL_RGBA_INTEGER;

        // --- 8-bit normalised ---
        put(GL30.GL_R8, RED, UB, 1, "R8");
        put(GL31.GL_R8_SNORM, RED, B, 1, "R8_SNORM");
        put(GL30.GL_RG8, RG, UB, 2, "RG8");
        put(GL31.GL_RG8_SNORM, RG, B, 2, "RG8_SNORM");
        put(GL11.GL_RGB8, RGB, UB, 3, "RGB8");
        put(GL31.GL_RGB8_SNORM, RGB, B, 3, "RGB8_SNORM");
        put(GL11.GL_RGBA8, RGBA, UB, 4, "RGBA8");
        put(GL31.GL_RGBA8_SNORM, RGBA, B, 4, "RGBA8_SNORM");
        put(GL21.GL_SRGB8, RGB, UB, 3, "SRGB8");
        put(GL21.GL_SRGB8_ALPHA8, RGBA, UB, 4, "SRGB8_ALPHA8");

        // --- 16-bit normalised ---
        put(GL30.GL_R16, RED, US, 2, "R16");
        put(GL31.GL_R16_SNORM, RED, S, 2, "R16_SNORM");
        put(GL30.GL_RG16, RG, US, 4, "RG16");
        put(GL31.GL_RG16_SNORM, RG, S, 4, "RG16_SNORM");
        put(GL11.GL_RGB16, RGB, US, 6, "RGB16");
        put(GL31.GL_RGB16_SNORM, RGB, S, 6, "RGB16_SNORM");
        put(GL11.GL_RGBA16, RGBA, US, 8, "RGBA16");
        put(GL31.GL_RGBA16_SNORM, RGBA, S, 8, "RGBA16_SNORM");

        // --- packed ---
        // Read back at full width rather than in their packed type: the packed
        // types are lossy to re-pack by hand and the driver does the conversion
        // for us correctly in both directions.
        put(GL11.GL_RGB10_A2, RGBA, UI, 4, "RGB10_A2");          // via GL_UNSIGNED_INT_2_10_10_10_REV below
        put(GL33.GL_RGB10_A2UI, RGBA_I, UI, 4, "RGB10_A2UI");
        put(GL30.GL_R11F_G11F_B10F, RGB, F, 12, "R11F_G11F_B10F");
        put(GL30.GL_RGB9_E5, RGB, F, 12, "RGB9_E5");
        put(GL11.GL_RGB5_A1, RGBA, UB, 4, "RGB5_A1");
        put(GL11.GL_RGBA4, RGBA, UB, 4, "RGBA4");
        put(GL41.GL_RGB565, RGB, UB, 3, "RGB565");

        // --- float ---
        put(GL30.GL_R16F, RED, F, 4, "R16F");
        put(GL30.GL_RG16F, RG, F, 8, "RG16F");
        put(GL30.GL_RGB16F, RGB, F, 12, "RGB16F");
        put(GL30.GL_RGBA16F, RGBA, F, 16, "RGBA16F");
        put(GL30.GL_R32F, RED, F, 4, "R32F");
        put(GL30.GL_RG32F, RG, F, 8, "RG32F");
        put(GL30.GL_RGB32F, RGB, F, 12, "RGB32F");
        put(GL30.GL_RGBA32F, RGBA, F, 16, "RGBA32F");

        // --- integer ---
        put(GL30.GL_R8UI, RED_I, UI, 4, "R8UI");
        put(GL30.GL_R8I, RED_I, I, 4, "R8I");
        put(GL30.GL_R16UI, RED_I, UI, 4, "R16UI");
        put(GL30.GL_R16I, RED_I, I, 4, "R16I");
        put(GL30.GL_R32UI, RED_I, UI, 4, "R32UI");
        put(GL30.GL_R32I, RED_I, I, 4, "R32I");
        put(GL30.GL_RG8UI, RG_I, UI, 8, "RG8UI");
        put(GL30.GL_RG8I, RG_I, I, 8, "RG8I");
        put(GL30.GL_RG16UI, RG_I, UI, 8, "RG16UI");
        put(GL30.GL_RG16I, RG_I, I, 8, "RG16I");
        put(GL30.GL_RG32UI, RG_I, UI, 8, "RG32UI");
        put(GL30.GL_RG32I, RG_I, I, 8, "RG32I");
        put(GL30.GL_RGB8UI, RGB_I, UI, 12, "RGB8UI");
        put(GL30.GL_RGB8I, RGB_I, I, 12, "RGB8I");
        put(GL30.GL_RGB16UI, RGB_I, UI, 12, "RGB16UI");
        put(GL30.GL_RGB16I, RGB_I, I, 12, "RGB16I");
        put(GL30.GL_RGB32UI, RGB_I, UI, 12, "RGB32UI");
        put(GL30.GL_RGB32I, RGB_I, I, 12, "RGB32I");
        put(GL30.GL_RGBA8UI, RGBA_I, UI, 16, "RGBA8UI");
        put(GL30.GL_RGBA8I, RGBA_I, I, 16, "RGBA8I");
        put(GL30.GL_RGBA16UI, RGBA_I, UI, 16, "RGBA16UI");
        put(GL30.GL_RGBA16I, RGBA_I, I, 16, "RGBA16I");
        put(GL30.GL_RGBA32UI, RGBA_I, UI, 16, "RGBA32UI");
        put(GL30.GL_RGBA32I, RGBA_I, I, 16, "RGBA32I");

        // --- depth / stencil ---
        put(GL14.GL_DEPTH_COMPONENT16, GL11.GL_DEPTH_COMPONENT, UI, 4, "DEPTH_COMPONENT16");
        put(GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, UI, 4, "DEPTH_COMPONENT24");
        put(GL14.GL_DEPTH_COMPONENT32, GL11.GL_DEPTH_COMPONENT, UI, 4, "DEPTH_COMPONENT32");
        put(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, F, 4, "DEPTH_COMPONENT32F");
        put(GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH_STENCIL,
                GL30.GL_UNSIGNED_INT_24_8, 4, "DEPTH24_STENCIL8");
        put(GL30.GL_DEPTH32F_STENCIL8, GL30.GL_DEPTH_STENCIL,
                GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV, 8, "DEPTH32F_STENCIL8");
        put(GL30.GL_STENCIL_INDEX8, GL11.GL_STENCIL_INDEX, UB, 1, "STENCIL_INDEX8");

        // --- unsized legacy aliases: drivers report these for textures created
        // with an unsized internal format. Minecraft does this in a few places. ---
        put(GL11.GL_RGBA, RGBA, UB, 4, "RGBA(unsized)");
        put(GL11.GL_RGB, RGB, UB, 3, "RGB(unsized)");
        put(GL11.GL_RED, RED, UB, 1, "RED(unsized)");
        put(GL30.GL_RG, RG, UB, 2, "RG(unsized)");
        put(GL11.GL_LUMINANCE, GL11.GL_LUMINANCE, UB, 1, "LUMINANCE");
        put(GL11.GL_LUMINANCE_ALPHA, GL11.GL_LUMINANCE_ALPHA, UB, 2, "LUMINANCE_ALPHA");
        put(GL11.GL_ALPHA, GL11.GL_ALPHA, UB, 1, "ALPHA");
        put(GL11.GL_INTENSITY, GL11.GL_INTENSITY, UB, 1, "INTENSITY");
        put(GL11.GL_DEPTH_COMPONENT, GL11.GL_DEPTH_COMPONENT, UI, 4, "DEPTH_COMPONENT(unsized)");
        put(GL30.GL_DEPTH_STENCIL, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, 4,
                "DEPTH_STENCIL(unsized)");
    }

    /** RGB10_A2 needs its packed type; the generic table entry above is a placeholder. */
    static {
        put(GL11.GL_RGB10_A2, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_2_10_10_10_REV, 4, "RGB10_A2");
        put(GL33.GL_RGB10_A2UI, GL30.GL_RGBA_INTEGER, GL12.GL_UNSIGNED_INT_2_10_10_10_REV, 4,
                "RGB10_A2UI");
        put(GL11.GL_RGB5_A1, GL11.GL_RGBA, GL12.GL_UNSIGNED_SHORT_5_5_5_1, 2, "RGB5_A1");
        put(GL11.GL_RGBA4, GL11.GL_RGBA, GL12.GL_UNSIGNED_SHORT_4_4_4_4, 2, "RGBA4");
        put(GL41.GL_RGB565, GL11.GL_RGB, GL12.GL_UNSIGNED_SHORT_5_6_5, 2, "RGB565");
        put(GL30.GL_R11F_G11F_B10F, GL11.GL_RGB, GL30.GL_UNSIGNED_INT_10F_11F_11F_REV, 4,
                "R11F_G11F_B10F");
        put(GL30.GL_RGB9_E5, GL11.GL_RGB, GL30.GL_UNSIGNED_INT_5_9_9_9_REV, 4, "RGB9_E5");
    }

    public static Transfer transferFor(int internalFormat) {
        Transfer t = TABLE.get(internalFormat);
        if (t == null) throw new UnsupportedFormatException(internalFormat);
        return t;
    }

    public static boolean isKnown(int internalFormat) {
        return TABLE.containsKey(internalFormat);
    }

    /** Human-readable name, for reports and error messages. */
    public static String nameOf(int internalFormat) {
        Transfer t = TABLE.get(internalFormat);
        return t != null ? t.name() : String.format("0x%04X", internalFormat);
    }

    /**
     * Byte size of one level, honouring a 1-byte pack alignment. The snapshot
     * always sets {@code GL_PACK_ALIGNMENT}/{@code GL_UNPACK_ALIGNMENT} to 1 so
     * rows are tightly packed and this arithmetic is exact.
     */
    public static long levelBytes(int internalFormat, int w, int h, int d) {
        Transfer t = transferFor(internalFormat);
        return (long) Math.max(w, 1) * Math.max(h, 1) * Math.max(d, 1) * t.bytesPerPixel();
    }

    private GlFormats() {}
}
