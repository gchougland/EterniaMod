package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.PrefabLocalOffset;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class EterniaManagementBlock implements Component<ChunkStore> {
    @Nonnull
    public static final BuilderCodec<EterniaManagementBlock> CODEC =
        BuilderCodec.builder(EterniaManagementBlock.class, EterniaManagementBlock::new)
            .append(new KeyedCodec<>("PlotId", Codec.STRING), (s, v) -> s.plotId = v != null ? v : "", s -> s.plotId)
            .add()
            .build();

    @Nullable
    private static volatile ComponentType<ChunkStore, EterniaManagementBlock> componentType;

    public static void register(@Nonnull ComponentRegistryProxy<ChunkStore> registry) {
        componentType = registry.registerComponent(EterniaManagementBlock.class, "EterniaManagementBlock", CODEC);
    }

    @Nonnull
    public static ComponentType<ChunkStore, EterniaManagementBlock> getComponentType() {
        ComponentType<ChunkStore, EterniaManagementBlock> t = componentType;
        if (t == null) {
            throw new IllegalStateException("EterniaManagementBlock not registered");
        }
        return t;
    }

    private String plotId = "";

    public EterniaManagementBlock() {}

    public EterniaManagementBlock(@Nonnull String plotId) {
        this.plotId = plotId != null ? plotId : "";
    }

    @Nonnull
    public String getPlotId() {
        return plotId;
    }

    public void setPlotId(@Nonnull String plotId) {
        this.plotId = plotId != null ? plotId : "";
    }

    @Nullable
    @Override
    public Component<ChunkStore> clone() {
        return new EterniaManagementBlock(plotId);
    }
}
