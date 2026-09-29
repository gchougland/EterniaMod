package com.hexvane.eterniamod.housing.relocation;

import com.google.gson.Gson;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.hub.*;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bson.*;
import org.joml.Vector3i;

/** Explicit pack/restore entry points. Call on the affected world thread; errors retain custody and lock ambiguity. */
public final class NativeRelocationCoordinator {
    private static final Gson JSON=new Gson();
    private final EterniaModPlugin plugin;
    public NativeRelocationCoordinator(EterniaModPlugin plugin){this.plugin=plugin;}
    private NativeSnapshotStore snapshots(){return NativePlacementTransactions.snapshots(plugin);}
    public record RecoveryInspection(UUID operation,String kind,String state,String owner,String snapshot,boolean verified,String problem) {}
    /** Read-only administrator evidence; never clears a lock simply because a process restarted. */
    public RecoveryInspection inspect(UUID operation) {
        var op=plugin.getServices().journal().find(operation).orElseThrow();
        try{snapshots().files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash()));return new RecoveryInspection(op.id(),op.kind(),op.state().name(),op.owner().key(),op.snapshotRef(),true,"");}
        catch(Exception failure){return new RecoveryInspection(op.id(),op.kind(),op.state().name(),op.owner().key(),op.snapshotRef(),false,failure.getMessage());}
    }
    /** Explicit admin recovery of an interrupted source pack. Restores verified source custody before releasing its move credit. */
    public void recoverPackToSource(World world,Owner owner,UUID operation) {
        var services=plugin.getServices();var slot=services.housing().find(owner).orElseThrow();var op=services.journal().find(operation).orElseThrow();
        if(slot.state()!=HousingService.State.MOVING||!slot.operationId().equals(operation)||!Set.of("MOVE","FORCED_RETURN").contains(op.kind()))throw new IllegalStateException("Only an interrupted source pack can use this recovery");
        try {
            var store=snapshots();var manifest=BsonDocument.parse(new String(store.files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())),StandardCharsets.UTF_8));
            var plot=JSON.fromJson(manifest.getDocument("plot").toJson(),HubPlotRecord.class);if(!plot.getWorldName().equals(world.getName()))throw new IllegalStateException("Recovery needs the original world");
            var original=store.load(parseReference(manifest.getString("original").getValue()));
            var baseline=store.load(parseReference(manifest.getString("baseline").getValue()));
            var instances=services.provenance().instances(slot.propertyId());Set<UUID> known=new HashSet<>();instances.forEach(i->known.add(i.id()));
            var current=store.capture(world,original.bounds(),known,true,true);
            if(!index(current.getArray("blocks")).keySet().equals(index(original.document().getArray("blocks")).keySet()))throw new IllegalStateException("Protected infrastructure changed; recovery needs review");
            // Only the two documented write states may be overwritten; unexpected edits retain the lock for review.
            var sourceCells=index(original.document().getArray("blocks"));var baselineCells=index(baseline.document().getArray("blocks"));
            for(var value:current.getArray("blocks")){String key=key(value.asDocument());if(!value.equals(sourceCells.get(key))&&!value.equals(baselineCells.get(key)))throw new IllegalStateException("Unexpected source edits prevent automatic recovery");}
            var sourceFluids=index(original.document().getArray("fluids",new BsonArray()));var baselineFluids=index(baseline.document().getArray("fluids",new BsonArray()));var actualFluids=index(current.getArray("fluids",new BsonArray()));
            for(String cell:sourceCells.keySet())if(!Objects.equals(actualFluids.get(cell),sourceFluids.get(cell))&&!Objects.equals(actualFluids.get(cell),baselineFluids.get(cell)))throw new IllegalStateException("Unexpected fluid edits prevent automatic recovery");
            removeEntities(world,original.bounds(),known);store.apply(world,original,original.bounds().origin());store.verify(world,original,original.bounds().origin(),known,true);NativePlacementTransactions.flush(world,original.bounds());
            var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);manager.updatePlot(plot);manager.saveIfDirty();
            Set<UUID> originallyPlaced=new HashSet<>();for(var value:manifest.getArray("instances"))originallyPlaced.add(UUID.fromString(value.asString().getValue()));for(var value:manifest.getArray("guildReturns"))originallyPlaced.add(UUID.fromString(value.asDocument().getString("instance").getValue()));
            for(var item:instances)if(originallyPlaced.contains(item.id())&&item.state().equals("PACKED"))services.provenance().acknowledgeRestored(item.id(),item.revision(),slot.propertyId());
            op=services.journal().find(operation).orElseThrow();
            if(op.state()!=JournalService.State.SNAPSHOT_READY){if(op.state()!=JournalService.State.RECOVERY_REQUIRED)op=services.journal().advance(operation,op.revision(),JournalService.State.RECOVERY_REQUIRED);services.journal().advance(operation,op.revision(),JournalService.State.SNAPSHOT_READY);}
            services.housing().cancelRelocationBeforeMutation(owner,operation);
        }catch(Throwable failure){NativePlacementTransactions.lock(plugin,operation);throw new IllegalStateException("Recovery retained its lock: "+failure.getMessage(),failure);}
    }
    public static NativeSnapshotStore.Bounds bounds(HubPlotRecord plot){var f=plot.getFootprint();return new NativeSnapshotStore.Bounds(f.getMinX(),ChunkUtil.MIN_Y,f.getMinZ(),f.getMaxX()+1,ChunkUtil.HEIGHT,f.getMaxZ()+1);}
    /** Before claim activation, while construction is still locked. Existing unproven plots are never auto-baselined. */
    public static void captureBaseline(EterniaModPlugin plugin,World world,HubPlotRecord plot) {
        try {
            var store=NativePlacementTransactions.snapshots(plugin);var b=bounds(plot);
            Path pointer=store.files().resolve("baseline-"+plot.getPlotId()+".pointer");
            if(Files.exists(pointer)){loadPointer(store,pointer);return;}
            if(plot.hasBuilding()||!plot.getProps().isEmpty())throw new IllegalStateException("Cannot invent original terrain for an existing house");
            var baseline=store.save(plot.getPlotId()+"-baseline.json",b,store.capture(world,b,Set.of(),true,false));
            writePointer(pointer,baseline.file());
        } catch(IOException e){throw new UncheckedIOException(e);}
    }
    public UUID pack(World world,Owner owner,UUID operation,boolean forced) {
        var services=plugin.getServices();var slot=services.housing().find(owner).orElseThrow();
        if(slot.state()==HousingService.State.PACKED&&slot.operationId().equals(operation))return operation;
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);var plot=manager.getPlot(slot.propertyId());
        if(plot==null||!plot.getWorldName().equals(world.getName())||!NativePlacementTransactions.owner(plot).equals(owner))throw new IllegalStateException("Native property projection not found");
        NativePlacementTransactions.requireActive(plugin,plot);
        var store=snapshots();boolean began=false,mutated=false;
        try {
            com.hexvane.eterniamod.guildroads.GuildRoads.beforePack(world,plot);
            var baseline=loadPointer(store,store.files().resolve("baseline-"+plot.getPlotId()+".pointer"));
            var b=bounds(plot);if(!baseline.bounds().equals(b))throw new IllegalStateException("Baseline bounds differ from property");
            var instances=services.provenance().instances(plot.getPlotId()).stream().filter(i->i.state().equals("PLACED")).toList();
            Set<UUID> known=new HashSet<>();instances.forEach(i->known.add(i.id()));
            if(plot.hasBuilding()&&instances.stream().noneMatch(i->"HOUSE".equals(i.nativeData().get("kind")))||plot.getProps().stream().anyMatch(p->!known.contains(p.getInstanceId())))throw new IllegalStateException("Legacy object lacks proven ownership; migration required");
            var current=store.save(operation+"-property.json",b,store.capture(world,b,known,true,true));
            for(var item:instances){var placed=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));NativeDecorativeEntities.requireIdentity(placed.document(),current.document(),item.id());}
            if(!index(current.document().getArray("blocks")).keySet().equals(index(baseline.document().getArray("blocks")).keySet()))throw new IllegalStateException("Protected infrastructure changed since the plot baseline; review required");
            BsonDocument personal=current.document().clone();BsonArray returned=new BsonArray();
            for(var item:instances)if(!item.owner().equals(owner)) {
                if(item.owner().kind()!=Owner.Kind.GUILD)throw new IllegalStateException("Property contains foreign player-owned content");
                var before=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash")));
                var placedAfter=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));
                var mask=difference(placedAfter.document(),before.document());
                var actual=boundsIn(current,before.bounds());requireSameBlocks(actual,placedAfter.document(),mask);
                var sub=onlyCapturedCells(actual,mask);
                NativeDecorativeEntities.filterInstances(sub,item.id(),true);NativeDecorativeEntities.requireIdentity(placedAfter.document(),sub,item.id());
                requireEmptyGuildContainers(sub);
                var guildSnapshot=store.save(operation+"-guild-"+item.id()+".json",before.bounds(),sub);
                // Overlapping provenance cannot tell whose later additions should be removed.
                for(var other:instances)if(!other.id().equals(item.id())&&Long.parseLong(other.nativeData().getOrDefault("placedAt","9223372036854775807"))>=Long.parseLong(item.nativeData().getOrDefault("placedAt","0"))) {
                    var otherBefore=store.load(new SnapshotFiles.Saved(other.nativeData().get("before"),other.nativeData().get("beforeHash")));
                    if(overlaps(before.bounds(),otherBefore.bounds()))throw new IllegalStateException("Overlapping player/guild objects require ownership review");
                }
                var guildBefore=store.save(operation+"-guild-"+item.id()+"-before.json",before.bounds(),onlyCapturedCells(before.document(),mask));
                replaceRegion(personal,b,guildBefore);
                NativeDecorativeEntities.filterInstances(personal,item.id(),false);
                returned.add(new BsonDocument("instance",new BsonString(item.id().toString())).append("snapshot",new BsonString(reference(guildSnapshot.file()))));
            }
            var delta=store.save(operation+"-personal.json",b,difference(personal,baseline.document()));
            var manifest=new BsonDocument("format",new BsonString("eternia-property-pack-1"))
                .append("plot",BsonDocument.parse(JSON.toJson(plot)))
                .append("original",new BsonString(reference(current.file())))
                .append("baseline",new BsonString(reference(baseline.file())))
                .append("personal",new BsonString(reference(delta.file())))
                .append("instances",new BsonArray(instances.stream().filter(i->i.owner().equals(owner)).map(i->(BsonValue)new BsonString(i.id().toString())).toList()))
                .append("guildReturns",returned);
            var file=store.files().write(operation+"-pack.json",manifest.toJson().getBytes(StandardCharsets.UTF_8));
            services.housing().beginRelocation(owner,operation,forced);began=true;
            var op=services.journal().find(operation).orElseThrow();services.journal().attachVerifiedSnapshot(operation,op.revision(),file.reference(),file.sha256());
            mutated=true;removeEntities(world,b,known);store.apply(world,baseline,b.origin());store.verify(world,baseline,b.origin(),Set.of(),true);NativePlacementTransactions.flush(world,b);
            for(var item:instances) {
                String custody=reference(delta.file());
                for(var value:returned)if(value.asDocument().getString("instance").getValue().equals(item.id().toString()))custody=value.asDocument().getString("snapshot").getValue();
                services.provenance().acknowledgePacked(item.id(),item.revision(),custody);
            }
            manager.removePlot(plot.getPlotId());manager.saveIfDirty();
            op=services.journal().find(operation).orElseThrow();services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);services.housing().acknowledgePacked(owner,operation);
            return operation;
        }catch(Throwable failure){
            if(began){if(mutated)NativePlacementTransactions.lock(plugin,operation);else services.housing().cancelRelocationBeforeMutation(owner,operation);}
            throw new IllegalStateException(mutated?"Move locked for recovery; original and packed snapshots retained":"Plot was not changed: "+failure.getMessage(),failure);
        }
    }
    /** Restore centers retained or larger dimensions and translates the complete change set to the selected ground elevation. */
    public void restore(World world,UUID actor,Owner owner,UUID operation,PlotRect destination,int groundY,HousingRules.Scope scope,boolean paid) {
        var services=plugin.getServices();var slot=services.housing().find(owner).orElseThrow();
        if(slot.state()!=HousingService.State.PACKED||!slot.operationId().equals(operation))throw new IllegalStateException("No matching packed plot");
        if(!com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()).isEmpty())throw new IllegalStateException("Recover the interrupted public road before restoring a plot in this world");
        if(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()).stream().anyMatch(r->r.overlaps(destination)))throw new IllegalStateException("Remove the intersecting guild road segment before restoring a plot here; rebuild it after the claim");
        if(owner.kind()==Owner.Kind.PLAYER&&!owner.id().equals(actor)||owner.kind()==Owner.Kind.GUILD&&!services.guilds().can(actor,owner.id(),"housing.claim"))throw new IllegalStateException("Owner permission required");
        var store=snapshots();boolean started=false;
        try {
            var op=services.journal().find(operation).orElseThrow();
            var manifest=BsonDocument.parse(new String(store.files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())),StandardCharsets.UTF_8));
            var oldPlot=JSON.fromJson(manifest.getDocument("plot").toJson(),HubPlotRecord.class);
            var original=store.load(parseReference(manifest.getString("original").getValue()));
            var personal=store.load(parseReference(manifest.getString("personal").getValue()));
            int dy=Math.subtractExact(groundY,oldPlot.getFootprint().resolveVisualCenterY());
            var inset=PlotSnapshotTransform.centeredInset(original.bounds().maxX()-original.bounds().minX(),original.bounds().maxZ()-original.bounds().minZ(),destination.width(),destination.depth());
            var movedPersonal=PlotSnapshotTransform.translate(personal.document(),inset.x(),dy,inset.z(),0,ChunkUtil.MIN_Y,0,destination.width(),ChunkUtil.HEIGHT,destination.depth());
            var infra=plugin.getInfrastructure().world(world.getName()).orElseThrow();if(!infra.supportsHousing())throw new IllegalStateException("Use a world with housing enabled");
            UUID guild=scope==HousingRules.Scope.PUBLIC?null:services.guilds().membership(actor).orElseThrow().guildId();
            if(owner.kind()==Owner.Kind.GUILD&&(scope!=HousingRules.Scope.GUILD_ROOT||!owner.id().equals(guild)))throw new IllegalStateException("Guild property owner cannot change");
            if(owner.kind()==Owner.Kind.PLAYER&&scope==HousingRules.Scope.GUILD_ROOT)throw new IllegalStateException("Player plot cannot become guild property");
            if(paid&&!services.ownership().owns(owner,owner.kind()==Owner.Kind.GUILD?"eternia:plot/guild_64":"eternia:plot/personal_32"))throw new IllegalStateException("Plot size entitlement required");
            var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
            var anchors=manager.listPlots().stream().map(p->new HousingRules.Anchor(p.getPlotId(),NativeHousingChecks.rect(p.getFootprint()),p.getGuildOwnerUuid()!=null?HousingRules.Scope.GUILD_ROOT:p.getAttachedGuildUuid()!=null?HousingRules.Scope.GUILD_MEMBER:HousingRules.Scope.PUBLIC,p.getGuildOwnerUuid()!=null?p.getGuildOwnerUuid():p.getAttachedGuildUuid(),p.hasBuilding())).toList();
            var result=HousingRules.claim(new HousingRules.Candidate(destination,scope,guild,paid,guild==null?0:services.guilds().roster(actor).size()),anchors,infra.roads(),infra.portals(),com.hexvane.eterniamod.pathtool.SplineRoadTool.roadNetwork(world.getName(),infra));if(!result.valid())throw new IllegalStateException(result.message());
            Vector3i target=new Vector3i(destination.x(),original.bounds().minY(),destination.z());
            var movedBounds=new NativeSnapshotStore.Bounds(target.x,target.y,target.z,destination.endX(),original.bounds().maxY(),destination.endZ());
            int horizontalX=target.x-original.bounds().minX()+inset.x(),horizontalZ=target.z-original.bounds().minZ()+inset.z();
            if(oldPlot.getBuilding()!=null){var h=oldPlot.getBuilding();var definition=plugin.getBuildingCatalog().get(h.getBuildingId());if(definition==null)throw new IllegalStateException("House catalog revision is missing");var prefab=com.hexvane.eterniamod.prefab.PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath());if(prefab==null)throw new IllegalStateException("House prefab is missing");var footprint=com.hexvane.eterniamod.placement.PlotFootprintUtil.computeFootprint(new Vector3i(h.getAnchorX()+horizontalX,h.getAnchorY()+dy,h.getAnchorZ()+horizontalZ),h.resolveRotationYaw(),prefab);var structure=HousingRules.structure(destination,NativeHousingChecks.rect(footprint),plugin.getInfrastructure().structureRoads(world.getName()));if(!structure.valid())throw new IllegalStateException(structure.message());}
            for(var prop:oldPlot.getProps()){var definition=plugin.getPropCatalog().get(prop.getPropId());if(definition!=null&&definition.getCategory().equals("addition")){var prefab=com.hexvane.eterniamod.prefab.PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath());if(prefab==null)throw new IllegalStateException("Addition prefab is missing");var footprint=com.hexvane.eterniamod.placement.PlotFootprintUtil.computeFootprint(new Vector3i(prop.getAnchorX()+horizontalX,prop.getAnchorY()+dy,prop.getAnchorZ()+horizontalZ),prop.resolveRotationYaw(),prefab);var structure=HousingRules.structure(destination,NativeHousingChecks.rect(footprint),plugin.getInfrastructure().structureRoads(world.getName()));if(!structure.valid())throw new IllegalStateException(structure.message());}}
            // A full-height translated snapshot must never carve a protected road/portal column.
            for(var value:movedPersonal.getArray("blocks")){var cell=value.asDocument();int x=target.x+cell.getInt32("x").getValue(),z=target.z+cell.getInt32("z").getValue();if(plugin.getInfrastructure().protectedColumn(world.getName(),x,z))throw new IllegalStateException("Packed content intersects a protected road or portal");}
            var newBaseline=store.save(operation+"-destination-baseline.json",movedBounds,store.capture(world,movedBounds,Set.of(),true,true));
            var merged=newBaseline.document().clone();overlay(merged,movedPersonal);
            var after=store.save(operation+"-destination.json",movedBounds,merged);
            services.housing().beginRestore(owner,operation);started=true;
            services.housing().updateRestoreLocation(owner,operation,new HousingService.ClaimLocation(world.getName(),destination.x(),destination.z(),destination.width(),destination.depth(),guild==null?HousingService.ClaimScope.PUBLIC:HousingService.ClaimScope.GUILD,guild,oldPlot.hasBuilding()));
            Set<UUID> ids=new HashSet<>();for(var value:manifest.getArray("instances"))ids.add(UUID.fromString(value.asString().getValue()));
            store.apply(world,after,target);store.verify(world,after,target,ids,true);NativePlacementTransactions.flush(world,movedBounds);
            int dx=horizontalX,dz=horizontalZ;
            var projection=new HubPlotRecord(oldPlot.getPlotId(),world.getName(),HubPlotFootprint.forCreate(destination.x(),destination.endX()-1,destination.z(),destination.endZ()-1,groundY),owner.kind()==Owner.Kind.PLAYER?owner.id():null);
            projection.setGuildOwnerUuid(owner.kind()==Owner.Kind.GUILD?owner.id():null);projection.setAttachedGuildUuid(owner.kind()==Owner.Kind.PLAYER?guild:null);
            if(oldPlot.getBuilding()!=null&&services.provenance().instances(slot.propertyId()).stream().anyMatch(i->ids.contains(i.id())&&"HOUSE".equals(i.nativeData().get("kind")))){var h=oldPlot.getBuilding();projection.setBuilding(new HubPlotBuilding(h.getBuildingId(),h.getAnchorX()+dx,h.getAnchorY()+dy,h.getAnchorZ()+dz,h.resolveRotationYaw(),List.of()));}
            for(var p:oldPlot.getProps())if(ids.contains(p.getInstanceId()))projection.addProp(new HubPlotProp(p.getInstanceId(),p.getPropId(),p.getAnchorX()+dx,p.getAnchorY()+dy,p.getAnchorZ()+dz,p.resolveRotationYaw()));
            manager.addPlot(projection);manager.saveIfDirty();writePointer(store.files().resolve("baseline-"+projection.getPlotId()+".pointer"),newBaseline.file());
            var oldBaseline=store.load(parseReference(manifest.getString("baseline").getValue()));
            for(var item:services.provenance().instances(slot.propertyId()))if(ids.contains(item.id())) {
                var oldBefore=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash")));var oldBounds=oldBefore.bounds();
                var moved=new NativeSnapshotStore.Bounds(oldBounds.minX()+dx,oldBounds.minY()+dy,oldBounds.minZ()+dz,oldBounds.maxX()+dx,oldBounds.maxY()+dy,oldBounds.maxZ()+dz);
                var beforeDocument=boundsIn(newBaseline,moved);overlay(beforeDocument,difference(oldBefore.document(),boundsIn(oldBaseline,oldBounds)));
                var before=store.save(operation+"-"+item.id()+"-before.json",moved,beforeDocument);
                var oldAfter=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));
                var afterDocument=boundsIn(newBaseline,moved);overlay(afterDocument,difference(oldAfter.document(),boundsIn(oldBaseline,oldBounds)));
                var afterInstance=store.save(operation+"-"+item.id()+"-after.json",moved,afterDocument);
                var data=new HashMap<>(item.nativeData());data.put("before",before.file().reference());data.put("beforeHash",before.file().sha256());data.put("after",afterInstance.file().reference());data.put("afterHash",afterInstance.file().sha256());
                services.provenance().acknowledgeRestored(item.id(),item.revision(),slot.propertyId(),data);
            }
            op=services.journal().find(operation).orElseThrow();services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);services.housing().activate(owner,operation);
        }catch(Throwable failure){if(started)NativePlacementTransactions.lock(plugin,operation);throw new IllegalStateException(started?"Restore locked for recovery; destination snapshot retained":"Packed plot remains safe: "+failure.getMessage(),failure);}
    }
    private static boolean overlaps(NativeSnapshotStore.Bounds a,NativeSnapshotStore.Bounds b){return a.minX()<b.maxX()&&b.minX()<a.maxX()&&a.minY()<b.maxY()&&b.minY()<a.maxY()&&a.minZ()<b.maxZ()&&b.minZ()<a.maxZ();}
    static BsonDocument difference(BsonDocument current,BsonDocument baseline){var result=current.clone();var original=index(baseline.getArray("blocks"));var fluids=index(baseline.getArray("fluids",new BsonArray()));var currentFluids=index(current.getArray("fluids",new BsonArray()));var changed=new BsonArray();var changedFluids=new BsonArray();for(var v:current.getArray("blocks")){var d=v.asDocument();String key=key(d);if(!d.equals(original.get(key))||!Objects.equals(fluids.get(key),currentFluids.get(key))){changed.add(d);if(currentFluids.containsKey(key))changedFluids.add(currentFluids.get(key));}}result.put("blocks",changed);result.put("fluids",changedFluids);return result;}
    static BsonDocument onlyCapturedCells(BsonDocument source,BsonDocument mask){var keys=index(mask.getArray("blocks")).keySet();var result=source.clone();for(String type:List.of("blocks","fluids")){var selected=new BsonArray();for(var value:source.getArray(type,new BsonArray()))if(keys.contains(key(value.asDocument())))selected.add(value);result.put(type,selected);}return result;}
    static void requireSameBlocks(BsonDocument actual,BsonDocument expected,BsonDocument mask){var current=index(actual.getArray("blocks"));var prior=index(expected.getArray("blocks"));for(var value:mask.getArray("blocks")){String k=key(value.asDocument());var a=current.get(k).asDocument().clone();var e=prior.get(k).asDocument().clone();a.remove("components");e.remove("components");a.remove("support");e.remove("support");if(!a.equals(e))throw new IllegalStateException("Object blocks were edited; use whole-property relocation or review their ownership");}}
    static void requireEmptyGuildContainers(BsonDocument snapshot){for(var value:snapshot.getArray("blocks")){var cell=value.asDocument();if(!cell.containsKey("components"))continue;var holder=com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.REGISTRY.deserialize(cell.getDocument("components"));var container=holder.getComponent(com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock.getComponentType());if(container!=null&&container.getItemContainer()!=null)for(short slot=0;slot<container.getItemContainer().getCapacity();slot++)if(!com.hypixel.hytale.server.core.inventory.ItemStack.isEmpty(container.getItemContainer().getItemStack(slot)))throw new IllegalStateException("Guild container has items without individual ownership provenance; segregate them before returning the property");}}
    static BsonDocument translateElevation(BsonDocument source,int dy,int minY,int maxY){var result=source.clone();for(String type:List.of("blocks","fluids"))for(var value:result.getArray(type,new BsonArray())){var cell=value.asDocument();int y=Math.addExact(cell.getInt32("y").getValue(),dy);if(y<minY||y>=maxY)throw new IllegalStateException("Moved content would cross the world height limit");cell.put("y",new BsonInt32(y));}if(result.containsKey("entities")){var entities=new BsonArray();for(var value:result.getArray("entities")){var holder=com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.deserialize(value.asDocument());var p=holder.getComponent(com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()).getPosition();p.y+=dy;if(p.y<minY||p.y>=maxY)throw new IllegalStateException("Moved entity would cross the world height limit");entities.add(com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.serialize(holder));}result.put("entities",entities);}return result;}
    static void overlay(BsonDocument base,BsonDocument overlay){var cells=index(base.getArray("blocks"));var fluids=index(base.getArray("fluids",new BsonArray()));for(var v:overlay.getArray("blocks")){var d=v.asDocument();cells.put(key(d),d);fluids.remove(key(d));}for(var v:overlay.getArray("fluids",new BsonArray())){var d=v.asDocument();fluids.put(key(d),d);}base.put("blocks",new BsonArray(new ArrayList<>(cells.values())));base.put("fluids",new BsonArray(new ArrayList<>(fluids.values())));if(overlay.containsKey("entities"))base.put("entities",overlay.getArray("entities"));else base.remove("entities");}
    private static Map<String,BsonValue> index(BsonArray cells){Map<String,BsonValue> map=new TreeMap<>();for(var v:cells)map.put(key(v.asDocument()),v);return map;}
    private static String key(BsonDocument d){return d.getInt32("x").getValue()+","+d.getInt32("y").getValue()+","+d.getInt32("z").getValue();}
    private static BsonDocument boundsIn(NativeSnapshotStore.Snapshot full,NativeSnapshotStore.Bounds region){var result=full.document().clone();for(String type:List.of("blocks","fluids")){var selected=new BsonArray();for(var v:result.getArray(type,new BsonArray())){var d=v.asDocument();int x=full.bounds().minX()+d.getInt32("x").getValue(),y=full.bounds().minY()+d.getInt32("y").getValue(),z=full.bounds().minZ()+d.getInt32("z").getValue();if(region.contains(x,y,z)){d=d.clone();d.put("x",new BsonInt32(x-region.minX()));d.put("y",new BsonInt32(y-region.minY()));d.put("z",new BsonInt32(z-region.minZ()));selected.add(d);}}result.put(type,selected);}if(result.containsKey("entities")){var selected=new BsonArray();for(var value:result.getArray("entities")){var holder=com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.deserialize(value.asDocument());var p=holder.getComponent(com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()).getPosition();double x=full.bounds().minX()+p.x,y=full.bounds().minY()+p.y,z=full.bounds().minZ()+p.z;if(region.contains(x,y,z)){p.set(x-region.minX(),y-region.minY(),z-region.minZ());selected.add(com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.serialize(holder));}}if(selected.isEmpty())result.remove("entities");else result.put("entities",selected);}return result;}
    private static void replaceRegion(BsonDocument full,NativeSnapshotStore.Bounds bounds,NativeSnapshotStore.Snapshot replacement){
        var shifted=replacement.document().clone();
        for(String type:List.of("blocks","fluids"))for(var v:shifted.getArray(type,new BsonArray())){var d=v.asDocument();d.put("x",new BsonInt32(d.getInt32("x").getValue()+replacement.bounds().minX()-bounds.minX()));d.put("y",new BsonInt32(d.getInt32("y").getValue()+replacement.bounds().minY()-bounds.minY()));d.put("z",new BsonInt32(d.getInt32("z").getValue()+replacement.bounds().minZ()-bounds.minZ()));}
        // Cell restoration must not erase unrelated player entities; the caller removes the exact guild instance separately.
        var retainedEntities=full.get("entities");overlay(full,shifted);if(retainedEntities!=null)full.put("entities",retainedEntities);
    }
    private static void removeEntities(World world,NativeSnapshotStore.Bounds b,Set<UUID> known){var store=world.getEntityStore().getStore();for(var ref:NativeSnapshotStore.entities(world,b)){var link=store.getComponent(ref,EterniaPlacedInstance.getComponentType());if(link==null||!known.contains(link.getInstanceId()))throw new IllegalStateException("Entity ownership changed before removal");NativeDecorativeEntities.remove(world,ref);}}
    public static String reference(SnapshotFiles.Saved file){return file.reference()+"#"+file.sha256();}
    public static SnapshotFiles.Saved parseReference(String value){int split=value.lastIndexOf('#');if(split<1)throw new IllegalArgumentException("Snapshot reference needs SHA-256");return new SnapshotFiles.Saved(value.substring(0,split),value.substring(split+1));}
    private static NativeSnapshotStore.Snapshot loadPointer(NativeSnapshotStore store,Path file)throws IOException{return store.load(parseReference(Files.readString(file)));}
    private static void writePointer(Path path,SnapshotFiles.Saved saved)throws IOException{var temp=path.resolveSibling(path.getFileName()+".pending");byte[] data=reference(saved).getBytes(StandardCharsets.UTF_8);try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var bytes=ByteBuffer.wrap(data);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
}
