package com.hexvane.eterniamod.prefab;

import com.hexvane.eterniamod.hub.ReplacedBlockCell;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.modules.block.BlockEntity;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.LocalCachedChunkAccessor;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Prefab paste with terrain snapshots. Placement carves prefab air cells and writes prefab solids. Pickup only
 * reverts cells that still match what the prefab placed — player-added blocks (e.g. a furnace in a carved room) are
 * left untouched.
 */
public final class BuildingPrefabOps {
    private static final int PLACE_SETTINGS = 0;
    /** Skips automatic block-entity attachment; attach explicitly after placeBlock. */
    private static final int INTERACTIVE_PLACE_SETTINGS = 10;

    private BuildingPrefabOps() {}

    @Nonnull
    public static List<ReplacedBlockCell> captureAndPaste(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer
    ) {
        BuildingPrefabSequence sequence = BuildingPrefabSequenceBuilder.build(buffer, yaw);
        List<ReplacedBlockCell> replaced = new ArrayList<>();
        LocalCachedChunkAccessor accessor = createAccessor(world, origin, buffer);
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        for (BuildingPrefabCell cell : sequence.cells()) {
            if (isFillerCompanion(cell)) {
                attachFillerHolder(world, origin, cell, accessor, blockTypeMap);
                continue;
            }
            int wx = origin.x + cell.x();
            int wy = origin.y + cell.y();
            int wz = origin.z + cell.z();
            ReplacedBlockCell snapshot = WorldBlockSnapshotUtil.captureIfNonEmpty(world, wx, wy, wz);
            if (snapshot != null) {
                replaced.add(snapshot);
            }
            if (isPureAirCell(cell)) {
                WorldBlockSnapshotUtil.clearCell(world, wx, wy, wz);
            } else if (isOriginSolid(cell)) {
                placeOriginSolid(world, wx, wy, wz, cell, accessor, blockTypeMap);
            }
        }
        return replaced;
    }

    public static void removeAndRestore(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer,
        @Nullable List<ReplacedBlockCell> replacedBlocks
    ) {
        BuildingPrefabSequence sequence = BuildingPrefabSequenceBuilder.build(buffer, yaw);
        Map<Long, ReplacedBlockCell> savedByPos = new HashMap<>();
        if (replacedBlocks != null) {
            for (ReplacedBlockCell cell : replacedBlocks) {
                savedByPos.put(cell.packPos(), cell);
            }
        }
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        Set<Long> clearedPrimaries = new HashSet<>();
        for (BuildingPrefabCell cell : sequence.cells()) {
            int wx = origin.x + cell.x();
            int wy = origin.y + cell.y();
            int wz = origin.z + cell.z();
            if (isFillerCompanion(cell)) {
                if (!stillMatchesFillerPlacement(world, wx, wy, wz, cell, blockTypeMap)) {
                    continue;
                }
                Vector3i primary = WorldBlockSnapshotUtil.resolvePrimaryBlock(world, wx, wy, wz);
                revertPrimaryIfNeeded(world, primary.x, primary.y, primary.z, savedByPos, clearedPrimaries);
                WorldBlockSnapshotUtil.clearCell(world, wx, wy, wz);
                continue;
            }
            if (isPureAirCell(cell)) {
                WorldChunk chunk = requireChunk(world, wx, wz);
                if (chunk == null || !WorldBlockSnapshotUtil.isEmptyBlock(chunk, wx, wy, wz)) {
                    continue;
                }
                revertCell(world, wx, wy, wz, savedByPos);
                continue;
            }
            if (!isOriginSolid(cell)) {
                continue;
            }
            if (!stillMatchesOriginSolid(world, wx, wy, wz, cell, blockTypeMap)) {
                continue;
            }
            revertPrimaryIfNeeded(world, wx, wy, wz, savedByPos, clearedPrimaries);
        }
    }

    private static void revertPrimaryIfNeeded(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull Map<Long, ReplacedBlockCell> savedByPos,
        @Nonnull Set<Long> clearedPrimaries
    ) {
        Vector3i primary = WorldBlockSnapshotUtil.resolvePrimaryBlock(world, wx, wy, wz);
        long key = packPos(primary.x, primary.y, primary.z);
        if (!clearedPrimaries.add(key)) {
            return;
        }
        revertCell(world, primary.x, primary.y, primary.z, savedByPos);
    }

    private static void revertCell(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull Map<Long, ReplacedBlockCell> savedByPos
    ) {
        ReplacedBlockCell saved = savedByPos.get(packPos(wx, wy, wz));
        if (saved != null) {
            WorldBlockSnapshotUtil.restoreCell(world, saved);
        } else {
            WorldBlockSnapshotUtil.clearPrefabBlockCell(world, wx, wy, wz);
        }
    }

    /** True when the prefab cell is explicit air/fluid-empty and should carve the world on placement. */
    public static boolean isPureAirCell(@Nonnull BuildingPrefabCell cell) {
        return cell.blockId() == 0 && cell.filler() == 0 && cell.fluidId() == 0;
    }

    public static boolean isOriginSolid(@Nonnull BuildingPrefabCell cell) {
        return cell.blockId() != 0 && cell.filler() == FillerBlockUtil.NO_FILLER;
    }

    private static boolean isFillerCompanion(@Nonnull BuildingPrefabCell cell) {
        return cell.filler() != FillerBlockUtil.NO_FILLER && cell.blockId() != 0;
    }

    /** Only revert origin solids that still contain what the prefab wrote. */
    private static boolean stillMatchesOriginSolid(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull BlockTypeAssetMap<String, BlockType> blockTypeMap
    ) {
        WorldChunk chunk = requireChunk(world, wx, wz);
        if (chunk == null) {
            return false;
        }
        BlockType expected = blockTypeMap.getAsset(cell.blockId());
        if (expected == null) {
            return false;
        }
        Vector3i primary = WorldBlockSnapshotUtil.resolvePrimaryBlock(world, wx, wy, wz);
        BlockType current = blockTypeMap.getAsset(chunk.getBlock(primary.x, primary.y, primary.z));
        return blockTypesMatch(cell.blockId(), expected, current, chunk, primary.x, primary.y, primary.z);
    }

    /** Only revert filler segments that still belong to an unchanged prefab multi-block. */
    private static boolean stillMatchesFillerPlacement(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull BlockTypeAssetMap<String, BlockType> blockTypeMap
    ) {
        WorldChunk chunk = requireChunk(world, wx, wz);
        if (chunk == null || chunk.getFiller(wx, wy, wz) == FillerBlockUtil.NO_FILLER) {
            return false;
        }
        BlockType expected = blockTypeMap.getAsset(cell.blockId());
        BlockType current = blockTypeMap.getAsset(chunk.getBlock(wx, wy, wz));
        if (!blockTypesMatch(cell.blockId(), expected, current, chunk, wx, wy, wz)) {
            return false;
        }
        Vector3i primary = WorldBlockSnapshotUtil.resolvePrimaryBlock(world, wx, wy, wz);
        BlockType primaryType = blockTypeMap.getAsset(chunk.getBlock(primary.x, primary.y, primary.z));
        return blockTypesMatch(cell.blockId(), expected, primaryType, chunk, primary.x, primary.y, primary.z);
    }

    private static boolean blockTypesMatch(
        int expectedBlockId,
        @Nullable BlockType expected,
        @Nullable BlockType current,
        @Nonnull WorldChunk chunk,
        int wx,
        int wy,
        int wz
    ) {
        if (expected == null || current == null || current == BlockType.EMPTY) {
            return false;
        }
        if (expectedBlockId == chunk.getBlock(wx, wy, wz)) {
            return true;
        }
        if (expected.getId() != null && expected.getId().equals(current.getId())) {
            return true;
        }
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        int expectedIndex = map.getIndex(expected.getId());
        int currentIndex = map.getIndex(current.getId());
        if (expectedIndex >= 0 && expectedIndex == currentIndex) {
            return true;
        }
        return samePrefabBlockFamily(expected.getId(), current.getId());
    }

    private static boolean samePrefabBlockFamily(@Nullable String expectedId, @Nullable String currentId) {
        if (expectedId == null || currentId == null) {
            return false;
        }
        String normalizedExpected = expectedId.startsWith("*") ? expectedId.substring(1) : expectedId;
        if (normalizedExpected.contains("Door") && currentId.contains("Door")) {
            return normalizedExpected.contains("Village") == currentId.contains("Village");
        }
        return false;
    }

    @Nullable
    private static WorldChunk requireChunk(@Nonnull World world, int wx, int wz) {
        return world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
    }

    /** Only revert cells that still contain what the prefab wrote (air carve or solid block). */
    private static boolean stillMatchesPrefabPlacement(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull BlockTypeAssetMap<String, BlockType> blockTypeMap
    ) {
        if (isPureAirCell(cell)) {
            WorldChunk chunk = requireChunk(world, wx, wz);
            return chunk != null && WorldBlockSnapshotUtil.isEmptyBlock(chunk, wx, wy, wz);
        }
        return stillMatchesOriginSolid(world, wx, wy, wz, cell, blockTypeMap);
    }

    private static void placeOriginSolid(
        @Nonnull World world,
        int wx,
        int wy,
        int wz,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull LocalCachedChunkAccessor accessor,
        @Nonnull BlockTypeAssetMap<String, BlockType> blockTypeMap
    ) {
        WorldChunk chunk = accessor.getNonTickingChunk(ChunkUtil.indexChunkFromBlock(wx, wz));
        if (chunk == null || !chunk.getReference().isValid()) {
            chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
        }
        if (chunk == null || !chunk.getReference().isValid()) {
            return;
        }
        BlockType block = blockTypeMap.getAsset(cell.blockId());
        if (block == null) {
            return;
        }
        if (needsInteractivePlacement(block, cell)) {
            placeInteractiveSolid(world, chunk, wx, wy, wz, cell, block);
            return;
        }
        chunk.setBlock(
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
            attachBlockEntity(world, chunk, wx, wy, wz, block, cell.blockRotation(), cell.holder().clone());
        } else if (block.getBlockEntity() != null) {
            attachBlockEntity(world, chunk, wx, wy, wz, block, cell.blockRotation(), block.getBlockEntity().clone());
        }
    }

    private static boolean needsInteractivePlacement(@Nonnull BlockType block, @Nonnull BuildingPrefabCell cell) {
        return cell.holder() != null || block.getBlockEntity() != null;
    }

    private static void placeInteractiveSolid(
        @Nonnull World world,
        @Nonnull WorldChunk chunk,
        int wx,
        int wy,
        int wz,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull BlockType block
    ) {
        RotationTuple rotation = RotationTuple.get(cell.blockRotation());
        chunk.setTicking(wx, wy, wz, true);
        if (!chunk.placeBlock(wx, wy, wz, block.getId(), rotation, INTERACTIVE_PLACE_SETTINGS, false)) {
            chunk.setBlock(
                wx,
                wy,
                wz,
                cell.blockId(),
                block,
                cell.blockRotation(),
                FillerBlockUtil.NO_FILLER,
                PLACE_SETTINGS
            );
        }
        if (cell.holder() != null) {
            attachBlockEntity(world, chunk, wx, wy, wz, block, cell.blockRotation(), cell.holder().clone());
        } else if (block.getBlockEntity() != null) {
            attachBlockEntity(world, chunk, wx, wy, wz, block, cell.blockRotation(), block.getBlockEntity().clone());
        }
    }

    private static void attachFillerHolder(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull BuildingPrefabCell cell,
        @Nonnull LocalCachedChunkAccessor accessor,
        @Nonnull BlockTypeAssetMap<String, BlockType> blockTypeMap
    ) {
        if (cell.holder() == null) {
            return;
        }
        int wx = origin.x + cell.x();
        int wy = origin.y + cell.y();
        int wz = origin.z + cell.z();
        WorldChunk chunk = accessor.getNonTickingChunk(ChunkUtil.indexChunkFromBlock(wx, wz));
        if (chunk == null || !chunk.getReference().isValid()) {
            chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
        }
        if (chunk == null || !chunk.getReference().isValid()) {
            return;
        }
        BlockType block = blockTypeMap.getAsset(cell.blockId());
        if (block == null) {
            return;
        }
        attachBlockEntity(world, chunk, wx, wy, wz, block, cell.blockRotation(), cell.holder().clone());
    }

    private static void attachBlockEntity(
        @Nonnull World world,
        @Nonnull WorldChunk chunk,
        int x,
        int y,
        int z,
        @Nonnull BlockType blockType,
        int rotation,
        @Nonnull Holder<ChunkStore> holder
    ) {
        Ref<ChunkStore> chunkRef = chunk.getReference();
        if (!chunkRef.isValid() || chunk.getBlockComponentChunk() == null) {
            return;
        }
        BlockEntity.setBlockEntity(
            world.getChunkStore().getStore(),
            chunkRef,
            chunk.getBlockComponentChunk(),
            x,
            y,
            z,
            blockType,
            rotation,
            holder
        );
    }

    @Nonnull
    private static LocalCachedChunkAccessor createAccessor(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull IPrefabBuffer buffer
    ) {
        double xLength = buffer.getMaxX() - buffer.getMinX();
        double zLength = buffer.getMaxZ() - buffer.getMinZ();
        int prefabRadius = (int) Math.floor(0.5 * Math.sqrt(xLength * xLength + zLength * zLength));
        return LocalCachedChunkAccessor.atWorldCoords(world, origin.x(), origin.z(), prefabRadius);
    }

    private static long packPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | (long) z & 0x3FFFFFFL;
    }
}
