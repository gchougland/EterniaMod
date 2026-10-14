package com.hexvane.eterniamod.runtime;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.*;

/** Load both columns and the actual vertical sections inspected by a safe arrival search.
 * Since Hytale 0.7.0 a section starts idle whatever its column does, so each one is asked to simulate too. */
public final class TravelLanding {
    private TravelLanding(){}
    public static CompletableFuture<Void> load(World world,Vector3d point,int radius){
        int minX=Math.floorDiv((int)Math.floor(point.x)-radius,32),maxX=Math.floorDiv((int)Math.floor(point.x)+radius,32);
        int minZ=Math.floorDiv((int)Math.floor(point.z)-radius,32),maxZ=Math.floorDiv((int)Math.floor(point.z)+radius,32);
        int minY=Math.floorDiv(Math.max(0,(int)Math.floor(point.y)-4),32),maxY=Math.floorDiv(Math.min(319,(int)Math.floor(point.y)+4),32);
        var columns=new ArrayList<CompletableFuture<?>>();
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)columns.add(world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(x,z),GetChunkFlags.SET_TICKING));
        return CompletableFuture.allOf(columns.toArray(CompletableFuture[]::new)).thenCompose(v->{
            var sections=new ArrayList<CompletableFuture<?>>();for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++)sections.add(world.getChunkStore().getChunkSectionReferenceAsync(x,y,z,GetChunkFlags.SET_TICKING));
            return CompletableFuture.allOf(sections.toArray(CompletableFuture[]::new));
        });
    }
}
