package com.hexvane.eterniamod.prefab;

import com.hexvane.eterniamod.hub.ReplacedBlockCell;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class WorldBlockSnapshotUtil {
    private static final int RESTORE_SETTINGS = 2;
    private static final int CLEAR_SETTINGS = SetBlockSettings.NO_SEND_PARTICLES | SetBlockSettings.NO_DROP_ITEMS;

    private WorldBlockSnapshotUtil() {}

    @Nullable
    public static ReplacedBlockCell captureIfNonEmpty(@Nonnull World world, int x, int y, int z) {
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            return null;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(chunk.getBlock(x, y, z));
        if (blockType == null || blockType == BlockType.EMPTY || blockType.getId() == null) {
            return null;
        }
        return new ReplacedBlockCell(x, y, z, blockType.getId(), readRotationIndex(world, x, y, z));
    }

    public static void restoreCell(@Nonnull World world, @Nonnull ReplacedBlockCell cell) {
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(cell.getX(), cell.getZ()));
        if (chunk == null) {
            return;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(cell.getBlockId());
        if (blockType == null || blockType == BlockType.EMPTY) {
            clearCell(world, cell.getX(), cell.getY(), cell.getZ());
            return;
        }
        int index = BlockType.getAssetMap().getIndex(cell.getBlockId());
        chunk.setBlock(cell.getX(), cell.getY(), cell.getZ(), index, blockType, cell.getRotationIndex(), 0, RESTORE_SETTINGS);
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
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            return new Vector3i(x, y, z);
        }
        int filler = chunk.getFiller(x, y, z);
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
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            return;
        }
        Ref<ChunkStore> blockEntityRef = chunk.getBlockComponentEntity(x, y, z);
        chunk.setBlock(x, y, z, BlockType.EMPTY_ID, BlockType.EMPTY, 0, 0, CLEAR_SETTINGS);
        if (blockEntityRef != null && blockEntityRef.isValid()) {
            world.getChunkStore().getStore().removeEntity(blockEntityRef, RemoveReason.REMOVE);
        }
    }

    public static boolean isEmptyBlock(@Nonnull WorldChunk chunk, int x, int y, int z) {
        BlockType blockType = BlockType.getAssetMap().getAsset(chunk.getBlock(x, y, z));
        return blockType == null || blockType == BlockType.EMPTY || blockType.getId() == null;
    }

    public static int readRotationIndex(@Nonnull World world, int x, int y, int z) {
        if (y < ChunkUtil.MIN_Y || y > ChunkUtil.HEIGHT_MINUS_1) {
            return 0;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (sectionRef == null || !sectionRef.isValid()) {
            return 0;
        }
        Store<ChunkStore> store = sectionRef.getStore();
        BlockSection section = store.getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) {
            return 0;
        }
        RotationTuple current = section.getRotation(x, y, z);
        for (int i = 0; i < 256; i++) {
            RotationTuple tuple = RotationTuple.get(i);
            if (tuple.yaw() == current.yaw() && tuple.pitch() == current.pitch() && tuple.roll() == current.roll()) {
                return i;
            }
        }
        return 0;
    }
}
