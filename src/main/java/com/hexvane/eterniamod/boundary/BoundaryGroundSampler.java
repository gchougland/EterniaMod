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
        double[] grounds = new double[4];
        int i = 0;
        // Include the full horizontal sprite envelope, not just the emitter's central column.
        for (double dx : new double[] {-BoundaryFogSystem.SPRITE_ENVELOPE_RADIUS, BoundaryFogSystem.SPRITE_ENVELOPE_RADIUS}) {
            for (double dz : new double[] {-BoundaryFogSystem.SPRITE_ENVELOPE_RADIUS, BoundaryFogSystem.SPRITE_ENVELOPE_RADIUS}) {
                int x = (int) Math.floor(sample.x() + dx);
                int z = (int) Math.floor(sample.z() + dz);
                // This is a visual marker, not a placed block. Roads must not erase a plot side.
                if (!ChunkSectionBlockUtil.isChunkInMemory(world, x, z)) return Double.NaN;
                grounds[i++] = surface(world, x, z, sample.groundY());
            }
        }
        return combine(grounds);
    }

    /**
     * Where the glow sits, given the ground found in each column under it (NaN where none: a fence, a wall, a house
     * corner). Columns without ground are skipped, so a fence on one side does not hide the line. A one block step
     * (a levelled plot beside natural land) glows along its top edge; only a taller cliff is left out.
     */
    static double combine(double[] grounds) {
        double lowest = Double.POSITIVE_INFINITY, highest = Double.NEGATIVE_INFINITY;
        for (double ground : grounds) {
            if (!Double.isFinite(ground)) continue;
            lowest = Math.min(lowest, ground);
            highest = Math.max(highest, ground);
        }
        if (!Double.isFinite(highest)) return Double.NaN;
        return highest - lowest > 1.0 ? Double.NaN : highest;
    }

    private double surface(World world, int x, int z, int baseline) {
        // Search around claim terrain elevation, not a roof/canopy heightmap or the viewer's flying elevation.
        for (int distance = 0; distance <= SEARCH_RADIUS; distance++) {
            for (int direction : new int[] {-1, 1}) {
                if (distance == 0 && direction == 1) continue;
                int y = baseline + distance * direction;
                // No section above the top of the world, so empty() already turns such a spot down.
                if (y < ChunkUtil.MIN_Y) continue;
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
