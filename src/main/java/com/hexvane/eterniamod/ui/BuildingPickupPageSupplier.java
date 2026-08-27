package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaManagementBlock;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BuildingPickupPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {
    public static final BuilderCodec<BuildingPickupPageSupplier> CODEC =
        BuilderCodec.builder(BuildingPickupPageSupplier.class, BuildingPickupPageSupplier::new).build();

    @Override
    @Nullable
    public CustomUIPage tryCreate(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull ComponentAccessor<EntityStore> componentAccessor,
        @Nonnull PlayerRef playerRef,
        @Nonnull InteractionContext context
    ) {
        BlockPosition targetBlock = context.getTargetBlock();
        if (targetBlock == null) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        Ref<ChunkStore> blockRef =
            ChunkSectionBlockUtil.blockEntityRefAt(world, targetBlock.x, targetBlock.y, targetBlock.z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        Store<ChunkStore> chunkStore = blockRef.getStore();
        EterniaManagementBlock mb = chunkStore.getComponent(blockRef, EterniaManagementBlock.getComponentType());
        if (mb == null || mb.getPlotId().isBlank()) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.noBuildingToPickup"));
            return null;
        }
        UUID plotId;
        try {
            plotId = UUID.fromString(mb.getPlotId().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uc == null) {
            return null;
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin == null) {
            return null;
        }
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.getPlot(plotId);
        if (plot == null || !plot.hasBuilding()) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.noBuildingToPickup"));
            return null;
        }
        if (!plot.isOwnedBy(uc.getUuid())) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.notYourPlot"));
            return null;
        }
        return new BuildingPickupPage(playerRef, plotId);
    }
}
