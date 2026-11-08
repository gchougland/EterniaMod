package com.hexvane.eterniamod.claimmap;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * World map tiles for the claim map: what is already in memory, and a way to ask for the rest. Tiles are the same
 * pictures the world map shows (one per chunk, palette packed); this turns them into plain ARGB pixels. Approach
 * ported from Gibson's Aetherhaven ({@code TownMapImagePixels}), proven on 0.7.x.
 */
public final class ClaimMapTiles {
    private ClaimMapTiles() {}

    /** Chunk range under a map window, inclusive: {x0, z0, x1, z1}. */
    public static int[] chunkRange(ClaimMapGeometry g) {
        int b = ClaimMapRenderer.TILE_BLOCKS;
        return new int[] {
            Math.floorDiv((int) Math.floor(g.worldX0()), b), Math.floorDiv((int) Math.floor(g.worldZ0()), b),
            Math.floorDiv((int) Math.ceil(g.worldX0() + g.worldWidth()) - 1, b), Math.floorDiv((int) Math.ceil(g.worldZ0() + g.worldHeight()) - 1, b)};
    }

    /** Key of a chunk in {@link #inMemory}. */
    public static long key(int chunkX, int chunkZ) { return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL); }

    /**
     * The tiles under the window that the world map already holds, decoded, plus the chunks it still has to draw.
     * Call on the world thread (cheap map lookups); decoding is quick but can also run elsewhere.
     */
    public record Snapshot(Map<Long, MapImage> images, List<long[]> missing) {
        public ClaimMapRenderer.TileSource source() {
            Map<Long, ClaimMapRenderer.Tile> decoded = new HashMap<>();
            return (x, z) -> decoded.computeIfAbsent(key(x, z), k -> decode(images.get(k)));
        }
    }

    public static Snapshot inMemory(WorldMapManager maps, ClaimMapGeometry g) {
        int[] r = chunkRange(g);
        Map<Long, MapImage> images = new HashMap<>();
        List<long[]> missing = new ArrayList<>();
        if (maps == null || !maps.isWorldMapEnabled()) return new Snapshot(images, missing);
        for (int z = r[1]; z <= r[3]; z++)
            for (int x = r[0]; x <= r[2]; x++) {
                MapImage image = maps.getImageIfInMemory(x, z);
                if (image != null) images.put(key(x, z), image);
                else missing.add(new long[] {x, z});
            }
        return new Snapshot(images, missing);
    }

    /** Asks the world map to draw the missing chunks; completes when all have answered (failures are ignored). */
    public static CompletableFuture<Void> request(WorldMapManager maps, List<long[]> missing) {
        if (maps == null || missing.isEmpty()) return CompletableFuture.completedFuture(null);
        List<CompletableFuture<?>> all = new ArrayList<>(missing.size());
        for (long[] c : missing) all.add(maps.getImageAsync((int) c[0], (int) c[1]).handle((image, error) -> null));
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new));
    }

    /** ARGB pixels of a world map tile, or null when it has none. */
    public static ClaimMapRenderer.Tile decode(MapImage image) {
        if (image == null || image.width <= 0 || image.height <= 0 || image.palette == null || image.palette.length == 0
            || image.packedIndices == null || image.packedIndices.length == 0 || Byte.toUnsignedInt(image.bitsPerIndex) == 0) return null;
        int count = image.width * image.height;
        BitFieldArr indices = new BitFieldArr(Byte.toUnsignedInt(image.bitsPerIndex), count);
        indices.set(image.packedIndices);
        int[] palette = image.palette, out = new int[count];
        for (int i = 0; i < count; i++) {
            int index = indices.get(i);
            out[i] = index >= 0 && index < palette.length ? toArgb(palette[index]) : 0;
        }
        return new ClaimMapRenderer.Tile(image.width, image.height, out);
    }

    /** Map palette colours are RGBA (red in the top byte); Java wants ARGB. A zero alpha means opaque here. */
    static int toArgb(int rgba) {
        int a = rgba & 0xFF, b = (rgba >> 8) & 0xFF, g = (rgba >> 16) & 0xFF, r = (rgba >>> 24) & 0xFF;
        if (a == 0) a = 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
