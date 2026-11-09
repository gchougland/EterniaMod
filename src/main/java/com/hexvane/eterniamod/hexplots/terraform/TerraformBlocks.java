package com.hexvane.eterniamod.hexplots.terraform;

import com.hexvane.eterniamod.boundary.BoundarySurfacePolicy;
import java.util.Locale;

/**
 * Which blocks plot shaping may touch, by block id. Pure. Anything not listed here (planks, bricks, furniture, crops,
 * paths, fences, lamps, anything a player built) makes its whole column off limits.
 */
public final class TerraformBlocks {
    private TerraformBlocks() {}

    /** Natural ground: soil, grass, sand, rock, snow, ice and ore, never crafted masonry. */
    public static boolean isGround(String id) {
        if (id == null || id.isEmpty()) return false;
        return BoundarySurfacePolicy.isNaturalGround(id) || id.startsWith("Ore_");
    }

    /** Ground, or natural growth that may be cleared or buried: plants, leaves, tree trunks and roots, loose rocks. */
    public static boolean isNatural(String id) {
        if (isGround(id)) return true;
        if (id == null || id.isEmpty()) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("plant_crop") || lower.contains("planter") || lower.contains("pot")) return false;
        if (lower.startsWith("plant_")) return true;
        if (lower.startsWith("wood_")) return lower.endsWith("_trunk") || lower.endsWith("_trunk_full") || lower.contains("_roots") || lower.contains("_branch");
        return lower.startsWith("rubble_");
    }

    /**
     * Growth cleared from claimed hexagons: plants, flowers, bushes, leaves, tree trunks, roots and branches, loose
     * rubble. Never crops or planters.
     */
    public static boolean isGrowth(String id) {
        if (id == null || id.isEmpty()) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("plant_crop") || lower.contains("planter") || lower.contains("pot")) return false;
        if (lower.startsWith("plant_") || lower.contains("leaves")) return true;
        if (lower.startsWith("wood_")) return lower.endsWith("_trunk") || lower.endsWith("_trunk_full") || lower.contains("_roots") || lower.contains("_branch");
        return lower.startsWith("rubble_");
    }

    /** Grass blades are the one growth that stays on claimed land. */
    public static boolean isKeptGrass(String id) { return id != null && id.toLowerCase(Locale.ROOT).startsWith("plant_grass"); }

    /** A top block worth keeping on shaped ground: grass, dirt, sand or snow (not bare rock). */
    public static boolean isTopsoil(String id) {
        if (!isGround(id)) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.startsWith("soil_") || lower.startsWith("grass_") || lower.startsWith("sand") || lower.startsWith("snow");
    }

    /** What goes under a top block: dirt under grass, the same sand under sand, dirt otherwise. */
    public static String subsoilFor(String top) {
        String lower = top == null ? "" : top.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sand")) return top;
        return "Soil_Dirt";
    }
}
