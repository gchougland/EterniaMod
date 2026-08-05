package com.hexvane.eterniamod.prefab;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.block.BlockEntity;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.LocalCachedChunkAccessor;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

/** Prefab paste for props: solids only, no terrain carving. */
public final class PropPrefabOps {
    private static final int PLACE_SETTINGS = 0;

    private PropPrefabOps() {}

    public static void pasteSolidsOnly(
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull IPrefabBuffer buffer
    ) {
        BuildingPrefabSequence sequence = BuildingPrefabSequenceBuilder.build(buffer, yaw);
        LocalCachedChunkAccessor accessor = createAccessor(world, origin, buffer);
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        for (BuildingPrefabCell cell : sequence.cells()) {
            if (isFillerCompanion(cell)) {
                attachFillerHolder(world, origin, cell, accessor, blockTypeMap);
                continue;
            }
            if (BuildingPrefabOps.isPureAirCell(cell)) {
                continue;
            }
            if (BuildingPrefabOps.isOriginSolid(cell)) {
                int wx = origin.x + cell.x();
                int wy = origin.y + cell.y();
                int wz = origin.z + cell.z();
                placeOriginSolid(world, wx, wy, wz, cell, accessor, blockTypeMap);
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
        LocalCachedChunkAccessor accessor = createAccessor(world, origin, buffer);
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        for (BuildingPrefabCell cell : sequence.cells()) {
            if (!BuildingPrefabOps.isOriginSolid(cell)) {
                continue;
            }
            int wx = origin.x + cell.x();
            int wy = origin.y + cell.y();
            int wz = origin.z + cell.z();
            WorldChunk chunk = accessor.getNonTickingChunk(ChunkUtil.indexChunkFromBlock(wx, wz));
            if (chunk == null || !chunk.getReference().isValid()) {
                return false;
            }
            BlockType block = blockTypeMap.getAsset(cell.blockId());
            if (block == null) {
                return false;
            }
            if (!WorldBlockSnapshotUtil.isEmptyBlock(chunk, wx, wy, wz)
                && !chunk.testPlaceBlock(wx, wy, wz, block, cell.blockRotation())) {
                return false;
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
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(wx, wz));
        if (chunk == null) {
            return false;
        }
        BlockType expected = blockTypeMap.getAsset(cell.blockId());
        if (expected == null) {
            return false;
        }
        BlockType current = blockTypeMap.getAsset(chunk.getBlock(wx, wy, wz));
        if (current == null || current == BlockType.EMPTY) {
            return false;
        }
        return cell.blockId() == chunk.getBlock(wx, wy, wz)
            || expected.getId() != null && expected.getId().equals(current.getId());
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
}
