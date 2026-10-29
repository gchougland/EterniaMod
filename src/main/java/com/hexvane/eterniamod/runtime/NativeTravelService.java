package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.PrefabLocalOffset;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.joml.Vector3d;

public final class NativeTravelService {
    private final EterniaModPlugin plugin;
    public NativeTravelService(EterniaModPlugin plugin){this.plugin=plugin;}
    public boolean canUseTravel(Ref<EntityStore> ref,Store<EntityStore> store,UUID player) {
        var world=store.getExternalData().getWorld();var plan=plugin.getInfrastructure().world(world.getName()).orElse(null);
        var transform=store.getComponent(ref,TransformComponent.getComponentType());if(plan==null||transform==null)return false;
        var pos=transform.getPosition();var point=new PlotRect((int)Math.floor(pos.x),(int)Math.floor(pos.z),1,1);
        if(plan.allowsPublicTravelFrom(point))return true;
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).findPlotContainingHorizontal(point.x(),point.z());
        if(plot==null)return false;
        if(plot.isOwnedBy(player))return plugin.getServices().ownership().owns(Owner.player(player),"eternia:convenience/teleporter");
        UUID guild=plot.getGuildOwnerUuid();return guild!=null&&plugin.getServices().guilds().can(player,guild,"convenience.use")&&plugin.getServices().ownership().owns(Owner.guild(guild),"eternia:convenience/teleporter");
    }
    public void selectWorld(PlayerRef player,String worldName) {
        var ref=player.getReference();if(ref==null||!ref.isValid())return;var store=ref.getStore();
        if(!canUseTravel(ref,store,player.getUuid())){player.sendMessage(Message.raw("Use a public portal or an unlocked home teleporter."));return;}
        var plan=plugin.getInfrastructure().world(worldName).orElseThrow(()->new IllegalArgumentException("Unknown destination"));
        World target=Universe.get().getWorld(worldName);
        if(target!=null&&plan.role().equals("adventure")){enterAdventure(player,ref,store,target,plan);return;}
        if(target==null||plan.arrival()==null){player.sendMessage(Message.raw("This destination is not available yet."));return;}
        arrive(player,target,new Vector3d(plan.arrival().x(),plan.arrival().y(),plan.arrival().z()),null).thenAccept(ok->{if(!ok)player.sendMessage(Message.raw("The destination is obstructed. An administrator needs to clear its arrival point."));});
    }
    /** A world visited before resumes where the player left it; a first visit starts at the world's spawn. */
    private void enterAdventure(PlayerRef player,Ref<EntityStore> ref,Store<EntityStore> store,World target,HousingInfrastructure.WorldPlan plan) {
        var component=store.getComponent(ref,Player.getComponentType());
        // The engine writes this whenever the player leaves the world, by travel or by logging out there.
        var visited=component==null?null:component.getPlayerConfigData().getPerWorldData().get(target.getName());
        var last=visited==null?null:visited.getLastPosition();
        if(last!=null){teleport(player,target,new Transform(new Vector3d(last.getPosition()),new Rotation3f(last.getRotation())));return;}
        if(plan.arrival()!=null){arrive(player,target,new Vector3d(plan.arrival().x(),plan.arrival().y(),plan.arrival().z()),null).thenAccept(ok->{if(!ok)player.sendMessage(Message.raw("The destination is obstructed. An administrator needs to clear its arrival point."));});return;}
        target.getWorldConfig().getSpawnProvider().getSpawnPointAsync(target,player.getUuid()).thenAccept(spawn->{
            if(spawn==null){player.sendMessage(Message.raw("This destination is not available yet."));return;}
            var point=new Vector3d(spawn.getPosition());var facing=new Rotation3f(spawn.getRotation());
            arrive(player,target,point,null,facing).thenAccept(ok->{if(!ok)teleport(player,target,new Transform(point,facing));});
        }).exceptionally(error->{plugin.getLogger().atWarning().withCause(error).log("Could not resolve world spawn");return null;});
    }
    /** Exact placement for a spot the player already stood on; only the chunks are prepared. */
    private CompletableFuture<Boolean> teleport(PlayerRef player,World world,Transform transform) {
        return TravelLanding.load(world,transform.getPosition(),1).thenCompose(loaded->{
            var result=new CompletableFuture<Boolean>();var ref=player.getReference();
            if(ref==null||!ref.isValid()){result.complete(false);return result;}
            var source=ref.getStore();source.getExternalData().getWorld().execute(()->{
                if(!ref.isValid()){result.complete(false);return;}
                source.putComponent(ref,Teleport.getComponentType(),Teleport.createForPlayer(world,transform));result.complete(true);
            });return result;
        }).exceptionally(error->{plugin.getLogger().atWarning().withCause(error).log("Could not return to the last position");return false;});
    }
    public void visitPlot(PlayerRef player,Owner owner) {
        var ref=player.getReference();if(ref==null||!ref.isValid())return;
        if(owner.kind()==Owner.Kind.PLAYER&&!owner.id().equals(player.getUuid())||owner.kind()==Owner.Kind.GUILD&&!plugin.getServices().guilds().can(player.getUuid(),owner.id(),"housing.visit"))return;
        if(!canUseTravel(ref,ref.getStore(),player.getUuid())){player.sendMessage(Message.raw("Use Locate plot from the Hub or a public portal. The plot coordinates are shown in My plots."));return;}
        var property=plugin.getServices().housing().property(owner).orElseThrow();var slot=property.slot();var location=java.util.Objects.requireNonNull(property.location());
        var world=Universe.get().getWorld(location.worldId());if(world==null||slot.state()!=HousingService.State.ACTIVE)return;
        world.execute(()->{var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());if(plot==null)return;
            var fp=plot.getFootprint();arrive(player,world,new Vector3d(fp.getMinX()+2.5,fp.resolveVisualCenterY(),fp.getMinZ()+2.5),NativeHousingChecks.rect(fp)).thenAccept(ok->player.sendMessage(Message.raw(ok?"Your plot. Open /e housing to choose a house or decoration.":"The plot arrival is obstructed. Use the coordinates in My plots to reach it.")));
        });
    }
    public enum JoinTarget { FIRST_JOIN, NEWCOMER, HOME }
    /** A checked place to stand: the world, a safe cell and the way to face. */
    public record Landing(World world,Vector3d point,Rotation3f facing) {
        public Transform transform(){return new Transform(new Vector3d(point),new Rotation3f(facing));}
    }
    /** Players with a plot always come home; others meet the Hub NPCs first, then wait in the housing area to claim. */
    public static JoinTarget joinTarget(boolean introduced,boolean activePlot){return activePlot?JoinTarget.HOME:introduced?JoinTarget.NEWCOMER:JoinTarget.FIRST_JOIN;}
    /**
     * Where a connecting player should appear, resolved before they enter any world so the engine spawns them there.
     * A teleport sent while the client is still finishing its join crashes the 0.7.4 client.
     */
    public CompletableFuture<Landing> joinLanding(UUID player) {
        boolean introduced=plugin.getServices().accounts().find(player).map(AccountService.Account::introduced).orElse(false);
        var property=plugin.getServices().housing().property(Owner.player(player)).orElse(null);
        boolean active=property!=null&&property.slot()!=null&&property.slot().state()==HousingService.State.ACTIVE&&property.location()!=null;
        return switch(joinTarget(introduced,active)){
            case HOME->homeLanding(player);
            case NEWCOMER->newcomerLanding();
            case FIRST_JOIN->firstJoinLanding();
        };
    }
    /** Home first, then the empty plot, then wherever a player without a plot belongs. */
    public void returnHome(PlayerRef player){travel(player,homeLanding(player.getUuid()));}
    public void returnHub(PlayerRef player){travel(player,hubLanding());}
    private CompletableFuture<Landing> homeLanding(UUID player) {
        var property=plugin.getServices().housing().property(Owner.player(player)).orElse(null);
        var slot=property==null?null:property.slot();var location=property==null?null:property.location();
        World world=location==null?null:Universe.get().getWorld(location.worldId());
        if(slot==null||slot.state()!=HousingService.State.ACTIVE||world==null)return withoutHomeLanding(player);
        var points=new CompletableFuture<HomePoints>();
        world.execute(()->{
            try{
                var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());
                if(plot==null){points.complete(null);return;}
                var fp=plot.getFootprint();var plotEdge=new Vector3d(fp.getMinX()+2.5,fp.resolveVisualCenterY(),fp.getMinZ()+2.5);
                var building=plot.hasBuilding()?plot.getBuilding():null;var def=building==null?null:plugin.getBuildingCatalog().get(building.getBuildingId());
                Vector3d house=null;
                if(def!=null&&def.getSpawnLocalPos()!=null){int[] spawn=def.getSpawnLocalPos();var offset=PrefabLocalOffset.rotate(building.resolveRotationYaw(),spawn[0],spawn[1],spawn[2]);
                    house=new Vector3d(building.getAnchorX()+offset.x+.5,building.getAnchorY()+offset.y,building.getAnchorZ()+offset.z+.5);}
                points.complete(new HomePoints(house,plotEdge,NativeHousingChecks.rect(fp)));
            }catch(RuntimeException failure){points.completeExceptionally(failure);}
        });
        return points.thenCompose(found->{
            if(found==null)return withoutHomeLanding(player);
            var edge=landing(world,found.edge(),found.area(),new Rotation3f());
            var home=found.house()==null?edge:or(landing(world,found.house(),found.area(),new Rotation3f()),()->edge);
            return or(home,()->withoutHomeLanding(player));
        });
    }
    private record HomePoints(Vector3d house,Vector3d edge,PlotRect area) {}
    private CompletableFuture<Landing> withoutHomeLanding(UUID player) {
        boolean introduced=plugin.getServices().accounts().find(player).map(AccountService.Account::introduced).orElse(false);
        return introduced?newcomerLanding():firstJoinLanding();
    }
    private CompletableFuture<Landing> firstJoinLanding(){return or(spotLanding(JoinSpawns.Kind.FIRST_JOIN),this::hubLanding);}
    private CompletableFuture<Landing> newcomerLanding() {
        return or(spotLanding(JoinSpawns.Kind.NEWCOMER),()->{
            var housing=housingArrival().orElse(null);World world=housing==null?null:Universe.get().getWorld(housing.getKey());
            if(world==null)return hubLanding();var p=housing.getValue().arrival();
            return or(landing(world,new Vector3d(p.x(),p.y(),p.z()),null,new Rotation3f()),this::hubLanding);
        });
    }
    private CompletableFuture<Landing> spotLanding(JoinSpawns.Kind kind) {
        var spot=plugin.getJoinSpawns().get(kind).orElse(null);World world=spot==null?null:Universe.get().getWorld(spot.world());
        if(world==null)return CompletableFuture.completedFuture(null);
        return landing(world,new Vector3d(spot.x(),spot.y(),spot.z()),null,new Rotation3f(0,spot.yaw(),0));
    }
    private CompletableFuture<Landing> hubLanding() {
        var hub=plugin.getInfrastructure().worlds().entrySet().stream().filter(e->e.getValue().role().equals("hub")&&e.getValue().arrival()!=null).findFirst().orElse(null);
        World world=hub==null?null:Universe.get().getWorld(hub.getKey());
        if(world==null)return CompletableFuture.completedFuture(null);
        var p=hub.getValue().arrival();return landing(world,new Vector3d(p.x(),p.y(),p.z()),null,new Rotation3f());
    }
    private static CompletableFuture<Landing> or(CompletableFuture<Landing> first,java.util.function.Supplier<CompletableFuture<Landing>> fallback) {
        return first.thenCompose(found->found!=null?CompletableFuture.completedFuture(found):fallback.get());
    }
    /** A separate housing world wins over a Hub that also hosts housing. */
    private Optional<Map.Entry<String,HousingInfrastructure.WorldPlan>> housingArrival() {
        return plugin.getInfrastructure().worlds().entrySet().stream().filter(e->e.getValue().supportsHousing()&&e.getValue().arrival()!=null&&Universe.get().getWorld(e.getKey())!=null)
            .min(Comparator.comparing((Map.Entry<String,HousingInfrastructure.WorldPlan> e)->e.getValue().role().equals("housing")?0:1).thenComparing(Map.Entry::getKey));
    }
    public CompletableFuture<Boolean> returnFromShop(PlayerRef player,String worldName,Vector3d point){
        var world=Universe.get().getWorld(worldName);if(world==null)return CompletableFuture.completedFuture(false);
        return arrive(player,world,point,null);
    }
    private CompletableFuture<Boolean> arrive(PlayerRef player,World world,Vector3d point,PlotRect allowed) {return arrive(player,world,point,allowed,new Rotation3f());}
    private CompletableFuture<Boolean> arrive(PlayerRef player,World world,Vector3d point,PlotRect allowed,Rotation3f facing) {return travel(player,landing(world,point,allowed,facing));}
    private CompletableFuture<Boolean> travel(PlayerRef player,CompletableFuture<Landing> landing) {
        return landing.thenCompose(found->{
            var result=new CompletableFuture<Boolean>();var ref=player.getReference();
            if(found==null||ref==null||!ref.isValid()){result.complete(false);return result;}
            var source=ref.getStore();source.getExternalData().getWorld().execute(()->{
                if(!ref.isValid()){result.complete(false);return;}
                source.putComponent(ref,Teleport.getComponentType(),Teleport.createForPlayer(found.world(),found.point(),found.facing()));result.complete(true);
            });return result;
        }).exceptionally(error->{plugin.getLogger().atWarning().withCause(error).log("Could not resolve safe travel destination");return false;});
    }
    /** Search a few cells around the point for solid ground with two free cells above; null when none is safe. */
    private CompletableFuture<Landing> landing(World world,Vector3d point,PlotRect allowed,Rotation3f facing) {
        return TravelLanding.load(world,point,2).thenCompose(chunk->{
            var result=new CompletableFuture<Landing>();world.execute(()->{
                try{
                    Vector3d target=null;
                    for(int radius=0;radius<=2&&target==null;radius++)for(int dx=-radius;dx<=radius&&target==null;dx++)for(int dz=-radius;dz<=radius&&target==null;dz++) {
                        int x=(int)Math.floor(point.x)+dx,z=(int)Math.floor(point.z)+dz,y=(int)Math.floor(point.y);
                        for(int dy:new int[]{0,1,-1,2,-2,3,-3})if((allowed==null||allowed.contains(x,z))&&safe(world,x,y+dy,z,allowed!=null)){target=new Vector3d(x+.5,y+dy,z+.5);break;}
                    }
                    result.complete(target==null?null:new Landing(world,target,facing));
                }catch(RuntimeException failure){result.completeExceptionally(failure);}
            });return result;
        });
    }
    private boolean safe(World w,int x,int y,int z,boolean homeInterior) {
        // Authored world arrivals may be on protected public pads; only home spawns must avoid infrastructure.
        if(homeInterior&&plugin.getInfrastructure().isHousing(w.getName())&&plugin.getInfrastructure().protectedColumn(w.getName(),x,z))return false;
        var floor=ChunkSectionBlockUtil.blockType(w,x,y-1,z);
        if(floor==null||!floor.getMaterial().name().equals("Solid"))return false;
        for(int dy=0;dy<2;dy++) {
            var section=ChunkSectionBlockUtil.sectionRefAt(w,x,y+dy,z);if(section==null)return false;
            if(ChunkSectionBlockUtil.blockId(w,x,y+dy,z)!=0)return false;
            var fluids=section.getStore().getComponent(section,FluidSection.getComponentType());
            if(fluids!=null&&fluids.getFluidId(x,y+dy,z)!=0)return false;
        }return true;
    }
}
