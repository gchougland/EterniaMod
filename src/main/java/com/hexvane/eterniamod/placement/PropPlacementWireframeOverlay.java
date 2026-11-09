package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import org.joml.Matrix4d;
import com.hexvane.eterniamod.hub.HubPlotBoundaryWireframe;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Plot boundary during prop placement, a thick outline of the decoration (green fits, red not) and its middle. */
public final class PropPlacementWireframeOverlay {
    private static final float OUTLINE_DISPLAY_SECONDS = 6f * 60f * 60f;
    private static final double EDGE_THICKNESS = 0.08;
    private static final double MARKER = 0.3;

    private PropPlacementWireframeOverlay() {}

    public static void clearFor(@Nullable PlayerRef player) {
        if (player == null) {
            return;
        }
        player.getPacketHandler().write(new ClearDebugShapes());
    }

    public static void send(
        @Nonnull PlayerRef player,
        @Nonnull HubPlotFootprint plotFootprint,
        @Nonnull HubPlotFootprint propFootprint,
        boolean placementValid
    ) {
        clearFor(player);
        HubPlotBoundaryWireframe.sendPlotBoundary(player, plotFootprint, DebugUtils.COLOR_WHITE);
        // A thick outline of the decoration's size (green fits, red does not) and a small marker where its middle lands.
        var color = placementValid ? DebugUtils.COLOR_LIME : DebugUtils.COLOR_RED;
        double x0 = propFootprint.getMinX(), y0 = propFootprint.getMinY(), z0 = propFootprint.getMinZ();
        double x1 = propFootprint.getMaxX() + 1.0, y1 = propFootprint.getMaxY() + 1.0, z1 = propFootprint.getMaxZ() + 1.0;
        double[][] corners = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] edge : edges) line(player, corners[edge[0]], corners[edge[1]], color);
        double cx = (x0 + x1) * 0.5, cz = (z0 + z1) * 0.5;
        Matrix4d marker = new Matrix4d().identity().translate(cx, y0 + MARKER / 2, cz).scale(MARKER, MARKER, MARKER);
        player.getPacketHandler().write(new DisplayDebug(DebugShape.Cube, Matrix4dUtil.asFloatData(marker), DebugUtils.COLOR_YELLOW,
            OUTLINE_DISPLAY_SECONDS, (byte) DebugUtils.FLAG_NO_WIREFRAME, null, 0.9f));
    }

    private static void line(PlayerRef player, double[] a, double[] b, org.joml.Vector3f color) {
        double length = Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
        Matrix4d matrix = DebugLineCylinderUtil.segmentMatrix(a[0], a[1], a[2], b[0], b[1], b[2], EDGE_THICKNESS, length);
        if (matrix == null) return;
        player.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder, Matrix4dUtil.asFloatData(matrix), color,
            OUTLINE_DISPLAY_SECONDS, (byte) DebugUtils.FLAG_NO_WIREFRAME, null, 1f));
    }
}
