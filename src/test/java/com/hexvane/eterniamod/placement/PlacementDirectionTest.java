package com.hexvane.eterniamod.placement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.placement.BuildingPlacementNudgeUtil.*;

class PlacementDirectionTest {
    @Test void forwardFollowsNearestLookDirectionIncludingAlmostEast(){
        assertArrayEquals(new int[]{0,-1},horizontalStep(false,0,Horizontal.NEG_Z));
        assertArrayEquals(new int[]{1,0},horizontalStep(false,(float)Math.toRadians(-80),Horizontal.NEG_Z));
        assertArrayEquals(new int[]{0,1},horizontalStep(false,(float)Math.PI,Horizontal.NEG_Z));
        assertArrayEquals(new int[]{-1,0},horizontalStep(false,(float)Math.PI/2,Horizontal.NEG_Z));
        assertArrayEquals(new int[]{0,-1},horizontalStep(false,(float)(2*Math.PI),Horizontal.NEG_Z));
    }
    @Test void sidewaysRemainsPerpendicularAndBackwardsOpposite(){
        for(int degrees=-360;degrees<=360;degrees++){
            float yaw=(float)Math.toRadians(degrees);
            var f=horizontalStep(false,yaw,Horizontal.NEG_Z);var r=horizontalStep(false,yaw,Horizontal.POS_X);
            assertEquals(1,Math.abs(f[0])+Math.abs(f[1]));assertEquals(0,f[0]*r[0]+f[1]*r[1]);
            assertArrayEquals(new int[]{-f[0],-f[1]},horizontalStep(false,yaw,Horizontal.POS_Z));
            assertArrayEquals(new int[]{-r[0],-r[1]},horizontalStep(false,yaw,Horizontal.NEG_X));
        }
    }
    @Test void overheadDirectionsIgnoreThePlayersBodyAndHead(){
        for(float yaw:new float[]{0,1,-1,3}){
            assertArrayEquals(new int[]{0,-1},horizontalStep(true,yaw,Horizontal.NEG_Z));
            assertArrayEquals(new int[]{1,0},horizontalStep(true,yaw,Horizontal.POS_X));
        }
    }
}
