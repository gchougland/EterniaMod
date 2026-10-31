package com.hexvane.eterniamod.loot;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Marks a chest whose loot belongs to each player. The droplist moves here so the engine never fills the shared container. */
public final class LootChestBlock implements Component<ChunkStore> {
    @Nonnull
    public static final BuilderCodec<LootChestBlock> CODEC =
        BuilderCodec.builder(LootChestBlock.class, LootChestBlock::new)
            .append(new KeyedCodec<>("Droplist", Codec.STRING), (s, v) -> s.droplist = v, s -> s.droplist)
            .add()
            .append(new KeyedCodec<>("Instance", Codec.STRING), (s, v) -> s.instance = v, s -> s.instance)
            .add()
            .build();

    @Nullable
    private static volatile ComponentType<ChunkStore, LootChestBlock> componentType;

    static void register(@Nonnull ComponentRegistryProxy<ChunkStore> registry) {
        componentType = registry.registerComponent(LootChestBlock.class, "EterniaLootChest", CODEC);
    }

    @Nonnull
    public static ComponentType<ChunkStore, LootChestBlock> getComponentType() {
        ComponentType<ChunkStore, LootChestBlock> t = componentType;
        if (t == null) {
            throw new IllegalStateException("LootChestBlock not registered");
        }
        return t;
    }

    private String droplist = "";
    private String instance = "";

    public LootChestBlock() {}

    LootChestBlock(@Nonnull String droplist, @Nonnull String instance) {
        this.droplist = droplist;
        this.instance = instance;
    }

    @Nonnull
    public String getDroplist() {
        return droplist;
    }

    @Nonnull
    public String getInstance() {
        return instance;
    }

    @Nullable
    @Override
    public Component<ChunkStore> clone() {
        return new LootChestBlock(droplist, instance);
    }
}
