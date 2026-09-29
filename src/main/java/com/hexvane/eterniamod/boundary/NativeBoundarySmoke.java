package com.hexvane.eterniamod.boundary;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;

public final class NativeBoundarySmoke {
    private NativeBoundarySmoke() {}
    public static void validate(World world) {
        BoundarySurfacePolicy policy=(w,x,z)->x>=5;
        var sampler=new BoundaryGroundSampler(policy);
        for(var sample:new BoundaryGeometry.Sample[]{new BoundaryGeometry.Sample(0,3,0,0),new BoundaryGeometry.Sample(6,3,0,0),new BoundaryGeometry.Sample(3,0,0,0),new BoundaryGeometry.Sample(3,6,0,0)})
            if(!Double.isFinite(sampler.sample(world,sample)))throw new IllegalStateException("A plot side was erased by infrastructure clipping");
        if(!policy.isGround(world,6,0,3,BlockType.getAssetMap().getAsset("Rock_Stone_Cobble")))throw new IllegalStateException("Public paving cannot support the cosmetic border");
    }
}
