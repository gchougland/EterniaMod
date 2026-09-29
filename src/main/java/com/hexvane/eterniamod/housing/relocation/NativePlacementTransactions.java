package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.*;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.*;
import org.joml.Vector3i;

/** Journalled native placement and pickup; physical token metadata never conveys ownership. */
public final class NativePlacementTransactions {
    private NativePlacementTransactions() {}
    public static Owner owner(HubPlotRecord plot){return plot.getGuildOwnerUuid()!=null?Owner.guild(plot.getGuildOwnerUuid()):Owner.player(Objects.requireNonNull(plot.getOwnerUuid()));}
    public static NativeSnapshotStore snapshots(EterniaModPlugin plugin){return new NativeSnapshotStore(plugin.getDataDirectory().resolve("housing-snapshots"));}
    public static void requireActive(EterniaModPlugin plugin,HubPlotRecord plot) {
        var slot=plugin.getServices().housing().find(owner(plot)).orElseThrow(()->new IllegalStateException("Legacy property needs provenance migration"));
        if(slot.state()!=HousingService.State.ACTIVE||!slot.propertyId().equals(plot.getPlotId()))throw new IllegalStateException("Property is locked by another operation");
        if(plugin.getServices().journal().unfinished().stream().anyMatch(o->o.owner().equals(owner(plot))&&!o.state().equals(JournalService.State.PACKED)))throw new IllegalStateException("Unfinished native operation requires recovery");
    }
    public record Placed(UUID instanceId,NativeSnapshotStore.Snapshot before,NativeSnapshotStore.Snapshot after,UUID operation,Owner contentOwner,String source) {}
    public static Placed place(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor,String catalogId,Vector3i origin,Rotation yaw,IPrefabBuffer buffer,boolean house) throws IOException {
        return place(plugin,world,plot,actor,catalogId,origin,yaw,buffer,house,owner(plot));
    }
    /** Explicit source owner enables a permitted guild quantity to remain guild-owned on an attached member plot. */
    public static Placed place(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor,String catalogId,Vector3i origin,Rotation yaw,IPrefabBuffer buffer,boolean house,Owner custodyOwner) throws IOException {
        requireActive(plugin,plot);Owner owner=owner(plot);String content=(house?"eternia:house/":"eternia:prop/")+catalogId;
        if(!com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,actor,house?"housing.structure":"housing.prop.place"))throw new IllegalStateException("Housing permission required");
        if(house&&!custodyOwner.equals(owner))throw new IllegalStateException("House unlock must belong to the property owner");
        if(!house)com.hexvane.eterniamod.housing.HousingCustody.require(plugin,plot,actor,custodyOwner,com.hexvane.eterniamod.housing.HousingCustody.PLACE);
        if(house&&(!plugin.getServices().ownership().owns(owner,content)||plot.hasBuilding()))throw new IllegalStateException("House unlock required and only one house may be placed");
        var packed=plugin.getServices().provenance().ownedInstances(custodyOwner).stream().filter(i->i.contentId().equals(content)&&i.state().equals("PACKED")).findFirst().orElse(null);
        var nativeStore=snapshots(plugin);UUID operation=UUID.randomUUID(),instance=packed!=null?packed.id():UUID.randomUUID();
        var decorativeEntities=NativeDecorativeEntities.prepare(buffer,yaw,instance,catalogId,house);
        int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},max={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        var call=new PrefabBufferCall(new FastRandom(),PrefabRotation.fromRotation(yaw));
        buffer.forEach(IPrefabBuffer.iterateAllColumns(),(x,y,z,b,h,s,r,f,t,fluid,level)->{
            if(!house&&b==0&&f==0&&fluid==0)return;
            int wx=Math.addExact(origin.x,x),wz=Math.addExact(origin.z,z);
            if(!plot.getFootprint().containsHorizontal(wx,wz)||plugin.getInfrastructure().protectedColumn(world.getName(),wx,wz))throw new IllegalStateException("Prefab crosses plot or protected infrastructure");
            min[0]=Math.min(min[0],x);min[1]=Math.min(min[1],y);min[2]=Math.min(min[2],z);max[0]=Math.max(max[0],x);max[1]=Math.max(max[1],y);max[2]=Math.max(max[2],z);
        },null,null,call);
        for(var holder:decorativeEntities){
            var p=holder.getComponent(com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()).getPosition();
            int x=(int)Math.floor(p.x),y=(int)Math.floor(p.y),z=(int)Math.floor(p.z);
            if(!plot.getFootprint().containsHorizontal(origin.x+x,origin.z+z)||plugin.getInfrastructure().protectedColumn(world.getName(),origin.x+x,origin.z+z))throw new IllegalStateException("Decorative entity crosses plot or protected infrastructure");
            min[0]=Math.min(min[0],x);min[1]=Math.min(min[1],y);min[2]=Math.min(min[2],z);max[0]=Math.max(max[0],x);max[1]=Math.max(max[1],y);max[2]=Math.max(max[2],z);
        }
        if(min[0]==Integer.MAX_VALUE)throw new IllegalStateException("Empty prefab");
        var bounds=new NativeSnapshotStore.Bounds(origin.x+min[0],origin.y+min[1],origin.z+min[2],origin.x+max[0]+1,origin.y+max[1]+1,origin.z+max[2]+1);
        // Existing entities are never overwritten as a side effect of an ordinary placement.
        var before=nativeStore.save(operation+"-before.json",bounds,nativeStore.capture(world,bounds,Set.of(),true,false));
        BlockSelection desired=SelectionPrefabSerializer.deserialize(before.document());
        buffer.forEach(IPrefabBuffer.iterateAllColumns(),(x,y,z,b,h,s,r,f,t,fluid,level)->{
            if(!house&&b==0&&f==0&&fluid==0)return;
            var block=BlockType.getAssetMap().getAsset(b);
            if(block==null)throw new IllegalStateException("Unknown prefab block asset");
            var holder=h!=null?h.clone():f==0&&block.getBlockEntity()!=null?block.getBlockEntity().clone():null;
            if(holder!=null)NativeSnapshotStore.assertLossless(holder,ChunkStore.REGISTRY);
            desired.addBlockAtLocalPos(x-min[0],y-min[1],z-min[2],b,r,f,s,holder);
            desired.addFluidAtLocalPos(x-min[0],y-min[1],z-min[2],Math.max(0,fluid),(byte)level);
        },null,null,new PrefabBufferCall(new FastRandom(),PrefabRotation.fromRotation(yaw)));
        for(var entity:decorativeEntities){entity.getComponent(com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()).getPosition().sub(min[0],min[1],min[2]);desired.addEntityHolderRaw(entity);}
        desired.sortEntitiesByPosition();
        BsonDocument desiredDocument=SelectionPrefabSerializer.serialize(desired);
        if(packed!=null) {
            var saved=nativeStore.load(NativeRelocationCoordinator.parseReference(packed.snapshotRef()));
            if(!yaw.name().equals(packed.nativeData().get("yaw"))||saved.bounds().maxX()-saved.bounds().minX()!=bounds.maxX()-bounds.minX()||saved.bounds().maxY()-saved.bounds().minY()!=bounds.maxY()-bounds.minY()||saved.bounds().maxZ()-saved.bounds().minZ()!=bounds.maxZ()-bounds.minZ())throw new IllegalStateException("Packed object must retain its original orientation and catalog bounds");
            desiredDocument=before.document().clone();NativeRelocationCoordinator.overlay(desiredDocument,saved.document());
        }
        var after=nativeStore.save(operation+"-after.json",bounds,desiredDocument);
        var journal=plugin.getServices().journal();
        var op=journal.prepare(operation,house?"PLACE_HOUSE":"PLACE_PROP",owner,plot.getPlotId().toString());
        String source;
        try {source=packed!=null?packed.sourceReference():house?"unlock:"+content:plugin.getServices().ownership().reserve(custodyOwner,content,1,"native-place:"+operation).id();}
        catch(RuntimeException failure){journal.advance(operation,op.revision(),JournalService.State.CANCELLED);throw failure;}
        var manifest=new BsonDocument("before",new BsonString(before.file().reference())).append("beforeHash",new BsonString(before.file().sha256())).append("after",new BsonString(after.file().reference())).append("afterHash",new BsonString(after.file().sha256())).append("instance",new BsonString(instance.toString())).append("source",new BsonString(source));
        try {
            var manifestFile=nativeStore.files().write(operation+"-placement.json",manifest.toJson().getBytes(StandardCharsets.UTF_8));
            op=journal.attachVerifiedSnapshot(operation,op.revision(),manifestFile.reference(),manifestFile.sha256());
        }catch(IOException|RuntimeException failure){
            if(!house&&packed==null)plugin.getServices().ownership().releaseReservation(source);
            var current=journal.find(operation).orElseThrow();journal.advance(operation,current.revision(),JournalService.State.CANCELLED);throw failure;
        }
        try {
            nativeStore.apply(world,after,bounds.origin());nativeStore.verify(world,after,bounds.origin(),Set.of(instance),true);flush(world,bounds);
            var data=Map.of("before",before.file().reference(),"beforeHash",before.file().sha256(),"after",after.file().reference(),"afterHash",after.file().sha256(),"kind",house?"HOUSE":"PROP","catalog",catalogId,"yaw",yaw.name(),"placedAt",Long.toString(System.currentTimeMillis()));
            if(packed==null)plugin.getServices().provenance().recordVerifiedPlacement(instance,custodyOwner,actor,plot.getPlotId(),content,source,data,"native-placement:"+operation);
            else plugin.getServices().provenance().acknowledgeRestored(instance,packed.revision(),plot.getPlotId(),data);
            journal.advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);
            return new Placed(instance,before,after,operation,custodyOwner,source);
        } catch(Throwable failure){lock(plugin,operation);throw new IllegalStateException("Placement is locked for recovery; snapshot retained",failure);}
    }
    /** Returns native custody to the build inventory. The quantity reservation remains held by the packed instance. */
    public static UUID pickup(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID instanceId) throws IOException {
        return pickup(plugin,world,plot,null,instanceId);
    }
    /** Player-facing callers pass the actor and re-resolve the original inventory owner at the instant of pickup. */
    public static UUID pickup(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor,UUID instanceId) throws IOException {
        if(instanceId.equals(plot.getPlotId()))instanceId=plugin.getServices().provenance().instances(plot.getPlotId()).stream().filter(i->i.state().equals("PLACED")&&"HOUSE".equals(i.nativeData().get("kind"))).map(ProvenanceService.Instance::id).findFirst().orElseThrow(()->new IllegalStateException("House provenance missing"));
        requireActive(plugin,plot);var services=plugin.getServices();var item=services.provenance().find(instanceId).orElseThrow(()->new IllegalStateException("Unproven legacy object cannot be packaged"));
        if(!item.state().equals("PLACED")||!item.propertyId().equals(plot.getPlotId()))throw new IllegalStateException("Placed object provenance does not match this property");
        if(actor==null){if(!item.owner().equals(owner(plot)))throw new IllegalStateException("Guild custody pickup requires the authorized actor");}
        else if("HOUSE".equals(item.nativeData().get("kind"))){if(!item.owner().equals(owner(plot))||!com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,actor,"housing.structure"))throw new IllegalStateException("House ownership and structure permission are required");}
        else com.hexvane.eterniamod.housing.HousingCustody.require(plugin,plot,actor,item.owner(),com.hexvane.eterniamod.housing.HousingCustody.PACK);
        var store=snapshots(plugin);var before=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash")));var b=before.bounds();
        for(var other:services.provenance().instances(plot.getPlotId()))if(!other.id().equals(instanceId)&&other.state().equals("PLACED")&&Long.parseLong(other.nativeData().getOrDefault("placedAt","9223372036854775807"))>=Long.parseLong(item.nativeData().getOrDefault("placedAt","0"))) {
            if("HOUSE".equals(item.nativeData().get("kind"))){var definition=plugin.getPropCatalog().get(other.nativeData().get("catalog"));if(definition!=null&&definition.getCategory().equals("addition"))throw new IllegalStateException("Package house additions first, or move the whole plot");}
            var otherBefore=store.load(new SnapshotFiles.Saved(other.nativeData().get("before"),other.nativeData().get("beforeHash")));var o=otherBefore.bounds();
            if(b.minX()<o.maxX()&&o.minX()<b.maxX()&&b.minY()<o.maxY()&&o.minY()<b.maxY()&&b.minZ()<o.maxZ()&&o.minZ()<b.maxZ())throw new IllegalStateException("Package overlapping objects first, or move the whole plot");
        }
        var placedAfter=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));
        var mask=NativeRelocationCoordinator.difference(placedAfter.document(),before.document());
        var captured=store.capture(world,b,Set.of(instanceId),true,false);
        NativeDecorativeEntities.requireIdentity(placedAfter.document(),captured,instanceId);
        NativeRelocationCoordinator.requireSameBlocks(captured,placedAfter.document(),mask);
        if(item.owner().kind()==Owner.Kind.GUILD&&!item.owner().equals(owner(plot)))NativeRelocationCoordinator.requireEmptyGuildContainers(NativeRelocationCoordinator.onlyCapturedCells(captured,mask));
        UUID operation=UUID.randomUUID();var packed=store.save(operation+"-pickup.json",b,NativeRelocationCoordinator.onlyCapturedCells(captured,mask));
        var restore=store.save(operation+"-pickup-before.json",b,NativeRelocationCoordinator.onlyCapturedCells(before.document(),mask));
        // Recovery locks the edited property; provenance retains the distinct inventory owner.
        var op=services.journal().prepare(operation,"PICKUP",owner(plot),plot.getPlotId().toString());op=services.journal().attachVerifiedSnapshot(operation,op.revision(),packed.file().reference(),packed.file().sha256());
        try {
            for(var ref:NativeSnapshotStore.entities(world,b)){
                var link=world.getEntityStore().getStore().getComponent(ref,EterniaPlacedInstance.getComponentType());
                if(link!=null&&link.getInstanceId().equals(instanceId))NativeDecorativeEntities.remove(world,ref);
            }
            store.apply(world,restore,b.origin());store.verify(world,restore,b.origin(),Set.of(),false);flush(world,b);
            services.provenance().acknowledgePacked(instanceId,item.revision(),NativeRelocationCoordinator.reference(packed.file()));
            services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);return operation;
        }catch(Throwable failure){lock(plugin,operation);throw new IllegalStateException("Pickup is locked for recovery; saved contents retained",failure);}
    }
    /** Must run after the native plot metadata was successfully saved. */
    public static void complete(EterniaModPlugin plugin,UUID operation){var op=plugin.getServices().journal().find(operation).orElseThrow();plugin.getServices().journal().advance(operation,op.revision(),JournalService.State.COMPLETED);}
    public static void lock(EterniaModPlugin plugin,UUID operation){var j=plugin.getServices().journal();var op=j.find(operation).orElse(null);if(op!=null&&op.state()!=JournalService.State.RECOVERY_REQUIRED&&op.state()!=JournalService.State.COMPLETED)j.advance(operation,op.revision(),JournalService.State.RECOVERY_REQUIRED);}
    public static void flush(World world,NativeSnapshotStore.Bounds b) throws IOException {
        var saver=world.getChunkStore().getSaver();if(saver==null)throw new IOException("World has no durable chunk saver");
        NativeDecorativeEntities.flushBodies(world,b);
        Set<Long> saved=new HashSet<>();
        for(int x=b.minX();x<b.maxX();x+=Math.min(16,b.maxX()-x))for(int z=b.minZ();z<b.maxZ();z+=Math.min(16,b.maxZ()-z)){
            var chunk=ChunkSectionBlockUtil.worldChunkIfInMemory(world,x,z);if(chunk==null)throw new IOException("Affected chunk unloaded before flush");
            long key=((long)chunk.getX()<<32)^(chunk.getZ()&0xffffffffL);
            if(saved.add(key))saver.saveChunkColumn(chunk.getX(),chunk.getZ(),world.getChunkStore().getStore(),chunk.getReference(),null,null).join();
        }
        // Include a final partial chunk when the region starts in the middle of a column.
        for(int x:b.minX()==b.maxX()-1?new int[]{b.minX()}:new int[]{b.minX(),b.maxX()-1})for(int z:new int[]{b.minZ(),b.maxZ()-1}) {
            var chunk=ChunkSectionBlockUtil.worldChunkIfInMemory(world,x,z);long key=((long)chunk.getX()<<32)^(chunk.getZ()&0xffffffffL);
            if(saved.add(key))saver.saveChunkColumn(chunk.getX(),chunk.getZ(),world.getChunkStore().getStore(),chunk.getReference(),null,null).join();
        }
        saver.flush();
    }
}
