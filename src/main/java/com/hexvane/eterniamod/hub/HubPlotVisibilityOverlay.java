package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.boundary.HexBorderPlanner;
import com.hexvane.eterniamod.boundary.HexViewers;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.joml.Vector3f;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Admin plot outline preview. Uses a fixed visual height; plot logic still ignores height for ownership. Hex plots
 * are coloured by relation to the viewer with the shared palette; rectangle plots stay white boxes.
 */
public final class HubPlotVisibilityOverlay {
    private HubPlotVisibilityOverlay() {}

    public static void clearFor(@Nullable PlayerRef player) {
        if (player == null) {
            return;
        }
        player.getPacketHandler().write(new ClearDebugShapes());
    }

    /** Hex plots drawn as the exact block staircase; the rest get six straight lines per hexagon side. */
    public static final int MAX_STAIRCASE_PLOTS = 4;
    public static final double STAIRCASE_RADIUS = 64;

    public static void showAll(@Nonnull PlayerRef player, @Nonnull HubPlotManager manager) {
        clearFor(player);
        World world = manager.getWorld();
        HexLandView view = world == null ? null : HexLand.view(world).orElse(null);
        HexLandView.Viewer viewer = HexViewers.viewer(player.getUuid());
        var position = player.getTransform().getPosition();
        List<HubPlotRecord> plots = new ArrayList<>(manager.listPlots());
        plots.sort(Comparator.comparingDouble(plot -> distance(plot, position.x, position.z)));
        int detailed = 0;
        for (HubPlotRecord plot : plots) {
            HubPlotFootprint footprint = plot.getFootprint();
            HexLandView.PlotInfo info = view == null ? null : view.plots().get(plot.getPlotId());
            if (footprint == null || !footprint.isHex() || info == null) {
                HubPlotBoundaryWireframe.sendPlotBoundary(player, footprint, DebugUtils.COLOR_WHITE);
                continue;
            }
            boolean staircase = detailed < MAX_STAIRCASE_PLOTS && distance(plot, position.x, position.z) <= STAIRCASE_RADIUS;
            if (staircase) detailed++;
            HubPlotBoundaryWireframe.sendHexPlot(player, footprint, color(HexBorderPlanner.relation(view, info, viewer).color()), staircase);
        }
    }

    /** Distance from (x, z) to the plot's bounding box, 0 inside. */
    private static double distance(HubPlotRecord plot, double x, double z) {
        HubPlotFootprint fp = plot.getFootprint();
        if (fp == null) return Double.MAX_VALUE;
        double dx = Math.max(Math.max(fp.getMinX() - x, 0), x - (fp.getMaxX() + 1.0));
        double dz = Math.max(Math.max(fp.getMinZ() - z, 0), z - (fp.getMaxZ() + 1.0));
        return Math.hypot(dx, dz);
    }

    static Vector3f color(int rgb) {
        return new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
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
