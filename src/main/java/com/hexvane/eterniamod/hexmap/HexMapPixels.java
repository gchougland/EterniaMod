package com.hexvane.eterniamod.hexmap;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Unpacks and repacks the palette based {@link MapImage} world map tiles (colours are {@code 0xRRGGBBAA}). Ported from
 * Aetherhaven's {@code TownMapImagePixels}, which has drawn town borders on 0.7.x maps.
 */
public final class HexMapPixels {
    /** Blocks along one side of a map tile (one chunk). */
    public static final int TILE_BLOCKS = 32;

    private HexMapPixels() {}

    public static boolean hasPixelData(@Nullable MapImage image) {
        return image != null && image.width > 0 && image.height > 0 && image.palette != null && image.palette.length > 0
            && image.packedIndices != null && image.packedIndices.length > 0 && Byte.toUnsignedInt(image.bitsPerIndex) > 0;
    }

    @Nullable
    public static MapImage cloneImage(@Nullable MapImage source) {
        if (!hasPixelData(source)) return null;
        return new MapImage(source.width, source.height, source.palette.clone(), source.bitsPerIndex, source.packedIndices.clone());
    }

    /** Pixels as {@code 0xRRGGBBAA}, row by row (north to south, west to east). */
    @Nullable
    public static int[] unpack(@Nullable MapImage image) {
        if (!hasPixelData(image)) return null;
        int count = image.width * image.height;
        BitFieldArr indices = new BitFieldArr(Byte.toUnsignedInt(image.bitsPerIndex), count);
        indices.set(image.packedIndices);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int p = indices.get(i);
            out[i] = p >= 0 && p < image.palette.length ? image.palette[p] : 0;
        }
        return out;
    }

    /** Packs {@code 0xRRGGBBAA} pixels into a palette tile with the smallest index width the client accepts. */
    public static MapImage pack(int width, int height, int[] pixels) {
        Map<Integer, Integer> index = new LinkedHashMap<>();
        for (int pixel : pixels) index.putIfAbsent(pixel, index.size());
        int[] palette = new int[index.size()];
        index.forEach((color, i) -> palette[i] = color);
        int bits = bitsFor(palette.length);
        BitFieldArr indices = new BitFieldArr(bits, width * height);
        for (int i = 0; i < pixels.length; i++) indices.set(i, index.get(pixels[i]));
        return new MapImage(width, height, palette, (byte) bits, indices.get());
    }

    static int bitsFor(int colors) {
        if (colors <= 16) return 4;
        if (colors <= 256) return 8;
        if (colors <= 4096) return 12;
        return 16;
    }
}
