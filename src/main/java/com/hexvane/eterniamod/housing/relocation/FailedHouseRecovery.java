package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.HousingAccess;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.*;
import org.joml.Vector3i;

/** Finish an interrupted house only after proving the entire saved world image and custody.
 * Does not paste, remove, or replace any blocks or entities. World-thread only. */
public final class FailedHouseRecovery {
    private FailedHouseRecovery() {}
    private record Pose(Vector3i origin,Rotation yaw) {}

    public static boolean recover(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor) throws Exception {
        world.getEntityStore().getStore().assertThread();
        var services=plugin.getServices();var owner=HousingAccess.owner(plot);
        if(!plot.isOwnedBy(actor)&&(plot.getGuildOwnerUuid()==null||!services.guilds().can(actor,plot.getGuildOwnerUuid(),"housing.structure")))
            throw new IllegalStateException("You do not have permission to manage this house.");
        var property=services.housing().property(owner).orElseThrow();
        if(property.slot().state()!=HousingService.State.ACTIVE||!property.slot().propertyId().equals(plot.getPlotId())
            ||property.location()==null||!property.location().worldId().equals(world.getName()))return false;
        var pending=services.journal().unfinished().stream().filter(o->o.owner().equals(owner)).toList();
        if(pending.size()!=1)return false;
        var op=pending.getFirst();
        if(!op.kind().equals("PLACE_HOUSE")||!op.detail().equals(plot.getPlotId().toString())
            ||!Set.of(JournalService.State.RECOVERY_REQUIRED,JournalService.State.SNAPSHOT_READY,JournalService.State.WORLD_APPLIED).contains(op.state()))return false;
        var snapshots=NativePlacementTransactions.snapshots(plugin);
        var manifest=BsonDocument.parse(new String(snapshots.files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())),StandardCharsets.UTF_8));
        String source=manifest.getString("source").getValue();
        if(!source.startsWith("unlock:eternia:house/"))return false;
        String content=source.substring("unlock:".length()),catalog=content.substring("eternia:house/".length());
        if(!services.ownership().owns(owner,content))return false;
        UUID instance=UUID.fromString(manifest.getString("instance").getValue());
        var existing=services.provenance().find(instance).orElse(null);
        if(existing!=null&&(!existing.owner().equals(owner)||!existing.contentId().equals(content)||!existing.sourceReference().equals(source)
            ||!existing.propertyId().equals(plot.getPlotId())||!Set.of("PLACED","PACKED").contains(existing.state())))return false;
        if(services.provenance().instances(plot.getPlotId()).stream().anyMatch(i->!i.id().equals(instance)&&i.state().equals("PLACED")&&"HOUSE".equals(i.nativeData().get("kind"))))return false;
        var before=snapshots.load(new SnapshotFiles.Saved(manifest.getString("before").getValue(),manifest.getString("beforeHash").getValue()));
        var after=snapshots.load(new SnapshotFiles.Saved(manifest.getString("after").getValue(),manifest.getString("afterHash").getValue()));
        if(!before.bounds().equals(after.bounds())||!before.document().getArray("entities",new BsonArray()).isEmpty())return false;
        var bounds=after.bounds();
        for(int x=bounds.minX();x<bounds.maxX();x++)for(int z=bounds.minZ();z<bounds.maxZ();z++)
            if(!plot.getFootprint().containsHorizontal(x,z)||plugin.getInfrastructure().protectedColumn(world.getName(),x,z))return false;
        for(var entity:after.document().getArray("entities",new BsonArray()))if(!NativeDecorativeEntities.instance(entity).equals(instance))return false;
        Pose pose=pose(plugin,catalog,manifest,after);
        if(plot.hasBuilding()) {
            var house=plot.getBuilding();
            if(!catalog.equals(house.getBuildingId())||house.getAnchorX()!=pose.origin.x||house.getAnchorY()!=pose.origin.y||house.getAnchorZ()!=pose.origin.z||house.resolveRotationYaw()!=pose.yaw)return false;
        }
        if(existing!=null&&existing.state().equals("PLACED")&&(!after.file().reference().equals(existing.nativeData().get("after"))||!after.file().sha256().equals(existing.nativeData().get("afterHash"))))return false;
        // Verify before changing any ownership/plot metadata. Includes entity identity, contents and rotation.
        snapshots.verify(world,after,bounds.origin(),Set.of(instance),true);
        NativePlacementTransactions.flush(world,bounds);
        var data=Map.of("before",before.file().reference(),"beforeHash",before.file().sha256(),"after",after.file().reference(),"afterHash",after.file().sha256(),
            "kind","HOUSE","catalog",catalog,"yaw",pose.yaw.name(),"placedAt",manifest.getString("placedAt",new BsonString("0")).getValue());
        // Idempotent across interruption after provenance, plot saving, or the world acknowledgement.
        if(existing==null)services.provenance().recordVerifiedPlacement(instance,owner,
            UUID.fromString(manifest.getString("actor",new BsonString(actor.toString())).getValue()),plot.getPlotId(),content,source,data,"native-placement:"+op.id());
        else if(existing.state().equals("PACKED"))services.provenance().acknowledgeRestored(instance,existing.revision(),plot.getPlotId(),data);
        plot.setBuilding(new HubPlotBuilding(catalog,pose.origin.x,pose.origin.y,pose.origin.z,pose.yaw,List.of()));
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);manager.updatePlot(plot);manager.saveIfDirty();
        services.housing().updateBuildingPresent(owner,plot.getPlotId(),true);
        if(op.state()!=JournalService.State.WORLD_APPLIED)services.journal().advance(op.id(),op.revision(),JournalService.State.WORLD_APPLIED);
        NativePlacementTransactions.complete(plugin,op.id());
        plugin.getLogger().atInfo().log("Recovered house placement %s on property %s without changing its blocks or entities",op.id(),plot.getPlotId());
        return true;
    }

    private static Pose pose(EterniaModPlugin plugin,String catalog,BsonDocument manifest,NativeSnapshotStore.Snapshot after) {
        if(manifest.containsKey("originX")) {
            if(!manifest.getString("catalog").getValue().equals(catalog))throw new IllegalStateException("The saved house details do not match.");
            return new Pose(new Vector3i(manifest.getInt32("originX").getValue(),manifest.getInt32("originY").getValue(),manifest.getInt32("originZ").getValue()),Rotation.valueOf(manifest.getString("yaw").getValue()));
        }
        // Older manifests predate saved anchor/rotation. Infer only a unique exact template shape
        // from the immutable after image, never from nearby blocks or player-supplied coordinates.
        var definition=plugin.getBuildingCatalog().get(catalog);
        if(definition==null)throw new IllegalStateException("This house is missing from the housing catalog.");
        var buffer=PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath());
        if(buffer==null)throw new IllegalStateException("This house could not be loaded.");
        var cells=new HashMap<String,BsonDocument>();
        for(var value:after.document().getArray("blocks")){var b=value.asDocument();cells.put(key(b.getInt32("x").getValue(),b.getInt32("y").getValue(),b.getInt32("z").getValue()),b);}
        var matches=new ArrayList<Pose>();
        for(var yaw:Rotation.values()) {
            int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},max={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
            buffer.forEach(IPrefabBuffer.iterateAllColumns(),(x,y,z,b,h,s,r,f,t,fluid,level)->include(min,max,x,y,z),null,null,new PrefabBufferCall(new FastRandom(),PrefabRotation.fromRotation(yaw)));
            for(var entity:NativeDecorativeEntities.prepare(buffer,yaw,UUID.randomUUID(),catalog,true)) {
                var p=entity.getComponent(com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()).getPosition();
                include(min,max,(int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z));
            }
            var b=after.bounds();
            if(max[0]-min[0]+1!=b.maxX()-b.minX()||max[1]-min[1]+1!=b.maxY()-b.minY()||max[2]-min[2]+1!=b.maxZ()-b.minZ())continue;
            boolean[] match={true};
            buffer.forEach(IPrefabBuffer.iterateAllColumns(),(x,y,z,id,h,s,r,f,t,fluid,level)->{
                var saved=cells.get(key(x-min[0],y-min[1],z-min[2]));var block=BlockType.getAssetMap().getAsset(id);
                if(saved==null||block==null||!saved.getString("name").getValue().equals(block.getId())||saved.getInt32("rotation",new BsonInt32(0)).getValue()!=r||saved.getInt32("filler",new BsonInt32(0)).getValue()!=f)match[0]=false;
            },null,null,new PrefabBufferCall(new FastRandom(),PrefabRotation.fromRotation(yaw)));
            if(match[0])matches.add(new Pose(new Vector3i(b.minX()-min[0],b.minY()-min[1],b.minZ()-min[2]),yaw));
        }
        if(matches.size()!=1)throw new IllegalStateException("The saved house direction could not be confirmed. Nothing was changed.");
        return matches.getFirst();
    }
    private static String key(int x,int y,int z){return x+","+y+","+z;}
    private static void include(int[] min,int[] max,int x,int y,int z){min[0]=Math.min(min[0],x);min[1]=Math.min(min[1],y);min[2]=Math.min(min[2],z);max[0]=Math.max(max[0],x);max[1]=Math.max(max[1],y);max[2]=Math.max(max[2],z);}
}
