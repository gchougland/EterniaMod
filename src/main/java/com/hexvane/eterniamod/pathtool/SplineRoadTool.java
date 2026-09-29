package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** World-editing input comes only from the held item and server ray casts; item metadata grants no authority. */
public final class SplineRoadTool {
    public static final String ITEM="Eternia_Spline_Road_Tool",HUD="eternia:spline-road";
    private static volatile SplineRoadService service;private static EterniaModPlugin plugin;
    private static final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    static final class Session{
        final String world;List<SplineGeometry.Node> nodes=List.of();final Deque<List<SplineGeometry.Node>> undo=new ArrayDeque<>();int width=5,selected=-1,style;
        UUID editing,recovery;Instant recoveryExpiry;SplineRoadService.Review review;SplineRoadService.Plan plan;String message="Aim at the ground and add the first node";long revision,lastPaint,confirmAfter;boolean shown;
        Session(String world){this.world=world;}
        void change(List<SplineGeometry.Node> value){undo.push(nodes);while(undo.size()>32)undo.removeLast();nodes=List.copyOf(value);invalidate();}
        void invalidate(){review=null;recovery=null;plan=null;revision++;}
    }
    private SplineRoadTool(){}
    public static synchronized void startup(EterniaModPlugin owner)throws IOException{if(service!=null)throw new IllegalStateException("Spline road tool already started");plugin=owner;service=new SplineRoadService(owner);owner.getCodecRegistry(Interaction.CODEC).register("EterniaSplineRoad",SplineRoadInteraction.class,SplineRoadInteraction.CODEC);owner.getEntityStoreRegistry().registerSystem(new SplineRoadPreviewSystem());}
    public static synchronized void close(){service=null;plugin=null;sessions.clear();}
    static SplineRoadService service(){return Objects.requireNonNull(service,"Spline road tool is unavailable");}
    static Session session(PlayerRef player,World world){return sessions.compute(player.getUuid(),(id,old)->old==null||!old.world.equals(world.getName())?new Session(world.getName()):old);}
    static Session existing(UUID player){return sessions.get(player);}
    public static com.hexvane.eterniamod.housing.PublicRoadNetwork roadNetwork(String world,com.hexvane.eterniamod.housing.HousingInfrastructure.WorldPlan plan){var current=service;return current==null?com.hexvane.eterniamod.housing.PublicRoadNetwork.rectangles(plan==null?List.of():plan.roads()):current.network(world,plan,null);}
    public static List<PlotRect> pendingRects(String world){var current=service;return current==null?List.of():current.pendingRects(world);}
    public static boolean pendingColumn(String world,int x,int z){var current=service;return current!=null&&current.pendingColumn(world,x,z);}
    public static boolean mayWriteArea(String world,String area,int x,int z){return SplineRoadService.mayWriteArea(world,area,x,z);}
    public static void validateConnections(String world,com.hexvane.eterniamod.housing.HousingInfrastructure.WorldPlan after){var current=service;if(current!=null)current.validateConnections(world,after,null);}
    static boolean holding(Ref<EntityStore> ref,Store<EntityStore> store){var item=InventoryComponent.getItemInHand(store,ref);return !ItemStack.isEmpty(item)&&ITEM.equals(item.getItemId());}
    /** Idempotent tool grant. WorldEditor permission is still checked on every later click and commit. */
    public static boolean grant(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        SetupAccess.require(player.getUuid());var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.EVERYTHING);if(inventory==null)return false;
        for(short i=0;i<inventory.getCapacity();i++){var item=inventory.getItemStack(i);if(!ItemStack.isEmpty(item)&&ITEM.equals(item.getItemId()))return true;}
        var item=new ItemStack(ITEM,1);return inventory.canAddItemStack(item)&&inventory.addItemStack(item,true,false,true).succeeded();
    }
    public static void give(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){try{if(!grant(ref,store,player))throw new IllegalStateException("Free an inventory slot for the road tool");store.getComponent(ref,Player.getComponentType()).getPageManager().setPage(ref,store,Page.None);player.sendMessage(Message.raw("Equip the Road Designer. Your mapped controls appear on the left: add nodes, shape the curve, then review and confirm."));}catch(Exception error){error(player,error);}}
    static void action(String action,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        try{
            SetupAccess.require(player.getUuid());if(!holding(ref,store))return;var world=store.getExternalData().getWorld();var s=session(player,world);
            if(action.equals("Settings")){store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new SplineRoadSettingsPage(player,s));return;}
            if(action.equals("Manage")){manage(ref,store,player,s);return;}
            if(action.equals("Use")){use(world,player,s);return;}
            if(action.equals("Undo")){if(!s.undo.isEmpty()){s.nodes=s.undo.pop();s.selected=-1;s.invalidate();s.message="Previous node edit restored";}return;}
            var look=TargetUtil.getLook(ref,store);int picked=look==null?-1:SplineGeometry.pick(s.nodes,look.getPosition().x,look.getPosition().y,look.getPosition().z,look.getDirection().x,look.getDirection().y,look.getDirection().z);
            if(action.equals("Select")&&picked>=0){s.selected=picked;s.message="Node "+(picked+1)+" selected · aim at new ground and use Select / move again";s.revision++;return;}
            if(action.equals("Add")&&picked>=0){var next=new ArrayList<>(s.nodes);next.remove(picked);s.change(next);s.selected=-1;s.message="Node removed · undo restores it";return;}
            var target=TargetUtil.getTargetBlock(ref,64,store);if(target==null){s.message="Aim at solid ground within 64 blocks";return;}
            var node=new SplineGeometry.Node(target.x+.5,target.y+1,target.z+.5);var next=new ArrayList<>(s.nodes);
            if(action.equals("Add")){if(next.size()>=SplineGeometry.MAX_NODES)throw new IllegalStateException("This road has 32 nodes; finish it before starting another");next.add(node);s.change(next);s.selected=next.size()-1;s.message="Node "+next.size()+" added · keep adding nodes to shape the curve";}
            else if(action.equals("Select")&&s.selected>=0&&s.selected<next.size()){next.set(s.selected,node);s.change(next);s.message="Node moved · the grounded preview has updated";}
            else s.message="Aim at a node to select it, or use Add node on the ground";
        }catch(Exception error){error(player,error);var s=existing(player.getUuid());if(s!=null)s.message=error.getMessage();}
    }
    private static void use(World world,PlayerRef player,Session session)throws Exception{
        if(session.recovery!=null){if(Instant.now().isAfter(session.recoveryExpiry))throw new IllegalStateException("Recovery review expired; select the road again");service().recover(world,player.getUuid(),session.recovery);sessions.remove(player.getUuid());player.sendMessage(Message.raw("Previous road version and terrain restored."));return;}
        if(session.review!=null){if(System.currentTimeMillis()<session.confirmAfter)return;var review=session.review;session.review=null;service().confirm(world,review);session.nodes=List.of();session.undo.clear();session.editing=null;session.selected=-1;session.invalidate();session.message="Road saved · exact columns are registered for housing";player.sendMessage(Message.raw(review.pending().change().next()==null?"Road removed and original terrain restored.":"Road built and protected. Its nodes are saved; aim at it and use Manage to edit."));return;}
        var styles=service().styles();var style=styles.get(Math.floorMod(session.style,styles.size()));session.review=service().review(world,player.getUuid(),session.nodes,session.width,style.id(),session.editing);session.confirmAfter=System.currentTimeMillis()+500;session.lastPaint=0;session.message="REVIEW · "+session.review.pending().change().next().cells().size()+" road cells. Clears everything above the road. Press Use again to build; edit a node to cancel.";session.revision++;player.sendMessage(Message.raw(session.message));
    }
    private static void manage(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Session session){
        var target=TargetUtil.getTargetBlock(ref,64,store);var world=store.getExternalData().getWorld();var choices=new ArrayList<ChoicePage.Choice>();
        choices.add(new ChoicePage.Choice("Start a fresh curve; discard only this unsaved preview","New road",(r,s)->{sessions.remove(player.getUuid());s.getComponent(r,Player.getComponentType()).getPageManager().setPage(r,s,Page.None);}));
        choices.add(new ChoicePage.Choice("Recover or undo rectangular paving created before the Road Designer","Legacy records",(r,s)->com.hexvane.eterniamod.setup.paving.RoadPaving.openLegacy(plugin,r,s,player)));
        var aimed=target==null?null:service().roads().stream().filter(road->road.world().equals(world.getName())&&road.protectedCells().stream().anyMatch(c->c.x()==target.x&&c.z()==target.z)).findFirst().orElse(null);
        var roads=aimed==null?service().roads().stream().filter(r->r.world().equals(world.getName())&&r.state()==SplineRoadStore.State.PENDING).toList():List.of(aimed);
        for(var road:roads){
            if(road.state()==SplineRoadStore.State.PENDING){choices.add(new ChoicePage.Choice("Interrupted road · restore its previous version","Review recovery",(r,s)->{SetupAccess.require(player.getUuid());session.review=null;session.recovery=road.id();session.recoveryExpiry=Instant.now().plusSeconds(90);session.message="RECOVERY REVIEW · Press Use to restore the saved previous road and terrain";s.getComponent(r,Player.getComponentType()).getPageManager().setPage(r,s,Page.None);}));continue;}
            if(road.state()!=SplineRoadStore.State.ACTIVE)continue;
            choices.add(new ChoicePage.Choice("Reopen this road's "+road.current().nodes().size()+" saved spline nodes","Edit curve",(r,s)->{SetupAccess.require(player.getUuid());session.editing=road.id();session.nodes=road.current().nodes();session.undo.clear();session.width=road.current().width();var styles=service().styles();for(int i=0;i<styles.size();i++)if(styles.get(i).id().equals(road.current().style()))session.style=i;session.selected=-1;session.invalidate();session.message="Editing a saved road · move nodes, then review with Use";s.getComponent(r,Player.getComponentType()).getPageManager().setPage(r,s,Page.None);}));
            choices.add(new ChoicePage.Choice("Remove this authored road and restore its original ground","Review undo",(r,s)->{try{session.review=service().reviewRemoval(s.getExternalData().getWorld(),player.getUuid(),road.id());session.message="UNDO ROAD REVIEW · Press Use to restore the original terrain and remove protection";session.revision++;s.getComponent(r,Player.getComponentType()).getPageManager().setPage(r,s,Page.None);}catch(Exception error){error(player,error);}}));
        }
        ChoicePage.open(ref,store,player,"Road Designer",aimed==null?"Aim at an authored road to edit or undo it. Interrupted roads in this world appear here for recovery.":"Manage the road under your crosshair. Changes remain a preview until you confirm with the tool.",choices);
    }
    static void error(PlayerRef player,Exception error){player.sendMessage(Message.raw(error.getMessage()==null?"Road action could not complete":error.getMessage()));}
    /** Root's dedicated local playground uses the exact same validation, snapshots and commit as the item. */
    public static UUID authorLocalExample(World world,UUID actor,List<SplineGeometry.Node> nodes,int width,String style)throws Exception{requirePlayground(world);SetupAccess.require(actor);var review=service().review(world,actor,nodes,width,style,null);service().confirm(world,review);return review.pending().id();}
    /** A stable local fixture receipt preserves subsequent edits and deliberate removal on every rerun. */
    public static UUID ensureExample(World world,UUID actor)throws Exception{
        requirePlayground(world);SetupAccess.require(actor);world.getEntityStore().getStore().assertThread();
        UUID id=UUID.nameUUIDFromBytes(("eternia-local-playground-spline-v1:"+world.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));var previous=service().find(id);
        if(previous!=null){if(previous.state()==SplineRoadStore.State.PENDING)throw new IllegalStateException("Recover the interrupted playground road with the Road Designer before preparing the playground again");return id;}
        var review=service().review(world,actor,List.of(new SplineGeometry.Node(3.5,1,23.5),new SplineGeometry.Node(3.5,1,65.5),new SplineGeometry.Node(3.5,1,105.5),new SplineGeometry.Node(30.5,1,125.5)),5,"cobblestone",null,id);service().confirm(world,review);return id;
    }
    private static void requirePlayground(World world){if(plugin==null||!plugin.getRuntimeConfig().local()||!com.hexvane.eterniamod.localplayground.LocalPlayground.managedWorld(plugin,world.getName()))throw new IllegalStateException("Example authoring is limited to the managed local playground");}
    public static void nativeSmoke(EterniaModPlugin owner,World world)throws Exception{if(!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))||owner!=plugin||!owner.getRuntimeConfig().local()||owner.getRuntimeConfig().postgres()||!world.getName().equals("eternia_smoke"))throw new IllegalStateException("Spline smoke requires the isolated native fixture");service().nativeSmoke(world);}
}

