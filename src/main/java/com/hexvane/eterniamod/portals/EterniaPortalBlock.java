package com.hexvane.eterniamod.portals;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/**
 * Marks a Teleporter pad as an Eternia portal. It carries no data: the portal itself lives in {@code PortalService}.
 * The pad's block asset lists it in {@code BlockEntity.Components}, so placing and removing a pad can be seen by
 * systems that query for it, the way vanilla's own Teleporter component is.
 */
public final class EterniaPortalBlock implements Component<ChunkStore> {
    public static final BuilderCodec<EterniaPortalBlock> CODEC = BuilderCodec.builder(EterniaPortalBlock.class, EterniaPortalBlock::new).build();
    private static volatile ComponentType<ChunkStore, EterniaPortalBlock> type;

    static void register(@Nonnull ComponentRegistryProxy<ChunkStore> registry) {
        type = registry.registerComponent(EterniaPortalBlock.class, "EterniaPortal", CODEC);
    }

    @Nonnull
    public static ComponentType<ChunkStore, EterniaPortalBlock> getComponentType() {
        var t = type;
        if (t == null) throw new IllegalStateException("EterniaPortal not registered");
        return t;
    }

    @Nonnull @Override public Component<ChunkStore> clone() { return new EterniaPortalBlock(); }
}
