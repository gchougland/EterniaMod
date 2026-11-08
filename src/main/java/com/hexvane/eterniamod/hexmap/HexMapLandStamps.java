package com.hexvane.eterniamod.hexmap;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandEvents;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.housing.PlotRect;
import java.util.HashMap;
import java.util.Map;

/**
 * Which map tiles of one world changed: a stamp per tile that moves whenever a {@link HexLandEvents} change touches
 * the tile (the changed cells plus a two block margin for the border shadow). A painted tile is reused while its
 * stamp is the same, so a claim repaints only the few tiles under it, for every viewer. A lattice change (or an
 * event without cells) moves every stamp. Thread safe: events arrive on any thread, stamps are read on the world thread.
 */
public final class HexMapLandStamps {
    private static final int MARGIN = 2;
    private int worldGeneration;
    private final Map<Long, Integer> tiles = new HashMap<>();
    private long changes;

    /** Applies one land change. {@code lattice} is the world's lattice (null: treat as whole world). */
    public synchronized void apply(HexLandEvents.Change change, HexLattice lattice) {
        changes++;
        if (change.wholeWorld() || lattice == null) {
            worldGeneration++;
            tiles.clear();
            return;
        }
        for (HexCoord cell : change.cells()) {
            PlotRect r = lattice.bounds(cell);
            int minX = Math.floorDiv(r.x() - MARGIN, HexMapPixels.TILE_BLOCKS), maxX = Math.floorDiv(r.endX() + MARGIN, HexMapPixels.TILE_BLOCKS);
            int minZ = Math.floorDiv(r.z() - MARGIN, HexMapPixels.TILE_BLOCKS), maxZ = Math.floorDiv(r.endZ() + MARGIN, HexMapPixels.TILE_BLOCKS);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) tiles.merge(key(x, z), 1, Integer::sum);
        }
    }

    /** The stamp of a tile; equal stamps mean nothing drawn on the tile changed. */
    public synchronized long stamp(int chunkX, int chunkZ) {
        return ((long) worldGeneration << 32) | (tiles.getOrDefault(key(chunkX, chunkZ), 0) & 0xffffffffL);
    }

    /** Changes applied so far (cheap "anything changed?" check). */
    public synchronized long changes() { return changes; }

    static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
