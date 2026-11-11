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
    public static void requireActive(EterniaModPlugin plugin,HubPlotRecord plot) {plugin.getServices().batch(()->{requireActiveInBatch(plugin,plot);return null;});}
    private static void requireActiveInBatch(EterniaModPlugin plugin,HubPlotRecord plot) {
        var slot=plugin.getServices().housing().find(owner(plot)).orElseThrow(()->new IllegalStateException("Legacy property needs provenance migration"));
        if(slot.state()!=HousingService.State.ACTIVE||!slot.propertyId().equals(plot.getPlotId()))throw new IllegalStateException("Property is locked by another operation");
        if(plugin.getServices().journal().unfinished().stream().anyMatch(o->o.owner().equals(owner(plot))&&!o.state().equals(JournalService.State.PACKED)))throw new IllegalStateException("Unfinished native operation requires recovery");
    }
    /**
     * Resolves pickups that stopped part way, so a plot never stays locked by one. For each locked pickup on this plot:
     * if every placed object still matches the world exactly, the pickup changed nothing and is cancelled; if exactly
     * one object no longer matches, that is the one being packed, so packing is finished from the snapshot saved before
     * anything changed and the object goes back to its owner's inventory. Anything else stays locked. World thread.
     * Returns the number of pickups resolved.
     */
    public static int healPickups(EterniaModPlugin plugin,World world,HubPlotRecord plot){
        var services=plugin.getServices();Owner owner=owner(plot);String property=plot.getPlotId().toString();
        var locked=services.journal().unfinished().stream().filter(o->o.owner().equals(owner)&&o.kind().equals("PICKUP")&&o.detail().equals(property)&&o.state()==JournalService.State.RECOVERY_REQUIRED).toList();
        int resolved=0;
        for(var op:locked){
            try{
                var current=services.journal().find(op.id()).orElseThrow();
                var target=target(plugin,current);
                if(target!=null){
                    var item=services.provenance().find(target).orElse(null);
                    // Already packed, or no longer on this plot: nothing in the world is waiting on this pickup.
                    if(item==null||!item.state().equals("PLACED")||!item.propertyId().equals(plot.getPlotId())){services.journal().advance(op.id(),current.revision(),JournalService.State.CANCELLED);resolved++;continue;}
                    if(!changed(plugin,world,item)){services.journal().advance(op.id(),current.revision(),JournalService.State.CANCELLED);resolved++;continue;}
                    finishPickup(plugin,world,plot,current,item);resolved++;continue;
                }
                var changed=changedObjects(plugin,world,plot);
                if(changed.isEmpty()){services.journal().advance(op.id(),current.revision(),JournalService.State.CANCELLED);resolved++;continue;}
                if(changed.size()!=1||op.snapshotRef().isEmpty())continue;
                finishPickup(plugin,world,plot,current,changed.getFirst());resolved++;
            }catch(Exception failure){plugin.getLogger().atWarning().withCause(failure).log("Could not resolve pickup %s",op.id());}
        }
        if(resolved>0)plugin.getLogger().atInfo().log("Resolved %d interrupted pickups on plot %s",resolved,property);
        return resolved;
    }
    /** The object a pickup was packing, read from the snapshot it saved before changing anything; null when unknown. */
    private static UUID target(EterniaModPlugin plugin,JournalService.Operation op){
        if(op.snapshotRef().isEmpty())return null;
        try{
            var packed=snapshots(plugin).load(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash()));
            var found=new HashSet<UUID>();
            for(var value:packed.document().getArray("entities",new org.bson.BsonArray()))try{found.add(NativeDecorativeEntities.instance(value));}catch(IllegalStateException unlinked){}
            return found.size()==1?found.iterator().next():null;
        }catch(Exception unreadable){return null;}
    }
    /** Placed objects on the plot whose blocks or entities no longer match how they were placed. */
    private static List<ProvenanceService.Instance> changedObjects(EterniaModPlugin plugin,World world,HubPlotRecord plot) throws IOException {
        var changed=new ArrayList<ProvenanceService.Instance>();
        for(var item:plugin.getServices().provenance().instances(plot.getPlotId()))
            if(item.state().equals("PLACED")&&item.nativeData().containsKey("after")&&changed(plugin,world,item))changed.add(item);
        return changed;
    }
    /**
     * Whether a placed object's blocks or its own entities differ from how it was placed. Other objects' entities in the
     * same area (a shelf inside a house) are ignored. An unloaded chunk throws rather than answering.
     */
    private static boolean changed(EterniaModPlugin plugin,World world,ProvenanceService.Instance item) throws IOException {
        var store=snapshots(plugin);
        var before=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash")));
        var placedAfter=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));
        var mask=NativeRelocationCoordinator.difference(placedAfter.document(),before.document());
        var captured=store.capture(world,before.bounds(),before.mask(),Set.of(),false,false);
        try{NativeRelocationCoordinator.requireSameBlocks(captured,placedAfter.document(),mask);}catch(IllegalStateException differs){return true;}
        return !entityIds(placedAfter.document(),item.id()).equals(liveEntityIds(world,before,item.id()));
    }
    /** UUIDs of an object's entities as recorded when it was placed. */
    private static Set<UUID> entityIds(org.bson.BsonDocument placed,UUID instance){
        var ids=new HashSet<UUID>();
        for(var value:placed.getArray("entities",new org.bson.BsonArray())){
            UUID owner;try{owner=NativeDecorativeEntities.instance(value);}catch(IllegalStateException unlinked){continue;}
            if(!owner.equals(instance))continue;
            ids.add(value.asDocument().getDocument("Components").getDocument("UUID").get("UUID").asBinary().asUuid());
        }
        return ids;
    }
    /** UUIDs of the entities in the world that belong to this object. */
    private static Set<UUID> liveEntityIds(World world,NativeSnapshotStore.Snapshot before,UUID instance){
        var ids=new HashSet<UUID>();var store=world.getEntityStore().getStore();
        for(var ref:NativeSnapshotStore.entities(world,before.bounds(),before.mask())){
            var link=store.getComponent(ref,EterniaPlacedInstance.getComponentType());
            if(link==null||!link.getInstanceId().equals(instance))continue;
            var uuid=store.getComponent(ref,com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
            if(uuid!=null)ids.add(uuid.getUuid());
        }
        return ids;
    }
    /** Completes an interrupted pickup: removes what is left of the object, restores the ground under it, and packs it. */
    private static void finishPickup(EterniaModPlugin plugin,World world,HubPlotRecord plot,JournalService.Operation op,ProvenanceService.Instance item) throws IOException {
        var services=plugin.getServices();var store=snapshots(plugin);
        var before=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash")));var b=before.bounds();
        var placedAfter=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));
        var mask=NativeRelocationCoordinator.difference(placedAfter.document(),before.document());
        store.load(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash()));
        var restore=store.save(op.id()+"-pickup-finish.json",b,before.cells(),NativeRelocationCoordinator.onlyCapturedCells(before.document(),mask));
        for(var ref:NativeSnapshotStore.entities(world,b,before.mask())){
            var link=world.getEntityStore().getStore().getComponent(ref,EterniaPlacedInstance.getComponentType());
            if(link!=null&&link.getInstanceId().equals(item.id()))NativeDecorativeEntities.remove(world,ref);
        }
        store.apply(world,restore,b.origin());store.verify(world,restore,b.origin(),Set.of(),false);flush(world,b);
        services.provenance().acknowledgePacked(item.id(),item.revision(),NativeRelocationCoordinator.reference(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())));
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);plot.removeProp(item.id());manager.updatePlot(plot);manager.saveIfDirty();
        var applied=services.journal().advance(op.id(),op.revision(),JournalService.State.WORLD_APPLIED);
        services.journal().advance(op.id(),applied.revision(),JournalService.State.COMPLETED);
    }
    /** Whether any block in a saved snapshot carries an item container with something in it. */
    static boolean holdsItems(BsonDocument content){
        for(var value:content.getArray("blocks",new org.bson.BsonArray())){
            var block=value.asDocument();if(!block.containsKey("components"))continue;
            String json=block.get("components").toString();
            if(json.contains("Container")&&json.contains("\"Id\""))return true;
        }
        return false;
    }
    public record Placed(UUID instanceId,NativeSnapshotStore.Snapshot before,NativeSnapshotStore.Snapshot after,UUID operation,Owner contentOwner,String source) {}
    public static Placed place(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor,String catalogId,Vector3i origin,Rotation yaw,IPrefabBuffer buffer,boolean house) throws IOException {
        return place(plugin,world,plot,actor,catalogId,origin,yaw,buffer,house,owner(plot));
    }
    /** Explicit source owner enables a permitted guild quantity to remain guild-owned on an attached member plot. */
    public static Placed place(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor,String catalogId,Vector3i origin,Rotation yaw,IPrefabBuffer buffer,boolean house,Owner custodyOwner) throws IOException {
        healPickups(plugin,world,plot);
        Owner owner=owner(plot);String content=(house?"eternia:house/":"eternia:prop/")+catalogId;
        var packed=plugin.getServices().batch(()->{
        requireActive(plugin,plot);
        if(!house&&catalogId.equals("guild_waygate")&&!com.hexvane.eterniamod.runtime.GuildWaygate.canPlace(plugin,plot,custodyOwner))throw new IllegalStateException("Place this portal on its guild estate with Guild Waygate unlocked.");
        if(!com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,actor,house?"housing.structure":"housing.prop.place"))throw new IllegalStateException("Housing permission required");
        if(house&&!custodyOwner.equals(owner))throw new IllegalStateException("House unlock must belong to the property owner");
        if(!house)com.hexvane.eterniamod.housing.HousingCustody.require(plugin,plot,actor,custodyOwner,com.hexvane.eterniamod.housing.HousingCustody.PLACE);
        if(house&&(!plugin.getServices().ownership().owns(owner,content)||plot.hasBuilding()))throw new IllegalStateException("House unlock required and only one house may be placed");
        return plugin.getServices().provenance().ownedInstances(custodyOwner).stream().filter(i->i.contentId().equals(content)&&i.state().equals("PACKED")).findFirst().orElse(null);
        });
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
        // Hex plots: the object box may reach past a stair edge; only the plot's own columns are read and written.
        var cells=plot.getCells();
        var before=nativeStore.save(operation+"-before.json",bounds,cells,nativeStore.capture(world,bounds,ColumnMask.of(cells),Set.of(),true,false));
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
            boolean sameBounds=saved.bounds().maxX()-saved.bounds().minX()==bounds.maxX()-bounds.minX()&&saved.bounds().maxY()-saved.bounds().minY()==bounds.maxY()-bounds.minY()&&saved.bounds().maxZ()-saved.bounds().minZ()==bounds.maxZ()-bounds.minZ();
            BsonDocument savedContent=saved.document();
            if(!house&&catalogId.equals("guild_waygate")&&!sameBounds){
                savedContent=GuildWaygateSnapshot.withoutPlinth(savedContent).orElseThrow(()->new IllegalStateException("This saved portal needs review before it can be placed."));
                sameBounds=bounds.maxX()-bounds.minX()==1&&bounds.maxY()-bounds.minY()==1&&bounds.maxZ()-bounds.minZ()==1;
            }
            boolean turned=!yaw.name().equals(packed.nativeData().get("yaw"));
            if(turned){
                // Turned since it was packed: its saved copy only fits the old direction. A decoration holding nothing is
                // the same as a fresh one from the catalog, so place that at the new direction; one holding items keeps them.
                if(holdsItems(savedContent))throw new IllegalStateException("This decoration still holds items, so it can only go back facing the way it was packed. Take the items out first to turn it.");
            }else{
                if(!sameBounds)throw new IllegalStateException("This decoration changed shape since it was packed. Try placing it again.");
                desiredDocument=before.document().clone();NativeRelocationCoordinator.overlay(desiredDocument,savedContent);
            }
        }
        var after=nativeStore.save(operation+"-after.json",bounds,cells,desiredDocument);
        var journal=plugin.getServices().journal();
        var op=journal.prepare(operation,house?"PLACE_HOUSE":"PLACE_PROP",owner,plot.getPlotId().toString());
        String source;
        try {source=packed!=null?packed.sourceReference():house?"unlock:"+content:plugin.getServices().ownership().reserve(custodyOwner,content,1,"native-place:"+operation).id();}
        catch(RuntimeException failure){journal.advance(operation,op.revision(),JournalService.State.CANCELLED);throw failure;}
        var manifest=new BsonDocument("before",new BsonString(before.file().reference())).append("beforeHash",new BsonString(before.file().sha256())).append("after",new BsonString(after.file().reference())).append("afterHash",new BsonString(after.file().sha256())).append("instance",new BsonString(instance.toString())).append("source",new BsonString(source));
        manifest.append("catalog",new BsonString(catalogId)).append("yaw",new BsonString(yaw.name()))
            .append("originX",new BsonInt32(origin.x)).append("originY",new BsonInt32(origin.y)).append("originZ",new BsonInt32(origin.z))
            .append("actor",new BsonString(actor.toString())).append("placedAt",new BsonString(Long.toString(System.currentTimeMillis())));
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
        } catch(Throwable failure){lock(plugin,operation);plugin.getLogger().atWarning().withCause(failure).log("Placement %s did not finish",operation);throw new IllegalStateException("Placing did not finish. Nothing was lost. Try again in a moment.",failure);}
    }
    /** The house standing on the plot: the most recently placed house record. */
    public static UUID house(EterniaModPlugin plugin,HubPlotRecord plot){
        return plugin.getServices().provenance().instances(plot.getPlotId()).stream().filter(i->i.state().equals("PLACED")&&"HOUSE".equals(i.nativeData().get("kind")))
            .max(Comparator.comparingLong(i->Long.parseLong(i.nativeData().getOrDefault("placedAt","0")))).map(ProvenanceService.Instance::id).orElseThrow(()->new IllegalStateException("House provenance missing"));
    }
    /**
     * Objects placed after {@code instanceId} that would be left floating or buried if it were packed alone: anything
     * whose space overlaps it, and for a house every house addition. Newest first, the order they must be packed in.
     */
    public static List<ProvenanceService.Instance> inTheWay(EterniaModPlugin plugin,HubPlotRecord plot,UUID instanceId) throws IOException {
        var services=plugin.getServices();var item=services.provenance().find(instanceId).orElseThrow(()->new IllegalStateException("Unproven legacy object cannot be packaged"));
        var store=snapshots(plugin);var b=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash"))).bounds();
        boolean house="HOUSE".equals(item.nativeData().get("kind"));long since=Long.parseLong(item.nativeData().getOrDefault("placedAt","0"));
        var out=new ArrayList<ProvenanceService.Instance>();
        for(var other:services.provenance().instances(plot.getPlotId())) {
            if(other.id().equals(instanceId)||!other.state().equals("PLACED")||"HOUSE".equals(other.nativeData().get("kind"))||"PATH".equals(other.nativeData().get("kind")))continue;
            if(Long.parseLong(other.nativeData().getOrDefault("placedAt","9223372036854775807"))<since)continue;
            var definition=plugin.getPropCatalog().get(other.nativeData().get("catalog"));
            boolean addition=house&&definition!=null&&"addition".equals(definition.getCategory());
            var o=store.load(new SnapshotFiles.Saved(other.nativeData().get("before"),other.nativeData().get("beforeHash"))).bounds();
            boolean overlaps=b.minX()<o.maxX()&&o.minX()<b.maxX()&&b.minY()<o.maxY()&&o.minY()<b.maxY()&&b.minZ()<o.maxZ()&&o.minZ()<b.maxZ();
            if(addition||overlaps)out.add(other);
        }
        out.sort(Comparator.comparingLong((ProvenanceService.Instance i)->Long.parseLong(i.nativeData().getOrDefault("placedAt","0"))).reversed());
        return out;
    }
    /** Player name of a placed object. */
    public static String name(EterniaModPlugin plugin,ProvenanceService.Instance item){
        var definition=plugin.getPropCatalog().get(item.nativeData().get("catalog"));
        return definition!=null&&definition.getDisplayName()!=null?definition.getDisplayName():"A decoration";
    }
    /** Returns native custody to the build inventory. The quantity reservation remains held by the packed instance. */
    public static UUID pickup(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID instanceId) throws IOException {
        return pickup(plugin,world,plot,null,instanceId);
    }
    /** Player-facing callers pass the actor and re-resolve the original inventory owner at the instant of pickup. */
    public static UUID pickup(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor,UUID instanceId) throws IOException {
        healPickups(plugin,world,plot);
        if(instanceId.equals(plot.getPlotId()))instanceId=house(plugin,plot);
        requireActive(plugin,plot);var services=plugin.getServices();var item=services.provenance().find(instanceId).orElseThrow(()->new IllegalStateException("Unproven legacy object cannot be packaged"));
        if(!item.state().equals("PLACED")||!item.propertyId().equals(plot.getPlotId()))throw new IllegalStateException("Placed object provenance does not match this property");
        if(actor==null){if(!item.owner().equals(owner(plot)))throw new IllegalStateException("Guild custody pickup requires the authorized actor");}
        else if("HOUSE".equals(item.nativeData().get("kind"))){if(!item.owner().equals(owner(plot))||!com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,actor,"housing.structure"))throw new IllegalStateException("House ownership and structure permission are required");}
        else com.hexvane.eterniamod.housing.HousingCustody.require(plugin,plot,actor,item.owner(),com.hexvane.eterniamod.housing.HousingCustody.PACK);
        var store=snapshots(plugin);var before=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash")));var b=before.bounds();
        var first=inTheWay(plugin,plot,instanceId);
        if(!first.isEmpty()){var o=first.get(0);throw new IllegalStateException(name(plugin,o)+" stands in the way and has to be packed first.");}
        var placedAfter=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));
        var mask=NativeRelocationCoordinator.difference(placedAfter.document(),before.document());
        // Other placed objects on the plot may share this area (a shelf inside a house); their entities are known, not strangers.
        var known=new HashSet<UUID>();for(var other:services.provenance().instances(plot.getPlotId()))if(other.state().equals("PLACED"))known.add(other.id());known.add(instanceId);
        var captured=store.capture(world,b,before.mask(),known,true,false);
        try{NativeDecorativeEntities.requireIdentity(placedAfter.document(),captured,instanceId);}catch(IllegalStateException moved){plugin.getLogger().atWarning().log("Pickup of %s: %s; packing it as it is",instanceId,moved.getMessage());}
        try{NativeRelocationCoordinator.requireSameBlocks(captured,placedAfter.document(),mask,b);}catch(NativeRelocationCoordinator.ObjectEdited edited){plugin.getLogger().atWarning().log("Pickup of %s: changed %s; packing it as it is",instanceId,edited.detail());}
        if(item.owner().kind()==Owner.Kind.GUILD&&!item.owner().equals(owner(plot)))NativeRelocationCoordinator.requireEmptyGuildContainers(NativeRelocationCoordinator.onlyCapturedCells(captured,mask));
        UUID operation=UUID.randomUUID();var packed=store.save(operation+"-pickup.json",b,before.cells(),NativeRelocationCoordinator.onlyCapturedCells(captured,mask));
        var restore=store.save(operation+"-pickup-before.json",b,before.cells(),NativeRelocationCoordinator.onlyCapturedCells(before.document(),mask));
        // Recovery locks the edited property; provenance retains the distinct inventory owner.
        var op=services.journal().prepare(operation,"PICKUP",owner(plot),plot.getPlotId().toString());op=services.journal().attachVerifiedSnapshot(operation,op.revision(),packed.file().reference(),packed.file().sha256());
        try {
            for(var ref:NativeSnapshotStore.entities(world,b,before.mask())){
                var link=world.getEntityStore().getStore().getComponent(ref,EterniaPlacedInstance.getComponentType());
                if(link!=null&&link.getInstanceId().equals(instanceId))NativeDecorativeEntities.remove(world,ref);
            }
            store.apply(world,restore,b.origin());store.verify(world,restore,b.origin(),Set.of(),false);flush(world,b);
            services.provenance().acknowledgePacked(instanceId,item.revision(),NativeRelocationCoordinator.reference(packed.file()));
            services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);return operation;
        }catch(Throwable failure){lock(plugin,operation);plugin.getLogger().atWarning().withCause(failure).log("Pickup %s did not finish",operation);throw new IllegalStateException("Packing did not finish. Your decoration and its contents are kept safe. Try again in a moment.",failure);}
    }
    /** Must run after the native plot metadata was successfully saved. */
    public static void complete(EterniaModPlugin plugin,UUID operation){plugin.getServices().batch(()->{var op=plugin.getServices().journal().find(operation).orElseThrow();plugin.getServices().journal().advance(operation,op.revision(),JournalService.State.COMPLETED);return null;});}
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
