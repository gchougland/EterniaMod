package com.hexvane.eterniamod.placement;

public final class PropPlacementNudgeUtil {
    private PropPlacementNudgeUtil(){}
    public static void nudgeHorizontal(PropPlacementSession session,float yaw,BuildingPlacementNudgeUtil.Horizontal kind){nudgeHorizontal(session,false,yaw,kind);}
    public static void nudgeHorizontal(PropPlacementSession session,boolean birdsEye,float yaw,BuildingPlacementNudgeUtil.Horizontal kind){
        int[] step=BuildingPlacementNudgeUtil.horizontalStep(birdsEye,yaw,kind);session.nudge(step[0],0,step[1]);
    }
}
