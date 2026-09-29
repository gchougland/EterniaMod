package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.Dirty;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.block.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.*;
import com.hypixel.hytale.server.core.universe.world.chunk.section.*;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import com.hypixel.hytale.server.core.util.*;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.joml.Vector3d;
import static com.hexvane.eterniamod.setup.ManagedHubRegistry.*;

/** In-game setup of persistent, tagged public NPCs and portal pads. Unmanaged entities are never adopted. */
public final class ManagedHubServices implements AutoCloseable {
    private static volatile ManagedHubServices active;
    private final EterniaModPlugin plugin;
    private final ManagedHubRegistry registry;
    private volatile boolean closed;
    private static final int BLOCK_FLAGS=SetBlockSettings.NO_SET_FILLER|SetBlockSettings.NO_BREAK_FILLER|SetBlockSettings.NO_UPDATE_STATE|SetBlockSettings.NO_SEND_PARTICLES|SetBlockSettings.NO_DROP_ITEMS|SetBlockSettings.NO_FIRE_ON_BREAK;
    private ManagedHubServices(EterniaModPlugin plugin)throws IOException{this.plugin=plugin;registry=new ManagedHubRegistry(plugin.getDataDirectory().resolve("managed-hub-services"));}
    public static ManagedHubServices register(EterniaModPlugin plugin)throws IOException{
        ManagedNpcTag.TYPE=plugin.getEntityStoreRegistry().registerComponent(ManagedNpcTag.class,"EterniaManagedServiceNPC",ManagedNpcTag.CODEC);
        ManagedPortalTag.TYPE=plugin.getChunkStoreRegistry().registerComponent(ManagedPortalTag.class,"EterniaManagedPortal",ManagedPortalTag.CODEC);
        var result=new ManagedHubServices(plugin);active=result;
        plugin.getEntityStoreRegistry().registerSystem(new com.hypixel.hytale.component.system.RefSystem<EntityStore>() {
            public com.hypixel.hytale.component.query.Query<EntityStore> getQuery(){return com.hypixel.hytale.component.query.Query.and(ManagedNpcTag.TYPE,NPCEntity.getComponentType());}
            public void onEntityAdded(Ref<EntityStore> ref,AddReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
                store.getExternalData().getWorld().execute(()->{if(result.closed||!ref.isValid())return;try{result.refreshIdentity(store.getExternalData().getWorld(),ref);}catch(Exception failure){plugin.getLogger().atWarning().withCause(failure).log("Managed NPC identity could not refresh; reopen its setup menu");}});
            }
            public void onEntityRemove(Ref<EntityStore> ref,RemoveReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){}
        });
        return result;
    }
    @Override public void close(){closed=true;if(active==this)active=null;}
    public static boolean intersectsManaged(String world,PlotRect rectangle){var service=active;return service!=null&&service.registry.entries().stream().filter(e->e.live()&&e.world().equals(world)).flatMap(e->e.footprints().stream()).anyMatch(r->r.overlaps(rectangle));}
    public static void validateInfrastructure(World world,HousingInfrastructure.WorldPlan proposed){
        var service=active;if(service==null)return;
        for(var entry:service.registry.entries())if(entry.live()&&entry.world().equals(world.getName()))for(var footprint:entry.footprints()){
            if(proposed.portals().stream().noneMatch(area->area.contains(footprint)))throw new IllegalArgumentException("Move or remove "+entry.name()+" before removing its public services plaza.");
            if(proposed.roads().stream().anyMatch(area->area.overlaps(footprint)))throw new IllegalArgumentException("Move or remove "+entry.name()+" before registering a road over its location.");
            var arrival=proposed.arrival();if(arrival!=null&&footprint.contains((int)Math.floor(arrival.x()),(int)Math.floor(arrival.z())))throw new IllegalArgumentException("Keep the public arrival outside managed service footprints.");
        }
    }
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        try{var service=require(plugin);service.guard(ref,store,player);service.menu(ref,store,player);}catch(Exception failure){error(player,failure);}
    }
    private static ManagedHubServices require(EterniaModPlugin plugin){var value=active;if(value==null||value.closed||value.plugin!=plugin)throw new IllegalStateException("Managed hub setup is unavailable");return value;}
    /** Guarded, idempotent operator entry point for authored local playgrounds and setup automation. */
    public static UUID ensureNpc(EterniaModPlugin plugin,World world,UUID actor,HubNpcIdentity identity,int x,int y,int z,float yaw)throws Exception {
        SetupAccess.require(actor);world.getEntityStore().getStore().assertThread();var service=require(plugin);
        var existing=service.registry.entries().stream().filter(entry->entry.live()&&entry.kind()==Kind.NPC&&entry.world().equals(world.getName())&&entry.role().equals(identity.role())).findFirst().orElse(null);
        if(existing!=null){if(existing.state()!=State.ACTIVE)service.finish(world,existing,actor);var current=service.registry.find(existing.id()).orElseThrow();if(current.live()){service.refreshIdentity(world,service.ownedNpc(world,current,true));return current.id();}}
        return service.add(world,actor,Kind.NPC,identity.role(),new Pose(x,y,z,yaw)).id();
    }
    /** Reuses an existing managed portal in this world; never adopts arbitrary portal blocks. */
    public static UUID ensurePortal(EterniaModPlugin plugin,World world,UUID actor,int x,int y,int z,float yaw)throws Exception {
        SetupAccess.require(actor);world.getEntityStore().getStore().assertThread();var service=require(plugin);
        var existing=service.registry.entries().stream().filter(entry->entry.live()&&entry.kind()==Kind.PORTAL&&entry.world().equals(world.getName())).findFirst().orElse(null);
        if(existing!=null){if(existing.state()!=State.ACTIVE)service.finish(world,existing,actor);if(service.registry.find(existing.id()).orElseThrow().live())return existing.id();}
        return service.add(world,actor,Kind.PORTAL,"Eternia_World_Portal",new Pose(x,y,z,yaw)).id();
    }
    private void refreshIdentity(World world,Ref<EntityStore> ref)throws IOException {
        var store=world.getEntityStore().getStore();store.assertThread();var uuid=store.getComponent(ref,UUIDComponent.getComponentType());if(uuid==null)return;
        var entry=registry.find(uuid.getUuid()).orElse(null);if(entry==null||!entry.live()||entry.kind()!=Kind.NPC||!entry.world().equals(world.getName()))return;
        var tag=store.getComponent(ref,ManagedNpcTag.TYPE);var npc=store.getComponent(ref,NPCEntity.getComponentType());
        if(tag==null||!tag.matches(entry)||npc==null||!entry.role().equals(npc.getRoleName()))throw new IllegalStateException("Managed NPC identity does not match its saved service");
        var identity=HubNpcIdentity.forRole(entry.role()).orElseThrow();var component=store.getComponent(ref,ModelComponent.getComponentType());
        if(tag.identityRevision()!=HubNpcIdentity.REVISION||component==null||!identity.model().equals(component.getModel().getModelAssetId())){
            var asset=com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset.getAssetMap().getAsset(identity.model());if(asset==null)throw new IllegalStateException("NPC identity model is unavailable: "+identity.model());
            npc.setInitialModelScale(asset.getMinScale());npc.setAppearance(ref,asset,store);tag.markIdentityCurrent();
        }
        com.hypixel.hytale.server.npc.role.support.DisplayNameSupport.setDisplayName(ref,identity.nameplate(),store);
        var interactions=store.ensureAndGetComponent(ref,com.hypixel.hytale.server.core.modules.interaction.Interactions.getComponentType());
        interactions.setInteractionId(com.hypixel.hytale.protocol.InteractionType.Use,com.hypixel.hytale.server.npc.interactions.UseNPCInteraction.DEFAULT_ID);
        interactions.setInteractionHint(identity.hint());
        flushNpc(world,ref,entry.pose());
    }
    private void guard(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        SetupAccess.require(player.getUuid());store.assertThread();
        if(closed||!ref.isValid()||ref.getStore()!=store||store.getComponent(ref,PlayerRef.getComponentType())!=player)throw new IllegalStateException("Reopen setup in your current world");
    }
    private void menu(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        var choices=new ArrayList<ChoicePage.Choice>();
        for(String role:ROLES.keySet().stream().sorted().toList())choices.add(new ChoicePage.Choice("Place "+ROLES.get(role),"Place NPC",(r,s)->attempt(player,()->previewAdd(r,s,player,Kind.NPC,role))));
        choices.add(new ChoicePage.Choice("Place a physical world-select portal","Place portal",(r,s)->attempt(player,()->previewAdd(r,s,player,Kind.PORTAL,"Eternia_World_Portal"))));
        choices.add(new ChoicePage.Choice("List, move, remove or finish setup of managed services in this world","Manage",(r,s)->attempt(player,()->list(r,s,player))));
        ChoicePage.open(ref,store,player,"Hub NPCs and portals","Aim at level ground inside a registered portal / public services plaza. NPCs face you. If no block is aimed at, placement uses the ground three blocks ahead. Each placement shows its exact location before confirmation.",choices);
    }
    private void list(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        guard(ref,store,player);String world=store.getExternalData().getWorld().getName();var choices=new ArrayList<ChoicePage.Choice>();
        for(var entry:registry.entries())if(entry.live()&&entry.world().equals(world))choices.add(new ChoicePage.Choice(entry.name()+" · "+location(entry.pose())+" · "+(entry.state()==State.ACTIVE?"Placed":"Setup unfinished"),"Manage",(r,s)->attempt(player,()->manage(r,s,player,entry.id()))));
        choices.add(new ChoicePage.Choice("Back to placement choices","Back",(r,s)->open(plugin,r,s,player)));
        ChoicePage.open(ref,store,player,"Managed services","Only NPCs and portal pads created by this setup menu appear here. Stand near an existing service to load it before moving or removing it.",choices);
    }
    private void manage(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,UUID id){
        guard(ref,store,player);var entry=registry.find(id).orElseThrow();sameWorld(store,entry);var choices=new ArrayList<ChoicePage.Choice>();
        if(entry.kind()==Kind.NPC&&entry.state()==State.ACTIVE)try{refreshIdentity(store.getExternalData().getWorld(),ownedNpc(store.getExternalData().getWorld(),entry,true));}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
        if(entry.state()==State.ACTIVE){
            if(entry.kind()==Kind.NPC)choices.add(new ChoicePage.Choice("Move this NPC to the ground you are aiming at","Move",(r,s)->attempt(player,()->previewMove(r,s,player,entry))));
            choices.add(new ChoicePage.Choice("Remove this managed "+(entry.kind()==Kind.NPC?"NPC":"portal pad"),"Remove",(r,s)->attempt(player,()->previewRemove(r,s,player,entry))));
        }else if(entry.live())choices.add(new ChoicePage.Choice("Finish the saved placement, move or removal","Finish setup",(r,s)->attempt(player,()->{
            guard(r,s,player);var current=registry.require(id,entry.revision());sameWorld(s,current);confirm(r,s,player,"Finish setup?",current.name()+" at "+location(current.pose())+". Only its recorded, tagged objects will be changed.",()->finish(s.getExternalData().getWorld(),current,player.getUuid()));
        })));
        choices.add(new ChoicePage.Choice("Back to managed services","Back",(r,s)->attempt(player,()->list(r,s,player))));
        ChoicePage.open(ref,store,player,entry.name(),"Managed service at "+location(entry.pose())+". Portal pads can be removed and placed again; NPC moves keep their managed identity.",choices);
    }
    private void previewAdd(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Kind kind,String role)throws Exception{
        guard(ref,store,player);Pose pose=target(ref,store);World world=store.getExternalData().getWorld();validatePlace(world,kind,pose,null);
        String name=kind==Kind.PORTAL?"world-select portal":ROLES.get(role);
        confirm(ref,store,player,"Place "+name+"?","Place at "+location(pose)+" in "+world.getName()+". The marked public plaza stays open to visitors.",()->{
            add(world,player.getUuid(),kind,role,pose);
        });
    }
    private void previewMove(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Entry original)throws Exception{
        guard(ref,store,player);var current=registry.require(original.id(),original.revision());sameWorld(store,current);World world=store.getExternalData().getWorld();ownedNpc(world,current,true);
        Pose pose=target(ref,store);validatePlace(world,Kind.NPC,pose,current.id());
        confirm(ref,store,player,"Move "+current.name()+"?","Move from "+location(current.pose())+" to "+location(pose)+". Its service will be available at the new location.",()->{
            move(world,player.getUuid(),current,pose);
        });
    }
    private void previewRemove(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Entry original)throws Exception{
        guard(ref,store,player);var current=registry.require(original.id(),original.revision());sameWorld(store,current);
        confirm(ref,store,player,"Remove "+current.name()+"?","Remove the managed service at "+location(current.pose())+". The registered public plaza and world destinations remain available.",()->{
            remove(store.getExternalData().getWorld(),player.getUuid(),current);
        });
    }
    private void confirm(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,String title,String description,Work action){
        String world=store.getExternalData().getWorld().getName();Instant expires=Instant.now().plusSeconds(90);
        ChoicePage.open(ref,store,player,title,description,List.of(new ChoicePage.Choice("Apply this setup change","Confirm",(r,s)->attempt(player,()->{
            guard(r,s,player);if(!world.equals(s.getExternalData().getWorld().getName())||Instant.now().isAfter(expires))throw new IllegalStateException("Setup confirmation expired. Open a fresh preview.");
            action.run();player.sendMessage(Message.raw("Hub service setup saved."));list(r,s,player);
        })),new ChoicePage.Choice("Keep the current setup","Cancel",(r,s)->open(plugin,r,s,player))));
    }
    private Entry add(World world,UUID actor,Kind kind,String role,Pose pose)throws Exception{
        SetupAccess.require(actor);validatePlace(world,kind,pose,null);var entry=registry.create(kind,world.getName(),role,pose,actor);finish(world,entry,actor);return registry.find(entry.id()).orElseThrow();
    }
    private Entry move(World world,UUID actor,Entry expected,Pose destination)throws Exception{
        SetupAccess.require(actor);var entry=registry.require(expected.id(),expected.revision());if(!entry.world().equals(world.getName()))throw new IllegalStateException("Open setup in this service's world");ownedNpc(world,entry,true);validatePlace(world,Kind.NPC,destination,entry.id());
        finish(world,registry.begin(entry.id(),entry.revision(),State.MOVING,destination,actor),actor);return registry.find(entry.id()).orElseThrow();
    }
    private void remove(World world,UUID actor,Entry expected)throws Exception{
        SetupAccess.require(actor);var entry=registry.require(expected.id(),expected.revision());if(!entry.world().equals(world.getName()))throw new IllegalStateException("Open setup in this service's world");
        if(entry.kind()==Kind.NPC)ownedNpc(world,entry,true);else verifyPortal(world,entry,false);finish(world,registry.begin(entry.id(),entry.revision(),State.REMOVING,null,actor),actor);
    }
    private void finish(World world,Entry expected,UUID actor)throws Exception{
        SetupAccess.require(actor);
        world.getEntityStore().getStore().assertThread();var entry=registry.require(expected.id(),expected.revision());if(!entry.live()||entry.state()==State.ACTIVE)throw new IllegalStateException("Setup operation already finished");
        if(!entry.world().equals(world.getName()))throw new IllegalStateException("Finish setup in its recorded world");
        loaded(world,entry.pose());if(entry.source()!=null)loaded(world,entry.source());
        if(entry.state()!=State.REMOVING)validateArea(world,entry.pose(),entry.id());
        if(entry.kind()==Kind.NPC){
            var ref=ownedNpc(world,entry,false);
            if(entry.state()==State.REMOVING){
                if(ref!=null)NativeDecorativeEntities.remove(world,ref);
                else if(world.getChunkStore().getSaver() instanceof IChunkSaver.Cubic saver)saver.removeEntity(entry.id()).join();
                flush(world,entry.pose());
            }
            else{
                if(ref!=null){var position=world.getEntityStore().getStore().getComponent(ref,TransformComponent.getComponentType()).getPosition();
                    if(!near(position,entry.pose())){if(entry.source()==null||!near(position,entry.source()))throw new IllegalStateException("The managed NPC moved outside its saved setup locations. Inspect it before recovery.");NativeDecorativeEntities.remove(world,ref);flush(world,entry.source());ref=null;}
                }
                if(ref==null){
                    if(entry.state()==State.MOVING){if(world.getChunkStore().getSaver() instanceof IChunkSaver.Cubic saver)saver.removeEntity(entry.id()).join();flush(world,entry.source());}
                    validatePlace(world,Kind.NPC,entry.pose(),entry.id());int index=NPCPlugin.get().getIndex(entry.role());if(index<0)throw new IllegalStateException("The selected NPC service role is unavailable");
                    var pair=NPCPlugin.get().spawnEntity(world.getEntityStore().getStore(),index,position(entry.pose()),new Rotation3f(0,entry.pose().yaw(),0),null,
                        (npc,holder,store)->{holder.putComponent(UUIDComponent.getComponentType(),new UUIDComponent(entry.id()));holder.putComponent(ManagedNpcTag.TYPE,new ManagedNpcTag(entry.id(),entry.role()));},null);
                    if(pair==null)throw new IllegalStateException("The native NPC could not spawn. Finish the saved setup operation to retry.");ref=pair.first();
                }
                ownedNpc(world,entry,true);var store=world.getEntityStore().getStore();var rotation=new Rotation3f(0,entry.pose().yaw(),0);
                store.getComponent(ref,TransformComponent.getComponentType()).setRotation(rotation);store.putComponent(ref,HeadRotation.getComponentType(),new HeadRotation(rotation));store.getComponent(ref,NPCEntity.getComponentType()).saveLeashInformation(position(entry.pose()),rotation);
                refreshIdentity(world,ref);
            }
        }else{
            portalShape();if(entry.state()==State.REMOVING)removePortal(world,entry);else if(entry.state()==State.PLACING)placePortal(world,entry);else throw new IllegalStateException("Unsupported portal setup state");
        }
        registry.complete(entry.id(),entry.revision());
    }
    private Ref<EntityStore> ownedNpc(World world,Entry entry,boolean required){
        loaded(world,entry.pose());if(entry.source()!=null)loaded(world,entry.source());var store=world.getEntityStore().getStore();var ref=world.getEntityStore().getRefFromUUID(entry.id());
        if(ref==null){if(required)throw new IllegalStateException("This NPC is not loaded at its saved location. Approach it and reopen setup.");return null;}
        var tag=store.getComponent(ref,ManagedNpcTag.TYPE);var npc=store.getComponent(ref,NPCEntity.getComponentType());
        if(tag==null||!tag.matches(entry)||npc==null||!entry.role().equals(npc.getRoleName())||store.getComponent(ref,Player.getComponentType())!=null)throw new IllegalStateException("Entity identity does not match this managed NPC. No entity was changed.");
        var position=store.getComponent(ref,TransformComponent.getComponentType()).getPosition();if(!near(position,entry.pose())&&(entry.source()==null||!near(position,entry.source())))throw new IllegalStateException("The managed NPC is outside its saved setup positions. Inspect it before changing this record.");
        return ref;
    }
    private void validateArea(World world,Pose pose,UUID ignore){
        loaded(world,pose);var plan=plugin.getInfrastructure().world(world.getName()).orElseThrow(()->new IllegalStateException("Register this world in setup first"));var area=pose.footprint();
        if(plan.portals().stream().noneMatch(rect->rect.contains(area)))throw new IllegalStateException("Place services inside a registered public portal / services plaza with a clear 3 × 3 footprint.");
        if(plan.roads().stream().anyMatch(rect->rect.overlaps(area))||com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()).stream().anyMatch(rect->rect.overlaps(area)))throw new IllegalStateException("Keep service objects off road columns");
        if(EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).listPlots().stream().anyMatch(plot->area.overlaps(NativeHousingChecks.rect(plot.getFootprint()))))throw new IllegalStateException("Public services cannot occupy a player or guild plot");
        for(var slot:plugin.getServices().housing().allSlots())if(slot.state()!=com.hexvane.eterniamod.domain.HousingService.State.PACKED){
            var location=plugin.getServices().housing().location(slot.owner()).orElse(null);
            if(location!=null&&location.worldId().equals(world.getName())&&area.overlaps(new PlotRect(location.minX(),location.minZ(),location.width(),location.depth())))throw new IllegalStateException("Public services cannot occupy a reserved housing plot");
        }
        if(com.hexvane.eterniamod.setup.paving.RoadPaving.protectedRects(world.getName()).stream().anyMatch(area::overlaps))throw new IllegalStateException("Finish or undo pending paving before placing services here");
        for(var entry:registry.entries())if(entry.live()&&entry.world().equals(world.getName())&&!entry.id().equals(ignore)&&entry.footprints().stream().anyMatch(area::overlaps))throw new IllegalStateException("Another managed service uses this space");
        var arrival=plan.arrival();if(arrival!=null&&area.contains((int)Math.floor(arrival.x()),(int)Math.floor(arrival.z())))throw new IllegalStateException("Keep the world's arrival point clear. Choose a nearby position for this service.");
    }
    private void validatePlace(World world,Kind kind,Pose pose,UUID ignore){
        validateArea(world,pose,ignore);var area=pose.footprint();
        for(int x=area.x();x<area.endX();x++)for(int z=area.z();z<area.endZ();z++){
            var ground=ChunkSectionBlockUtil.blockType(world,x,pose.y()-1,z);if(ground==null||!ground.isCubeDrawType()||ground.getMaterial()==BlockMaterial.Empty)throw new IllegalStateException("Services need level solid ground across their 3 × 3 footprint");
            for(int y=pose.y();y<pose.y()+3;y++)if(!empty(world,x,y,z))throw new IllegalStateException("Clear the space above the service's 3 × 3 footprint first");
        }
        for(var entity:NativeSnapshotStore.entities(world,bounds(pose))){var id=world.getEntityStore().getStore().getComponent(entity,UUIDComponent.getComponentType());if(id==null||!id.getUuid().equals(ignore))throw new IllegalStateException("A player or entity is standing in this service's placement area");}
        if(kind==Kind.PORTAL)portalShape();
    }
    private static Pose target(Ref<EntityStore> ref,Store<EntityStore> store){
        var transform=store.getComponent(ref,TransformComponent.getComponentType());var head=store.getComponent(ref,HeadRotation.getComponentType());if(transform==null||head==null)throw new IllegalStateException("Player position is unavailable");
        var target=TargetUtil.getTargetBlock(ref,12,store);float yaw=head.getRotation().yaw()+(float)Math.PI;
        if(target!=null)return new Pose(target.x,target.y+1,target.z,yaw);
        var direction=PhysicsMath.vectorFromAngles(head.getRotation().yaw(),0,new Vector3d());var position=transform.getPosition();int x=(int)Math.floor(position.x+direction.x*3),z=(int)Math.floor(position.z+direction.z*3),feet=(int)Math.floor(position.y);
        for(int y=feet;y>=feet-3;y--){var ground=ChunkSectionBlockUtil.blockType(store.getExternalData().getWorld(),x,y-1,z);if(ground!=null&&ground.isCubeDrawType()&&ground.getMaterial()!=BlockMaterial.Empty)return new Pose(x,y,z,yaw);}
        throw new IllegalStateException("Aim at solid ground within twelve blocks");
    }
    private void placePortal(World world,Entry entry)throws IOException{
        var pose=entry.pose();var existing=portalTag(world,pose);
        if(existing==null){validatePlace(world,Kind.PORTAL,pose,entry.id());setPortalTag(world,pose,new ManagedPortalTag(entry.id()));}
        else if(!existing.matches(entry.id()))throw new IllegalStateException("This portal position belongs to a different managed object");
        verifyPortal(world,entry,true);BlockType type=portalType();int id=BlockType.getAssetMap().getIndex(type.getId());
        for(var cell:portalShape())ChunkSectionBlockUtil.setBlock(world,pose.x()+cell.x(),pose.y()+cell.y(),pose.z()+cell.z(),id,type,0,FillerBlockUtil.pack(cell.x(),cell.y(),cell.z()),BLOCK_FLAGS);
        verifyPortal(world,entry,false);flush(world,pose);
    }
    private void removePortal(World world,Entry entry)throws IOException{
        var pose=entry.pose();var tag=portalTag(world,pose);
        if(tag==null){for(var cell:portalShape())if(!empty(world,pose.x()+cell.x(),pose.y()+cell.y(),pose.z()+cell.z()))throw new IllegalStateException("Portal identity is missing and blocks remain. Inspect this placement before recovery.");flush(world,pose);return;}
        if(!tag.matches(entry.id()))throw new IllegalStateException("This portal belongs to a different managed object");verifyPortal(world,entry,true);
        for(var cell:portalShape())ChunkSectionBlockUtil.setBlock(world,pose.x()+cell.x(),pose.y()+cell.y(),pose.z()+cell.z(),BlockType.EMPTY_ID,BlockType.EMPTY,0,0,BLOCK_FLAGS);
        setPortalTag(world,pose,null);for(var cell:portalShape())if(!empty(world,pose.x()+cell.x(),pose.y()+cell.y(),pose.z()+cell.z()))throw new IllegalStateException("Portal removal could not be verified");flush(world,pose);
    }
    private static void verifyPortal(World world,Entry entry,boolean allowAir){
        var pose=entry.pose();var tag=portalTag(world,pose);if(tag==null||!tag.matches(entry.id()))throw new IllegalStateException("The portal's managed identity is missing or changed");int expected=BlockType.getAssetMap().getIndex("Eternia_World_Portal");
        for(var cell:portalShape()){
            int x=pose.x()+cell.x(),y=pose.y()+cell.y(),z=pose.z()+cell.z();boolean primary=cell.x()==0&&cell.y()==0&&cell.z()==0;
            int id=ChunkSectionBlockUtil.blockId(world,x,y,z);if(!(allowAir&&id==BlockType.EMPTY_ID)&&!(id==expected&&ChunkSectionBlockUtil.rotationIndex(world,x,y,z)==0&&ChunkSectionBlockUtil.filler(world,x,y,z)==FillerBlockUtil.pack(cell.x(),cell.y(),cell.z())))throw new IllegalStateException("Portal cells changed. No unrelated blocks will be overwritten.");
            var holder=ChunkSectionBlockUtil.blockEntityHolderAt(world,x,y,z);if(!primary&&holder!=null)throw new IllegalStateException("A functional block now overlaps this portal");
            if(primary&&holder!=null&&holder.getComponent(com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock.getComponentType())!=null)throw new IllegalStateException("A container was added to this portal. Inspect it before setup changes.");
            if(fluid(world,x,y,z))throw new IllegalStateException("Fluid now overlaps the managed portal");
        }
    }
    private static BlockType portalType(){var type=BlockType.getAssetMap().getAsset("Eternia_World_Portal");if(type==null||type.getBlockEntity()!=null)throw new IllegalStateException("The supported inventory-free portal asset is unavailable");return type;}
    private record Offset(int x,int y,int z){}
    private static List<Offset> portalShape(){
        var type=portalType();var boxes=BlockBoundingBoxes.getAssetMap().getAsset(type.getHitboxTypeIndex());if(boxes==null)throw new IllegalStateException("Portal hitbox is unavailable");var result=new ArrayList<Offset>();
        FillerBlockUtil.forEachFillerBlock(boxes.get(0),(x,y,z)->{if(x < -1||x>1||z < -1||z>1||y!=0)throw new IllegalStateException("Portal shape changed; update its setup adapter first");result.add(new Offset(x,y,z));});
        if(result.size()!=9||!result.contains(new Offset(0,0,0)))throw new IllegalStateException("Portal setup requires the verified 3 × 3 pad");return List.copyOf(result);
    }
    private static ManagedPortalTag portalTag(World world,Pose pose){var holder=ChunkSectionBlockUtil.blockEntityHolderAt(world,pose.x(),pose.y(),pose.z());return holder==null?null:holder.getComponent(ManagedPortalTag.TYPE);}
    private static void setPortalTag(World world,Pose pose,ManagedPortalTag tag){
        var ref=ChunkSectionBlockUtil.sectionRefAt(world,pose.x(),pose.y(),pose.z());var section=world.getChunkStore().getStore().getComponent(ref,BlockComponentSection.getComponentType());if(section==null)throw new IllegalStateException("Portal block metadata storage is unavailable");
        Holder<ChunkStore> holder=null;if(tag!=null){holder=ChunkStore.REGISTRY.newHolder();holder.putComponent(ManagedPortalTag.TYPE,tag);}
        BlockEntity.setBlockEntity(world.getChunkStore().getStore(),ref,section,pose.x(),pose.y(),pose.z(),tag==null?BlockType.EMPTY:portalType(),0,holder);
    }
    private static void loaded(World world,Pose pose){
        world.getEntityStore().getStore().assertThread();if(world.getChunkStore().supportsCubicSections())throw new IllegalStateException("Managed services require the supported section layout");
        for(int x=pose.x()-1;x<=pose.x()+1;x++)for(int z=pose.z()-1;z<=pose.z()+1;z++)for(int y=pose.y()-1;y<=pose.y()+2;y++){
            var chunk=ChunkSectionBlockUtil.worldChunkIfInMemory(world,x,z);var ref=ChunkSectionBlockUtil.sectionRefAt(world,x,y,z);if(chunk==null||ref==null||chunk.isSaving())throw new IllegalStateException("Approach the saved service location and let its terrain finish loading before setup");
            var entities=world.getChunkStore().getStore().getComponent(ref,EntitySection.getComponentType());if(entities!=null&&(entities.isSaving()||!entities.getEntityHolders().isEmpty()))throw new IllegalStateException("Service entities are still loading or saving. Try again shortly.");
            var section=world.getChunkStore().getStore().getComponent(ref,ChunkSection.getComponentType());var blocks=world.getChunkStore().getStore().getComponent(ref,BlockComponentSection.getComponentType());if(section!=null&&section.isSaving()||blocks!=null&&blocks.isSaving())throw new IllegalStateException("Service terrain is saving. Try again shortly.");
        }
    }
    private static boolean empty(World world,int x,int y,int z){return ChunkSectionBlockUtil.blockId(world,x,y,z)==BlockType.EMPTY_ID&&ChunkSectionBlockUtil.filler(world,x,y,z)==0&&ChunkSectionBlockUtil.blockEntityHolderAt(world,x,y,z)==null&&!fluid(world,x,y,z);}
    private static boolean fluid(World world,int x,int y,int z){var ref=ChunkSectionBlockUtil.sectionRefAt(world,x,y,z);if(ref==null)return true;var fluid=world.getChunkStore().getStore().getComponent(ref,FluidSection.getComponentType());return fluid!=null&&fluid.getFluidId(x,y,z)!=0;}
    private static NativeSnapshotStore.Bounds bounds(Pose pose){return new NativeSnapshotStore.Bounds(pose.x()-1,pose.y(),pose.z()-1,pose.x()+2,pose.y()+3,pose.z()+2);}
    private static void flush(World world,Pose pose)throws IOException{NativePlacementTransactions.flush(world,bounds(pose));}
    private static void flushNpc(World world,Ref<EntityStore> ref,Pose pose)throws IOException{
        var store=world.getEntityStore().getStore();var dirty=store.getComponent(ref,Dirty.getComponentType());if(dirty!=null){if(dirty.isSaving())throw new IllegalStateException("NPC storage is still in flight. Finish setup after it completes.");dirty.forceMarkDirty();}
        if(world.getChunkStore().getSaver() instanceof IChunkSaver.Cubic saver)saver.saveEntity(store.getComponent(ref,UUIDComponent.getComponentType()).getUuid(),store,ref).join();flush(world,pose);
    }
    /** Native acceptance in the explicit disposable fixture, including a fresh disk read through Hytale's loader. */
    public static void nativeSmoke(EterniaModPlugin plugin,World world)throws Exception{
        if(!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))||!plugin.getRuntimeConfig().local()||plugin.getRuntimeConfig().postgres()||!world.getName().equals("eternia_smoke"))throw new IllegalStateException("Managed service smoke requires the isolated native fixture");
        var service=require(plugin);var actor=UUID.randomUUID();var pose=new Pose(-25,1,11,0);var destination=new Pose(-19,1,11,1);var pad=new Pose(-25,1,17,0);
        var permissions=com.hypixel.hytale.server.core.permissions.PermissionsModule.get();var builder=Set.of(com.hypixel.hytale.server.core.permissions.HytalePermissions.BUILDER_TOOLS_EDITOR.getId());
        var infrastructure=plugin.getInfrastructure();var before=infrastructure.snapshot();var old=before.worlds().get(world.getName());var areas=new TreeMap<>(old.areas());
        areas.put("smoke-managed-services",new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL,new PlotRect(-28,8,12,12)));
        infrastructure.saveWorld(before.revision(),world.getName(),old.withAreas(areas));
        try{
            boolean denied=false;try{service.add(world,actor,Kind.NPC,"Eternia_Greeter",pose);}catch(com.hexvane.eterniamod.domain.DomainException expected){denied=true;}
            if(!denied)throw new IllegalStateException("Managed NPC placement accepted an unauthorized actor");
            permissions.addUserPermission(actor,builder);
            var npc=service.add(world,actor,Kind.NPC,"Eternia_Greeter",pose);var ref=service.ownedNpc(world,npc,true);var store=world.getEntityStore().getStore();
            checkSavedNpc(world,npc,true);
            if(new ManagedHubRegistry(plugin.getDataDirectory().resolve("managed-hub-services")).require(npc.id(),npc.revision()).state()!=State.ACTIVE)throw new IllegalStateException("Managed NPC authority did not survive a fresh registry load");
            permissions.removeUserPermission(actor,builder);denied=false;
            try{service.remove(world,actor,npc);}catch(com.hexvane.eterniamod.domain.DomainException expected){denied=true;}
            if(!denied||!ref.isValid()||service.registry.require(npc.id(),npc.revision()).state()!=State.ACTIVE)throw new IllegalStateException("Revoked setup permission changed a managed NPC");
            permissions.addUserPermission(actor,builder);
            // The managed UUID alone must never authorize deletion of an entity with different provenance.
            store.putComponent(ref,ManagedNpcTag.TYPE,new ManagedNpcTag(UUID.randomUUID(),npc.role()));boolean foreign=false;
            try{service.remove(world,actor,npc);}catch(IllegalStateException expected){foreign=true;}finally{store.putComponent(ref,ManagedNpcTag.TYPE,new ManagedNpcTag(npc.id(),npc.role()));}
            if(!foreign||!ref.isValid())throw new IllegalStateException("Managed setup adopted an entity with a mismatched identity tag");
            var moved=service.move(world,actor,npc,destination);checkSavedNpc(world,moved,true);
            boolean stale=false;try{service.remove(world,actor,npc);}catch(IllegalStateException expected){stale=true;}
            if(!stale||!service.ownedNpc(world,moved,true).isValid())throw new IllegalStateException("A stale setup confirmation changed the moved NPC");
            service.remove(world,actor,moved);checkSavedNpc(world,moved,false);
            for(var identity:HubNpcIdentity.values()){
                var id=ensureNpc(plugin,world,actor,identity,pose.x(),pose.y(),pose.z(),pose.yaw());var character=service.registry.find(id).orElseThrow();var characterRef=service.ownedNpc(world,character,true);
                checkNpcPresentation(store,characterRef,identity);checkSavedNpc(world,character,true);
                // A persisted old revision / old Prowl model must migrate on the same managed identity.
                store.putComponent(characterRef,ManagedNpcTag.TYPE,new ManagedNpcTag(id,identity.role()));
                store.getComponent(characterRef,NPCEntity.getComponentType()).setAppearance(characterRef,com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset.getAssetMap().getAsset("Eternia_Prowl"),store);
                service.refreshIdentity(world,characterRef);checkNpcPresentation(store,characterRef,identity);checkSavedNpc(world,character,true);
                if(!ensureNpc(plugin,world,actor,identity,pose.x(),pose.y(),pose.z(),pose.yaw()).equals(id))throw new IllegalStateException("Managed NPC setup duplicated an existing role");
                service.remove(world,actor,character);checkSavedNpc(world,character,false);
            }
            var portal=service.add(world,actor,Kind.PORTAL,"Eternia_World_Portal",pad);checkSavedPortal(world,portal,true);
            boolean roadRejected=false;var activePlan=infrastructure.world(world.getName()).orElseThrow();var roadAreas=new TreeMap<>(activePlan.areas());roadAreas.put("smoke-forbidden-road",new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,pad.footprint()));
            try{validateInfrastructure(world,activePlan.withAreas(roadAreas));}catch(IllegalArgumentException expected){roadRejected=true;}
            if(!roadRejected)throw new IllegalStateException("Road registration bypassed managed service protection");
            service.remove(world,actor,portal);checkSavedPortal(world,portal,false);
            // Simulate a crash after durable pad creation and before registry acknowledgement.
            var pending=service.registry.create(Kind.PORTAL,world.getName(),"Eternia_World_Portal",pad,actor);service.placePortal(world,pending);checkSavedPortal(world,pending,true);
            if(new ManagedHubRegistry(plugin.getDataDirectory().resolve("managed-hub-services")).require(pending.id(),pending.revision()).state()!=State.PLACING)throw new IllegalStateException("Interrupted portal placement lost its saved operation");
            service.finish(world,pending,actor);var recovered=service.registry.find(pending.id()).orElseThrow();checkSavedPortal(world,recovered,true);service.remove(world,actor,recovered);checkSavedPortal(world,recovered,false);
            if(service.registry.entries().stream().anyMatch(e->e.live()&&e.editor().equals(actor)))throw new IllegalStateException("Managed service smoke left a live fixture record");
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_MANAGED_SERVICES_SMOKE_PASS: current permissions, tagged NPC spawn/move/remove, stale and foreign identity rejection, native disk reload of NPC UUID/tag and portal pad/tag, pending portal recovery and protected plaza edits");
        }finally{
            permissions.removeUserPermission(actor,builder);
            // A failed acceptance keeps the plaza around any pending records so recovery remains safe.
            if(service.registry.entries().stream().noneMatch(e->e.live()&&e.editor().equals(actor)))infrastructure.saveWorld(infrastructure.snapshot().revision(),world.getName(),old);
        }
    }
    private static Holder<ChunkStore> savedSection(World world,Pose pose){
        var chunk=ChunkSectionBlockUtil.worldChunkIfInMemory(world,pose.x(),pose.z());
        var disk=world.getChunkStore().getLoader().loadHolder(chunk.getX(),chunk.getZ()).join();
        if(disk==null)throw new IllegalStateException("Native saved column did not reload");
        var column=disk.getComponent(com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn.getComponentType());
        var sections=column==null?null:column.getSectionHolders();int index=com.hypixel.hytale.math.util.ChunkUtil.indexSection(pose.y());
        if(sections==null||index>=sections.length||sections[index]==null)throw new IllegalStateException("Native saved section did not reload");return sections[index];
    }
    private static void checkSavedNpc(World world,Entry entry,boolean expected){
        var section=savedSection(world,entry.pose()).getComponent(EntitySection.getComponentType());
        var matches=section==null?List.<Holder<EntityStore>>of():section.getEntityHolders().stream().filter(holder->{var uuid=holder.getComponent(UUIDComponent.getComponentType());return uuid!=null&&uuid.getUuid().equals(entry.id());}).toList();
        if(matches.size()!=(expected?1:0))throw new IllegalStateException("Native NPC save/reload changed entity count");
        if(expected){var holder=matches.getFirst();var tag=holder.getComponent(ManagedNpcTag.TYPE);var npc=holder.getComponent(NPCEntity.getComponentType());var transform=holder.getComponent(TransformComponent.getComponentType());
            if(tag==null||!tag.matches(entry)||tag.identityRevision()!=HubNpcIdentity.REVISION||npc==null||!entry.role().equals(npc.getRoleName())||transform==null||!near(transform.getPosition(),entry.pose()))throw new IllegalStateException("Native NPC save/reload changed managed identity, role or position");
            var identity=HubNpcIdentity.forRole(entry.role()).orElseThrow();var interactions=holder.getComponent(com.hypixel.hytale.server.core.modules.interaction.Interactions.getComponentType());
            if(interactions==null||!identity.hint().equals(interactions.getInteractionHint()))throw new IllegalStateException("Native NPC save/reload lost its interaction hint");}
    }
    private static void checkNpcPresentation(Store<EntityStore> store,Ref<EntityStore> ref,HubNpcIdentity identity){
        var model=store.getComponent(ref,ModelComponent.getComponentType());var name=store.getComponent(ref,com.hypixel.hytale.server.core.entity.nameplate.Nameplate.getComponentType());var hint=store.getComponent(ref,com.hypixel.hytale.server.core.modules.interaction.Interactions.getComponentType());
        if(model==null||!identity.model().equals(model.getModel().getModelAssetId())||name==null||!identity.nameplate().equals(name.getText())||hint==null||!identity.hint().equals(hint.getInteractionHint())||!com.hypixel.hytale.server.npc.interactions.UseNPCInteraction.DEFAULT_ID.equals(hint.getInteractionId(com.hypixel.hytale.protocol.InteractionType.Use)))throw new IllegalStateException("NPC nameplate, distinct model, or native Use hint is missing: "+identity);
    }
    private static void checkSavedPortal(World world,Entry entry,boolean expected){
        var pose=entry.pose();var section=savedSection(world,pose);var components=section.getComponent(BlockComponentSection.getComponentType());
        var holder=components==null?null:components.getBlockHolder(com.hypixel.hytale.math.util.ChunkUtil.indexBlock(pose.x(),pose.y(),pose.z()));var tag=holder==null?null:holder.getComponent(ManagedPortalTag.TYPE);
        if(expected?(tag==null||!tag.matches(entry.id())):tag!=null)throw new IllegalStateException("Native portal identity did not survive block mutation and disk reload");
        var blocks=section.getComponent(BlockSection.getComponentType());int portal=BlockType.getAssetMap().getIndex("Eternia_World_Portal");
        for(var cell:portalShape()){int x=pose.x()+cell.x(),y=pose.y()+cell.y(),z=pose.z()+cell.z();
            if(blocks==null||blocks.get(x,y,z)!=(expected?portal:BlockType.EMPTY_ID)||blocks.getFiller(x,y,z)!=(expected?FillerBlockUtil.pack(cell.x(),cell.y(),cell.z()):0)||blocks.getRotationIndex(x,y,z)!=0)throw new IllegalStateException("Native portal cells did not survive disk reload");}
    }
    private static Vector3d position(Pose pose){return new Vector3d(pose.x()+.5,pose.y(),pose.z()+.5);}
    private static boolean near(Vector3d position,Pose pose){return position.distanceSquared(position(pose))<.25;}
    private static void sameWorld(Store<EntityStore> store,Entry entry){if(!entry.world().equals(store.getExternalData().getWorld().getName()))throw new IllegalStateException("Open setup in this service's world");}
    private static String location(Pose pose){return pose.x()+", "+pose.y()+", "+pose.z();}
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void attempt(PlayerRef player,Work action){try{action.run();}catch(Exception failure){error(player,failure);}}
    private static void error(PlayerRef player,Exception failure){player.sendMessage(Message.raw(failure.getMessage()==null?"Hub setup could not finish. Reopen managed services to inspect it.":failure.getMessage()));}
}
