package com.hexvane.eterniamod.pathtool;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Half block steps on roads: where the road rises one block from a cell to its neighbour, the lower cell gets a half
 * block of its own paving on top, so walking up the road is smooth. Pure, except {@link #available}.
 */
final class RoadSlabs {
    private RoadSlabs() {}
    static final String SUFFIX = "_Half";

    /** The half block of a paving block, by the game's naming: Rock_Stone_Cobble to Rock_Stone_Cobble_Half. */
    static String half(String block) { return block + SUFFIX; }

    /** True when every paving block has a half block, so a style can use slab steps. Needs the block assets loaded. */
    static boolean available(Collection<String> blocks) {
        if (blocks.isEmpty()) return false;
        for (String b : blocks) if (BlockType.getAssetMap().getIndex(half(b)) < 0) return false;
        return true;
    }

    /** The half blocks to place: one above each road cell whose neighbour along the road is exactly one block higher. */
    static Map<SplineGeometry.Cell, String> steps(List<SplineGeometry.Cell> ground, Map<SplineGeometry.Column, String> materials) {
        Map<SplineGeometry.Column, Integer> floor = new HashMap<>();
        for (var c : ground) floor.put(c.column(), c.y());
        Map<SplineGeometry.Cell, String> out = new HashMap<>();
        for (var c : ground) {
            String material = materials.get(c.column());
            if (material == null || material.isEmpty()) continue;
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Integer up = floor.get(new SplineGeometry.Column(c.x() + d[0], c.z() + d[1]));
                if (up != null && up == c.y() + 1) { out.put(new SplineGeometry.Cell(c.x(), c.y() + 1, c.z()), half(material)); break; }
            }
        }
        return out;
    }
}
