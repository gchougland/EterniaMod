package com.hexvane.eterniamod.inventory.ui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.PrefabLocalOffset;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.NativeHousingChecks;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.*;

/** Seller destinations come from the active native property, never from a UI coordinate or stale listing snapshot. */
public final class NativeShopTravel {
    private NativeShopTravel(){}
    private static final ConcurrentHashMap<UUID,CompletableFuture<Boolean>> visits=new ConcurrentHashMap<>();
    private record Visit(UUID property,String shopWorld,String originWorld,Vector3d origin){}
    private static final ConcurrentHashMap<UUID,Visit> guests=new ConcurrentHashMap<>();
    public static boolean isGuest(UUID player,HubPlotRecord plot){var visit=guests.get(player);return visit!=null&&visit.property.equals(plot.getPlotId())&&visit.shopWorld.equals(plot.getWorldName());}
    public static void forget(UUID player){guests.remove(player);}
    public static void returnFromVisit(EterniaModPlugin plugin,PlayerRef player){
        var visit=guests.get(player.getUuid());var ref=player.getReference();if(visit==null||ref==null||!ref.isValid())throw new IllegalStateException("You have no current shop visit to return from.");
        var store=ref.getStore();var world=store.getExternalData().getWorld();var t=store.getComponent(ref,TransformComponent.getComponentType());
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(visit.property);
        if(t==null||plot==null||!world.getName().equals(visit.shopWorld)||!NativeHousingChecks.rect(plot.getFootprint()).contains((int)Math.floor(t.getPosition().x),(int)Math.floor(t.getPosition().z)))throw new IllegalStateException("Return from the shop plot where this visit began.");
        plugin.getTravel().returnFromShop(player,visit.originWorld,new Vector3d(visit.origin)).thenAccept(ok->{if(ok)guests.remove(player.getUuid(),visit);else player.sendMessage(com.hypixel.hytale.server.core.Message.raw("The return point is obstructed. Your shop doors remain usable; try returning after the obstruction is cleared."));});
    }
    private record Destination(MarketService.Listing listing,World world,HubPlotRecord plot,Vector3d point){}
    public static MarketService.Listing requireAtShop(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef buyer,String listingId){
        store.assertThread();var identity=store.getComponent(ref,PlayerRef.getComponentType());if(identity==null||!identity.getUuid().equals(buyer.getUuid()))throw new IllegalStateException("Player location is unavailable.");
        var listing=listing(plugin,listingId);var world=store.getExternalData().getWorld();var destination=destination(plugin,listing,world);
        var position=store.getComponent(ref,TransformComponent.getComponentType());
        if(position==null||!NativeHousingChecks.rect(destination.plot.getFootprint()).contains((int)Math.floor(position.getPosition().x),(int)Math.floor(position.getPosition().z))||position.getPosition().distanceSquared(destination.point)>144)
            throw new IllegalStateException("Visit this house's shop entrance before purchasing.");
        return listing;
    }
    public static CompletableFuture<Boolean> visit(EterniaModPlugin plugin,PlayerRef buyer,String listingId){
        var sourceRef=buyer.getReference();if(sourceRef==null||!sourceRef.isValid())return CompletableFuture.completedFuture(false);
        var source=sourceRef.getStore();source.assertThread();
        var originTransform=source.getComponent(sourceRef,TransformComponent.getComponentType());if(originTransform==null)return CompletableFuture.completedFuture(false);
        String originWorld=source.getExternalData().getWorld().getName();Vector3d origin=new Vector3d(originTransform.getPosition());
        if(!plugin.getTravel().canUseTravel(sourceRef,source,buyer.getUuid()))throw new IllegalStateException("Visit player shops from the hub, a public portal, or an unlocked home teleporter.");
        var listing=listing(plugin,listingId);var location=plugin.getServices().housing().location(Owner.player(listing.seller())).orElseThrow();World target=Universe.get().getWorld(location.worldId());
        if(target==null)return CompletableFuture.completedFuture(false);
        var result=new CompletableFuture<Boolean>();if(visits.putIfAbsent(buyer.getUuid(),result)!=null)throw new IllegalStateException("A shop visit is already in progress.");
        result.orTimeout(12,TimeUnit.SECONDS).whenComplete((value,error)->visits.remove(buyer.getUuid(),result));target.execute(()->{
            if(result.isDone())return;
            try{
                var first=destination(plugin,listing(plugin,listingId),target);var p=first.point;
                com.hexvane.eterniamod.runtime.TravelLanding.load(target,p,2).whenComplete((chunk,error)->target.execute(()->{
                    if(result.isDone())return;
                    if(error!=null){result.complete(false);return;}
                    try{
                        var current=destination(plugin,listing(plugin,listingId),target);if(!current.plot.getPlotId().equals(first.plot.getPlotId())||current.point.distanceSquared(p)>0.01){result.complete(false);return;}
                        Vector3d safe=null;for(int radius=0;radius<=2&&safe==null;radius++)for(int dx=-radius;dx<=radius&&safe==null;dx++)for(int dz=-radius;dz<=radius&&safe==null;dz++){
                            int x=(int)Math.floor(p.x)+dx,y=(int)Math.floor(p.y),z=(int)Math.floor(p.z)+dz;
                            for(int dy:new int[]{0,1,-1,2,-2,3,-3})if(NativeHousingChecks.rect(current.plot.getFootprint()).contains(x,z)&&safe(plugin,target,x,y+dy,z)){safe=new Vector3d(x+.5,y+dy,z+.5);break;}
                        }
                        if(safe==null){result.complete(false);return;}Vector3d arrival=safe;var ref=buyer.getReference();if(ref==null||!ref.isValid()){result.complete(false);return;}
                        var store=ref.getStore();store.getExternalData().getWorld().execute(()->{
                            try{if(result.isDone())return;if(!ref.isValid()||!plugin.getTravel().canUseTravel(ref,store,buyer.getUuid())){result.complete(false);return;}
                                guests.put(buyer.getUuid(),new Visit(current.plot.getPlotId(),target.getName(),originWorld,origin));
                                store.putComponent(ref,Teleport.getComponentType(),Teleport.createForPlayer(target,arrival,new Rotation3f()));awaitArrival(plugin,buyer,listingId,target,result,25);
                            }catch(RuntimeException failure){result.complete(false);}
                        });
                    }catch(RuntimeException failure){result.complete(false);}
                }));
            }catch(RuntimeException failure){result.complete(false);}
        });return result;
    }
    private static void awaitArrival(EterniaModPlugin plugin,PlayerRef buyer,String listingId,World target,CompletableFuture<Boolean> result,int remaining){
        if(result.isDone())return;if(remaining==0){result.complete(false);return;}
        HytaleServer.SCHEDULED_EXECUTOR.schedule(()->{
            var ref=buyer.getReference();if(ref==null||!ref.isValid()){result.complete(false);return;}var store=ref.getStore();
            try{store.getExternalData().getWorld().execute(()->{
                if(!ref.isValid()){result.complete(false);return;}
                if(store.getExternalData().getWorld()==target&&store.getComponent(ref,Teleport.getComponentType())==null){
                    try{requireAtShop(plugin,ref,store,buyer,listingId);result.complete(true);}catch(RuntimeException failure){result.complete(false);}return;
                }awaitArrival(plugin,buyer,listingId,target,result,remaining-1);
            });}catch(RuntimeException failure){result.complete(false);}
        },200,TimeUnit.MILLISECONDS);
    }
    private static MarketService.Listing listing(EterniaModPlugin plugin,String id){return plugin.getServices().market().search("").stream().filter(l->l.id().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("This listing is no longer available."));}
    private static Destination destination(EterniaModPlugin plugin,MarketService.Listing listing,World world){
        Owner seller=Owner.player(listing.seller());var slot=plugin.getServices().housing().find(seller).orElseThrow();var location=plugin.getServices().housing().location(seller).orElseThrow();
        if(slot.state()!=HousingService.State.ACTIVE||!slot.propertyId().equals(listing.propertyId())||!location.worldId().equals(world.getName())||!location.buildingPresent())throw new IllegalStateException("The seller's house is currently unavailable.");
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());if(plot==null||!plot.isOwnedBy(listing.seller())||!plot.hasBuilding())throw new IllegalStateException("The seller's house is not ready for visitors.");
        var house=plot.getBuilding();var definition=plugin.getBuildingCatalog().get(house.getBuildingId());int[] local=definition==null?null:definition.getSpawnLocalPos();if(local==null)throw new IllegalStateException("This house has no configured shop entrance.");
        var buffer=com.hexvane.eterniamod.prefab.PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath());if(buffer==null)throw new IllegalStateException("The shop building is unavailable.");
        var footprint=com.hexvane.eterniamod.placement.PlotFootprintUtil.computeFootprint(new org.joml.Vector3i(house.getAnchorX(),house.getAnchorY(),house.getAnchorZ()),house.resolveRotationYaw(),buffer);
        // The house's login spawn is indoors. Place shoppers in the five-block yard,
        // beyond the complete prefab footprint, regardless of rotation or style.
        return new Destination(listing,world,plot,new Vector3d((footprint.getMinX()+footprint.getMaxX()+1)/2.0,plot.getFootprint().resolveVisualCenterY(),footprint.getMinZ()-2.5));
    }
    private static boolean safe(EterniaModPlugin plugin,World world,int x,int y,int z){
        if(plugin.getInfrastructure().protectedColumn(world.getName(),x,z))return false;var floor=ChunkSectionBlockUtil.blockType(world,x,y-1,z);if(floor==null||!floor.getMaterial().name().equals("Solid"))return false;
        for(int dy=0;dy<2;dy++){var section=ChunkSectionBlockUtil.sectionRefAt(world,x,y+dy,z);if(section==null||ChunkSectionBlockUtil.blockId(world,x,y+dy,z)!=0)return false;var fluid=section.getStore().getComponent(section,FluidSection.getComponentType());if(fluid!=null&&fluid.getFluidId(x,y+dy,z)!=0)return false;}return true;
    }
}
