package com.hexvane.eterniamod.prefab;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import javax.annotation.Nonnull;

public final class BuildingPrefabSequenceBuilder {
    private static final long PREFAB_BUFFER_ITERATION_SEED = 0L;

    private BuildingPrefabSequenceBuilder() {}

    @Nonnull
    public static BuildingPrefabSequence build(@Nonnull IPrefabBuffer buffer, @Nonnull Rotation yaw) {
        Random bufferIterationRandom = new Random(PREFAB_BUFFER_ITERATION_SEED);
        PrefabRotation prefabRotation = PrefabRotation.fromRotation(yaw);
        PrefabBufferCall call = new PrefabBufferCall(bufferIterationRandom, prefabRotation);
        List<BuildingPrefabCell> pending = new ArrayList<>();
        buffer.forEach(
            IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, supportValue, blockRotation, filler, t, fluidId, fluidLevel) -> {
                pending.add(
                    new BuildingPrefabCell(
                        x,
                        y,
                        z,
                        blockId,
                        holder != null ? holder.clone() : null,
                        supportValue,
                        blockRotation,
                        filler,
                        fluidId,
                        fluidLevel
                    )
                );
            },
            null,
            null,
            call
        );
        Comparator<BuildingPrefabCell> byColumn =
            Comparator.comparingInt(BuildingPrefabCell::y)
                .thenComparingInt(BuildingPrefabCell::x)
                .thenComparingInt(BuildingPrefabCell::z);
        pending.sort(byColumn);
        return new BuildingPrefabSequence(pending);
    }
}
