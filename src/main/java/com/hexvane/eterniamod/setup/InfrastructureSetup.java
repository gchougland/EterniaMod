package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3f;

/** In-game setup uses server-owned drafts; every confirmation rereads permissions, claims and infrastructure. */
public final class InfrastructureSetup {
    private record Corner(String world,int x,int y,int z) {}
    private record Selection(Corner first,Corner second,long expires) {}
    private record Draft(UUID id,String world,long revision,HousingInfrastructure.WorldPlan before,HousingInfrastructure.WorldPlan after,String description,PlotRect bounds,int y,long expires) {}
    private static final Map<UUID,Selection> SELECTIONS=new ConcurrentHashMap<>();
    private static final Map<UUID,Draft> DRAFTS=new ConcurrentHashMap<>();
    private InfrastructureSetup() {}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        SetupAccess.require(player.getUuid());clean();var world=store.getExternalData().getWorld();var plan=plugin.getInfrastructure().world(world.getName()).orElse(null);
        var choices=new ArrayList<ChoicePage.Choice>();
        choices.add(new ChoicePage.Choice("Choose this world's role","World role",(r,s)->roles(plugin,r,s,player)));
        choices.add(new ChoicePage.Choice("Use the safe position where you are standing","Set arrival",(r,s)->arrival(plugin,r,s,player)));
        choices.add(new ChoicePage.Choice("Place, move or remove the Hub's service NPCs and portal","Hub services",(r,s)->ManagedHubServices.open(plugin,r,s,player)));
        choices.add(new ChoicePage.Choice("Equip a tool to shape roads with editable spline nodes","Road Designer",(r,s)->com.hexvane.eterniamod.pathtool.SplineRoadTool.give(r,s,player)));
        choices.add(new ChoicePage.Choice("Mark the first corner of a public services plaza","Plaza start",(r,s)->corner(r,s,player,1)));
        choices.add(new ChoicePage.Choice("Mark the opposite corner of the public services plaza","Plaza end",(r,s)->corner(r,s,player,2)));
        choices.add(new ChoicePage.Choice("Protect a public portal and Hub NPC plaza using the selected corners","Register plaza",(r,s)->area(plugin,r,s,player,HousingInfrastructure.AreaKind.PORTAL,newName("plaza"))));
        choices.add(new ChoicePage.Choice("Inspect public plazas and legacy registered road areas","Manage areas",(r,s)->list(plugin,r,s,player)));
        choices.add(new ChoicePage.Choice("Refresh the selection outline without changing the world","Show selection",(r,s)->show(r,s,player)));
        ChoicePage.open(ref,store,player,"World setup · "+world.getName(),"Current role: "+(plan==null?"not configured":plan.role()+(plan.supportsHousing()?" · housing enabled":""))+". Shape public roads directly in the world with the Road Designer. Its confirmation builds and registers the road together. Plaza registration protects existing terrain.",choices);
    }
    private static void roles(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        SetupAccess.require(player.getUuid());var choices=new ArrayList<ChoicePage.Choice>();
        for(String role:List.of("hub-housing","hub","housing","adventure"))choices.add(new ChoicePage.Choice(switch(role){case "hub-housing"->"Hub with player and guild housing";case "hub"->"Hub without housing";case "housing"->"Separate housing world";default->"Adventure world";},"Review",(r,s)->role(plugin,r,s,player,role)));
        ChoicePage.open(ref,store,player,"World role","Housing-enabled worlds use Eternia's checked construction tools and disable native hand placement, gathering and fluid/fire simulation across the world. Public service interactions remain available.",choices);
    }
    public static void role(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,String value) {
        SetupAccess.require(player.getUuid());if(!Set.of("hub-housing","hub","housing","adventure").contains(value))throw input("Choose hub-housing, hub, housing or adventure.");
        var snapshot=plugin.getInfrastructure().snapshot();String world=store.getExternalData().getWorld().getName();var old=snapshot.worlds().get(world);
        String role=value.equals("hub-housing")?"hub":value;boolean housing=value.equals("hub-housing");
        if(role.equals("hub")&&snapshot.worlds().entrySet().stream().anyMatch(e->!e.getKey().equals(world)&&e.getValue().role().equals("hub")))throw input("A Hub is already configured. Change its role before assigning a different Hub.");
        var next=old==null?new HousingInfrastructure.WorldPlan(role,List.of(),List.of(),null,housing):old.withRole(role,housing);
        review(plugin,ref,store,player,snapshot,old,next,"Set "+world+" to "+value+". "+(next.supportsHousing()?"This enables the whole-world housing construction policy. ":"")+(next.arrival()==null?"Set a safe arrival point next.":"Existing roads, plazas and arrival are retained."),null,0);
    }
    public static void arrival(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        SetupAccess.require(player.getUuid());var pos=position(ref,store);var point=new HousingInfrastructure.Point(pos.x+.5,pos.y,pos.z+.5);var snapshot=plugin.getInfrastructure().snapshot();var old=requirePlan(snapshot,pos.world);
        review(plugin,ref,store,player,snapshot,old,old.withArrival(point),"Set "+pos.world+" arrival to "+point.x()+", "+point.y()+", "+point.z()+". All future public arrivals use this safe pad.",new PlotRect(pos.x,pos.z,1,1),pos.y);
    }
    public static void corner(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,int number) {
        SetupAccess.require(player.getUuid());clean();var pos=position(ref,store);var prior=SELECTIONS.get(player.getUuid());
        if(number==2&&(prior==null||!prior.first.world.equals(pos.world)))throw input("Mark the first corner in this world before the second.");
        if(number!=1&&number!=2)throw input("Corner must be 1 or 2.");
        if(SELECTIONS.size()>=256&&!SELECTIONS.containsKey(player.getUuid()))throw input("Too many setup selections are open. Try again shortly.");
        var next=number==1?new Selection(pos,null,System.currentTimeMillis()+1_200_000):new Selection(prior.first,pos,System.currentTimeMillis()+1_200_000);
        if(next.second!=null)InfrastructureRules.selection(next.first.x,next.first.z,next.second.x,next.second.z);
        SELECTIONS.put(player.getUuid(),next);DRAFTS.remove(player.getUuid());
        var component=store.getComponent(ref,Player.getComponentType());if(component!=null)component.getPageManager().setPage(ref,store,Page.None);
        player.sendMessage(Message.raw("Corner "+number+" marked at "+pos.x+", "+pos.z+" in "+pos.world+". "+(number==1?"Walk to the opposite corner and use /e admin setup corner 2.":"Use /e admin setup to register the road or public plaza.")));
        if(next.second!=null)draw(player,bounds(next),Math.max(next.first.y,next.second.y)+.08f,false);
    }
    public static void show(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        SetupAccess.require(player.getUuid());var selection=selection(player.getUuid(),store.getExternalData().getWorld().getName());var rect=bounds(selection);
        draw(player,rect,Math.max(selection.first.y,selection.second.y)+.08f,false);player.sendMessage(Message.raw(describe(rect)+" · outline visible for 20 seconds."));
    }
    public static void area(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,HousingInfrastructure.AreaKind kind,String id) {
        SetupAccess.require(player.getUuid());HousingInfrastructure.requireAreaId(id);String world=store.getExternalData().getWorld().getName();var selection=selection(player.getUuid(),world);var rect=bounds(selection);
        var snapshot=plugin.getInfrastructure().snapshot();var old=requirePlan(snapshot,world);var areas=new TreeMap<>(old.areas());var prior=areas.get(id);
        if(prior!=null&&prior.kind()!=kind)throw input("This name belongs to a different area type. Choose another name.");areas.put(id,new HousingInfrastructure.Area(kind,rect));
        review(plugin,ref,store,player,snapshot,old,old.withAreas(areas),(prior==null?"Register ":"Resize ")+kind.name().toLowerCase(Locale.ROOT)+" "+id+" · "+describe(rect)+". Protect all heights; no terrain is painted. "+(kind==HousingInfrastructure.AreaKind.PORTAL?"This plaza is public travel and claim-anchor infrastructure, and cannot be claimed.":"Road columns cannot be changed by housing tools."),rect,Math.max(selection.first.y,selection.second.y));
    }
    /** The paving tool can hand its verified physical footprint directly to road registration. */
    public static void openRoadArea(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,PlotRect rect,int groundY) {
        SetupAccess.require(player.getUuid());String world=store.getExternalData().getWorld().getName();
        SELECTIONS.put(player.getUuid(),new Selection(new Corner(world,rect.x(),groundY+1,rect.z()),new Corner(world,rect.endX()-1,groundY+1,rect.endZ()-1),System.currentTimeMillis()+1_200_000));
        area(plugin,ref,store,player,HousingInfrastructure.AreaKind.ROAD,newName("road"));
    }
    public static void remove(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,String id) {
        SetupAccess.require(player.getUuid());var snapshot=plugin.getInfrastructure().snapshot();String world=store.getExternalData().getWorld().getName();var old=requirePlan(snapshot,world);var area=old.areas().get(id);if(area==null)throw input("No area has that name in this world.");
        var next=new TreeMap<>(old.areas());next.remove(id);review(plugin,ref,store,player,snapshot,old,old.withAreas(next),"Unregister "+id+" · "+describe(area.rect())+". Physical terrain remains; this area's protection and anchors are removed. Existing claims and managed services must remain supported.",area.rect(),position(ref,store).y);
    }
    public static void list(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        SetupAccess.require(player.getUuid());var snapshot=plugin.getInfrastructure().snapshot();String world=store.getExternalData().getWorld().getName();var plan=requirePlan(snapshot,world);var choices=new ArrayList<ChoicePage.Choice>();
        choices.add(new ChoicePage.Choice("Aim at a spline road with the Road Designer to edit its nodes or restore the original terrain","Road Designer",(r,s)->com.hexvane.eterniamod.pathtool.SplineRoadTool.give(r,s,player)));
        new TreeMap<>(plan.areas()).forEach((id,area)->{if(id.startsWith("spline-"))return;choices.add(new ChoicePage.Choice(id+" · "+area.kind().name().toLowerCase(Locale.ROOT)+" · "+describe(area.rect()),"Manage",(r,s)->{
            SetupAccess.require(player.getUuid());if(!s.getExternalData().getWorld().getName().equals(world))throw input("Reopen the setup menu in this world.");var now=plugin.getInfrastructure().world(world).orElseThrow();if(!Objects.equals(now.areas().get(id),area))throw input("This area changed. Reopen the area list.");
            ChoicePage.open(r,s,player,"Area · "+id,describe(area.rect())+". New corners replace this named area's footprint after review.",List.of(
                new ChoicePage.Choice("Show the current protected footprint","Outline",(rr,ss)->{SetupAccess.require(player.getUuid());draw(player,area.rect(),position(rr,ss).y+.08,false);}),
                new ChoicePage.Choice("Use your current two-corner selection","Resize",(rr,ss)->area(plugin,rr,ss,player,area.kind(),id)),
                new ChoicePage.Choice("Keep physical blocks and remove this registered area","Unregister",(rr,ss)->remove(plugin,rr,ss,player,id))));
        }));});
        ChoicePage.open(ref,store,player,"Roads and public plazas · "+world,"Area names remain stable across reloads. To choose your own name, use /e admin setup road <name> or portal <name> after marking both corners.",choices);
    }
    private static void review(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,HousingInfrastructure.Snapshot snapshot,HousingInfrastructure.WorldPlan before,HousingInfrastructure.WorldPlan after,String description,PlotRect rect,int y) {
        SetupAccess.require(player.getUuid());clean();World world=store.getExternalData().getWorld();validate(plugin,world,before,after);
        if(DRAFTS.size()>=256&&!DRAFTS.containsKey(player.getUuid()))throw input("Too many setup reviews are open. Try again shortly.");
        var draft=new Draft(UUID.randomUUID(),world.getName(),snapshot.revision(),before,after,description,rect,y,System.currentTimeMillis()+300_000);DRAFTS.put(player.getUuid(),draft);
        if(rect!=null)draw(player,rect,y+.08,true);
        ChoicePage.open(ref,store,player,"Review world setup",description+" Review expires in five minutes; all rules run again when you confirm.",List.of(
            new ChoicePage.Choice("Save this reviewed setup change","Confirm",(r,s)->confirm(plugin,r,s,player,draft.id)),
            new ChoicePage.Choice("Keep the current setup","Cancel",(r,s)->{SetupAccess.require(player.getUuid());DRAFTS.remove(player.getUuid(),draft);open(plugin,r,s,player);})));
    }
    public static void confirm(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {var draft=DRAFTS.get(player.getUuid());if(draft==null)throw input("Review a setup change first.");confirm(plugin,ref,store,player,draft.id);}
    private static void confirm(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,UUID id) {
        SetupAccess.require(player.getUuid());clean();var draft=DRAFTS.get(player.getUuid());World world=store.getExternalData().getWorld();
        if(draft==null||!draft.id.equals(id)||!draft.world.equals(world.getName()))throw input("This review expired or belongs to a different world. Start it again.");
        var snapshot=plugin.getInfrastructure().snapshot();if(snapshot.revision()!=draft.revision||!Objects.equals(snapshot.worlds().get(draft.world),draft.before))throw input("Infrastructure changed since preview. Start a new review.");
        validate(plugin,world,draft.before,draft.after);
        try{plugin.getInfrastructure().saveWorld(draft.revision,draft.world,draft.after);}catch(IOException failure){throw new DomainException(DomainException.Code.CONFLICT,failure.getMessage(),failure);}
        DRAFTS.remove(player.getUuid(),draft);player.sendMessage(Message.raw("Saved setup for "+draft.world+". "+draft.description));
        plugin.getLogger().atInfo().log("Administrator %s saved infrastructure revision %s in world %s",player.getUuid(),draft.revision+1,draft.world);
        com.hexvane.eterniamod.customization.HousingWorldPolicy.refreshAll().whenComplete((unused,failure)->{if(failure!=null){plugin.getLogger().atSevere().withCause(failure).log("Saved world setup policy did not apply");player.sendMessage(Message.raw("Setup was saved, but the world gameplay policy could not apply. Check the server log before allowing housing claims."));}});
        open(plugin,ref,store,player);
    }
    private static void validate(EterniaModPlugin plugin,World world,HousingInfrastructure.WorldPlan before,HousingInfrastructure.WorldPlan after) {
        com.hexvane.eterniamod.pathtool.SplineRoadTool.validateConnections(world.getName(),after);
        var splineNames=new HashSet<String>();if(before!=null)splineNames.addAll(before.areas().keySet());splineNames.addAll(after.areas().keySet());
        for(String id:splineNames)if(id.startsWith("spline-")&&!Objects.equals(before==null?null:before.areas().get(id),after.areas().get(id)))throw input("Edit authored spline roads with the Road Designer so terrain and protection stay synchronized.");
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);var claims=new LinkedHashMap<UUID,HousingRules.Anchor>();
        for(var plot:manager.listPlots())claims.put(plot.getPlotId(),new HousingRules.Anchor(plot.getPlotId(),NativeHousingChecks.rect(plot.getFootprint()),plot.getGuildOwnerUuid()!=null?HousingRules.Scope.GUILD_ROOT:plot.getAttachedGuildUuid()!=null?HousingRules.Scope.GUILD_MEMBER:HousingRules.Scope.PUBLIC,plot.getGuildOwnerUuid()!=null?plot.getGuildOwnerUuid():plot.getAttachedGuildUuid(),plot.hasBuilding()));
        for(var slot:plugin.getServices().housing().allSlots()) {
            var location=plugin.getServices().housing().location(slot.owner()).orElse(null);if(location==null||!location.worldId().equals(world.getName())||slot.state()==HousingService.State.PACKED)continue;
            if(slot.state()!=HousingService.State.ACTIVE)throw input("A housing operation is unfinished in this world. Recover it before changing infrastructure.");
            var rect=new PlotRect(location.minX(),location.minZ(),location.width(),location.depth());var projection=claims.get(slot.propertyId());
            if(projection!=null&&!projection.rect().equals(rect))throw input("A plot's saved reservation differs from its world projection. Recover it first.");
            claims.putIfAbsent(slot.propertyId(),new HousingRules.Anchor(slot.propertyId(),rect,slot.owner().kind()==Owner.Kind.GUILD?HousingRules.Scope.GUILD_ROOT:location.guildId()!=null?HousingRules.Scope.GUILD_MEMBER:HousingRules.Scope.PUBLIC,location.guildId(),location.buildingPresent()));
        }
        var pending=new ArrayList<>(com.hexvane.eterniamod.setup.paving.RoadPaving.protectedRects(world.getName()));
        pending.addAll(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()));
        pending.addAll(com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()));
        try{InfrastructureRules.validate(before,after,List.copyOf(claims.values()),pending,com.hexvane.eterniamod.pathtool.SplineRoadTool.roadNetwork(world.getName(),before),com.hexvane.eterniamod.pathtool.SplineRoadTool.roadNetwork(world.getName(),after));ManagedHubServices.validateInfrastructure(world,after);}catch(IllegalArgumentException failure){throw input(failure.getMessage());}
        if(after.arrival()!=null&&(before==null||!Objects.equals(before.arrival(),after.arrival())))requireSafeArrival(world,after.arrival());
    }
    private static void requireSafeArrival(World world,HousingInfrastructure.Point point) {
        int x=(int)Math.floor(point.x()),y=(int)Math.floor(point.y()),z=(int)Math.floor(point.z());if(y<1||y>317)throw input("Choose an arrival with supported floor and headroom height.");
        var floor=ChunkSectionBlockUtil.blockType(world,x,y-1,z);if(floor==null||!floor.getMaterial().name().equals("Solid"))throw input("Stand on solid ground before setting the arrival.");
        for(int dy=0;dy<2;dy++) {
            var section=ChunkSectionBlockUtil.sectionRefAt(world,x,y+dy,z);if(section==null||ChunkSectionBlockUtil.blockId(world,x,y+dy,z)!=0)throw input("Arrival needs two empty blocks above its solid floor.");
            var fluid=section.getStore().getComponent(section,FluidSection.getComponentType());if(fluid!=null&&fluid.getFluidId(x,y+dy,z)!=0)throw input("An arrival cannot contain fluid.");
        }
    }
    private static HousingInfrastructure.WorldPlan requirePlan(HousingInfrastructure.Snapshot snapshot,String world){var plan=snapshot.worlds().get(world);if(plan==null)throw input("Choose this world's role in /e admin setup first.");return plan;}
    private static Corner position(Ref<EntityStore> ref,Store<EntityStore> store){var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)throw input("Your current position is unavailable.");var p=transform.getPosition();return new Corner(store.getExternalData().getWorld().getName(),(int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z));}
    private static Selection selection(UUID player,String world){clean();var value=SELECTIONS.get(player);if(value==null||value.second==null||!value.first.world.equals(world))throw input("Mark both corners in this world using /e admin setup corner 1 and corner 2.");return value;}
    private static PlotRect bounds(Selection selection){return InfrastructureRules.selection(selection.first.x,selection.first.z,selection.second.x,selection.second.z);}
    private static String newName(String prefix){return prefix+"-"+UUID.randomUUID().toString().substring(0,8);}
    private static String describe(PlotRect rect){return rect.width()+" × "+rect.depth()+" columns · X "+rect.x()+"–"+(rect.endX()-1)+", Z "+rect.z()+"–"+(rect.endZ()-1);}
    private static void clean(){long now=System.currentTimeMillis();SELECTIONS.entrySet().removeIf(e->e.getValue().expires<now);DRAFTS.entrySet().removeIf(e->e.getValue().expires<now);}
    private static DomainException input(String message){return new DomainException(DomainException.Code.INVALID_INPUT,message);}
    private static void draw(PlayerRef player,PlotRect rect,double y,boolean valid){var color=valid?new Vector3f(.57f,.79f,.55f):new Vector3f(.87f,.71f,.40f);line(player,rect.x(),y,rect.z(),rect.endX(),rect.z(),color);line(player,rect.endX(),y,rect.z(),rect.endX(),rect.endZ(),color);line(player,rect.endX(),y,rect.endZ(),rect.x(),rect.endZ(),color);line(player,rect.x(),y,rect.endZ(),rect.x(),rect.z(),color);}
    private static void line(PlayerRef player,double x,double y,double z,double ex,double ez,Vector3f color){var matrix=DebugLineCylinderUtil.segmentMatrix(x,y,z,ex,y,ez,.03,Math.hypot(ex-x,ez-z));if(matrix!=null)player.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder,Matrix4dUtil.asFloatData(matrix),color,20f,(byte)DebugUtils.FLAG_NO_WIREFRAME,null,.85f));}
}
