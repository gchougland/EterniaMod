package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3i;

public final class NativeHousingChecks {
    private NativeHousingChecks() {}
    public static PlotRect rect(HubPlotFootprint f) { return new PlotRect(f.getMinX(),f.getMinZ(),f.getMaxX()-f.getMinX()+1,f.getMaxZ()-f.getMinZ()+1); }
    /** Check every mutation, including carved air and multi-block companions, before consuming an item. */
    public static String mutation(World world, HubPlotRecord plot, Vector3i origin, Rotation yaw, IPrefabBuffer buffer, boolean carveAir) {
        EterniaModPlugin plugin=EterniaModPlugin.get();
        if (plugin==null) return "unavailable";
        String[] reason={null};
        buffer.forEach(IPrefabBuffer.iterateAllColumns(),(x,y,z,blockId,holder,support,rotation,filler,t,fluidId,fluidLevel)->{
            if (reason[0]!=null || !carveAir && blockId==0 && filler==0) return;
            int wx=Math.addExact(origin.x,x), wy=Math.addExact(origin.y,y), wz=Math.addExact(origin.z,z);
            if (!plot.getFootprint().containsHorizontal(wx,wz)) reason[0]="outsidePlot";
            else if (plugin.getInfrastructure().protectedColumn(world.getName(),wx,wz)) reason[0]="roadProtected";
            else if (ChunkSectionBlockUtil.sectionRefAt(world,wx,wy,wz)==null) reason[0]="unloaded";
        },null,null,new PrefabBufferCall(new FastRandom(),PrefabRotation.fromRotation(yaw)));
        return reason[0];
    }
}
