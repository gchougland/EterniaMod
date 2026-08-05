package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.PrefabLocalOffset;
import com.hexvane.eterniamod.hub.ReplacedBlockCell;
import com.hexvane.eterniamod.prefab.WorldBlockSnapshotUtil;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.modules.block.BlockEntity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Ensures the management block exists at the building's configured local cell and links it to the plot. */
public final class ManagementBlockLinker {
    /** Bit 2 skips automatic block-entity attachment; we attach explicitly after placeBlock. */
    private static final int PLACE_SETTINGS = 10;

    private ManagementBlockLinker() {}

    public static void linkPlot(
        @Nonnull World world,
        @Nonnull UUID plotId,
        @Nonnull BuildingDefinition def,
        @Nonnull Vector3i buildingAnchor,
        @Nonnull Rotation yaw
    ) {
        int[] local = def.getManagementBlockLocalPos();
        if (local == null) {
            return;
        }
        Vector3i offset = PrefabLocalOffset.rotate(yaw, local[0], local[1], local[2]);
        int wx = buildingAnchor.x + offset.x;
        int wy = buildingAnchor.y + offset.y;
        int wz = buildingAnchor.z + offset.z;
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
        if (chunk == null) {
            return;
        }
        Integer managementY = findBlockY(world, wx, wy, wz, EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID);
        if (managementY == null) {
            RotationTuple rotation = resolveRotation(world, wx, wy, wz, yaw);
            ensureBlockPlaced(world, chunk, wx, wy, wz, EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID, rotation);
            managementY = findBlockY(world, wx, wy, wz, EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID);
            if (managementY == null) {
                return;
            }
        }
        attachPlotComponent(world, chunk, wx, managementY, wz, plotId);
    }

    /** Removes the linked management block and restores terrain that was there before placement, if known. */
    public static void removeLinked(
        @Nonnull World world,
        @Nonnull BuildingDefinition def,
        @Nonnull Vector3i buildingAnchor,
        @Nonnull Rotation yaw,
        @Nullable List<ReplacedBlockCell> replacedBlocks
    ) {
        int[] local = def.getManagementBlockLocalPos();
        if (local == null) {
            return;
        }
        Vector3i offset = PrefabLocalOffset.rotate(yaw, local[0], local[1], local[2]);
        int wx = buildingAnchor.x + offset.x;
        int wy = buildingAnchor.y + offset.y;
        int wz = buildingAnchor.z + offset.z;
        Integer managementY = findBlockY(world, wx, wy, wz, EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID);
        if (managementY == null) {
            return;
        }
        ReplacedBlockCell saved = findSaved(replacedBlocks, wx, managementY, wz);
        if (saved != null) {
            WorldBlockSnapshotUtil.restoreCell(world, saved);
        } else {
            WorldBlockSnapshotUtil.clearPrefabBlockCell(world, wx, managementY, wz);
        }
    }

    @Nullable
    private static ReplacedBlockCell findSaved(@Nullable List<ReplacedBlockCell> replacedBlocks, int x, int y, int z) {
        if (replacedBlocks == null) {
            return null;
        }
        for (ReplacedBlockCell cell : replacedBlocks) {
            if (cell.getX() == x && cell.getY() == y && cell.getZ() == z) {
                return cell;
            }
        }
        return null;
    }

    private static void attachPlotComponent(
        @Nonnull World world,
        @Nonnull WorldChunk chunk,
        int wx,
        int y,
        int wz,
        @Nonnull UUID plotId
    ) {
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(wx, y, wz);
        if (blockRef == null || !blockRef.isValid()) {
            BlockType blockType = BlockType.getAssetMap().getAsset(EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID);
            if (blockType == null || blockType.getBlockEntity() == null || chunk.getBlockComponentChunk() == null) {
                return;
            }
            int rotationIndex = WorldBlockSnapshotUtil.readRotationIndex(world, wx, y, wz);
            BlockEntity.setBlockEntity(
                world.getChunkStore().getStore(),
                chunk.getReference(),
                chunk.getBlockComponentChunk(),
                wx,
                y,
                wz,
                blockType,
                rotationIndex,
                blockType.getBlockEntity().clone()
            );
            blockRef = chunk.getBlockComponentEntity(wx, y, wz);
        }
        if (blockRef == null || !blockRef.isValid()) {
            return;
        }
        Store<ChunkStore> cs = blockRef.getStore();
        cs.putComponent(blockRef, EterniaManagementBlock.getComponentType(), new EterniaManagementBlock(plotId.toString()));
    }

    private static void ensureBlockPlaced(
        @Nonnull World world,
        @Nonnull WorldChunk chunk,
        int wx,
        int y,
        int wz,
        @Nonnull String blockTypeId,
        @Nonnull RotationTuple rotation
    ) {
        boolean placed = chunk.placeBlock(wx, y, wz, blockTypeId, rotation, PLACE_SETTINGS, false);
        if (!placed) {
            world.breakBlock(wx, y, wz, PLACE_SETTINGS);
            placed = chunk.placeBlock(wx, y, wz, blockTypeId, rotation, PLACE_SETTINGS, false);
        }
        if (!placed) {
            BlockTypeAssetMap<String, BlockType> typeMap = BlockType.getAssetMap();
            int indexKey = typeMap.getIndex(blockTypeId);
            BlockType blockType = typeMap.getAsset(indexKey);
            if (blockType != null) {
                chunk.setBlock(wx, y, wz, indexKey, blockType, rotation.index(), 0, PLACE_SETTINGS);
            }
        }
        chunk.setTicking(wx, y, wz, true);
    }

    @Nonnull
    private static RotationTuple resolveRotation(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull Rotation yaw
    ) {
        int bookY = wy - 1;
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
        if (chunk != null) {
            BlockType below = BlockType.getAssetMap().getAsset(chunk.getBlock(wx, bookY, wz));
            if (below != null && "Furniture_Village_Bookcase".equals(below.getId())) {
                return RotationTuple.get(WorldBlockSnapshotUtil.readRotationIndex(world, wx, bookY, wz));
            }
        }
        return RotationTuple.of(yaw, Rotation.None, Rotation.None);
    }

    @Nullable
    private static Integer findBlockY(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull String blockTypeId
    ) {
        for (int dy = -2; dy <= 2; dy++) {
            int y = wy + dy;
            if (y < ChunkUtil.MIN_Y || y > ChunkUtil.HEIGHT_MINUS_1) {
                continue;
            }
            BlockType bt = world.getBlockType(wx, y, wz);
            if (bt != null && blockTypeId.equals(bt.getId())) {
                return y;
            }
        }
        return null;
    }
}
