package com.hexvane.eterniamod.prefab;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

record BuildingPrefabCell(
    int x,
    int y,
    int z,
    int blockId,
    @Nullable Holder<ChunkStore> holder,
    int supportValue,
    int blockRotation,
    int filler,
    int fluidId,
    int fluidLevel
) {}

public record BuildingPrefabSequence(@Nonnull List<BuildingPrefabCell> cells) {}
