package com.hexvane.eterniamod.setup;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;

/** Block models snap to quarter turns. Hytale heading zero faces negative Z. */
final class PortalFacing {
    private PortalFacing() {}
    static float toward(int blockX,int blockZ,double viewerX,double viewerZ,float fallbackYaw) {
        double dx=viewerX-(blockX+.5),dz=viewerZ-(blockZ+.5);
        return dx*dx+dz*dz<.0001?fallbackYaw:(float)Math.atan2(-dx,-dz);
    }
    static int rotation(float yaw) {
        if(!Float.isFinite(yaw))throw new IllegalArgumentException("Invalid portal direction");
        int steps=(int)Math.floorMod(Math.round(yaw/(Math.PI/2)),4L);
        var rotation=switch(steps){case 1->Rotation.Ninety;case 2->Rotation.OneEighty;case 3->Rotation.TwoSeventy;default->Rotation.None;};
        return RotationTuple.of(rotation,Rotation.None,Rotation.None).index();
    }
}
