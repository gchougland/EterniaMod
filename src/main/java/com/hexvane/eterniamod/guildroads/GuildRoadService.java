package com.hexvane.eterniamod.guildroads;

import com.google.gson.Gson;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.customization.CustomizationCatalog;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.bson.*;
import static com.hexvane.eterniamod.guildroads.GuildRoadRegistry.*;

/** Native road edits are independent guild custody, never player grants or plot anchors. */
final class GuildRoadService {
    static final String CAPABILITY="housing.road.manage";
    private static final Gson JSON=new Gson();
    record Preview(UUID actor,Road road,boolean remove,Instant expires){}
    private final EterniaModPlugin plugin;private final GuildRoadRegistry registry;private final CustomizationCatalog catalog;
    private volatile Map<UUID,Road> emergency=Map.of();
    GuildRoadService(EterniaModPlugin plugin)throws IOException{
        this.plugin=plugin;registry=new GuildRoadRegistry(plugin.getDataDirectory().resolve("guild-roads"));catalog=new CustomizationCatalog(plugin.getDataDirectory());
        for(var road:registry.all()){files().files().read(road.before());files().files().read(road.after());}
        // The journal is written before the registry and the world. Reconcile that crash boundary conservatively.
        for(var op:plugin.getServices().journal().unfinished())if(op.kind().startsWith("GUILD_ROAD_")){
            if(op.snapshotRef().isBlank()){
                if(op.state()!=JournalService.State.PREPARED)throw new IOException("Road operation lacks its verified manifest");
                plugin.getServices().journal().advance(op.id(),op.revision(),JournalService.State.CANCELLED);continue;
            }
            var road=JSON.fromJson(new String(files().files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())),StandardCharsets.UTF_8),Road.class);
            if(!road.operation().equals(op.id())||!Owner.guild(road.guild()).equals(op.owner()))throw new IOException("Road journal identity differs from its manifest");
            var current=registry.find(road.id()).orElse(null);
            if(current!=null&&!current.operation().equals(op.id())&&!RoadRecovery.removalPredecessor(current,road,op,plugin.getServices().journal().find(current.operation()).orElse(null)))throw new IOException("Road journal conflicts with a later registry operation");
            files().files().read(road.before());files().files().read(road.after());
            registry.put(road.state(State.RECOVERY,op.id()));
        }
    }
    private NativeSnapshotStore files(){return NativePlacementTransactions.snapshots(plugin);}
    List<Road> roads(){return RoadRecovery.protectedRoads(registry.all(),emergency);}
    private Road find(UUID id){return emergency.containsKey(id)?emergency.get(id):registry.find(id).orElseThrow();}
    UUID membership(UUID actor){return plugin.getServices().guilds().membership(actor).map(GuildService.Membership::guildId).orElse(null);}
    boolean canManage(UUID actor,UUID guild){return guild!=null&&plugin.getServices().guilds().can(actor,guild,CAPABILITY);}
    HubPlotRecord personalPlot(World world,UUID actor,int x,int z){return EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).listPlots().stream().filter(p->actor.equals(p.getOwnerUuid())&&p.getGuildOwnerUuid()==null&&p.getAttachedGuildUuid()!=null&&p.getFootprint().containsHorizontal(x,z)).findFirst().orElse(null);}
    boolean easement(HubPlotRecord plot){return registry.easement(plot.getPlotId(),plot.getAttachedGuildUuid());}
    synchronized void setEasement(World world,UUID actor,UUID property,boolean allow)throws IOException{
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(property);
        if(plot==null||!actor.equals(plot.getOwnerUuid())||plot.getGuildOwnerUuid()!=null||plot.getAttachedGuildUuid()==null||!plot.getAttachedGuildUuid().equals(membership(actor)))throw new IllegalStateException("Only the current attached plot owner can change its road easement");
        NativePlacementTransactions.requireActive(plugin,plot);
        if(!allow&&roads().stream().anyMatch(r->r.world().equals(world.getName())&&r.rectangle().overlaps(NativeHousingChecks.rect(plot.getFootprint()))))throw new IllegalStateException("Remove intersecting road segments before revoking this easement");
        registry.easement(property,plot.getAttachedGuildUuid(),allow);
    }
    private boolean canRemove(World world,UUID actor,Road road){
        if(canManage(actor,road.guild()))return true;
        return EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).listPlots().stream().anyMatch(p->actor.equals(p.getOwnerUuid())&&p.getGuildOwnerUuid()==null&&road.guild().equals(p.getAttachedGuildUuid())&&road.rectangle().overlaps(NativeHousingChecks.rect(p.getFootprint())));
    }
    List<CustomizationCatalog.PathStyle> styles(UUID guild){return catalog.paths().stream().filter(s->s.id().equals("cobblestone")||plugin.getServices().ownership().owns(Owner.guild(guild),"eternia:path/"+s.id())).toList();}
    UUID guild(UUID actor){UUID guild=plugin.getServices().guilds().membership(actor).orElseThrow(()->new IllegalStateException("Join a guild to manage its roads")).guildId();if(!plugin.getServices().guilds().can(actor,guild,CAPABILITY))throw new IllegalStateException("Guild road permission is required");return guild;}
    private HubPlotRecord root(World world,UUID guild,boolean active){
        var slot=plugin.getServices().housing().find(Owner.guild(guild)).orElseThrow(()->new IllegalStateException("Place the guild estate first"));
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());
        if(plot==null||!guild.equals(plot.getGuildOwnerUuid())||!world.getName().equals(plot.getWorldName())||slot.state()!=HousingService.State.ACTIVE)throw new IllegalStateException("The guild estate must be active in this world");
        if(active)NativePlacementTransactions.requireActive(plugin,plot);return plot;
    }
    synchronized Preview preview(World world,UUID actor,GuildRoadPlanner.Cell start,GuildRoadPlanner.Cell end,int groundY,String styleId)throws Exception{
        world.getEntityStore().getStore().assertThread();UUID guild=guild(actor);var root=root(world,guild,true);
        var style=styles(guild).stream().filter(s->s.id().equals(styleId)).findFirst().orElseThrow(()->new IllegalStateException("Road style is unavailable"));plainBlock(style.blockId());
        var route=validateRoute(world,guild,actor,start,end,groundY,null);var rect=GuildRoadPlanner.rectangle(route);var bounds=bounds(rect,groundY);
        var beforeDoc=files().capture(world,bounds,Set.of(),false,false);requireGround(beforeDoc,route.size());
        var afterDoc=beforeDoc.clone();for(var value:afterDoc.getArray("blocks"))value.asDocument().put("name",new BsonString(style.blockId()));
        UUID operation=UUID.randomUUID();var before=files().save(operation+"-road-before.json",bounds,beforeDoc);var after=files().save(operation+"-road-after.json",bounds,afterDoc);
        return new Preview(actor,new Road(UUID.randomUUID(),guild,root.getPlotId(),world.getName(),rect,groundY,style.blockId(),before.file(),after.file(),operation,State.ADDING),false,Instant.now().plusSeconds(90));
    }
    synchronized Preview previewRemoval(World world,UUID actor,UUID id)throws Exception{
        var road=find(id);if(!canRemove(world,actor,road)||!road.world().equals(world.getName())||road.state()==State.REMOVED)throw new IllegalStateException("Guild road permission or ownership of an intersected attached plot is required");
        return new Preview(actor,road,true,Instant.now().plusSeconds(90));
    }
    synchronized void confirm(World world,Preview preview)throws Exception{
        world.getEntityStore().getStore().assertThread();if(Instant.now().isAfter(preview.expires())||!world.getName().equals(preview.road().world()))throw new IllegalStateException("Road preview expired or belongs to another world");
        var road=preview.road();
        if(preview.remove()){
            var current=find(road.id());if(!current.equals(road)||!canRemove(world,preview.actor(),current))throw new IllegalStateException("Road or removal permission changed after preview");remove(world,current);return;
        }
        if(!guild(preview.actor()).equals(road.guild()))throw new IllegalStateException("Your guild changed");root(world,road.guild(),true);plainBlock(road.blockId());
        if(catalog.paths().stream().filter(s->s.blockId().equals(road.blockId())).noneMatch(s->styles(road.guild()).contains(s)))throw new IllegalStateException("Road style ownership changed");
        validateRoute(world,road.guild(),preview.actor(),start(road),end(road),road.groundY(),null);
        var before=files().load(road.before());requireSame(files().capture(world,before.bounds(),Set.of(),false,false),before.document());
        var journal=plugin.getServices().journal();var op=prepare(road,"GUILD_ROAD_ADD");
        try{
            registry.put(road);var after=files().load(road.after());
            scoped(world,road,()->{files().apply(world,after,after.bounds().origin());files().verify(world,after,after.bounds().origin(),Set.of(),false);NativePlacementTransactions.flush(world,after.bounds());return null;});
            op=journal.advance(op.id(),op.revision(),JournalService.State.WORLD_APPLIED);registry.put(road.state(State.ACTIVE,op.id()));journal.advance(op.id(),op.revision(),JournalService.State.COMPLETED);
        }catch(Throwable failure){retainRecovery(road,op.id(),failure);throw new IllegalStateException("Road operation needs recovery. Its snapshots and protected columns are retained.",failure);}
    }
    /** Complete intersecting segments are returned to terrain before any plot baseline is compared. */
    synchronized void beforePack(World world,HubPlotRecord plot)throws Exception{
        world.getEntityStore().getStore().assertThread();var rect=NativeHousingChecks.rect(plot.getFootprint());
        for(var road:roads().stream().filter(r->r.world().equals(world.getName())&&(r.rectangle().overlaps(rect)||plot.getGuildOwnerUuid()!=null&&plot.getGuildOwnerUuid().equals(r.guild()))).toList()){
            if(road.state()!=State.ACTIVE)throw new IllegalStateException("Recover the interrupted guild road before this property can move");
            remove(world,road);
        }
        if(plot.getGuildOwnerUuid()==null&&plot.getAttachedGuildUuid()!=null&&registry.easement(plot.getPlotId(),plot.getAttachedGuildUuid()))registry.easement(plot.getPlotId(),plot.getAttachedGuildUuid(),false);
    }
    private void remove(World world,Road current)throws Exception{
        publicClear(world,current.rectangle());boolean recovering=current.state()!=State.ACTIVE;
        for(var plot:EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).listPlots())if(current.rectangle().overlaps(NativeHousingChecks.rect(plot.getFootprint()))){var owner=HousingAccess.owner(plot);var slot=owner==null?null:plugin.getServices().housing().find(owner).orElse(null);if(slot==null||slot.state()!=HousingService.State.ACTIVE||!slot.propertyId().equals(plot.getPlotId()))throw new IllegalStateException("An intersecting property is moving or requires recovery; retain the road until that operation is resolved");}
        var before=files().load(current.before());var after=files().load(current.after());
        scoped(world,current,()->{var actual=files().capture(world,before.bounds(),Set.of(),false,false);if(recovering)requireKnown(actual,before.document(),after.document());else requireSame(actual,after.document());return null;});
        var journal=plugin.getServices().journal();var road=current.state(State.REMOVING,recovering?current.operation():UUID.randomUUID());
        var op=recovering?journal.find(current.operation()).orElseThrow():prepare(road,"GUILD_ROAD_REMOVE");
        try{
            if(recovering&&op.state()!=JournalService.State.RECOVERY_REQUIRED)op=journal.advance(op.id(),op.revision(),JournalService.State.RECOVERY_REQUIRED);
            registry.put(road);
            scoped(world,road,()->{files().apply(world,before,before.bounds().origin());files().verify(world,before,before.bounds().origin(),Set.of(),false);NativePlacementTransactions.flush(world,before.bounds());return null;});
            if(recovering){registry.put(road.state(State.REMOVED,op.id()));journal.advance(op.id(),op.revision(),JournalService.State.CANCELLED);}
            else{op=journal.advance(op.id(),op.revision(),JournalService.State.WORLD_APPLIED);registry.put(road.state(State.REMOVED,op.id()));journal.advance(op.id(),op.revision(),JournalService.State.COMPLETED);}
            var remaining=new HashMap<>(emergency);remaining.remove(road.id());emergency=Map.copyOf(remaining);
        }catch(Throwable failure){retainRecovery(road,op.id(),failure);throw new IllegalStateException("Road removal needs recovery; the saved terrain and protected road are retained",failure);}
    }
    private JournalService.Operation prepare(Road road,String kind)throws IOException{
        var journal=plugin.getServices().journal();var op=journal.prepare(road.operation(),kind,Owner.guild(road.guild()),road.rootProperty().toString());
        try{var manifest=files().files().write(op.id()+"-road.json",JSON.toJson(road).getBytes(StandardCharsets.UTF_8));return journal.attachVerifiedSnapshot(op.id(),op.revision(),manifest.reference(),manifest.sha256());}
        catch(IOException|RuntimeException failure){try{var current=journal.find(op.id()).orElseThrow();journal.advance(current.id(),current.revision(),JournalService.State.CANCELLED);}catch(Throwable cancellation){failure.addSuppressed(cancellation);}throw failure;}
    }
    private void retainRecovery(Road road,UUID operation,Throwable failure){
        var recovery=road.state(State.RECOVERY,operation);var protectedNow=new HashMap<>(emergency);protectedNow.put(road.id(),recovery);emergency=Map.copyOf(protectedNow);
        try{registry.put(recovery);}catch(Throwable persistenceFailure){failure.addSuppressed(persistenceFailure);}
        try{NativePlacementTransactions.lock(plugin,operation);}catch(Throwable journalFailure){failure.addSuppressed(journalFailure);}
    }
    private List<GuildRoadPlanner.Cell> validateRoute(World world,UUID guild,UUID actor,GuildRoadPlanner.Cell start,GuildRoadPlanner.Cell end,int y,UUID ignore)throws IOException{
        if(!plugin.getInfrastructure().isHousing(world.getName())||y<0||y>316)throw new IllegalStateException("Roads require a configured housing world and supported height");
        var zone=new ArrayList<PlotRect>();var forbidden=new ArrayList<PlotRect>();var structures=new ArrayList<PlotRect>();
        var infrastructure=plugin.getInfrastructure().world(world.getName()).orElseThrow();forbidden.addAll(infrastructure.roads());forbidden.addAll(infrastructure.portals());
        for(var road:roads())if(road.world().equals(world.getName())&&!road.id().equals(ignore))forbidden.add(road.rectangle());
        for(var plot:EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).listPlots()){
            var rect=NativeHousingChecks.rect(plot.getFootprint());var owner=HousingAccess.owner(plot);var slot=owner==null?null:plugin.getServices().housing().find(owner).orElse(null);
            boolean member=guild.equals(plot.getGuildOwnerUuid())||guild.equals(plot.getAttachedGuildUuid())&&plot.getOwnerUuid()!=null&&plugin.getServices().guilds().membership(plot.getOwnerUuid()).map(m->guild.equals(m.guildId())).orElse(false);
            boolean consent=guild.equals(plot.getGuildOwnerUuid())||actor.equals(plot.getOwnerUuid())||registry.easement(plot.getPlotId(),guild);
            boolean usable=member&&consent&&slot!=null&&slot.state()==HousingService.State.ACTIVE&&slot.propertyId().equals(plot.getPlotId())&&!HousingAccess.locked(plugin,plot);
            if(usable)zone.add(rect);else forbidden.add(rect);
            for(var item:plugin.getServices().provenance().instances(plot.getPlotId()))if(item.state().equals("PLACED")){
                var image=files().load(new SnapshotFiles.Saved(item.nativeData().get("after"),item.nativeData().get("afterHash")));var b=image.bounds();var objectRect=new PlotRect(b.minX(),b.minZ(),b.maxX()-b.minX(),b.maxZ()-b.minZ());
                boolean structure="HOUSE".equals(item.nativeData().get("kind"));var prop=plugin.getPropCatalog().get(item.nativeData().get("catalog"));structure|=prop!=null&&"addition".equals(prop.getCategory());
                if(structure)structures.add(objectRect);else forbidden.add(objectRect);
            }
        }
        var route=GuildRoadPlanner.route(start,end,zone,forbidden,structures);
        for(var cell:route){if(!naturalGround(world,cell.x(),y,cell.z())||!empty(world,cell.x(),y+1,cell.z())||!empty(world,cell.x(),y+2,cell.z()))throw new IllegalStateException("Roads need level natural ground with two clear, dry blocks above every cell");}
        return route;
    }
    private void publicClear(World world,PlotRect rect){var plan=plugin.getInfrastructure().world(world.getName()).orElseThrow();if(plan.roads().stream().anyMatch(r->r.overlaps(rect))||plan.portals().stream().anyMatch(r->r.overlaps(rect)))throw new IllegalStateException("Public infrastructure now overlaps this road; operator review is required");}
    private <T>T scoped(World world,Road road,java.util.concurrent.Callable<T> action)throws Exception{
        world.getEntityStore().getStore().assertThread();publicClear(world,road.rectangle());
        if(roads().stream().anyMatch(r->!r.id().equals(road.id())&&r.world().equals(world.getName())&&r.rectangle().overlaps(road.rectangle())))throw new IllegalStateException("Road registry overlap needs operator review");
        return plugin.getInfrastructure().withGuildRoadWrite(world.getName(),road.rectangle(),action);
    }
    private static NativeSnapshotStore.Bounds bounds(PlotRect r,int y){return new NativeSnapshotStore.Bounds(r.x(),y,r.z(),r.endX(),y+1,r.endZ());}
    private static GuildRoadPlanner.Cell start(Road r){return new GuildRoadPlanner.Cell(r.rectangle().x(),r.rectangle().z());}
    private static GuildRoadPlanner.Cell end(Road r){return new GuildRoadPlanner.Cell(r.rectangle().endX()-1,r.rectangle().endZ()-1);}
    private static void plainBlock(String id){var block=BlockType.getAssetMap().getAsset(id);if(block==null||!block.isCubeDrawType()||block.getBlockEntity()!=null)throw new IllegalStateException("Road style must be a plain cube block");}
    private static boolean naturalGround(World w,int x,int y,int z){if(ChunkSectionBlockUtil.sectionRefAt(w,x,y,z)==null)return false;var block=ChunkSectionBlockUtil.blockType(w,x,y,z);if(block==null||!block.isCubeDrawType()||block.getBlockEntity()!=null)return false;String id=block.getId();return id.startsWith("Soil_")||id.startsWith("Grass_")||id.startsWith("Sand_")||id.equals("Rock_Stone_Cobble")||id.equals("Rock_Stone");}
    private static boolean empty(World w,int x,int y,int z){var ref=ChunkSectionBlockUtil.sectionRefAt(w,x,y,z);if(ref==null)return false;var block=ChunkSectionBlockUtil.blockType(w,x,y,z);var fluid=w.getChunkStore().getStore().getComponent(ref,com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType());return (block==null||block==BlockType.EMPTY||block.getMaterial()==com.hypixel.hytale.protocol.BlockMaterial.Empty)&&(fluid==null||fluid.getFluidId(x,y,z)==0);}
    private static void requireGround(BsonDocument doc,int count){if(doc.getArray("blocks").size()!=count||!doc.getArray("fluids",new BsonArray()).isEmpty())throw new IllegalStateException("Road ground has missing or fluid cells");for(var v:doc.getArray("blocks")){var b=v.asDocument();if(b.containsKey("components")||b.getInt32("filler",new BsonInt32(0)).getValue()!=0)throw new IllegalStateException("Road cannot replace a functional or multiblock cell");}}
    private static Map<String,BsonDocument> cells(BsonDocument doc,String field){var result=new HashMap<String,BsonDocument>();for(var v:doc.getArray(field,new BsonArray())){var b=v.asDocument().clone();String key=b.getInt32("x")+","+b.getInt32("y")+","+b.getInt32("z");b.remove("support");result.put(key,b);}return result;}
    private static void requireSame(BsonDocument actual,BsonDocument expected){if(!cells(actual,"blocks").equals(cells(expected,"blocks"))||!cells(actual,"fluids").equals(cells(expected,"fluids")))throw new IllegalStateException("Road cells changed; make a new preview or request recovery review");}
    private static void requireKnown(BsonDocument actual,BsonDocument before,BsonDocument after){Map<String,BsonDocument> a=cells(actual,"blocks"),b=cells(before,"blocks"),c=cells(after,"blocks");if(!a.keySet().equals(b.keySet())||!cells(actual,"fluids").equals(cells(before,"fluids")))throw new IllegalStateException("Road recovery has unknown cells or fluids");for(String key:a.keySet())if(!a.get(key).equals(b.get(key))&&!a.get(key).equals(c.get(key)))throw new IllegalStateException("Road recovery found an unrecognized edit; retain snapshots for review");}
}
