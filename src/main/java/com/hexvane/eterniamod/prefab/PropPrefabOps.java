package com.hexvane.eterniamod.prefab;

import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

/** Prefab paste for props: solids only, no terrain carving. */
public final class PropPrefabOps {
    private static final int PLACE_SETTINGS = SetBlockSettings.NONE;

    private PropPrefabOps() {}

    public static void pasteSolidsOnly(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer
    ) {
        BuildingPrefabSequence sequence = BuildingPrefabSequenceBuilder.build(buffer, yaw);
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        for (BuildingPrefabCell cell : sequence.cells()) {
            if (isFillerCompanion(cell)) {
                attachFillerHolder(world, origin, cell, blockTypeMap);
                continue;
            }
            if (BuildingPrefabOps.isPureAirCell(cell)) {
                continue;
            }
            if (BuildingPrefabOps.isOriginSolid(cell)) {
                int wx = origin.x + cell.x();
                int wy = origin.y + cell.y();
                int wz = origin.z + cell.z();
                placeOriginSolid(world, wx, wy, wz, cell, blockTypeMap);
            }
        }
    }

    public static void removeSolidsOnly(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer
    ) {
        BuildingPrefabSequence sequence = BuildingPrefabSequenceBuilder.build(buffer, yaw);
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        for (BuildingPrefabCell cell : sequence.cells()) {
            if (isFillerCompanion(cell)) {
                continue;
            }
            if (!BuildingPrefabOps.isOriginSolid(cell)) {
                continue;
            }
            int wx = origin.x + cell.x();
            int wy = origin.y + cell.y();
            int wz = origin.z + cell.z();
            if (!stillMatchesPrefabPlacement(world, wx, wy, wz, cell, blockTypeMap)) {
                continue;
            }
            WorldBlockSnapshotUtil.clearCell(world, wx, wy, wz);
        }
    }

    public static boolean canPlaceSolids(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer
    ) {
        BuildingPrefabSequence sequence = BuildingPrefabSequenceBuilder.build(buffer, yaw);
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        for (BuildingPrefabCell cell : sequence.cells()) {
            if (!BuildingPrefabOps.isOriginSolid(cell)) {
                continue;
            }
            int wx = origin.x + cell.x();
            int wy = origin.y + cell.y();
            int wz = origin.z + cell.z();
            BlockSection section = ChunkSectionBlockUtil.blockSectionAt(world, wx, wy, wz);
            if (section == null) {
                return false;
            }
            BlockType block = blockTypeMap.getAsset(cell.blockId());
            if (block == null) {
                return false;
            }
            if (!WorldBlockSnapshotUtil.isEmptyBlock(world, wx, wy, wz)) {
                Store<ChunkStore> store = world.getChunkStore().getStore();
                if (!BlockOperations.testPlaceBlock(store, section, wx, wy, wz, block, cell.blockRotation())) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isIntact(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer
    ) {
        BuildingPrefabSequence sequence = BuildingPrefabSequenceBuilder.build(buffer, yaw);
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        for (BuildingPrefabCell cell : sequence.cells()) {
            if (!BuildingPrefabOps.isOriginSolid(cell)) {
                continue;
            }
            int wx = origin.x + cell.x();
            int wy = origin.y + cell.y();
            int wz = origin.z + cell.z();
            if (!stillMatchesPrefabPlacement(world, wx, wy, wz, cell, blockTypeMap)) {
                return false;
            }
        }
        return true;
    }

    public static boolean blockBelongsToProp(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer,
        int wx,
        int wy,
        int wz
    ) {
        BuildingPrefabSequence sequence = BuildingPrefabSequenceBuilder.build(buffer, yaw);
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        for (BuildingPrefabCell cell : sequence.cells()) {
            if (!BuildingPrefabOps.isOriginSolid(cell)) {
                continue;
            }
            if (origin.x + cell.x() == wx && origin.y + cell.y() == wy && origin.z + cell.z() == wz) {
                return stillMatchesPrefabPlacement(world, wx, wy, wz, cell, blockTypeMap);
            }
        }
        return false;
    }

    private static boolean isFillerCompanion(@Nonnull BuildingPrefabCell cell) {
        return cell.filler() != FillerBlockUtil.NO_FILLER && cell.blockId() != 0;
    }

    private static boolean stillMatchesPrefabPlacement(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull BlockTypeAssetMap<String, BlockType> blockTypeMap
    ) {
        if (ChunkSectionBlockUtil.sectionRefAt(world, wx, wy, wz) == null) {
            return false;
        }
        BlockType expected = blockTypeMap.getAsset(cell.blockId());
        if (expected == null) {
            return false;
        }
        BlockType current = ChunkSectionBlockUtil.blockType(world, wx, wy, wz);
        if (current == null || current == BlockType.EMPTY) {
            return false;
        }
        return cell.blockId() == ChunkSectionBlockUtil.blockId(world, wx, wy, wz)
            || expected.getId() != null && expected.getId().equals(current.getId());
    }

    private static void placeOriginSolid(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull BlockTypeAssetMap<String, BlockType> blockTypeMap
    ) {
        if (ChunkSectionBlockUtil.sectionRefAt(world, wx, wy, wz) == null) {
            return;
        }
        BlockType block = blockTypeMap.getAsset(cell.blockId());
        if (block == null) {
            return;
        }
        ChunkSectionBlockUtil.setBlock(
            world,
            wx,
            wy,
            wz,
            cell.blockId(),
            block,
            cell.blockRotation(),
            FillerBlockUtil.NO_FILLER,
            PLACE_SETTINGS
        );
        if (cell.holder() != null) {
            BuildingPrefabOps.attachBlockEntity(world, wx, wy, wz, block, cell.blockRotation(), cell.holder().clone());
        } else if (block.getBlockEntity() != null) {
            BuildingPrefabOps.attachBlockEntity(
                world, wx, wy, wz, block, cell.blockRotation(), block.getBlockEntity().clone()
            );
        }
    }

    private static void attachFillerHolder(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull BlockTypeAssetMap<String, BlockType> blockTypeMap
    ) {
        if (cell.holder() == null) {
            return;
        }
        int wx = origin.x + cell.x();
        int wy = origin.y + cell.y();
        int wz = origin.z + cell.z();
        if (ChunkSectionBlockUtil.sectionRefAt(world, wx, wy, wz) == null) {
            return;
        }
        BlockType block = blockTypeMap.getAsset(cell.blockId());
        if (block == null) {
            return;
        }
        BuildingPrefabOps.attachBlockEntity(world, wx, wy, wz, block, cell.blockRotation(), cell.holder().clone());
    }
}
