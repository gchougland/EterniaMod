package com.hexvane.eterniamod.housing.relocation;

import com.google.gson.Gson;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.PlotCells;
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
            if(!Objects.equals(original.cells(),plot.getCells()))throw new IllegalStateException("Packed snapshot does not match the plot's hexagons");
            var current=store.capture(world,original.bounds(),original.mask(),known,true,true);
            if(!index(current.getArray("blocks")).keySet().equals(index(original.document().getArray("blocks")).keySet()))throw new IllegalStateException("Protected infrastructure changed; recovery needs review");
            // Only the two documented write states may be overwritten; unexpected edits retain the lock for review.
            var sourceCells=index(original.document().getArray("blocks"));var baselineCells=index(baseline.document().getArray("blocks"));
            for(var value:current.getArray("blocks")){String key=key(value.asDocument());if(!value.equals(sourceCells.get(key))&&!value.equals(baselineCells.get(key)))throw new IllegalStateException("Unexpected source edits prevent automatic recovery");}
            var sourceFluids=index(original.document().getArray("fluids",new BsonArray()));var baselineFluids=index(baseline.document().getArray("fluids",new BsonArray()));var actualFluids=index(current.getArray("fluids",new BsonArray()));
            for(String cell:sourceCells.keySet())if(!Objects.equals(actualFluids.get(cell),sourceFluids.get(cell))&&!Objects.equals(actualFluids.get(cell),baselineFluids.get(cell)))throw new IllegalStateException("Unexpected fluid edits prevent automatic recovery");
            removeEntities(world,original.bounds(),original.mask(),known);store.apply(world,original,original.bounds().origin());store.verify(world,original,original.bounds().origin(),known,true);NativePlacementTransactions.flush(world,original.bounds());
            var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);manager.updatePlot(plot);manager.saveIfDirty();
            Set<UUID> originallyPlaced=new HashSet<>();for(var value:manifest.getArray("instances"))originallyPlaced.add(UUID.fromString(value.asString().getValue()));for(var value:manifest.getArray("guildReturns"))originallyPlaced.add(UUID.fromString(value.asDocument().getString("instance").getValue()));
            for(var item:instances)if(originallyPlaced.contains(item.id())&&item.state().equals("PACKED"))services.provenance().acknowledgeRestored(item.id(),item.revision(),slot.propertyId());
            op=services.journal().find(operation).orElseThrow();
            if(op.state()!=JournalService.State.SNAPSHOT_READY){if(op.state()!=JournalService.State.RECOVERY_REQUIRED)op=services.journal().advance(operation,op.revision(),JournalService.State.RECOVERY_REQUIRED);services.journal().advance(operation,op.revision(),JournalService.State.SNAPSHOT_READY);}
            services.housing().cancelRelocationBeforeMutation(owner,operation);
        }catch(Throwable failure){NativePlacementTransactions.lock(plugin,operation);throw new IllegalStateException("Recovery retained its lock: "+failure.getMessage(),failure);}
    }
    public static NativeSnapshotStore.Bounds bounds(HubPlotRecord plot){var f=plot.getFootprint();return new NativeSnapshotStore.Bounds(f.getMinX(),ChunkUtil.MIN_Y,f.getMinZ(),f.getMaxX()+1,NativeSnapshotStore.MAX_Y,f.getMaxZ()+1);}
    /** Before claim activation, while construction is still locked. Existing unproven plots are never auto-baselined. */
    public static void captureBaseline(EterniaModPlugin plugin,World world,HubPlotRecord plot) {
        try {
            var store=NativePlacementTransactions.snapshots(plugin);var b=bounds(plot);
            Path pointer=store.files().resolve("baseline-"+plot.getPlotId()+".pointer");
            if(Files.exists(pointer)){loadPointer(store,pointer);return;}
            if(plot.hasBuilding()||!plot.getProps().isEmpty())throw new IllegalStateException("Cannot invent original terrain for an existing house");
            var cells=plot.getCells();
            var baseline=store.save(plot.getPlotId()+"-baseline.json",b,cells,store.capture(world,b,ColumnMask.of(cells),Set.of(),true,false));
            writePointer(pointer,baseline.file());
        } catch(IOException e){throw new UncheckedIOException(e);}
    }
    /**
     * After a hex plot grows: adds the new hexagons' ground to the plot's baseline so packing can later restore it.
     * Call on the world thread with {@code plot} already carrying the grown cells, before players can build on the new
     * hexagon (the plots engine calls it in the expansion tail and again when it reapplies a pending expansion).
     * Idempotent: a baseline that already has these cells is left alone. Old baseline files are kept, never deleted;
     * the pointer is replaced atomically, so an interruption leaves either the old or the grown baseline.
     */
    public static void extendBaseline(EterniaModPlugin plugin,World world,HubPlotRecord plot) {
        var cells=plot.getCells();if(cells==null)throw new IllegalStateException("Only hexagon plots grow");
        try {
            var store=NativePlacementTransactions.snapshots(plugin);Path pointer=store.files().resolve("baseline-"+plot.getPlotId()+".pointer");
            var old=loadPointer(store,pointer);
            if(cells.equals(old.cells()))return;
            if(old.cells()==null||!old.cells().lattice().equals(cells.lattice())||!cells.asSet().containsAll(old.cells().asSet()))throw new IllegalStateException("Plot baseline is not a smaller set of the same hexagons");
            var added=cells.cells().stream().filter(c->!old.cells().contains(c)).toList();
            var addedCells=com.hexvane.eterniamod.hexplots.PlotCells.of(cells.lattice(),added);
            var addedBox=addedCells.bounds();var addedBounds=new NativeSnapshotStore.Bounds(addedBox.x(),old.bounds().minY(),addedBox.z(),addedBox.endX(),old.bounds().maxY(),addedBox.endZ());
            var grown=bounds(plot);
            var capture=store.capture(world,addedBounds,ColumnMask.of(addedCells),Set.of(),true,false);
            var merged=HexRelocation.mergeBaseline(old.document(),old.bounds(),capture,addedBounds,grown);
            var baseline=store.save(plot.getPlotId()+"-baseline-"+cells.size()+"-"+UUID.randomUUID()+".json",grown,cells,merged);
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
            var cells=plot.getCells();var mask=ColumnMask.of(cells);
            if(!Objects.equals(baseline.cells(),cells))throw new IllegalStateException("Baseline hexagons differ from property; finish adding its newest hexagon first");
            var instances=services.provenance().instances(plot.getPlotId()).stream().filter(i->i.state().equals("PLACED")).toList();
            Set<UUID> known=new HashSet<>();instances.forEach(i->known.add(i.id()));
            if(plot.hasBuilding()&&instances.stream().noneMatch(i->"HOUSE".equals(i.nativeData().get("kind")))||plot.getProps().stream().anyMatch(p->!known.contains(p.getInstanceId())))throw new IllegalStateException("Legacy object lacks proven ownership; migration required");
            var current=store.save(operation+"-property.json",b,cells,store.capture(world,b,mask,known,true,true));
            for(var item:instances){var placed=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));NativeDecorativeEntities.requireIdentity(placed.document(),current.document(),item.id());}
            if(!index(current.document().getArray("blocks")).keySet().equals(index(baseline.document().getArray("blocks")).keySet()))throw new IllegalStateException("Protected infrastructure changed since the plot baseline; review required");
            BsonDocument personal=current.document().clone();BsonArray returned=new BsonArray();
            for(var item:instances)if(!item.owner().equals(owner)) {
                if(item.owner().kind()!=Owner.Kind.GUILD)throw new IllegalStateException("Property contains foreign player-owned content");
                var before=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash")));
                var placedAfter=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));
                var changed=difference(placedAfter.document(),before.document());
                var actual=boundsIn(current,before.bounds());try{requireSameBlocks(actual,placedAfter.document(),changed,before.bounds());}catch(ObjectEdited edited){plugin.getLogger().atWarning().log("Plot pack refused, guild object %s changed %s",item.id(),edited.detail());throw edited;}
                var sub=onlyCapturedCells(actual,changed);
                NativeDecorativeEntities.filterInstances(sub,item.id(),true);NativeDecorativeEntities.requireIdentity(placedAfter.document(),sub,item.id());
                requireEmptyGuildContainers(sub);
                var guildSnapshot=store.save(operation+"-guild-"+item.id()+".json",before.bounds(),cells,sub);
                // Overlapping provenance cannot tell whose later additions should be removed.
                for(var other:instances)if(!other.id().equals(item.id())&&Long.parseLong(other.nativeData().getOrDefault("placedAt","9223372036854775807"))>=Long.parseLong(item.nativeData().getOrDefault("placedAt","0"))) {
                    var otherBefore=store.load(new SnapshotFiles.Saved(other.nativeData().get("before"),other.nativeData().get("beforeHash")));
                    if(overlaps(before.bounds(),otherBefore.bounds()))throw new IllegalStateException("Overlapping player/guild objects require ownership review");
                }
                var guildBefore=store.save(operation+"-guild-"+item.id()+"-before.json",before.bounds(),cells,onlyCapturedCells(before.document(),changed));
                replaceRegion(personal,b,guildBefore);
                NativeDecorativeEntities.filterInstances(personal,item.id(),false);
                returned.add(new BsonDocument("instance",new BsonString(item.id().toString())).append("snapshot",new BsonString(reference(guildSnapshot.file()))));
            }
            var delta=store.save(operation+"-personal.json",b,cells,difference(personal,baseline.document()));
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
            mutated=true;removeEntities(world,b,mask,known);store.apply(world,baseline,b.origin());store.verify(world,baseline,b.origin(),Set.of(),true);NativePlacementTransactions.flush(world,b);
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
            if(oldPlot.isHex())throw new IllegalStateException("A packed hexagon plot is set down on hexagons");
            if(plugin.getInfrastructure().world(world.getName()).map(HousingInfrastructure.WorldPlan::hexPlots).orElse(false))throw new IllegalStateException("This world uses hexagon plots; set this plot down in a world with rectangle plots");
            var original=store.load(parseReference(manifest.getString("original").getValue()));
            var personal=store.load(parseReference(manifest.getString("personal").getValue()));
            int dy=Math.subtractExact(groundY,oldPlot.getFootprint().resolveVisualCenterY());
            var inset=PlotSnapshotTransform.centeredInset(original.bounds().maxX()-original.bounds().minX(),original.bounds().maxZ()-original.bounds().minZ(),destination.width(),destination.depth());
            var movedPersonal=PlotSnapshotTransform.translate(personal.document(),inset.x(),dy,inset.z(),0,ChunkUtil.MIN_Y,0,destination.width(),NativeSnapshotStore.MAX_Y,destination.depth());
            var infra=plugin.getInfrastructure().world(world.getName()).orElseThrow();if(!infra.supportsHousing())throw new IllegalStateException("Use a world with housing enabled");
            UUID guild=scope==HousingRules.Scope.PUBLIC?null:services.guilds().membership(actor).orElseThrow().guildId();
            if(owner.kind()==Owner.Kind.GUILD&&(scope!=HousingRules.Scope.GUILD_ROOT||!owner.id().equals(guild)))throw new IllegalStateException("Guild property owner cannot change");
            if(owner.kind()==Owner.Kind.PLAYER&&scope==HousingRules.Scope.GUILD_ROOT)throw new IllegalStateException("Player plot cannot become guild property");
            if(paid&&!services.ownership().owns(owner,owner.kind()==Owner.Kind.GUILD?"eternia:plot/guild_64":"eternia:plot/personal_32"))throw new IllegalStateException("Plot size entitlement required");
            var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
            var anchors=manager.listPlots().stream().map(p->new HousingRules.Anchor(p.getPlotId(),NativeHousingChecks.rect(p.getFootprint()),p.getGuildOwnerUuid()!=null?HousingRules.Scope.GUILD_ROOT:p.getAttachedGuildUuid()!=null?HousingRules.Scope.GUILD_MEMBER:HousingRules.Scope.PUBLIC,p.getGuildOwnerUuid()!=null?p.getGuildOwnerUuid():p.getAttachedGuildUuid(),p.hasBuilding())).toList();
            var result=HousingRules.claim(new HousingRules.Candidate(destination,scope,guild,paid,guild==null?0:services.guilds().roster(actor).size()),anchors,infra.roads(),infra.portals(),com.hexvane.eterniamod.pathtool.SplineRoadTool.roadNetwork(world.getName(),infra),plugin.getRuntimeConfig().local());if(!result.valid())throw new IllegalStateException(result.message());
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
    /** The cells of the owner's packed hex plot, or empty when nothing hex is packed. Domain read, any thread. */
    public Optional<PlotCells> packedCells(Owner owner){
        var property=plugin.getServices().housing().property(owner).orElse(null);
        if(property==null||property.slot().state()!=HousingService.State.PACKED||property.location()==null)return Optional.empty();
        return Optional.ofNullable(property.location().cells());
    }
    /**
     * Could the owner's packed plot be set down on {@code cells} right now? The preview twin of {@link #restoreCells}
     * (land rules only; the commit also checks roads, structures and the snapshot). World thread.
     */
    public HexClaimRules.Result restoreCheck(World world,Owner owner,UUID actor,HousingRules.Scope scope,Collection<HexCoord> cells){
        var packed=packedCells(owner).orElse(null);if(packed==null)return HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT);
        var view=HexLand.view(plugin,world).orElse(null);if(view==null)return HexClaimRules.Result.of(HexClaimRules.Reason.NO_LATTICE);
        if(!com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()).isEmpty())return HexClaimRules.Result.of(HexClaimRules.Reason.ROAD_WORK);
        return HexRelocation.check(view.land(),HexClaimCheck.kind(scope),packed,cells,anchors(view,actor,scope));
    }
    /**
     * Where the owner's packed hex plot can be set down near block (x, z): every valid placement of its exact shape
     * and turn with a cell within {@code radius} hexagons, nearest first, at most {@code limit}. For the claim map and
     * any destination picker. Empty when nothing hex is packed or the world has no grid. World thread.
     */
    public List<PlotCells> restoreDestinations(World world,Owner owner,UUID actor,HousingRules.Scope scope,int x,int z,int radius,int limit){
        var packed=packedCells(owner).orElse(null);var view=HexLand.view(plugin,world).orElse(null);
        if(packed==null||view==null||!com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()).isEmpty())return List.of();
        return HexRelocation.destinations(view.land(),HexClaimCheck.kind(scope),packed,anchors(view,actor,scope),view.lattice().cellAt(x,z),radius,limit);
    }
    private Set<HexCoord> anchors(com.hexvane.eterniamod.hexplots.HexLandView view,UUID actor,HousingRules.Scope scope){
        if(scope!=HousingRules.Scope.GUILD_MEMBER)return Set.of();
        var membership=plugin.getServices().guilds().membership(actor).orElse(null);
        return membership==null?Set.of():view.guildLand(membership.guildId());
    }
    /**
     * Sets a packed hex plot down on {@code destination}, which must be its own shape in the same turn
     * ({@link HexRelocation#move}). Everything is checked before the first world write. The destination is captured,
     * written and verified only inside its cells, so neighbouring plots whose boxes overlap this one are never read or
     * touched. Same journal as {@link #restore}: an error after the first write locks the property for recovery and
     * keeps every snapshot. World thread.
     */
    public void restoreCells(World world,UUID actor,Owner owner,UUID operation,PlotCells destination,int groundY,HousingRules.Scope scope) {
        var services=plugin.getServices();var slot=services.housing().find(owner).orElseThrow();
        if(slot.state()!=HousingService.State.PACKED||!slot.operationId().equals(operation))throw new IllegalStateException("No matching packed plot");
        if(!com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()).isEmpty())throw new IllegalStateException("Recover the interrupted public road before restoring a plot in this world");
        var lattice=destination.lattice();
        if(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()).stream().anyMatch(r->lattice.cellsTouching(r).stream().anyMatch(destination::contains)))throw new IllegalStateException("Remove the intersecting guild road segment before restoring a plot here; rebuild it after the claim");
        if(owner.kind()==Owner.Kind.PLAYER&&!owner.id().equals(actor)||owner.kind()==Owner.Kind.GUILD&&!services.guilds().can(actor,owner.id(),"housing.claim"))throw new IllegalStateException("Owner permission required");
        var store=snapshots();boolean started=false;
        try {
            var op=services.journal().find(operation).orElseThrow();
            var manifest=BsonDocument.parse(new String(store.files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())),StandardCharsets.UTF_8));
            var oldPlot=JSON.fromJson(manifest.getDocument("plot").toJson(),HubPlotRecord.class);
            var packed=oldPlot.getCells();if(packed==null)throw new IllegalStateException("This packed plot is a rectangle; set it down in a world with rectangle plots");
            var infra=plugin.getInfrastructure().world(world.getName()).orElseThrow();if(!infra.supportsHousing())throw new IllegalStateException("Use a world with housing enabled");
            if(!infra.hexPlots()||!infra.hexLattice().map(lattice::equals).orElse(false))throw new IllegalStateException("Pick hexagons in a world that uses this hexagon grid");
            var move=HexRelocation.move(packed,destination);
            var original=store.load(parseReference(manifest.getString("original").getValue()));
            var personal=store.load(parseReference(manifest.getString("personal").getValue()));
            if(!Objects.equals(original.cells(),packed)||!Objects.equals(personal.cells(),packed))throw new IllegalStateException("Packed snapshots do not match the plot's hexagons");
            var box=destination.bounds();int width=original.bounds().maxX()-original.bounds().minX(),depth=original.bounds().maxZ()-original.bounds().minZ();
            if(box.width()!=width||box.depth()!=depth)throw new IllegalStateException("Packed snapshot box does not match its hexagons");
            int dy=Math.subtractExact(groundY,oldPlot.getFootprint().resolveVisualCenterY());
            var movedPersonal=PlotSnapshotTransform.translate(personal.document(),0,dy,0,0,ChunkUtil.MIN_Y,0,width,NativeSnapshotStore.MAX_Y,depth);
            UUID guild=scope==HousingRules.Scope.PUBLIC?null:services.guilds().membership(actor).orElseThrow().guildId();
            if(owner.kind()==Owner.Kind.GUILD&&(scope!=HousingRules.Scope.GUILD_ROOT||!owner.id().equals(guild)))throw new IllegalStateException("Guild property owner cannot change");
            if(owner.kind()==Owner.Kind.PLAYER&&scope==HousingRules.Scope.GUILD_ROOT)throw new IllegalStateException("Player plot cannot become guild property");
            var view=HexLand.view(plugin,world).orElseThrow(()->new IllegalStateException("This world has no hexagon grid"));
            var verdict=HexRelocation.check(view.land(),HexClaimCheck.kind(scope),packed,destination.cells(),anchors(view,actor,scope));
            if(!verdict.valid())throw new IllegalStateException(verdict.message());
            Vector3i target=new Vector3i(box.x(),original.bounds().minY(),box.z());
            var movedBounds=new NativeSnapshotStore.Bounds(target.x,target.y,target.z,box.endX(),original.bounds().maxY(),box.endZ());
            var mask=ColumnMask.of(destination);
            HexRelocation.requireInside(movedPersonal,mask,target.x,target.z);
            int dx=move.dx(),dz=move.dz();
            var settings=com.hexvane.eterniamod.hexplots.HexPlotSettings.current();var roads=plugin.getInfrastructure().structureRoads(world.getName());
            if(oldPlot.getBuilding()!=null){var h=oldPlot.getBuilding();var definition=plugin.getBuildingCatalog().get(h.getBuildingId());if(definition==null)throw new IllegalStateException("House catalog revision is missing");var prefab=com.hexvane.eterniamod.prefab.PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath());if(prefab==null)throw new IllegalStateException("House prefab is missing");var footprint=com.hexvane.eterniamod.placement.PlotFootprintUtil.computeFootprint(new Vector3i(h.getAnchorX()+dx,h.getAnchorY()+dy,h.getAnchorZ()+dz),h.resolveRotationYaw(),prefab);var structure=HexClaimRules.structure(destination,NativeHousingChecks.rect(footprint),roads,settings);if(!structure.valid())throw new IllegalStateException(structure.message());}
            for(var prop:oldPlot.getProps()){var definition=plugin.getPropCatalog().get(prop.getPropId());if(definition!=null&&definition.getCategory().equals("addition")){var prefab=com.hexvane.eterniamod.prefab.PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath());if(prefab==null)throw new IllegalStateException("Addition prefab is missing");var footprint=com.hexvane.eterniamod.placement.PlotFootprintUtil.computeFootprint(new Vector3i(prop.getAnchorX()+dx,prop.getAnchorY()+dy,prop.getAnchorZ()+dz),prop.resolveRotationYaw(),prefab);var structure=HexClaimRules.structure(destination,NativeHousingChecks.rect(footprint),roads,settings);if(!structure.valid())throw new IllegalStateException(structure.message());}}
            // A full-height translated snapshot must never carve a protected road/portal column.
            for(var value:movedPersonal.getArray("blocks")){var cell=value.asDocument();int x=target.x+cell.getInt32("x").getValue(),z=target.z+cell.getInt32("z").getValue();if(plugin.getInfrastructure().protectedColumn(world.getName(),x,z))throw new IllegalStateException("Packed content intersects a protected road or portal");}
            var newBaseline=store.save(operation+"-destination-baseline.json",movedBounds,destination,store.capture(world,movedBounds,mask,Set.of(),true,true));
            var merged=newBaseline.document().clone();overlay(merged,movedPersonal);
            var after=store.save(operation+"-destination.json",movedBounds,destination,merged);
            Set<UUID> ids=new HashSet<>();for(var value:manifest.getArray("instances"))ids.add(UUID.fromString(value.asString().getValue()));
            // Object before/after images are written before the first world change, so a bad image refuses instead of locking.
            var oldBaseline=store.load(parseReference(manifest.getString("baseline").getValue()));
            var rebased=new ArrayList<Map.Entry<ProvenanceService.Instance,Map<String,String>>>();
            for(var item:services.provenance().instances(slot.propertyId()))if(ids.contains(item.id())) {
                var oldBefore=store.load(new SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash")));var oldBounds=oldBefore.bounds();
                var moved=new NativeSnapshotStore.Bounds(oldBounds.minX()+dx,oldBounds.minY()+dy,oldBounds.minZ()+dz,oldBounds.maxX()+dx,oldBounds.maxY()+dy,oldBounds.maxZ()+dz);
                var beforeDocument=boundsIn(newBaseline,moved);overlay(beforeDocument,difference(oldBefore.document(),boundsIn(oldBaseline,oldBounds)));
                var before=store.save(operation+"-"+item.id()+"-before.json",moved,destination,HexRelocation.keepInside(beforeDocument,mask,moved.minX(),moved.minZ()));
                var oldAfter=store.load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));
                var afterDocument=boundsIn(newBaseline,moved);overlay(afterDocument,difference(oldAfter.document(),boundsIn(oldBaseline,oldBounds)));
                var afterInstance=store.save(operation+"-"+item.id()+"-after.json",moved,destination,HexRelocation.keepInside(afterDocument,mask,moved.minX(),moved.minZ()));
                var data=new HashMap<>(item.nativeData());data.put("before",before.file().reference());data.put("beforeHash",before.file().sha256());data.put("after",afterInstance.file().reference());data.put("afterHash",afterInstance.file().sha256());
                rebased.add(Map.entry(item,data));
            }
            services.housing().beginRestore(owner,operation);started=true;
            services.housing().updateRestoreLocation(owner,operation,HousingService.ClaimLocation.ofCells(world.getName(),destination,guild==null?HousingService.ClaimScope.PUBLIC:HousingService.ClaimScope.GUILD,guild,oldPlot.hasBuilding()));
            store.apply(world,after,target);store.verify(world,after,target,ids,true);NativePlacementTransactions.flush(world,movedBounds);
            var projection=new HubPlotRecord(oldPlot.getPlotId(),world.getName(),HubPlotFootprint.forCells(destination,groundY),owner.kind()==Owner.Kind.PLAYER?owner.id():null);
            projection.setGuildOwnerUuid(owner.kind()==Owner.Kind.GUILD?owner.id():null);projection.setAttachedGuildUuid(owner.kind()==Owner.Kind.PLAYER?guild:null);
            if(oldPlot.getBuilding()!=null&&services.provenance().instances(slot.propertyId()).stream().anyMatch(i->ids.contains(i.id())&&"HOUSE".equals(i.nativeData().get("kind")))){var h=oldPlot.getBuilding();projection.setBuilding(new HubPlotBuilding(h.getBuildingId(),h.getAnchorX()+dx,h.getAnchorY()+dy,h.getAnchorZ()+dz,h.resolveRotationYaw(),List.of()));}
            for(var p:oldPlot.getProps())if(ids.contains(p.getInstanceId()))projection.addProp(new HubPlotProp(p.getInstanceId(),p.getPropId(),p.getAnchorX()+dx,p.getAnchorY()+dy,p.getAnchorZ()+dz,p.resolveRotationYaw()));
            var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
            manager.addPlot(projection);manager.saveIfDirty();writePointer(store.files().resolve("baseline-"+projection.getPlotId()+".pointer"),newBaseline.file());
            for(var entry:rebased)services.provenance().acknowledgeRestored(entry.getKey().id(),entry.getKey().revision(),slot.propertyId(),entry.getValue());
            op=services.journal().find(operation).orElseThrow();services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);services.housing().activate(owner,operation);
        }catch(Throwable failure){if(started)NativePlacementTransactions.lock(plugin,operation);throw new IllegalStateException(started?"Restore locked for recovery; destination snapshot retained":"Packed plot remains safe: "+failure.getMessage(),failure);}
    }
    private static boolean overlaps(NativeSnapshotStore.Bounds a,NativeSnapshotStore.Bounds b){return a.minX()<b.maxX()&&b.minX()<a.maxX()&&a.minY()<b.maxY()&&b.minY()<a.maxY()&&a.minZ()<b.maxZ()&&b.minZ()<a.maxZ();}
    static BsonDocument difference(BsonDocument current,BsonDocument baseline){var result=current.clone();var original=index(baseline.getArray("blocks"));var fluids=index(baseline.getArray("fluids",new BsonArray()));var currentFluids=index(current.getArray("fluids",new BsonArray()));var changed=new BsonArray();var changedFluids=new BsonArray();for(var v:current.getArray("blocks")){var d=v.asDocument();String key=key(d);if(!d.equals(original.get(key))||!Objects.equals(fluids.get(key),currentFluids.get(key))){changed.add(d);if(currentFluids.containsKey(key))changedFluids.add(currentFluids.get(key));}}result.put("blocks",changed);result.put("fluids",changedFluids);return result;}
    static BsonDocument onlyCapturedCells(BsonDocument source,BsonDocument mask){var keys=index(mask.getArray("blocks")).keySet();var result=source.clone();for(String type:List.of("blocks","fluids")){var selected=new BsonArray();for(var value:source.getArray(type,new BsonArray()))if(keys.contains(key(value.asDocument())))selected.add(value);result.put(type,selected);}return result;}
    static void requireSameBlocks(BsonDocument actual,BsonDocument expected,BsonDocument mask){requireSameBlocks(actual,expected,mask,null);}
    /**
     * Refuses when a block an object placed was replaced since. A block switching between its own states (a door
     * opened, a fireplace lit, a crop grown, stairs turning a corner) is the same block, not an edit. {@code where}
     * (the snapshot's bounds) turns the refusal into a world position the player can find.
     */
    static void requireSameBlocks(BsonDocument actual,BsonDocument expected,BsonDocument mask,NativeSnapshotStore.Bounds where){
        var current=index(actual.getArray("blocks"));var prior=index(expected.getArray("blocks"));
        for(var value:mask.getArray("blocks")){
            String k=key(value.asDocument());var a=current.get(k);var e=prior.get(k);
            if(a!=null&&e!=null&&sameBlock(a.asDocument(),e.asDocument()))continue;
            var cell=value.asDocument();int x=cell.getInt32("x").getValue(),y=cell.getInt32("y").getValue(),z=cell.getInt32("z").getValue();
            if(where!=null){x+=where.minX();y+=where.minY();z+=where.minZ();}
            String now=a==null?"nothing":a.asDocument().getString("name",new BsonString("?")).getValue(),was=e==null?"nothing":e.asDocument().getString("name",new BsonString("?")).getValue();
            throw new ObjectEdited(x,y,z,where!=null,was,now);
        }
    }
    /** A placed block was replaced; the message tells the player where. */
    public static final class ObjectEdited extends IllegalStateException {
        public final int x,y,z;public final String placed,now;
        ObjectEdited(int x,int y,int z,boolean inWorld,String placed,String now){super(inWorld
            ?"A block of this was changed after it was placed, at "+x+" "+y+" "+z+". Put the original block back there and try again."
            :"A block of this was changed after it was placed. Put the original block back and try again.");this.x=x;this.y=y;this.z=z;this.placed=placed;this.now=now;}
        /** For the server log: where, and what was placed against what stands there now. */
        public String detail(){return "at "+x+" "+y+" "+z+": placed "+placed+", now "+now;}
    }
    static boolean sameBlock(BsonDocument a,BsonDocument e){
        a=a.clone();e=e.clone();for(var d:List.of(a,e)){d.remove("components");d.remove("support");}
        if(a.equals(e))return true;
        String an=a.getString("name",new BsonString("")).getValue(),en=e.getString("name",new BsonString("")).getValue();
        if(an.equals(en)||!baseBlock(an).equals(baseBlock(en)))return false;
        // States of one block: the name and possibly the turn change (a stair corner), never the filler cell.
        return a.getInt32("filler",new BsonInt32(0)).equals(e.getInt32("filler",new BsonInt32(0)));
    }
    /** The block a state belongs to: "*Door_State_Definitions_Open" is a state of "Door". */
    static String baseBlock(String name){
        if(name.startsWith("*")){int i=name.indexOf("_State_Definitions_");if(i>1)return name.substring(1,i);}
        return name;
    }
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
    private static void removeEntities(World world,NativeSnapshotStore.Bounds b,ColumnMask mask,Set<UUID> known){var store=world.getEntityStore().getStore();for(var ref:NativeSnapshotStore.entities(world,b,mask)){var link=store.getComponent(ref,EterniaPlacedInstance.getComponentType());if(link==null||!known.contains(link.getInstanceId()))throw new IllegalStateException("Entity ownership changed before removal");NativeDecorativeEntities.remove(world,ref);}}
    public static String reference(SnapshotFiles.Saved file){return file.reference()+"#"+file.sha256();}
    public static SnapshotFiles.Saved parseReference(String value){int split=value.lastIndexOf('#');if(split<1)throw new IllegalArgumentException("Snapshot reference needs SHA-256");return new SnapshotFiles.Saved(value.substring(0,split),value.substring(split+1));}
    private static NativeSnapshotStore.Snapshot loadPointer(NativeSnapshotStore store,Path file)throws IOException{return store.load(parseReference(Files.readString(file)));}
    private static void writePointer(Path path,SnapshotFiles.Saved saved)throws IOException{var temp=path.resolveSibling(path.getFileName()+".pending");byte[] data=reference(saved).getBytes(StandardCharsets.UTF_8);try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var bytes=ByteBuffer.wrap(data);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
}
