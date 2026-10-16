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
        var head=store.getComponent(ref,com.hypixel.hytale.server.core.modules.entity.component.HeadRotation.getComponentType());
        if(head!=null)return head.getRotation().yaw();
        TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
        if (tc == null) {
            return 0f;
        }
        return tc.getRotation().yaw();
    }

    public static void nudgeHorizontal(
        @Nonnull BuildingPlacementSession session, boolean birdsEye, float yawRadians, @Nonnull Horizontal kind
    ) {
        int[] direction=horizontalStep(birdsEye,yawRadians,kind);
        session.nudge(direction[0],0,direction[1]);
    }

    public static int[] horizontalStep(boolean birdsEye,float yawRadians,Horizontal kind){
        if(birdsEye)return switch(kind){
            case NEG_Z -> new int[]{0,-1};case POS_Z -> new int[]{0,1};
            case NEG_X -> new int[]{-1,0};case POS_X -> new int[]{1,0};
        };
        double sin=Math.sin(yawRadians),cos=Math.cos(yawRadians);
        // Choose the forward cardinal once so left/right stay perpendicular at diagonal boundaries.
        int[] forward=dominantUnitXZ(-sin,-cos);
        return switch(kind){
            case NEG_Z -> forward;case POS_Z -> new int[]{-forward[0],-forward[1]};
            case NEG_X -> new int[]{forward[1],-forward[0]};case POS_X -> new int[]{-forward[1],forward[0]};
        };
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
