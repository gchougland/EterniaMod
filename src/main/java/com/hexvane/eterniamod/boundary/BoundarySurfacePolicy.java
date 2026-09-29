package com.hexvane.eterniamod.boundary;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Locale;

/** Identifies protected infrastructure so cosmetic borders can also follow paved ground. */
@FunctionalInterface
public interface BoundarySurfacePolicy {
    boolean isExcluded(World world, int x, int z);

    default boolean isGround(World world, int x, int y, int z, BlockType block) {
        return block!=null && block.isCubeDrawType() && (isNaturalGround(block.getId()) || isExcluded(world,x,z));
    }

    /** Conservative default excludes roofs, furniture, leaves and crafted masonry. */
    static boolean isNaturalGround(String id) {
        if (id == null) return false;
        String value = id.toLowerCase(Locale.ROOT);
        if (value.contains("roof") || value.contains("brick") || value.contains("cobble") || value.contains("polished")
            || value.contains("smooth") || value.contains("plank") || value.contains("tile") || value.contains("stair")
            || value.contains("slab") || value.contains("rubble") || value.contains("gravel")) return false;
        return value.startsWith("soil_") || value.startsWith("grass_") || value.startsWith("sand_")
            || value.startsWith("rock_") || value.startsWith("snow_") || value.startsWith("ice_");
    }
}
