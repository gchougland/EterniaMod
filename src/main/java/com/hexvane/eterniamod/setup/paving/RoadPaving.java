package com.hexvane.eterniamod.setup.paving;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.*;
import org.joml.Vector3f;
import static com.hexvane.eterniamod.setup.paving.PavingRegistry.*;

/** Operator-only physical road authoring without weakening housing gameplay or ownership rules. */
public final class RoadPaving {
    private static volatile RoadPaving active;
    private static final ThreadLocal<UUID> writing=new ThreadLocal<>();
    private static final Map<UUID,Start> starts=new ConcurrentHashMap<>();
    private final EterniaModPlugin plugin;private final PavingRegistry registry;
    private record Start(String world,int x,int y,int z,Instant expires){}
    private record Preview(Operation operation,Instant expires){}
    private RoadPaving(EterniaModPlugin plugin)throws IOException {
        this.plugin=plugin;registry=new PavingRegistry(plugin.getDataDirectory().resolve("public-road-paving"));
        // Plugin setup precedes native asset loading. Verify immutable bytes now; decode blocks only on use.
        for(var op:registry.all()){metadata(op,op.before(),false);metadata(op,op.after(),true);}
    }
    public static synchronized void startup(EterniaModPlugin plugin)throws IOException {if(active!=null)throw new IllegalStateException("Road paving is already started");active=new RoadPaving(plugin);}
    public static synchronized void close(){active=null;starts.clear();writing.remove();}
    public static List<PlotRect> protectedRects(String world){var service=active;return service==null?List.of():service.registry.all().stream().filter(op->op.state()==State.PENDING&&op.world().equals(world)).map(Operation::rect).toList();}
    /** The narrow world-thread write scope ignores only its own pending operation. */
    public static boolean protectedColumn(String world,int x,int z){var service=active;return service!=null&&service.registry.all().stream().anyMatch(op->op.state()==State.PENDING&&!op.id().equals(writing.get())&&op.world().equals(world)&&op.rect().contains(x,z));}
    private NativeSnapshotStore files(){return NativePlacementTransactions.snapshots(plugin);}
    private void metadata(Operation op,SnapshotFiles.Saved saved,boolean after)throws IOException {
        try{
            var envelope=BsonDocument.parse(new String(files().files().read(saved),StandardCharsets.UTF_8));
            if(!envelope.getString("format").getValue().equals("eternia-native-snapshot-1")||!envelope.getBoolean("clearFluidAtEveryBlock").getValue())throw new IllegalArgumentException("Unsupported paving snapshot");
            PavingRegistry.requireBounds(op.rect(),op.y(),envelope.getArray("bounds").stream().map(value->value.asInt32().getValue()).toList());
            var doc=envelope.getDocument("prefab");plainSnapshot(doc,op.rect().area());
            var seen=new HashSet<String>();
            for(var value:doc.getArray("blocks")){
                var cell=value.asDocument();int x=cell.getInt32("x").getValue(),y=cell.getInt32("y").getValue(),z=cell.getInt32("z").getValue();String name=cell.getString("name").getValue();
                if(x<0||x>=op.rect().width()||y!=0||z<0||z>=op.rect().depth()||!seen.add(x+","+z)||after&&!name.equals("Rock_Stone_Cobble")||!after&&!natural(name))throw new IllegalArgumentException("Paving snapshot has cells outside its reviewed plain-ground footprint");
            }
        }catch(RuntimeException e){throw new IOException("Paving snapshot metadata differs from its operation",e);}
    }
    private NativeSnapshotStore.Snapshot load(Operation op,boolean after)throws IOException {
        var pointer=after?op.after():op.before();metadata(op,pointer,after);var snapshot=files().load(pointer);
        var b=snapshot.bounds();PavingRegistry.requireBounds(op.rect(),op.y(),List.of(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()));return snapshot;
    }
    private static RoadPaving service(){return Objects.requireNonNull(active,"Public-road paving is unavailable");}
    /** Explicit isolated native acceptance; never callable in a normal world or PostgreSQL authority. */
    public static void nativeSmoke(EterniaModPlugin plugin,World world)throws Exception {
        if(!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))||!plugin.getRuntimeConfig().local()||plugin.getRuntimeConfig().postgres()||!world.getName().equals("eternia_smoke"))throw new IllegalStateException("Paving smoke requires the isolated native fixture");
        var service=service();var actor=UUID.randomUUID();var rect=new PlotRect(-8,-8,3,3);
        var permissions=com.hypixel.hytale.server.core.permissions.PermissionsModule.get();var builder=Set.of(com.hypixel.hytale.server.core.permissions.HytalePermissions.BUILDER_TOOLS_EDITOR.getId());
        boolean forbidden=false;try{service.preview(world,actor,rect,0);}catch(com.hexvane.eterniamod.domain.DomainException expected){forbidden=true;}if(!forbidden)throw new IllegalStateException("Paving accepted an unauthorized actor");
        permissions.addUserPermission(actor,builder);
        try{
            boolean roadRejected=false;try{service.preview(world,actor,new PlotRect(0,0,1,1),0);}catch(IllegalStateException expected){roadRejected=true;}if(!roadRejected)throw new IllegalStateException("Paving accepted protected public road columns");
            var preview=service.preview(world,actor,rect,0);
            permissions.removeUserPermission(actor,builder);boolean revoked=false;try{service.confirm(world,preview);}catch(com.hexvane.eterniamod.domain.DomainException expected){revoked=true;}if(!revoked||service.registry.get(preview.operation.id())!=null)throw new IllegalStateException("Revoked paving authority mutated the world or journal");
            permissions.addUserPermission(actor,builder);service.confirm(world,preview);
            boolean duplicate=false;try{service.confirm(world,preview);}catch(IllegalStateException expected){duplicate=true;}if(!duplicate)throw new IllegalStateException("Paving preview could be applied twice");
            // Model a restart boundary after native write but before final journal acknowledgement.
            service.registry.put(preview.operation);
            if(new PavingRegistry(plugin.getDataDirectory().resolve("public-road-paving")).get(preview.operation.id()).state()!=State.PENDING||!protectedColumn(world.getName(),-8,-8))throw new IllegalStateException("Interrupted paving lost durable column protection");
            var claim=plugin.getClaims().validate(world,actor,new PlotRect(-8,-8,24,24),HousingRules.Scope.PUBLIC,false);
            if(claim.valid()||!claim.code().equals("paving"))throw new IllegalStateException("Claim validation ignored pending paving");
            service.restore(world,actor,preview.operation.id());
            service.files().verify(world,service.files().load(preview.operation.before()),bounds(rect,0).origin(),Set.of(),false);
            if(protectedColumn(world.getName(),-8,-8)||service.registry.get(preview.operation.id()).state()!=State.RESTORED)throw new IllegalStateException("Verified rollback retained stale paving protection");
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_PAVING_SMOKE_PASS: permission recheck, protected road rejection, native cobblestone, duplicate confirmation, durable pending claim protection and exact terrain restoration");
        }finally{permissions.removeUserPermission(actor,builder);}
    }
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){com.hexvane.eterniamod.pathtool.SplineRoadTool.give(ref,store,player);}
    public static void openLegacy(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        try{
            SetupAccess.require(player.getUuid());var service=service();var world=store.getExternalData().getWorld();
            var choices=new ArrayList<ChoicePage.Choice>();
            choices.add(new ChoicePage.Choice("Create and edit curved roads directly in the world","Road Designer",(r,s)->com.hexvane.eterniamod.pathtool.SplineRoadTool.give(r,s,player)));
            for(var op:service.registry.all().stream().filter(op->op.world().equals(world.getName())&&op.state()!=State.RESTORED).sorted(Comparator.comparing(Operation::id)).toList())choices.add(new ChoicePage.Choice("Paving "+op.rect().x()+", "+op.rect().z()+" · "+op.rect().area()+" blocks · "+op.state(),op.state()==State.PENDING?"Recover":"Undo",(r,s)->{
                try{SetupAccess.require(player.getUuid());draw(player,op.rect(),op.y());ChoicePage.open(r,s,player,"Restore the original ground?","This restores the complete marked paving segment from its saved terrain. A road registered for housing must first be removed through Hub setup, which checks that existing claims remain valid.",List.of(new ChoicePage.Choice("Restore saved terrain","Confirm",(rr,ss)->{
                    try{SetupAccess.require(player.getUuid());service.restore(ss.getExternalData().getWorld(),player.getUuid(),op.id());player.sendMessage(Message.raw("Original ground restored."));openLegacy(plugin,rr,ss,player);}catch(Exception e){error(player,e);}
                })));}catch(Exception e){error(player,e);}
            }));
            ChoicePage.open(ref,store,player,"Legacy paving recovery","Existing rectangular paving records can be recovered or undone here. Create new roads with the Road Designer; it builds the physical spline and registers its exact footprint together.",choices);
        }catch(Exception e){error(player,e);}
    }
    private Preview preview(World world,UUID actor,PlotRect rect,int y)throws Exception {
        SetupAccess.require(actor);validate(world,rect,y,null,true);plain(BlockType.getAssetMap().getAsset("Rock_Stone_Cobble"));
        var bounds=bounds(rect,y);var beforeDoc=files().capture(world,bounds,Set.of(),false,false);plainSnapshot(beforeDoc,rect.area());
        var afterDoc=beforeDoc.clone();for(var value:afterDoc.getArray("blocks"))value.asDocument().put("name",new BsonString("Rock_Stone_Cobble"));
        UUID id=UUID.randomUUID();var before=files().save(id+"-paving-before.json",bounds,beforeDoc);var after=files().save(id+"-paving-after.json",bounds,afterDoc);
        var op=new Operation(id,actor,world.getName(),rect,y,before.file(),after.file(),State.PENDING);metadata(op,op.before(),false);metadata(op,op.after(),true);
        return new Preview(op,Instant.now().plusSeconds(90));
    }
    private synchronized void confirm(World world,Preview preview)throws Exception {
        var op=preview.operation;SetupAccess.require(op.actor());if(Instant.now().isAfter(preview.expires)||registry.get(op.id())!=null)throw new IllegalStateException("This paving preview expired or was already used");
        if(!op.world().equals(world.getName()))throw new IllegalStateException("Paving preview belongs to another world");
        validate(world,op.rect(),op.y(),null,true);var before=load(op,false);var after=load(op,true);
        requireSame(files().capture(world,before.bounds(),Set.of(),false,false),before.document());
        registry.put(op);
        try{scoped(op,()->{files().apply(world,after,after.bounds().origin());files().verify(world,after,after.bounds().origin(),Set.of(),false);NativePlacementTransactions.flush(world,after.bounds());});registry.put(op.state(State.COMPLETE));}
        catch(Exception failure){throw new IllegalStateException("Paving was interrupted. Its marked ground remains protected; use Recover to restore the saved terrain.",failure);}
    }
    private synchronized void restore(World world,UUID actor,UUID id)throws Exception {
        SetupAccess.require(actor);var op=registry.get(id);if(op==null||op.state()==State.RESTORED||!op.world().equals(world.getName()))throw new IllegalStateException("Paving record changed or belongs to another world");
        validate(world,op.rect(),op.y(),op.id(),false);var before=load(op,false);var after=load(op,true);
        scoped(op,()->{var actual=files().capture(world,before.bounds(),Set.of(),false,false);if(op.state()==State.COMPLETE)requireSame(actual,after.document());else requireKnown(actual,before.document(),after.document());});
        registry.put(op.state(State.PENDING));
        scoped(op,()->{files().apply(world,before,before.bounds().origin());files().verify(world,before,before.bounds().origin(),Set.of(),false);NativePlacementTransactions.flush(world,before.bounds());});
        registry.put(op.state(State.RESTORED));
    }
    private void validate(World world,PlotRect rect,int y,UUID ignore,boolean natural) {
        world.getEntityStore().getStore().assertThread();PavingRegistry.requireGeometry(rect,y);
        if(!plugin.getInfrastructure().world(world.getName()).map(plan->!plan.role().equals("adventure")).orElse(false))throw new IllegalStateException("Configure this world as Hub or Housing before paving");
        var forbidden=new ArrayList<PlotRect>();var plan=plugin.getInfrastructure().world(world.getName()).orElseThrow();forbidden.addAll(plan.roads());forbidden.addAll(plan.portals());forbidden.addAll(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()));
        registry.all().stream().filter(op->op.state()==State.PENDING&&!op.id().equals(ignore)&&op.world().equals(world.getName())).map(Operation::rect).forEach(forbidden::add);
        EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).listPlots().forEach(plot->forbidden.add(NativeHousingChecks.rect(plot.getFootprint())));
        for(var slot:plugin.getServices().housing().allSlots())if(slot.state()!=HousingService.State.PACKED)plugin.getServices().housing().location(slot.owner()).filter(location->location.worldId().equals(world.getName())).ifPresent(location->forbidden.add(new PlotRect(location.minX(),location.minZ(),location.width(),location.depth())));
        PavingRegistry.requireClear(rect,forbidden);
        for(int x=rect.x();x<rect.endX();x++)for(int z=rect.z();z<rect.endZ();z++){
            if(ChunkSectionBlockUtil.sectionRefAt(world,x,y,z)==null)throw new IllegalStateException("Load the complete road area before paving");
            var block=ChunkSectionBlockUtil.blockType(world,x,y,z);plain(block);
            if(natural&&!natural(block.getId()))throw new IllegalStateException("Paving replaces only natural ground, stone, or cobblestone");
            for(int yy=y;yy<=y+2;yy++){
                var section=ChunkSectionBlockUtil.sectionRefAt(world,x,yy,z);if(section==null)throw new IllegalStateException("Load the complete road area before paving");
                var fluid=world.getChunkStore().getStore().getComponent(section,com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType());
                if(fluid!=null&&fluid.getFluidId(x,yy,z)!=0)throw new IllegalStateException("Paving needs dry ground");
                if(yy>y){var above=ChunkSectionBlockUtil.blockType(world,x,yy,z);if(above!=null&&above.getMaterial()!=BlockMaterial.Empty)throw new IllegalStateException("The road needs two clear blocks above the ground");}
            }
        }
        var occupied=new boolean[1];var volume=new NativeSnapshotStore.Bounds(rect.x(),y,rect.z(),rect.endX(),y+3,rect.endZ());
        world.getEntityStore().getStore().forEachChunk(TransformComponent.getComponentType(),(chunk,commands)->{for(int i=0;i<chunk.size();i++){var p=chunk.getComponent(i,TransformComponent.getComponentType()).getPosition();if(volume.contains(p.x,p.y,p.z)&&chunk.getComponent(i,Player.getComponentType())==null)occupied[0]=true;}});
        if(occupied[0])throw new IllegalStateException("Move props, creatures and dropped items out of the road before paving");
    }
    private static boolean natural(String id){return id.startsWith("Soil_")||id.startsWith("Grass_")||id.startsWith("Sand_")||id.equals("Rock_Stone")||id.equals("Rock_Stone_Cobble");}
    private static void plain(BlockType block){if(block==null||!block.isCubeDrawType()||block.getBlockEntity()!=null)throw new IllegalStateException("Paving only supports plain cube ground blocks");}
    private static NativeSnapshotStore.Bounds bounds(PlotRect rect,int y){return new NativeSnapshotStore.Bounds(rect.x(),y,rect.z(),rect.endX(),y+1,rect.endZ());}
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void scoped(Operation op,Work work)throws Exception {if(writing.get()!=null)throw new IllegalStateException("Nested paving writes are unsupported");writing.set(op.id());try{work.run();}finally{writing.remove();}}
    private static void plainSnapshot(BsonDocument doc,long count){if(doc.getArray("blocks").size()!=count||!doc.getArray("fluids",new BsonArray()).isEmpty()||!doc.getArray("entities",new BsonArray()).isEmpty())throw new IllegalStateException("Road snapshot has missing, occupied or wet ground");for(var value:doc.getArray("blocks")){var cell=value.asDocument();if(cell.containsKey("components")||cell.getInt32("filler",new BsonInt32(0)).getValue()!=0)throw new IllegalStateException("Paving cannot replace inventory, functional, or multiblock cells");}}
    private static Map<String,BsonDocument> cells(BsonDocument doc){var cells=new HashMap<String,BsonDocument>();for(var value:doc.getArray("blocks")){var cell=value.asDocument().clone();cell.remove("support");cells.put(cell.getInt32("x")+","+cell.getInt32("y")+","+cell.getInt32("z"),cell);}return cells;}
    private static void requireSame(BsonDocument actual,BsonDocument expected){if(!cells(actual).equals(cells(expected))||!actual.getArray("fluids",new BsonArray()).equals(expected.getArray("fluids",new BsonArray())))throw new IllegalStateException("The ground changed after preview. Create a new preview.");}
    private static void requireKnown(BsonDocument actual,BsonDocument before,BsonDocument after){var a=cells(actual);var b=cells(before);var c=cells(after);if(!a.keySet().equals(b.keySet())||!actual.getArray("fluids",new BsonArray()).isEmpty())throw new IllegalStateException("Recovery found unknown ground or fluids; retain snapshots for operator review");for(var key:a.keySet())if(!a.get(key).equals(b.get(key))&&!a.get(key).equals(c.get(key)))throw new IllegalStateException("Recovery found an unrelated edit; retain snapshots for operator review");}
    private static void draw(PlayerRef player,PlotRect rect,int y){line(player,rect.x(),y+1.03,rect.z(),rect.endX(),rect.z());line(player,rect.x(),y+1.03,rect.endZ(),rect.endX(),rect.endZ());line(player,rect.x(),y+1.03,rect.z(),rect.x(),rect.endZ());line(player,rect.endX(),y+1.03,rect.z(),rect.endX(),rect.endZ());}
    private static void line(PlayerRef player,double x,double y,double z,double ex,double ez){var matrix=DebugLineCylinderUtil.segmentMatrix(x,y,z,ex,y,ez,.03,Math.hypot(ex-x,ez-z));if(matrix!=null)player.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder,Matrix4dUtil.asFloatData(matrix),new Vector3f(.57f,.79f,.55f),90f,(byte)DebugUtils.FLAG_NO_WIREFRAME,null,.8f));}
    private static void error(PlayerRef player,Exception error){player.sendMessage(Message.raw(error.getMessage()==null?"Road paving could not complete":error.getMessage()));}
}
