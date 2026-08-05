package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import org.joml.Matrix4d;
import org.joml.Vector3f;

/** Draws plot boundary boxes with edge outlines and a diagonal on each face for readability. */
public final class HubPlotBoundaryWireframe {
    private static final float OUTLINE_DISPLAY_SECONDS = 6f * 60f * 60f;
    private static final double LINE_THICKNESS = 0.04;
    private static final int LINE_FLAGS = DebugUtils.FLAG_NO_WIREFRAME;

    private HubPlotBoundaryWireframe() {}

    public static void sendPlotBoundary(
        @Nonnull PlayerRef player,
        @Nonnull HubPlotFootprint footprint,
        @Nonnull Vector3f color
    ) {
        double minX = footprint.getMinX();
        double minZ = footprint.getMinZ();
        double maxX = footprint.getMaxX() + 1.0;
        double maxZ = footprint.getMaxZ() + 1.0;
        double minY = footprint.getVisualMinY();
        double maxY = footprint.getVisualMaxY() + 1.0;
        sendBox(player, minX, minY, minZ, maxX, maxY, maxZ, color);
    }

    public static void sendBox(
        @Nonnull PlayerRef player,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        @Nonnull Vector3f color
    ) {
        addBoxEdges(player, minX, minY, minZ, maxX, maxY, maxZ, color);
        addFaceDiagonals(player, minX, minY, minZ, maxX, maxY, maxZ, color);
    }

    private static void addBoxEdges(
        @Nonnull PlayerRef player,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        @Nonnull Vector3f color
    ) {
        sendLine(player, minX, minY, minZ, maxX, minY, minZ, color);
        sendLine(player, maxX, minY, minZ, maxX, minY, maxZ, color);
        sendLine(player, maxX, minY, maxZ, minX, minY, maxZ, color);
        sendLine(player, minX, minY, maxZ, minX, minY, minZ, color);
        sendLine(player, minX, maxY, minZ, maxX, maxY, minZ, color);
        sendLine(player, maxX, maxY, minZ, maxX, maxY, maxZ, color);
        sendLine(player, maxX, maxY, maxZ, minX, maxY, maxZ, color);
        sendLine(player, minX, maxY, maxZ, minX, maxY, minZ, color);
        sendLine(player, minX, minY, minZ, minX, maxY, minZ, color);
        sendLine(player, maxX, minY, minZ, maxX, maxY, minZ, color);
        sendLine(player, maxX, minY, maxZ, maxX, maxY, maxZ, color);
        sendLine(player, minX, minY, maxZ, minX, maxY, maxZ, color);
    }

    /** One diagonal per face so overlapping plot edges remain distinguishable. */
    private static void addFaceDiagonals(
        @Nonnull PlayerRef player,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        @Nonnull Vector3f color
    ) {
        sendLine(player, minX, minY, minZ, maxX, minY, maxZ, color);
        sendLine(player, minX, maxY, minZ, maxX, maxY, maxZ, color);
        sendLine(player, minX, minY, minZ, minX, maxY, maxZ, color);
        sendLine(player, maxX, minY, minZ, maxX, maxY, maxZ, color);
        sendLine(player, minX, minY, minZ, maxX, maxY, minZ, color);
        sendLine(player, minX, maxY, maxZ, maxX, minY, maxZ, color);
    }

    private static void sendLine(
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
