package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Admin plot outline preview. Uses a fixed visual height; plot logic still ignores height for ownership. */
public final class HubPlotVisibilityOverlay {
    private HubPlotVisibilityOverlay() {}

    public static void clearFor(@Nullable PlayerRef player) {
        if (player == null) {
            return;
        }
        player.getPacketHandler().write(new ClearDebugShapes());
    }

    public static void showAll(@Nonnull PlayerRef player, @Nonnull HubPlotManager manager) {
        clearFor(player);
        for (HubPlotRecord plot : manager.listPlots()) {
            HubPlotBoundaryWireframe.sendPlotBoundary(player, plot.getFootprint(), DebugUtils.COLOR_WHITE);
        }
    }

    public static void refreshIfEnabled(@Nonnull PlayerRef player, @Nonnull HubPlotManager manager) {
        if (HubPlotVisibilityState.isEnabled(player.getUuid())) {
            showAll(player, manager);
        }
    }

    public static void refreshIfEnabled(@Nonnull PlayerRef player, @Nonnull World world, @Nonnull EterniaModPlugin plugin) {
        refreshIfEnabled(player, EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin));
    }
}
