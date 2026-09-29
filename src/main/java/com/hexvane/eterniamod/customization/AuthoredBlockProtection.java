package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.ProvenanceService;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions;
import com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import java.io.IOException;
import java.util.*;

/** Catalog objects are edited only by custody-aware tools: breaking an unlock never yields materials. */
final class AuthoredBlockProtection {
    record Mask(NativeSnapshotStore.Bounds bounds, Set<String> cells) {
        boolean contains(int x,int y,int z) {
            return bounds.contains(x,y,z)&&(cells==null||cells.contains((x-bounds.minX())+","+(y-bounds.minY())+","+(z-bounds.minZ())));
        }
    }
    private final EterniaModPlugin plugin;
    // Snapshots are content addressed and immutable. A new descriptor/revision selects a new cache key.
    private final Map<String,Mask> cache=Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Mask> eldest){return size()>4096;}
    });
    AuthoredBlockProtection(EterniaModPlugin plugin){this.plugin=plugin;}
    boolean protectedCell(com.hypixel.hytale.server.core.universe.world.World world,int x,int y,int z) {
        if(!plugin.getInfrastructure().isHousing(world.getName()))return false;
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).findPlotContainingHorizontal(x,z);
        if(plot==null)return false;
        try {
            var instances=plugin.getServices().provenance().instances(plot.getPlotId());
            if(plot.hasBuilding()&&instances.stream().noneMatch(i->i.state().equals("PLACED")&&"HOUSE".equals(i.nativeData().get("kind"))))return true;
            for(var instance:instances)if(instance.state().equals("PLACED")&&mask(instance).contains(x,y,z))return true;
            return false;
        }catch(IOException|RuntimeException unavailable){return true;}
    }
    private Mask mask(ProvenanceService.Instance instance)throws IOException {
        String key=instance.id()+":"+instance.revision();Mask found=cache.get(key);if(found!=null)return found;
        var data=instance.nativeData();var snapshot=NativePlacementTransactions.snapshots(plugin).load(new SnapshotFiles.Saved(data.get("after"),data.get("afterHash")));
        // Paths use sparse cells so ordinary ground between bends remains editable. Other prefab
        // bounds conservatively include authored air and interned components as a single object.
        Mask loaded=new Mask(snapshot.bounds(),"PATH".equals(data.get("kind"))?Set.copyOf(CellEdits.index(snapshot.document(),"blocks").keySet()):null);
        cache.put(key,loaded);return loaded;
    }
}
