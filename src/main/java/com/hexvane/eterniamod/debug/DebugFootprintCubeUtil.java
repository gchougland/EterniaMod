package com.hexvane.eterniamod.debug;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import org.joml.Matrix4d;
import org.joml.Vector3f;

public final class DebugFootprintCubeUtil {
    private static final byte CUBE_FLAGS = (byte) DebugUtils.FLAG_NO_WIREFRAME;

    private DebugFootprintCubeUtil() {}

    public static void sendFootprintCube(
        @Nonnull PlayerRef player,
        @Nonnull HubPlotFootprint footprint,
        @Nonnull Vector3f color,
        float opacity,
        float displaySeconds
    ) {
        sendFootprintCube(player, footprint, color, opacity, displaySeconds, 0.0);
    }

    public static void sendFootprintCube(
        @Nonnull PlayerRef player,
        @Nonnull HubPlotFootprint footprint,
        @Nonnull Vector3f color,
        float opacity,
        float displaySeconds,
        double padding
    ) {
        double minX = footprint.getMinX() - padding;
        double minY = footprint.getMinY() - padding;
        double minZ = footprint.getMinZ() - padding;
        double maxX = footprint.getMaxX() + 1.0 + padding;
        double maxY = footprint.getMaxY() + 1.0 + padding;
        double maxZ = footprint.getMaxZ() + 1.0 + padding;
        double cx = (minX + maxX) * 0.5;
        double cy = (minY + maxY) * 0.5;
        double cz = (minZ + maxZ) * 0.5;
        double sx = maxX - minX;
        double sy = maxY - minY;
        double sz = maxZ - minZ;
        Matrix4d matrix = new Matrix4d();
        matrix.identity();
        matrix.translate(cx, cy, cz);
        matrix.scale(sx, sy, sz);
        player
            .getPacketHandler()
            .write(
                new DisplayDebug(
                    DebugShape.Cube,
                    Matrix4dUtil.asFloatData(matrix),
                    color,
                    displaySeconds,
                    CUBE_FLAGS,
                    null,
                    opacity
                )
            );
    }
}
