package com.hexvane.eterniamod.debug;

import javax.annotation.Nullable;
import org.joml.Matrix4d;

/** Shared debug-cylinder segment matrix (same math as plot/wall wireframe overlays). */
public final class DebugLineCylinderUtil {
    private DebugLineCylinderUtil() {}

    @Nullable
    public static Matrix4d segmentMatrix(
        double startX,
        double startY,
        double startZ,
        double endX,
        double endY,
        double endZ,
        double thickness,
        double length
    ) {
        if (length < 0.001) {
            return null;
        }
        double dirX = endX - startX;
        double dirY = endY - startY;
        double dirZ = endZ - startZ;

        Matrix4d matrix = new Matrix4d();
        matrix.identity();
        matrix.translate(startX, startY, startZ);
        double angleY = Math.atan2(dirZ, dirX);
        // Match 0.5.0 DebugUtils.addLine (JOML rotate uses negated angles vs pre-0.5 rotateAxis).
        matrix.rotate(-(angleY + (Math.PI / 2)), 0.0, 1.0, 0.0);
        double horizontal = Math.sqrt(dirX * dirX + dirZ * dirZ);
        double angleX = Math.atan2(horizontal, dirY);
        matrix.rotate(-angleX, 1.0, 0.0, 0.0);
        matrix.translate(0.0, length / 2.0, 0.0);
        matrix.scale(thickness, length, thickness);
        return matrix;
    }
}
