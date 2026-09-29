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
import com.hypixel.hytale.server.core.Message;
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
        if(target==null||plan.arrival()==null){player.sendMessage(Message.raw("This destination is not available yet."));return;}
        arrive(player,target,new Vector3d(plan.arrival().x(),plan.arrival().y(),plan.arrival().z()),null).thenAccept(ok->{if(!ok)player.sendMessage(Message.raw("The destination is obstructed. An administrator needs to clear its arrival point."));});
    }
    public void visitPlot(PlayerRef player,Owner owner) {
        var ref=player.getReference();if(ref==null||!ref.isValid())return;
        if(owner.kind()==Owner.Kind.PLAYER&&!owner.id().equals(player.getUuid())||owner.kind()==Owner.Kind.GUILD&&!plugin.getServices().guilds().can(player.getUuid(),owner.id(),"housing.visit"))return;
        if(!canUseTravel(ref,ref.getStore(),player.getUuid())){player.sendMessage(Message.raw("Use Locate plot from the Hub or a public portal. The plot coordinates are shown in My plots."));return;}
        var slot=plugin.getServices().housing().find(owner).orElseThrow();var location=plugin.getServices().housing().location(owner).orElseThrow();
        var world=Universe.get().getWorld(location.worldId());if(world==null||slot.state()!=HousingService.State.ACTIVE)return;
        world.execute(()->{var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());if(plot==null)return;
            var fp=plot.getFootprint();arrive(player,world,new Vector3d(fp.getMinX()+2.5,fp.resolveVisualCenterY(),fp.getMinZ()+2.5),NativeHousingChecks.rect(fp)).thenAccept(ok->player.sendMessage(Message.raw(ok?"Your plot. Open /e housing to choose a house or decoration.":"The plot arrival is obstructed. Use the coordinates in My plots to reach it.")));
        });
    }
    public void returnHome(PlayerRef player) {
        var slot=plugin.getServices().housing().find(Owner.player(player.getUuid())).orElse(null);
        var location=plugin.getServices().housing().location(Owner.player(player.getUuid())).orElse(null);
        World world=location==null?null:Universe.get().getWorld(location.worldId());
        if(slot==null||slot.state()!=HousingService.State.ACTIVE||world==null){returnHub(player);return;}
        world.execute(()->{
            var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());
            if(plot==null||!plot.hasBuilding()){returnHub(player);return;}
            var building=plot.getBuilding();var def=plugin.getBuildingCatalog().get(building.getBuildingId());
            if(def==null||def.getSpawnLocalPos()==null){returnHub(player);return;}
            int[] spawn=def.getSpawnLocalPos();var offset=PrefabLocalOffset.rotate(building.resolveRotationYaw(),spawn[0],spawn[1],spawn[2]);
            var point=new Vector3d(building.getAnchorX()+offset.x+.5,building.getAnchorY()+offset.y,building.getAnchorZ()+offset.z+.5);
            arrive(player,world,point,NativeHousingChecks.rect(plot.getFootprint())).thenAccept(ok->{if(!ok)returnHub(player);});
        });
    }
    public void returnHub(PlayerRef player) {
        var hub=plugin.getInfrastructure().worlds().entrySet().stream().filter(e->e.getValue().role().equals("hub")&&e.getValue().arrival()!=null).findFirst().orElse(null);
        if(hub==null)return;World world=Universe.get().getWorld(hub.getKey());if(world==null)return;
        var p=hub.getValue().arrival();arrive(player,world,new Vector3d(p.x(),p.y(),p.z()),null);
    }
    public CompletableFuture<Boolean> returnFromShop(PlayerRef player,String worldName,Vector3d point){
        var world=Universe.get().getWorld(worldName);if(world==null)return CompletableFuture.completedFuture(false);
        return arrive(player,world,point,null);
    }
    private CompletableFuture<Boolean> arrive(PlayerRef player,World world,Vector3d point,PlotRect allowed) {
        return TravelLanding.load(world,point,2).thenCompose(chunk->{
            var result=new CompletableFuture<Boolean>();world.execute(()->{
                Vector3d target=null;
                for(int radius=0;radius<=2&&target==null;radius++)for(int dx=-radius;dx<=radius&&target==null;dx++)for(int dz=-radius;dz<=radius&&target==null;dz++) {
                    int x=(int)Math.floor(point.x)+dx,z=(int)Math.floor(point.z)+dz,y=(int)Math.floor(point.y);
                    for(int dy:new int[]{0,1,-1,2,-2,3,-3})if((allowed==null||allowed.contains(x,z))&&safe(world,x,y+dy,z,allowed!=null)){target=new Vector3d(x+.5,y+dy,z+.5);break;}
                }
                if(target==null){result.complete(false);return;}
                Vector3d destination=target;var ref=player.getReference();if(ref==null||!ref.isValid()){result.complete(false);return;}
                var source=ref.getStore();source.getExternalData().getWorld().execute(()->{
                    if(!ref.isValid()){result.complete(false);return;}
                    source.putComponent(ref,Teleport.getComponentType(),Teleport.createForPlayer(world,destination,new Rotation3f()));result.complete(true);
                });
            });return result;
        }).exceptionally(error->{plugin.getLogger().atWarning().withCause(error).log("Could not resolve safe travel destination");return false;});
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
