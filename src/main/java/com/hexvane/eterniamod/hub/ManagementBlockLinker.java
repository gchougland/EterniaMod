package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.PrefabLocalOffset;
import com.hexvane.eterniamod.prefab.WorldBlockSnapshotUtil;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.modules.block.BlockEntity;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Ensures the management block exists at the building's configured local cell and links it to the plot. */
public final class ManagementBlockLinker {
    /** Skips automatic block-entity attachment; we attach explicitly after setBlock. */
    private static final int PLACE_SETTINGS = SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES;

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
        if (ChunkSectionBlockUtil.sectionRefAt(world, wx, wy, wz) == null) {
            return;
        }
        Integer managementY = findBlockY(world, wx, wy, wz, EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID);
        if (managementY == null) {
            RotationTuple rotation = resolveRotation(world, wx, wy, wz, yaw);
            ensureBlockPlaced(world, wx, wy, wz, EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID, rotation);
            managementY = findBlockY(world, wx, wy, wz, EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID);
            if (managementY == null) {
                return;
            }
        }
        attachPlotComponent(world, wx, managementY, wz, plotId);
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
        int wx,
        int y,
        int wz,
        @Nonnull UUID plotId
    ) {
        Ref<ChunkStore> blockRef = ChunkSectionBlockUtil.blockEntityRefAt(world, wx, y, wz);
        if (blockRef == null || !blockRef.isValid()) {
            BlockType blockType = BlockType.getAssetMap().getAsset(EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID);
            Ref<ChunkStore> sectionRef = ChunkSectionBlockUtil.sectionRefAt(world, wx, y, wz);
            BlockComponentSection blockComponents = ChunkSectionBlockUtil.blockComponentSectionAt(world, wx, y, wz);
            if (blockType == null || blockType.getBlockEntity() == null || sectionRef == null || blockComponents == null) {
                return;
            }
            int rotationIndex = WorldBlockSnapshotUtil.readRotationIndex(world, wx, y, wz);
            BlockEntity.setBlockEntity(
                world.getChunkStore().getStore(),
                sectionRef,
                blockComponents,
                wx,
                y,
                wz,
                blockType,
                rotationIndex,
                blockType.getBlockEntity().clone()
            );
            blockRef = ChunkSectionBlockUtil.blockEntityRefAt(world, wx, y, wz);
        }
        if (blockRef == null || !blockRef.isValid()) {
            return;
        }
        Store<ChunkStore> cs = blockRef.getStore();
        cs.putComponent(blockRef, EterniaManagementBlock.getComponentType(), new EterniaManagementBlock(plotId.toString()));
    }

    private static void ensureBlockPlaced(
        @Nonnull World world,
        int wx,
        int y,
        int wz,
        @Nonnull String blockTypeId,
        @Nonnull RotationTuple rotation
    ) {
        BlockTypeAssetMap<String, BlockType> typeMap = BlockType.getAssetMap();
        int indexKey = typeMap.getIndex(blockTypeId);
        BlockType blockType = typeMap.getAsset(indexKey);
        if (blockType == null) {
            return;
        }
        boolean placed = tryPlace(world, wx, y, wz, indexKey, blockType, rotation);
        if (!placed) {
            ChunkSectionBlockUtil.breakBlock(world, wx, y, wz, PLACE_SETTINGS);
            placed = tryPlace(world, wx, y, wz, indexKey, blockType, rotation);
        }
        if (!placed) {
            ChunkSectionBlockUtil.setBlock(
                world,
                wx,
                y,
                wz,
                indexKey,
                blockType,
                rotation.index(),
                FillerBlockUtil.NO_FILLER,
                PLACE_SETTINGS
            );
        }
        ChunkSectionBlockUtil.setTicking(world, wx, y, wz, true);
    }

    private static boolean tryPlace(
        @Nonnull World world,
        int wx,
        int y,
        int wz,
        int indexKey,
        @Nonnull BlockType blockType,
        @Nonnull RotationTuple rotation
    ) {
        BlockSection section = ChunkSectionBlockUtil.blockSectionAt(world, wx, y, wz);
        if (section == null) {
            return false;
        }
        Store<ChunkStore> store = world.getChunkStore().getStore();
        if (!BlockOperations.testPlaceBlock(store, section, wx, y, wz, blockType, rotation.index())) {
            return false;
        }
        return ChunkSectionBlockUtil.setBlock(
            world,
            wx,
            y,
            wz,
            indexKey,
            blockType,
            rotation.index(),
            FillerBlockUtil.NO_FILLER,
            PLACE_SETTINGS
        );
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
        BlockType below = ChunkSectionBlockUtil.blockType(world, wx, bookY, wz);
        if (below != null && "Furniture_Village_Bookcase".equals(below.getId())) {
            return RotationTuple.get(WorldBlockSnapshotUtil.readRotationIndex(world, wx, bookY, wz));
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
            BlockType bt = ChunkSectionBlockUtil.blockType(world, wx, y, wz);
            if (bt != null && blockTypeId.equals(bt.getId())) {
                return y;
            }
        }
        return null;
    }
}
