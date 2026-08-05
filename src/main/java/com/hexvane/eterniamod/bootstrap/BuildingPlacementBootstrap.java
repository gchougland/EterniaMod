package com.hexvane.eterniamod.bootstrap;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaManagementBlock;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.ManagementBreakBlockSystem;
import com.hexvane.eterniamod.interaction.EterniaOpenBuildingPlacementInteraction;
import com.hexvane.eterniamod.placement.BuildingPlacementPlayerRemoveSystem;
import com.hexvane.eterniamod.ui.BuildingPickupPageSupplier;
import com.hexvane.eterniamod.ui.BuildingPlacementPageSupplier;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import javax.annotation.Nonnull;

public final class BuildingPlacementBootstrap {
    private BuildingPlacementBootstrap() {}

    public static void register(@Nonnull EterniaModPlugin plugin) {
        plugin
            .getCodecRegistry(Interaction.CODEC)
            .register(
                "EterniaOpenBuildingPlacement",
                EterniaOpenBuildingPlacementInteraction.class,
                EterniaOpenBuildingPlacementInteraction.CODEC
            );
        OpenCustomUIInteraction.registerCustomPageSupplier(
            plugin,
            BuildingPlacementPageSupplier.class,
            EterniaModConstants.PAGE_BUILDING_PLACEMENT,
            new BuildingPlacementPageSupplier()
        );
        OpenCustomUIInteraction.registerCustomPageSupplier(
            plugin,
            BuildingPickupPageSupplier.class,
            EterniaModConstants.PAGE_BUILDING_PICKUP,
            new BuildingPickupPageSupplier()
        );
        EterniaManagementBlock.register(plugin.getChunkStoreRegistry());
        plugin.getEntityStoreRegistry().registerSystem(new BuildingPlacementPlayerRemoveSystem());
        plugin.getEntityStoreRegistry().registerSystem(new ManagementBreakBlockSystem(plugin));
        plugin.getEventRegistry().registerGlobal(StartWorldEvent.class, event -> EterniaWorldRegistries.getOrCreateHubPlotManager(event.getWorld(), plugin));
        plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> EterniaWorldRegistries.unloadWorld(event.getWorld()));
    }
}
