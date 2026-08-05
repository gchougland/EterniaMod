package com.hexvane.eterniamod.bootstrap;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.placement.BuildingPlacementOpenHelper;
import com.hexvane.eterniamod.placement.BuildingPlacementPlayerRemoveSystem;
import com.hexvane.eterniamod.ui.BuildingPlacementPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import javax.annotation.Nonnull;

public final class BuildingPlacementBootstrap {
    private BuildingPlacementBootstrap() {}

    public static void register(@Nonnull EterniaModPlugin plugin) {
        OpenCustomUIInteraction.registerCustomPageSupplier(
            plugin,
            BuildingPlacementPage.class,
            com.hexvane.eterniamod.EterniaModConstants.PAGE_BUILDING_PLACEMENT,
            BuildingPlacementOpenHelper::tryOpen
        );
        plugin.getEntityStoreRegistry().registerSystem(new BuildingPlacementPlayerRemoveSystem());
        plugin.getEventRegistry().registerGlobal(StartWorldEvent.class, event -> EterniaWorldRegistries.getOrCreateHubPlotManager(event.getWorld(), plugin));
        plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> EterniaWorldRegistries.unloadWorld(event.getWorld()));
    }
}
