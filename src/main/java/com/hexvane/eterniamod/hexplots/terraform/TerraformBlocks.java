package com.hexvane.eterniamod.hexplots.terraform;

import com.hexvane.eterniamod.boundary.BoundarySurfacePolicy;
import java.util.Locale;

/**
 * Which blocks plot shaping may touch, by block id. Pure. Anything not listed here (planks, bricks, furniture, planters,
 * paths, fences, lamps, anything a player built) makes its whole column off limits. Crops count as wild growth here;
 * the terraformer keeps any crop growing on tilled soil.
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
        String lower = base(id);
        if (potted(lower)) return false;
        if (lower.startsWith("plant_")) return true;
        if (lower.startsWith("wood_")) return lower.endsWith("_trunk") || lower.endsWith("_trunk_full") || lower.contains("_roots") || lower.contains("_branch");
        return lower.startsWith("rubble_");
    }

    /**
     * Growth cleared from claimed hexagons: plants, flowers, bushes, leaves, tree trunks, roots and branches, loose
     * rubble, wild crops such as wheat and berry bushes. Never planters or pots.
     */
    public static boolean isGrowth(String id) {
        if (id == null || id.isEmpty()) return false;
        String lower = base(id);
        if (potted(lower)) return false;
        if (lower.startsWith("plant_") || lower.contains("leaves")) return true;
        if (lower.startsWith("wood_")) return lower.endsWith("_trunk") || lower.endsWith("_trunk_full") || lower.contains("_roots") || lower.contains("_branch");
        return lower.startsWith("rubble_");
    }

    /** A crop: kept when it grows on tilled soil, cleared as wild growth otherwise. */
    public static boolean isCrop(String id) { return id != null && base(id).startsWith("plant_crop"); }

    /** Farmed soil. */
    public static boolean isTilled(String id) { return id != null && base(id).contains("tilled"); }

    /** Tree wood: trunks, roots and branches. Connected wood outside a plot goes with a tree cleared inside it. */
    public static boolean isTreeWood(String id) {
        if (id == null) return false;
        String lower = base(id);
        return lower.startsWith("wood_") && (lower.endsWith("_trunk") || lower.endsWith("_trunk_full") || lower.contains("_roots") || lower.contains("_branch"));
    }

    /** Leaves of any tree. */
    public static boolean isLeaves(String id) { return id != null && base(id).contains("leaves") && !potted(base(id)); }

    /**
     * The block's own name in lower case. A block in one of its states (a grown crop, a berry bush with berries) is
     * named like "*Plant_Crop_Wheat_Block_State_Definitions_Stage4": the leading star and the state part are dropped.
     */
    static String base(String id) {
        if (id == null) return "";
        String s = id.startsWith("*") ? id.substring(1) : id;
        int state = s.indexOf("_State_Definitions");
        if (state > 0) s = s.substring(0, state);
        return s.toLowerCase(Locale.ROOT);
    }

    private static boolean potted(String lower) {
        if (lower.contains("planter")) return true;
        for (String word : lower.split("_")) if (word.equals("pot") || word.equals("potted") || word.equals("flowerpot")) return true;
        return false;
    }

    /** Grass blades are the one growth that stays on claimed land. */
    public static boolean isKeptGrass(String id) { return id != null && base(id).startsWith("plant_grass"); }

    /** A top block worth keeping on shaped ground: grass, dirt, sand or snow (not bare rock). */
    public static boolean isTopsoil(String id) {
        if (!isGround(id)) return false;
        String lower = base(id);
        return lower.startsWith("soil_") || lower.startsWith("grass_") || lower.startsWith("sand") || lower.startsWith("snow");
    }

    /** What goes under a top block: dirt under grass, the same sand under sand, dirt otherwise. */
    public static String subsoilFor(String top) {
        String lower = top == null ? "" : top.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sand")) return top;
        return "Soil_Dirt";
    }
}
