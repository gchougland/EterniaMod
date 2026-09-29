package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;

/** Reads loaded terrain only; never promotes/loads chunks for decorative effects. */
final class BoundaryGroundSampler {
    private static final int SEARCH_RADIUS = 12;
    private final BoundarySurfacePolicy policy;

    BoundaryGroundSampler(BoundarySurfacePolicy policy) { this.policy = policy; }

    double sample(World world, BoundaryGeometry.Sample sample) {
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        // Include the full horizontal sprite envelope, not just the emitter's central column.
        for (double dx : new double[] {-BoundaryFogSystem.SPRITE_ENVELOPE_RADIUS, BoundaryFogSystem.SPRITE_ENVELOPE_RADIUS}) {
            for (double dz : new double[] {-BoundaryFogSystem.SPRITE_ENVELOPE_RADIUS, BoundaryFogSystem.SPRITE_ENVELOPE_RADIUS}) {
                int x = (int) Math.floor(sample.x() + dx);
                int z = (int) Math.floor(sample.z() + dz);
                // This is a visual marker, not a placed block. Roads must not erase a plot side.
                if (!ChunkSectionBlockUtil.isChunkInMemory(world, x, z)) return Double.NaN;
                double ground = surface(world, x, z, sample.groundY());
                if (!Double.isFinite(ground)) return Double.NaN;
                lowest = Math.min(lowest, ground);
                highest = Math.max(highest, ground);
            }
        }
        // Omit a wisp across a cliff/step rather than exceed its height relative to the lower side.
        return highest - lowest > .5 ? Double.NaN : lowest;
    }

    private double surface(World world, int x, int z, int baseline) {
        // Search around claim terrain elevation, not a roof/canopy heightmap or the viewer's flying elevation.
        for (int distance = 0; distance <= SEARCH_RADIUS; distance++) {
            for (int direction : new int[] {-1, 1}) {
                if (distance == 0 && direction == 1) continue;
                int y = baseline + distance * direction;
                if (y < ChunkUtil.MIN_Y || y + 2 >= ChunkUtil.HEIGHT) continue;
                BlockType block = ChunkSectionBlockUtil.blockType(world, x, y, z);
                if (block == null || !policy.isGround(world, x, y, z, block)) continue;
                if (empty(world, x, y + 1, z) && empty(world, x, y + 2, z)) return y + 1.0;
            }
        }
        return Double.NaN;
    }

    private boolean empty(World world, int x, int y, int z) {
        if (ChunkSectionBlockUtil.sectionRefAt(world, x, y, z) == null) return false;
        BlockType block = ChunkSectionBlockUtil.blockType(world, x, y, z);
        return block == null || block == BlockType.EMPTY || block.getMaterial() == BlockMaterial.Empty;
    }
}
