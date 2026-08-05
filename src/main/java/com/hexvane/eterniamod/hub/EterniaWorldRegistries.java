package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

public final class EterniaWorldRegistries {
    private static final Map<String, HubPlotManager> HUB_PLOTS_BY_WORLD = new ConcurrentHashMap<>();

    private EterniaWorldRegistries() {}

    @Nonnull
    public static HubPlotManager getOrCreateHubPlotManager(@Nonnull World world, @Nonnull EterniaModPlugin plugin) {
        return HUB_PLOTS_BY_WORLD.computeIfAbsent(
            world.getName(),
            name -> {
                HubPlotManager manager = new HubPlotManager(world, plugin.getDataDirectory());
                manager.loadFromDisk();
                return manager;
            }
        );
    }

    public static void unloadWorld(@Nonnull World world) {
        HubPlotManager manager = HUB_PLOTS_BY_WORLD.remove(world.getName());
        if (manager != null) {
            manager.saveIfDirty();
        }
    }
}
