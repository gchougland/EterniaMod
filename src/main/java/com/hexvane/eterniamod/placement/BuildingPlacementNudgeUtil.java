package com.hexvane.eterniamod.placement;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

public final class BuildingPlacementNudgeUtil {
    public enum Horizontal {
        NEG_Z,
        POS_Z,
        NEG_X,
        POS_X
    }

    private BuildingPlacementNudgeUtil() {}

    public static float getPlayerYawRadians(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
        if (tc == null) {
            return 0f;
        }
        return tc.getRotation().yaw();
    }

    public static void nudgeHorizontal(
        @Nonnull BuildingPlacementSession session, boolean birdsEye, float yawRadians, @Nonnull Horizontal kind
    ) {
        int dx = 0;
        int dz = 0;
        if (birdsEye) {
            switch (kind) {
                case NEG_Z -> {
                    dx = 0;
                    dz = -1;
                }
                case POS_Z -> {
                    dx = 0;
                    dz = 1;
                }
                case NEG_X -> {
                    dx = -1;
                    dz = 0;
                }
                case POS_X -> {
                    dx = 1;
                    dz = 0;
                }
            }
        } else {
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
