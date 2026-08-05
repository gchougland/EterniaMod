package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hexvane.eterniamod.hub.HubPlotBoundaryWireframe;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3f;

public final class BuildingPlacementWireframeOverlay {
    private static final float OUTLINE_DISPLAY_SECONDS = 6f * 60f * 60f;
    private static final double LINE_THICKNESS = 0.04;
    private static final int LINE_FLAGS = DebugUtils.FLAG_NO_WIREFRAME;

    private BuildingPlacementWireframeOverlay() {}

    public static void clearFor(@Nullable PlayerRef player) {
        if (player == null) {
            return;
        }
        player.getPacketHandler().write(new ClearDebugShapes());
    }

    public static void send(
        @Nonnull PlayerRef player,
        @Nonnull HubPlotFootprint plotFootprint,
        @Nonnull HubPlotFootprint buildingFootprint,
        boolean buildingValid
    ) {
        clearFor(player);
        addPlotVisualBounds(player, plotFootprint, DebugUtils.COLOR_WHITE);
        Vector3f buildingColor = buildingValid ? DebugUtils.COLOR_WHITE : DebugUtils.COLOR_RED;
        addBoxEdges(player, buildingFootprint, buildingColor);
    }

    private static void addPlotVisualBounds(
        @Nonnull PlayerRef player,
        @Nonnull HubPlotFootprint plotFootprint,
        @Nonnull Vector3f color
    ) {
        HubPlotBoundaryWireframe.sendPlotBoundary(player, plotFootprint, color);
    }

    private static void addBoxEdges(@Nonnull PlayerRef player, @Nonnull HubPlotFootprint fp, @Nonnull Vector3f color) {
        double minX = fp.getMinX();
        double minY = fp.getMinY();
        double minZ = fp.getMinZ();
        double maxX = fp.getMaxX() + 1.0;
        double maxY = fp.getMaxY() + 1.0;
        double maxZ = fp.getMaxZ() + 1.0;
        sendLineCylinder(player, minX, minY, minZ, maxX, minY, minZ, color);
        sendLineCylinder(player, maxX, minY, minZ, maxX, minY, maxZ, color);
        sendLineCylinder(player, maxX, minY, maxZ, minX, minY, maxZ, color);
        sendLineCylinder(player, minX, minY, maxZ, minX, minY, minZ, color);
        sendLineCylinder(player, minX, maxY, minZ, maxX, maxY, minZ, color);
        sendLineCylinder(player, maxX, maxY, minZ, maxX, maxY, maxZ, color);
        sendLineCylinder(player, maxX, maxY, maxZ, minX, maxY, maxZ, color);
        sendLineCylinder(player, minX, maxY, maxZ, minX, maxY, minZ, color);
        sendLineCylinder(player, minX, minY, minZ, minX, maxY, minZ, color);
        sendLineCylinder(player, maxX, minY, minZ, maxX, maxY, minZ, color);
        sendLineCylinder(player, maxX, minY, maxZ, maxX, maxY, maxZ, color);
        sendLineCylinder(player, minX, minY, maxZ, minX, maxY, maxZ, color);
    }

    private static void sendLineCylinder(
        @Nonnull PlayerRef player,
        double startX,
        double startY,
        double startZ,
        double endX,
        double endY,
        double endZ,
        @Nonnull Vector3f color
    ) {
        double dirX = endX - startX;
        double dirY = endY - startY;
        double dirZ = endZ - startZ;
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        Matrix4d matrix = DebugLineCylinderUtil.segmentMatrix(startX, startY, startZ, endX, endY, endZ, LINE_THICKNESS, length);
        if (matrix == null) {
            return;
        }
        player
            .getPacketHandler()
            .write(
                new DisplayDebug(
                    DebugShape.Cylinder,
                    Matrix4dUtil.asFloatData(matrix),
                    color,
                    OUTLINE_DISPLAY_SECONDS,
                    (byte) LINE_FLAGS,
                    null,
                    DebugUtils.DEFAULT_OPACITY
                )
            );
    }
}
