package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.customization.CustomizationCatalog;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.setup.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.bson.*;
import static com.hexvane.eterniamod.pathtool.SplineRoadStore.*;

/** One durable road operation covers exact terrain cells and exact public-infrastructure row runs. */
final class SplineRoadService {
    record Plan(List<SplineGeometry.Cell> cells,List<SplineGeometry.Cell> connections,Map<SplineGeometry.Column,String> invalid,List<SplineGeometry.Cell> above){boolean valid(){return !cells.isEmpty()&&invalid.isEmpty();}List<SplineGeometry.Cell> previewCells(){var all=new ArrayList<>(cells);all.addAll(connections);return List.copyOf(all);}}
    record Review(UUID actor,Road expected,Road pending,HousingInfrastructure.Snapshot infrastructure,HousingInfrastructure.WorldPlan nextPlan,Instant expires){}
    record Write(String world,UUID road,Set<String> areas,Set<SplineGeometry.Column> cells){}
    private static final ThreadLocal<Write> WRITING=new ThreadLocal<>();
    private final EterniaModPlugin plugin;private final SplineRoadStore registry;private final CustomizationCatalog catalog;
    private volatile Map<UUID,Road> emergency=Map.of();
    private record PendingIndex(Map<UUID,Road> authority,Map<UUID,Road> emergency,Map<String,Map<SplineGeometry.Column,Set<UUID>>> columns,Map<String,List<PlotRect>> rectangles){}
    private volatile PendingIndex pendingIndex;
    SplineRoadService(EterniaModPlugin plugin)throws IOException{
        this.plugin=plugin;registry=new SplineRoadStore(plugin.getDataDirectory().resolve("spline-roads"));catalog=new CustomizationCatalog(plugin.getDataDirectory());
        for(var road:registry.all()){
            if(road.current()!=null)verifyVersion(road.id(),road.current());
            if(road.change()!=null){metadata(road.change().before(),road.change().cells(),null);metadata(road.change().after(),road.change().cells(),null);if(road.change().next()!=null)verifyVersion(road.id(),road.change().next());}
            if(road.state()==State.ACTIVE){var world=plugin.getInfrastructure().world(road.world()).orElseThrow(()->new IOException("Authored road world infrastructure is missing"));for(var area:road.current().areas().entrySet())if(!Objects.equals(world.areas().get(area.getKey()),new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,area.getValue())))throw new IOException("Authored road infrastructure differs from its saved footprint; preserve road generations and restore matching infrastructure before startup");}
        }
    }
    private NativeSnapshotStore files(){return NativePlacementTransactions.snapshots(plugin);}
    List<Road> roads(){var all=new TreeMap<UUID,Road>();registry.all().forEach(r->all.put(r.id(),r));all.putAll(emergency);return List.copyOf(all.values());}
    Road find(UUID id){var r=emergency.get(id);return r!=null?r:registry.get(id);}
    List<CustomizationCatalog.PathStyle> styles(){return catalog.paths();}
    PublicRoadNetwork network(String world,HousingInfrastructure.WorldPlan plan,Version extra){
        if(plan==null)return PublicRoadNetwork.rectangles(List.of());
        var versions=new ArrayList<Version>();if(extra!=null)versions.add(extra);
        for(var r:roads())if(r.world().equals(world)){if(r.current()!=null)versions.add(r.current());if(r.change()!=null&&r.change().next()!=null)versions.add(r.change().next());}
        var covered=new HashSet<String>();var routes=new ArrayList<PublicRoadNetwork.Route>();
        for(var v:versions)if(v.areas().entrySet().stream().allMatch(e->Objects.equals(plan.areas().get(e.getKey()),new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,e.getValue())))&&Collections.disjoint(covered,v.areas().keySet())){
            covered.addAll(v.areas().keySet());var surface=new ArrayList<>(v.areas().values());for(var c:v.connections())surface.add(new PlotRect(c.x(),c.z(),1,1));
            routes.add(new PublicRoadNetwork.Route(surface,SplineGeometry.sample(v.nodes()).stream().map(n->new PublicRoadNetwork.Point(n.x(),n.z())).toList()));
        }
        plan.areas().forEach((id,a)->{if(a.kind()==HousingInfrastructure.AreaKind.ROAD&&!covered.contains(id))routes.add(PublicRoadNetwork.rectangle(a.rect()));});
        return new PublicRoadNetwork(routes);
    }
    void validateConnections(String world,HousingInfrastructure.WorldPlan after,UUID ignored){RoadConnectionRules.preserveProtection(after,connections(world,ignored));}
    private List<SplineGeometry.Cell> connections(String world,UUID ignored){return roads().stream().filter(r->r.world().equals(world)&&r.state()!=State.REMOVED&&!r.id().equals(ignored)).flatMap(r->RoadConnectionRules.connections(r).stream()).toList();}
    static boolean mayWriteArea(String world,String area,int x,int z){var scope=WRITING.get();return scope!=null&&scope.world.equals(world)&&scope.areas.contains(area)&&scope.cells.contains(new SplineGeometry.Column(x,z));}
    boolean pendingColumn(String world,int x,int z){var owners=index().columns().getOrDefault(world,Map.of()).get(new SplineGeometry.Column(x,z));if(owners==null)return false;var scope=WRITING.get();return scope==null||!scope.world().equals(world)||owners.size()!=1||!owners.contains(scope.road());}
    List<PlotRect> pendingRects(String world){return index().rectangles().getOrDefault(world,List.of());}
    private PendingIndex index(){
        var authority=registry.projection();var overrides=emergency;var current=pendingIndex;if(current!=null&&current.authority()==authority&&current.emergency()==overrides)return current;
        var merged=new HashMap<>(authority);merged.putAll(overrides);var mutable=new HashMap<String,Map<SplineGeometry.Column,Set<UUID>>>();
        for(var road:merged.values())if(road.state()==State.PENDING){var columns=mutable.computeIfAbsent(road.world(),ignored->new HashMap<>());for(var cell:road.protectedCells())columns.computeIfAbsent(cell.column(),ignored->new HashSet<>()).add(road.id());}
        var columns=new HashMap<String,Map<SplineGeometry.Column,Set<UUID>>>();var rectangles=new HashMap<String,List<PlotRect>>();
        mutable.forEach((world,cells)->{var immutable=new HashMap<SplineGeometry.Column,Set<UUID>>();cells.forEach((column,owners)->immutable.put(column,Set.copyOf(owners)));columns.put(world,Map.copyOf(immutable));rectangles.put(world,SplineGeometry.rectangles(immutable.keySet()));});
        current=new PendingIndex(authority,overrides,Map.copyOf(columns),Map.copyOf(rectangles));pendingIndex=current;return current;
    }
    Plan plan(World world,List<SplineGeometry.Node> nodes,int width,UUID editing){
        requireWorld(world);
        var hints=SplineGeometry.footprint(nodes,width);var cells=new ArrayList<SplineGeometry.Cell>();var connections=new ArrayList<SplineGeometry.Cell>();var invalid=new LinkedHashMap<SplineGeometry.Column,String>();var forbidden=forbidden(world,editing);var publicRoads=connectionRoads(world,editing);
        for(var hint:hints){var c=hint.column();Integer y=ground(world,c.x(),c.z(),hint.y());if(y==null){invalid.put(c,"Load the ground under every part of the road");continue;}var cell=new SplineGeometry.Cell(c.x(),y,c.z());boolean shared=publicRoads.stream().anyMatch(r->r.contains(c.x(),c.z()));if(shared)connections.add(cell);else cells.add(cell);String problem=cellProblem(world,cell,forbidden);if(problem!=null)invalid.put(c,problem);if(shared&&!SplineGeometry.endpointConnection(c,nodes,width))invalid.put(c,"Only a short endpoint junction can overlap an existing road; route the curve around it");}
        var all=new ArrayList<>(cells);all.addAll(connections);var heights=new HashMap<SplineGeometry.Column,Integer>();all.forEach(c->heights.put(c.column(),c.y()));for(var cell:all)for(var neighbor:List.of(new SplineGeometry.Column(cell.x()+1,cell.z()),new SplineGeometry.Column(cell.x(),cell.z()+1))){Integer y=heights.get(neighbor);if(y!=null&&Math.abs(y-cell.y())>1){invalid.put(cell.column(),"The road is too steep here; move its nodes onto gentler ground");invalid.put(neighbor,"The road is too steep here");}}
        world.getEntityStore().getStore().forEachChunk(TransformComponent.getComponentType(),(chunk,commands)->{for(int i=0;i<chunk.size();i++){if(chunk.getComponent(i,Player.getComponentType())!=null)continue;var p=chunk.getComponent(i,TransformComponent.getComponentType()).getPosition();var column=new SplineGeometry.Column((int)Math.floor(p.x),(int)Math.floor(p.z));var floor=heights.get(column);if(floor!=null&&p.y>=floor)invalid.put(column,"Move props, creatures and dropped items out of the road");}});
        if(cells.isEmpty()&&!connections.isEmpty())invalid.put(connections.getFirst().column(),"Extend beyond the existing road to create new ground");var clearance=RoadClearance.scan(world,cells);invalid.putAll(clearance.invalid());return new Plan(List.copyOf(cells),List.copyOf(connections),Map.copyOf(invalid),clearance.blocks());
    }
    synchronized Review review(World world,UUID actor,List<SplineGeometry.Node> nodes,int width,String styleId,UUID editing)throws Exception{
        return review(world,actor,nodes,width,styleId,editing,null);
    }
    synchronized Review review(World world,UUID actor,List<SplineGeometry.Node> nodes,int width,String styleId,UUID editing,UUID initialId)throws Exception{
        SetupAccess.require(actor);world.getEntityStore().getStore().assertThread();requireWorld(world);
        var style=styles().stream().filter(s->s.id().equals(styleId)).findFirst().orElseThrow(()->new IllegalStateException("Unknown road style"));plain(style.blockId());
        Road current=editing==null?null:find(editing);if(editing!=null&&(current==null||current.state()!=State.ACTIVE||!current.world().equals(world.getName())))throw new IllegalStateException("The selected road changed; reopen it");
        var plan=plan(world,nodes,width,editing);if(!plan.valid())throw new IllegalStateException(plan.invalid().values().stream().findFirst().orElse("Add at least two nodes"));
        UUID id=current==null?(initialId==null?UUID.randomUUID():initialId):current.id();if(current==null&&find(id)!=null)throw new IllegalStateException("This road receipt already exists");long rev=current==null?0:current.revision();var floors=new HashMap<SplineGeometry.Column,Integer>();plan.cells().forEach(c->floors.put(c.column(),c.y()));var retained=current==null?List.<SplineGeometry.Cell>of():current.current().snapshotCells().stream().filter(c->floors.containsKey(c.column())&&c.y()>floors.get(c.column())+2).toList();var above=union(plan.above(),retained).stream().filter(c->c.y()>floors.get(c.column())+2).toList();var nextCells=union(SparseRoadSnapshots.withClearance(plan.cells(),2),above);var union=union(current==null?List.of():current.current().snapshotCells(),nextCells);var bounds=SparseRoadSnapshots.bounds(union);
        var scope=scope(world,id,current==null?Set.of():current.current().areas().keySet(),union);
        RoadClearance.requireLoaded(world,union);BsonDocument actual=scoped(scope,()->SparseRoadSnapshots.capture(files(),world,union));plainSnapshot(actual);plainGround(actual,bounds,plan.cells());
        if(current!=null){var expected=files().load(current.current().after());SparseRoadSnapshots.requireSame(SparseRoadSnapshots.select(actual,bounds,current.current().snapshotCells()),expected.document(),null);}
        var original=actual.clone();if(current!=null)SparseRoadSnapshots.overlay(original,bounds,files().load(current.current().before()));
        RoadClearance.validateSavedObjects(original,bounds,plan.cells());var nextOriginal=SparseRoadSnapshots.select(original,bounds,nextCells);var painted=nextOriginal.clone();paint(painted,SparseRoadSnapshots.bounds(nextCells),plan.cells(),style.blockId());
        String operation=UUID.randomUUID().toString();var nextBounds=SparseRoadSnapshots.bounds(nextCells);var savedOriginal=files().save(operation+"-original.json",nextBounds,nextOriginal);var savedPainted=files().save(operation+"-painted.json",nextBounds,painted);
        var next=new Version(nodes,width,style.id(),style.blockId(),plan.cells(),areas(id,plan.cells()),savedOriginal.file(),savedPainted.file(),plan.connections(),2,above);
        var desired=original.clone();SparseRoadSnapshots.overlay(desired,bounds,savedPainted);
        var before=files().save(operation+"-before.json",bounds,actual);var after=files().save(operation+"-after.json",bounds,desired);
        var pending=new Road(id,actor,world.getName(),rev+1,State.PENDING,current==null?null:current.current(),new Change(before.file(),after.file(),union,next));
        var snapshot=plugin.getInfrastructure().snapshot();var nextPlan=infrastructure(world,current==null?null:current.current(),next,snapshot,id);return new Review(actor,current,pending,snapshot,nextPlan,Instant.now().plusSeconds(90));
    }
    synchronized Review reviewRemoval(World world,UUID actor,UUID id)throws Exception{
        SetupAccess.require(actor);var current=find(id);if(current==null||!current.world().equals(world.getName())||current.state()==State.REMOVED)throw new IllegalStateException("The road is no longer available");
        if(current.state()==State.PENDING)throw new IllegalStateException("Use Recover to restore the interrupted road first");
        var snapshot=plugin.getInfrastructure().snapshot();var nextPlan=infrastructure(world,current.current(),null,snapshot,id);var v=current.current();RoadClearance.requireLoaded(world,v.snapshotCells());
        scoped(scope(world,id,v.areas().keySet(),v.snapshotCells()),()->{SparseRoadSnapshots.requireSame(SparseRoadSnapshots.capture(files(),world,v.snapshotCells()),files().load(v.after()).document(),null);return null;});
        var pending=new Road(id,actor,world.getName(),current.revision()+1,State.PENDING,v,new Change(v.after(),v.before(),v.snapshotCells(),null));return new Review(actor,current,pending,snapshot,nextPlan,Instant.now().plusSeconds(90));
    }
    synchronized void confirm(World world,Review review)throws Exception{
        SetupAccess.require(review.actor());world.getEntityStore().getStore().assertThread();var pending=review.pending();if(Instant.now().isAfter(review.expires())||!world.getName().equals(pending.world())||!Objects.equals(find(pending.id()),review.expected()))throw new IllegalStateException("Road preview expired or changed. Review again");
        var snapshot=plugin.getInfrastructure().snapshot();if(snapshot.revision()!=review.infrastructure().revision())throw new IllegalStateException("World infrastructure changed; review again");
        var nextPlan=infrastructure(world,pending.current(),pending.change().next(),snapshot,pending.id());
        if(pending.change().next()!=null){var next=pending.change().next();plain(next.block());var fresh=plan(world,next.nodes(),next.width(),pending.current()==null?null:pending.id());if(!fresh.valid()||!fresh.cells().equals(next.cells())||!fresh.connections().equals(next.connections())||!new HashSet<>(next.snapshotCells()).containsAll(fresh.above()))throw new IllegalStateException("Ground or protected areas changed after preview");}
        RoadClearance.requireLoaded(world,pending.change().cells());var before=files().load(pending.change().before());var after=files().load(pending.change().after());var allowed=areaIds(pending);var scope=scope(world,pending.id(),allowed,pending.change().cells());
        scoped(scope,()->{SparseRoadSnapshots.requireSame(SparseRoadSnapshots.capture(files(),world,pending.change().cells()),before.document(),null);return null;});
        registry.put(pending.revision()-1,pending);
        try{scoped(scope,()->{files().apply(world,after,after.bounds().origin());verify(world,after,pending.change().cells());flush(world,pending.change().cells());return null;});
            plugin.getInfrastructure().saveWorld(snapshot.revision(),world.getName(),nextPlan);
            var completed=new Road(pending.id(),pending.actor(),pending.world(),pending.revision()+1,pending.change().next()==null?State.REMOVED:State.ACTIVE,pending.change().next(),null);registry.put(pending.revision(),completed);
        }catch(Throwable failure){retain(pending);throw new IllegalStateException("Road edit was interrupted. Its exact cells stay protected; use Recover in the road tool",failure);}
    }
    synchronized void recover(World world,UUID actor,UUID id)throws Exception{
        SetupAccess.require(actor);world.getEntityStore().getStore().assertThread();var road=find(id);if(road==null||road.state()!=State.PENDING||!road.world().equals(world.getName()))throw new IllegalStateException("This road has no pending recovery");
        RoadClearance.requireLoaded(world,road.change().cells());var before=files().load(road.change().before());var after=files().load(road.change().after());var snapshot=plugin.getInfrastructure().snapshot();var currentPlan=snapshot.worlds().get(world.getName());var desired=rollbackInfrastructure(world,road,snapshot);
        var scope=scope(world,road.id(),areaIds(road),road.change().cells());
        try{scoped(scope,()->{var actual=SparseRoadSnapshots.capture(files(),world,road.change().cells());SparseRoadSnapshots.requireSame(actual,before.document(),after.document());files().apply(world,before,before.bounds().origin());verify(world,before,road.change().cells());flush(world,road.change().cells());return null;});
            if(!desired.equals(currentPlan))plugin.getInfrastructure().saveWorld(snapshot.revision(),world.getName(),desired);
            var restored=new Road(road.id(),actor,road.world(),road.revision()+1,road.current()==null?State.REMOVED:State.ACTIVE,road.current(),null);registry.put(road.revision(),restored);var remaining=new HashMap<>(emergency);remaining.remove(id);emergency=Map.copyOf(remaining);
        }catch(Throwable failure){retain(road);throw new IllegalStateException("Recovery retained the road lock: "+failure.getMessage(),failure);}
    }
    private void retain(Road road){var next=new HashMap<>(emergency);next.put(road.id(),road);emergency=Map.copyOf(next);}
    private HousingInfrastructure.WorldPlan infrastructure(World world,Version old,Version next,HousingInfrastructure.Snapshot snapshot,UUID id){
        RoadConnectionRules.preserveSurface(old,next,connections(world.getName(),id));
        var before=Objects.requireNonNull(snapshot.worlds().get(world.getName()),"Configure this world first");var areas=new TreeMap<>(before.areas());
        if(old!=null)for(var entry:old.areas().entrySet()){if(!Objects.equals(areas.get(entry.getKey()),new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,entry.getValue())))throw new IllegalStateException("An authored road area changed outside the tool; operator review is required");areas.remove(entry.getKey());}
        if(next!=null)next.areas().forEach((name,rect)->{if(areas.putIfAbsent(name,new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,rect))!=null)throw new IllegalStateException("Authored road area name is already in use");});
        var after=before.withAreas(areas);validateConnections(world.getName(),after,id);validateInfrastructure(world,before,after,id,next);return after;
    }
    private HousingInfrastructure.WorldPlan rollbackInfrastructure(World world,Road road,HousingInfrastructure.Snapshot snapshot){
        var before=snapshot.worlds().get(world.getName());var areas=new TreeMap<>(before.areas());var ids=areaIds(road);var old=road.current()==null?Map.<String,PlotRect>of():road.current().areas();var next=road.change().next()==null?Map.<String,PlotRect>of():road.change().next().areas();
        for(String id:ids){var found=areas.get(id);var oldValue=old.containsKey(id)?new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,old.get(id)):null;var newValue=next.containsKey(id)?new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,next.get(id)):null;if(!Objects.equals(found,oldValue)&&!Objects.equals(found,newValue))throw new IllegalStateException("Road infrastructure contains an unrecognized edit");areas.remove(id);}
        old.forEach((name,rect)->areas.put(name,new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,rect)));var after=before.withAreas(areas);validateConnections(world.getName(),after,road.id());validateInfrastructure(world,before,after,road.id(),road.current());return after;
    }
    private void validateInfrastructure(World world,HousingInfrastructure.WorldPlan before,HousingInfrastructure.WorldPlan after,UUID ignore,Version next){
        var claims=new LinkedHashMap<UUID,HousingRules.Anchor>();for(var plot:EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).listPlots())claims.put(plot.getPlotId(),new HousingRules.Anchor(plot.getPlotId(),NativeHousingChecks.rect(plot.getFootprint()),plot.getGuildOwnerUuid()!=null?HousingRules.Scope.GUILD_ROOT:plot.getAttachedGuildUuid()!=null?HousingRules.Scope.GUILD_MEMBER:HousingRules.Scope.PUBLIC,plot.getGuildOwnerUuid()!=null?plot.getGuildOwnerUuid():plot.getAttachedGuildUuid(),plot.hasBuilding()));
        for(var slot:plugin.getServices().housing().allSlots())if(slot.state()!=HousingService.State.PACKED){var loc=plugin.getServices().housing().location(slot.owner()).orElse(null);if(loc==null||!loc.worldId().equals(world.getName()))continue;if(slot.state()!=HousingService.State.ACTIVE)throw new IllegalStateException("Recover unfinished housing operations before editing public roads");claims.putIfAbsent(slot.propertyId(),new HousingRules.Anchor(slot.propertyId(),new PlotRect(loc.minX(),loc.minZ(),loc.width(),loc.depth()),slot.owner().kind()==Owner.Kind.GUILD?HousingRules.Scope.GUILD_ROOT:loc.guildId()!=null?HousingRules.Scope.GUILD_MEMBER:HousingRules.Scope.PUBLIC,loc.guildId(),loc.buildingPresent()));}
        var pending=new ArrayList<>(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()));pending.addAll(com.hexvane.eterniamod.setup.paving.RoadPaving.protectedRects(world.getName()));for(var r:roads())if(r.state()==State.PENDING&&!r.id().equals(ignore)&&r.world().equals(world.getName()))pending.addAll(SplineGeometry.rectangles(r.protectedCells().stream().map(SplineGeometry.Cell::column).toList()));
        InfrastructureRules.validate(before,after,List.copyOf(claims.values()),pending,network(world.getName(),before,null),network(world.getName(),after,next));ManagedHubServices.validateInfrastructure(world,after);
    }
    private List<PlotRect> forbidden(World world,UUID editing){
        var result=new ArrayList<PlotRect>();var plan=plugin.getInfrastructure().world(world.getName()).orElseThrow();plan.areas().forEach((id,a)->{if(a.kind()==HousingInfrastructure.AreaKind.PORTAL)result.add(a.rect());});
        result.addAll(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()));result.addAll(com.hexvane.eterniamod.setup.paving.RoadPaving.protectedRects(world.getName()));result.addAll(pendingRects(world.getName()));
        EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).listPlots().forEach(p->result.add(NativeHousingChecks.rect(p.getFootprint())));
        for(var slot:plugin.getServices().housing().allSlots())if(slot.state()!=HousingService.State.PACKED)plugin.getServices().housing().location(slot.owner()).filter(l->l.worldId().equals(world.getName())).ifPresent(l->result.add(new PlotRect(l.minX(),l.minZ(),l.width(),l.depth())));return result;
    }
    private List<PlotRect> connectionRoads(World world,UUID editing){var edited=editing==null?null:find(editing);Set<String> own=edited==null||edited.current()==null?Set.of():edited.current().areas().keySet();return plugin.getInfrastructure().world(world.getName()).orElseThrow().areas().entrySet().stream().filter(e->e.getValue().kind()==HousingInfrastructure.AreaKind.ROAD&&!own.contains(e.getKey())).map(e->e.getValue().rect()).toList();}
    private String cellProblem(World world,SplineGeometry.Cell cell,List<PlotRect> forbidden){
        if(forbidden.stream().anyMatch(r->r.contains(cell.x(),cell.z())))return "Road crosses a plot, reserved claim, public plaza or another protected road";
        var block=ChunkSectionBlockUtil.blockType(world,cell.x(),cell.y(),cell.z());if(block==null||!block.isCubeDrawType()||block.getBlockEntity()!=null||!natural(block.getId()))return "Road needs natural plain ground; move the node around structures";
        var section=ChunkSectionBlockUtil.sectionRefAt(world,cell.x(),cell.y(),cell.z());if(section==null)return "Load every ground section";var fluids=section.getStore().getComponent(section,com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType());if(fluids!=null&&fluids.getFluidId(cell.x(),cell.y(),cell.z())!=0)return "Move the road around water or lava";
        return null;
    }
    private Integer ground(World world,int x,int z,double hint){for(int y=Math.min(316,(int)Math.floor(hint)+6);y>=Math.max(0,(int)Math.floor(hint)-24);y--){if(ChunkSectionBlockUtil.sectionRefAt(world,x,y,z)==null)return null;var block=ChunkSectionBlockUtil.blockType(world,x,y,z);if(block!=null&&block.getMaterial()==BlockMaterial.Solid&&natural(block.getId())&&!surfaceDebris(block))return y;}return null;}
    private void requireWorld(World world){if(!plugin.getInfrastructure().world(world.getName()).map(p->!p.role().equals("adventure")).orElse(false))throw new IllegalStateException("Configure this world as Hub or Housing before authoring public roads");}
    private boolean natural(String id){return id.startsWith("Soil_")||id.startsWith("Grass_")||id.startsWith("Sand_")||id.equals("Rock_Stone")||styles().stream().anyMatch(s->s.blockId().equals(id));}
    private static boolean surfaceDebris(BlockType block){
        return block.getId().startsWith("Plant_")||block.getId().startsWith("Rubble_")||block.getId().startsWith("Wood_")&&!block.isCubeDrawType();
    }
    private static void paint(BsonDocument document,NativeSnapshotStore.Bounds bounds,List<SplineGeometry.Cell> ground,String block){
        var surface=new HashSet<String>();ground.forEach(c->surface.add(SparseRoadSnapshots.key(c.x()-bounds.minX(),c.y()-bounds.minY(),c.z()-bounds.minZ())));
        for(var value:document.getArray("blocks")){
            var cell=value.asDocument();cell.put("name",new BsonString(surface.contains(SparseRoadSnapshots.key(cell))?block:BlockType.EMPTY.getId()));if(!surface.contains(SparseRoadSnapshots.key(cell))){cell.remove("components");cell.remove("filler");cell.remove("rotation");}
        }
    }
    private void verifyPainted(Version version)throws IOException{
        // Startup precedes native asset registration. Validate names/masks without
        // deserializing a prefab against the still-empty native asset maps.
        var document=BsonDocument.parse(new String(files().files().read(version.after()),StandardCharsets.UTF_8)).getDocument("prefab");
        var expected=document.clone();paint(expected,SparseRoadSnapshots.bounds(version.snapshotCells()),version.cells(),version.block());
        if(!document.equals(expected))throw new IOException("Road surface or cleared headroom differs from its saved cells");
    }
    private static void plain(String id){var block=BlockType.getAssetMap().getAsset(id);if(block==null||!block.isCubeDrawType()||block.getBlockEntity()!=null)throw new IllegalStateException("Road styles require plain cube blocks");}
    private static void plainSnapshot(BsonDocument doc){if(!doc.getArray("fluids",new BsonArray()).isEmpty()||!doc.getArray("entities",new BsonArray()).isEmpty())throw new IllegalStateException("Road snapshot contains unsupported fluids or entities");}

    private static void plainGround(BsonDocument doc,NativeSnapshotStore.Bounds bounds,List<SplineGeometry.Cell> ground){
        var index=SparseRoadSnapshots.index(doc);for(var c:ground){var b=index.get(SparseRoadSnapshots.key(c.x()-bounds.minX(),c.y()-bounds.minY(),c.z()-bounds.minZ()));if(b==null||b.containsKey("components")||b.getInt32("filler",new BsonInt32(0)).getValue()!=0)throw new IllegalStateException("Road ground cannot replace a container, component or multiblock");}
    }
    private void verifyVersion(UUID id,Version version)throws IOException{metadata(version.before(),version.snapshotCells(),null);metadata(version.after(),version.snapshotCells(),null);verifyPainted(version);if(!version.areas().equals(areas(id,version.cells())))throw new IOException("Road area registration differs from its exact footprint");var all=new HashSet<>(version.cells().stream().map(SplineGeometry.Cell::column).toList());all.addAll(version.connections().stream().map(SplineGeometry.Cell::column).toList());if(!all.equals(new HashSet<>(SplineGeometry.footprint(version.nodes(),version.width()).stream().map(SplineGeometry.Hint::column).toList())))throw new IOException("Road cells differ from its saved spline geometry");}
    private void metadata(SnapshotFiles.Saved saved,List<SplineGeometry.Cell> cells,String painted)throws IOException{try{var envelope=BsonDocument.parse(new String(files().files().read(saved),StandardCharsets.UTF_8));if(!envelope.getString("format").getValue().equals("eternia-native-snapshot-1")||!envelope.getBoolean("clearFluidAtEveryBlock").getValue())throw new IllegalArgumentException("Unknown road snapshot");var bounds=SparseRoadSnapshots.bounds(cells);if(!envelope.getArray("bounds").stream().map(v->v.asInt32().getValue()).toList().equals(List.of(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ())))throw new IllegalArgumentException("Road snapshot bounds differ");var doc=envelope.getDocument("prefab");plainSnapshot(doc);Set<String> expected=new HashSet<>();cells.forEach(c->expected.add(SparseRoadSnapshots.key(c.x()-bounds.minX(),c.y()-bounds.minY(),c.z()-bounds.minZ())));if(!SparseRoadSnapshots.index(doc).keySet().equals(expected)||doc.getArray("blocks").size()!=cells.size())throw new IllegalArgumentException("Road snapshot mask differs");if(painted!=null)for(var value:doc.getArray("blocks"))if(!painted.equals(value.asDocument().getString("name").getValue()))throw new IllegalArgumentException("Road style differs from its saved cells");}catch(RuntimeException e){throw new IOException("Invalid road snapshot metadata",e);}}
    private static Map<String,PlotRect> areas(UUID id,List<SplineGeometry.Cell> cells){var rects=SplineGeometry.rectangles(cells.stream().map(SplineGeometry.Cell::column).toList());if(rects.size()>512)throw new IllegalArgumentException("This road footprint needs too many registration runs; shorten or simplify it");var map=new TreeMap<String,PlotRect>();for(int i=0;i<rects.size();i++)map.put("spline-"+id.toString().replace("-","")+"-"+i,rects.get(i));return Map.copyOf(map);}
    private static List<SplineGeometry.Cell> union(List<SplineGeometry.Cell> a,List<SplineGeometry.Cell> b){var cells=new HashSet<SplineGeometry.Cell>(a);cells.addAll(b);return cells.stream().sorted(Comparator.comparingInt(SplineGeometry.Cell::y).thenComparingInt(SplineGeometry.Cell::z).thenComparingInt(SplineGeometry.Cell::x)).toList();}
    private static Set<String> areaIds(Road road){var ids=new HashSet<String>();if(road.current()!=null)ids.addAll(road.current().areas().keySet());if(road.change()!=null&&road.change().next()!=null)ids.addAll(road.change().next().areas().keySet());return Set.copyOf(ids);}
    private static Write scope(World world,UUID id,Set<String> areas,List<SplineGeometry.Cell> cells){return new Write(world.getName(),id,Set.copyOf(areas),Set.copyOf(cells.stream().map(SplineGeometry.Cell::column).toList()));}
    private static <T>T scoped(Write scope,java.util.concurrent.Callable<T> action)throws Exception{if(WRITING.get()!=null)throw new IllegalStateException("Nested spline road write");WRITING.set(scope);try{return action.call();}finally{WRITING.remove();}}
    private void verify(World world,NativeSnapshotStore.Snapshot expected,List<SplineGeometry.Cell> cells){SparseRoadSnapshots.requireSame(SparseRoadSnapshots.capture(files(),world,cells),expected.document(),null);}
    private static void flush(World world,List<SplineGeometry.Cell> cells)throws IOException{var chunks=new HashSet<String>();for(var cell:cells){String key=(cell.x()>>5)+":"+(cell.z()>>5);if(chunks.add(key))NativePlacementTransactions.flush(world,new NativeSnapshotStore.Bounds(cell.x(),cell.y(),cell.z(),cell.x()+1,cell.y()+1,cell.z()+1));}}
    void nativeSmoke(World world)throws Exception{
        var tool=com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(SplineRoadTool.ITEM);if(tool==null||!tool.getInteractions().keySet().containsAll(Set.of(com.hypixel.hytale.protocol.InteractionType.Primary,com.hypixel.hytale.protocol.InteractionType.Secondary,com.hypixel.hytale.protocol.InteractionType.Use,com.hypixel.hytale.protocol.InteractionType.Ability1,com.hypixel.hytale.protocol.InteractionType.Ability2,com.hypixel.hytale.protocol.InteractionType.Pick)))throw new IllegalStateException("Road Designer item or native input bindings did not load");
        var actor=UUID.randomUUID();var permissions=com.hypixel.hytale.server.core.permissions.PermissionsModule.get();var builder=Set.of(com.hypixel.hytale.server.core.permissions.HytalePermissions.BUILDER_TOOLS_EDITOR.getId());
        var nodes=List.of(new SplineGeometry.Node(-26.5,1,-22.5),new SplineGeometry.Node(-20.5,1,-10.5),new SplineGeometry.Node(-10.5,1,-22.5));
        boolean denied=false;try{review(world,actor,nodes,3,"cobblestone",null);}catch(DomainException expected){denied=true;}if(!denied)throw new IllegalStateException("Spline road accepted an unauthorized actor");
        permissions.addUserPermission(actor,builder);
        try{
            var clutter=Map.of(1,"Plant_Grass_Wet_Short",2,"Rubble_Stone",4,"Plant_Seaweed_Grass_Short",40,"Rubble_Stone_Medium");
            for(var entry:clutter.entrySet()){var block=BlockType.getAssetMap().getAsset(entry.getValue());if(block==null||!ChunkSectionBlockUtil.setBlock(world,-27,entry.getKey(),-23,block,0))throw new IllegalStateException("Could not seed native road clutter: "+entry.getValue());}
            var initial=review(world,actor,nodes,3,"cobblestone",null);permissions.removeUserPermission(actor,builder);boolean revoked=false;try{confirm(world,initial);}catch(DomainException expected){revoked=true;}if(!revoked||find(initial.pending().id())!=null)throw new IllegalStateException("Revoked road authority wrote a journal");permissions.addUserPermission(actor,builder);confirm(world,initial);
            for(int y:clutter.keySet())if(ChunkSectionBlockUtil.blockId(world,-27,y,-23)!=BlockType.EMPTY_ID)throw new IllegalStateException("Road did not clear native clutter at height "+y);
            var id=initial.pending().id();var first=find(id);for(var cell:first.current().cells())if(!plugin.getInfrastructure().publicProtectedColumn(world.getName(),cell.x(),cell.z()))throw new IllegalStateException("Built spline lost exact public protection");
            if(plugin.getInfrastructure().protectedColumn(world.getName(),-18,-20))throw new IllegalStateException("The empty inside of the curve was protected as a bounding rectangle");
            var branch=review(world,actor,List.of(nodes.getFirst(),new SplineGeometry.Node(-29.5,1,-12.5)),3,"cobblestone",null);if(branch.pending().change().next().connections().isEmpty())throw new IllegalStateException("Endpoint junction did not retain read-only connection cells");confirm(world,branch);boolean parentLocked=false;try{reviewRemoval(world,actor,id);}catch(IllegalStateException expected){parentLocked=true;}if(!parentLocked||find(id).state()!=State.ACTIVE)throw new IllegalStateException("Parent road removal ignored a retained branch junction");var branchVersion=find(branch.pending().id()).current();if(branchVersion.cells().stream().anyMatch(c->first.current().cells().stream().anyMatch(a->a.column().equals(c.column()))))throw new IllegalStateException("A junction took terrain custody from its earlier road");confirm(world,reviewRemoval(world,actor,branch.pending().id()));scoped(scope(world,id,first.current().areas().keySet(),first.current().cells()),()->{SparseRoadSnapshots.requireSame(SparseRoadSnapshots.capture(files(),world,first.current().snapshotCells()),files().load(first.current().after()).document(),null);return null;});
            boolean duplicate=false;try{confirm(world,initial);}catch(IllegalStateException expected){duplicate=true;}if(!duplicate)throw new IllegalStateException("Spline confirmation replay succeeded");
            var moved=List.of(nodes.getFirst(),new SplineGeometry.Node(-20.5,1,-6.5),nodes.getLast());var edit=review(world,actor,moved,3,"cobblestone",id);confirm(world,edit);
            var current=find(id);if(current.current().nodes().equals(nodes))throw new IllegalStateException("Saved road nodes did not update");
            // Real crash boundary: new native cells are durable while infrastructure still holds the old version.
            var interrupted=review(world,actor,nodes,3,"cobblestone",id);registry.put(interrupted.pending().revision()-1,interrupted.pending());var change=interrupted.pending().change();scoped(scope(world,id,areaIds(interrupted.pending()),change.cells()),()->{var after=files().load(change.after());files().apply(world,after,after.bounds().origin());verify(world,after,change.cells());flush(world,change.cells());return null;});
            if(new SplineRoadStore(plugin.getDataDirectory().resolve("spline-roads")).get(id).state()!=State.PENDING)throw new IllegalStateException("Spline journal lost pending recovery after restart");
            var claim=plugin.getClaims().validate(world,actor,new PlotRect(-31,30,24,24),HousingRules.Scope.PUBLIC,false);if(claim.valid()||!claim.code().equals("splineRoad"))throw new IllegalStateException("A claim could depend on interrupted road infrastructure");
            recover(world,actor,id);if(!find(id).current().nodes().equals(moved)||!pendingRects(world.getName()).isEmpty())throw new IllegalStateException("Road recovery did not restore its previous version");
            confirm(world,reviewRemoval(world,actor,id));var removed=find(id);if(removed.state()!=State.REMOVED||!removed.protectedCells().isEmpty())throw new IllegalStateException("Road undo retained active road protection");
            SparseRoadSnapshots.requireSame(SparseRoadSnapshots.capture(files(),world,initial.pending().change().cells()),files().load(initial.pending().change().before()).document(),null);
            for(var entry:clutter.entrySet())if(!entry.getValue().equals(ChunkSectionBlockUtil.blockType(world,-27,entry.getKey(),-23).getId()))throw new IllegalStateException("Undo did not restore original road clutter at height "+entry.getKey());
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SPLINE_ROAD_SMOKE_PASS: full-height plants/rubble clearance and restoration (including Y=40), native curve, empty interior, read-only junction and independent undo, revoked permission, saved-node edit, interrupted write recovery, claim lock and exact original terrain undo");
        }finally{permissions.removeUserPermission(actor,builder);}
    }
}
