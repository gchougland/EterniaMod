package com.hexvane.eterniamod.prefab;

import com.hexvane.eterniamod.hub.ReplacedBlockCell;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class WorldBlockSnapshotUtil {
    private static final int RESTORE_SETTINGS = SetBlockSettings.NO_UPDATE_STATE;
    private static final int CLEAR_SETTINGS = SetBlockSettings.NO_SEND_PARTICLES | SetBlockSettings.NO_DROP_ITEMS;

    private WorldBlockSnapshotUtil() {}

    @Nullable
    public static ReplacedBlockCell captureIfNonEmpty(@Nonnull World world, int x, int y, int z) {
        if (ChunkSectionBlockUtil.sectionRefAt(world, x, y, z) == null) {
            return null;
        }
        BlockType blockType = ChunkSectionBlockUtil.blockType(world, x, y, z);
        if (blockType == null || blockType == BlockType.EMPTY || blockType.getId() == null) {
            return null;
        }
        return new ReplacedBlockCell(x, y, z, blockType.getId(), readRotationIndex(world, x, y, z));
    }

    public static void restoreCell(@Nonnull World world, @Nonnull ReplacedBlockCell cell) {
        if (ChunkSectionBlockUtil.sectionRefAt(world, cell.getX(), cell.getY(), cell.getZ()) == null) {
            return;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(cell.getBlockId());
        if (blockType == null || blockType == BlockType.EMPTY) {
            clearCell(world, cell.getX(), cell.getY(), cell.getZ());
            return;
        }
        int index = BlockType.getAssetMap().getIndex(cell.getBlockId());
        ChunkSectionBlockUtil.setBlock(
            world,
            cell.getX(),
            cell.getY(),
            cell.getZ(),
            index,
            blockType,
            cell.getRotationIndex(),
            FillerBlockUtil.NO_FILLER,
            RESTORE_SETTINGS
        );
    }

    public static void clearCell(@Nonnull World world, int x, int y, int z) {
        forceClearCell(world, x, y, z);
    }

    /** Clears a prefab-placed block, resolving multi-block furniture to its primary cell first. */
    public static void clearPrefabBlockCell(@Nonnull World world, int x, int y, int z) {
        Vector3i primary = resolvePrimaryBlock(world, x, y, z);
        forceClearCell(world, primary.x, primary.y, primary.z);
    }

    @Nonnull
    public static Vector3i resolvePrimaryBlock(@Nonnull World world, int x, int y, int z) {
        if (ChunkSectionBlockUtil.sectionRefAt(world, x, y, z) == null) {
            return new Vector3i(x, y, z);
        }
        int filler = ChunkSectionBlockUtil.filler(world, x, y, z);
        if (filler == FillerBlockUtil.NO_FILLER) {
            return new Vector3i(x, y, z);
        }
        return new Vector3i(
            x - FillerBlockUtil.unpackX(filler),
            y - FillerBlockUtil.unpackY(filler),
            z - FillerBlockUtil.unpackZ(filler)
        );
    }

    private static void forceClearCell(@Nonnull World world, int x, int y, int z) {
        if (ChunkSectionBlockUtil.sectionRefAt(world, x, y, z) == null) {
            return;
        }
        Ref<ChunkStore> blockEntityRef = ChunkSectionBlockUtil.blockEntityRefAt(world, x, y, z);
        ChunkSectionBlockUtil.setBlockEmpty(world, x, y, z, CLEAR_SETTINGS);
        if (blockEntityRef != null && blockEntityRef.isValid()) {
            world.getChunkStore().getStore().removeEntity(blockEntityRef, RemoveReason.REMOVE);
        }
    }

    public static boolean isEmptyBlock(@Nonnull World world, int x, int y, int z) {
        BlockType blockType = ChunkSectionBlockUtil.blockType(world, x, y, z);
        return blockType == null || blockType == BlockType.EMPTY || blockType.getId() == null;
    }

    public static int readRotationIndex(@Nonnull World world, int x, int y, int z) {
        return ChunkSectionBlockUtil.rotationIndex(world, x, y, z);
    }
}
