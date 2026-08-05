package com.hexvane.eterniamod.placement;

import javax.annotation.Nonnull;

public final class PropPlacementNudgeUtil {
    private PropPlacementNudgeUtil() {}

    public static void nudgeHorizontal(
        @Nonnull PropPlacementSession session,
        float yawRadians,
        @Nonnull BuildingPlacementNudgeUtil.Horizontal kind
    ) {
        int dx = 0;
        int dz = 0;
        double sin = Math.sin(yawRadians);
        double cos = Math.cos(yawRadians);
        switch (kind) {
            case NEG_Z -> {
                int[] xz = dominantUnitXZ(-sin, -cos);
                dx = xz[0];
                dz = xz[1];
            }
            case POS_Z -> {
                int[] xz = dominantUnitXZ(sin, cos);
                dx = xz[0];
                dz = xz[1];
            }
            case NEG_X -> {
                int[] xz = dominantUnitXZ(-cos, sin);
                dx = xz[0];
                dz = xz[1];
            }
            case POS_X -> {
                int[] xz = dominantUnitXZ(cos, -sin);
                dx = xz[0];
                dz = xz[1];
            }
        }
        session.nudge(dx, 0, dz);
    }

    @Nonnull
    private static int[] dominantUnitXZ(double fx, double fz) {
        if (Math.abs(fx) >= Math.abs(fz)) {
            if (fx > 1e-6) {
                return new int[] {1, 0};
            }
            if (fx < -1e-6) {
                return new int[] {-1, 0};
            }
            return new int[] {0, 0};
        }
        if (fz > 1e-6) {
            return new int[] {0, 1};
        }
        if (fz < -1e-6) {
            return new int[] {0, -1};
        }
        return new int[] {0, 0};
    }
}
