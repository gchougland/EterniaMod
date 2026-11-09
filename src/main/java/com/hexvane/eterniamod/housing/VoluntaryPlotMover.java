package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;
import java.util.concurrent.*;

/** Evacuates occupants before a voluntary move. Permissions and credit are checked again at mutation. */
public final class VoluntaryPlotMover implements AutoCloseable {
    private final EterniaModPlugin plugin;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"eternia-plot-moves");t.setDaemon(true);return t;});
    private final Set<Owner> pending=ConcurrentHashMap.newKeySet();
    public VoluntaryPlotMover(EterniaModPlugin plugin){this.plugin=plugin;}
    public void request(World world,Owner owner,PlayerRef actor){request(world,owner,actor,false);}
    /** Packs the plot; with {@code thenPlace} the map opens right after so the player can set it down somewhere new. */
    public void request(World world,Owner owner,PlayerRef actor,boolean thenPlace){
        if(!pending.add(owner)){actor.sendMessage(Message.raw("This plot is already being packed."));return;}
        step(world,owner,actor,UUID.randomUUID(),0,thenPlace);
    }
    private void step(World world,Owner owner,PlayerRef actor,UUID operation,int attempt,boolean thenPlace){
        try {
            if(owner.kind()==Owner.Kind.PLAYER&&!owner.id().equals(actor.getUuid())||owner.kind()==Owner.Kind.GUILD&&!plugin.getServices().guilds().can(actor.getUuid(),owner.id(),"housing.move"))throw new IllegalStateException("Your role cannot move this plot.");
            if(plugin.getServices().ownership().available(owner,HousingService.MOVE_CREDIT)<1)throw new IllegalStateException("A move credit is required.");
            var slot=plugin.getServices().housing().find(owner).orElseThrow();
            var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());
            if(plot==null)throw new IllegalStateException("The original plot is unavailable.");
            NativePlacementTransactions.requireActive(plugin,plot);
            boolean occupied=false;
            for(var ref:NativeSnapshotStore.entities(world,NativeRelocationCoordinator.bounds(plot))){
                var player=ref.getStore().getComponent(ref,PlayerRef.getComponentType());
                if(player!=null){stepOff(world,plot,player);occupied=true;}
            }
            if(occupied){
                if(attempt>=15)throw new IllegalStateException("Everyone needs to step off the plot before it can be packed. Walk onto the road and try again.");
                if(attempt==0)actor.sendMessage(Message.raw("Stepping everyone onto the road beside the plot before packing."));
                scheduler.schedule(()->world.execute(()->step(world,owner,actor,operation,attempt+1,thenPlace)),1,TimeUnit.SECONDS);return;
            }
            plugin.getRelocation().pack(world,owner,operation,false);pending.remove(owner);
            if(thenPlace){
                actor.sendMessage(Message.raw("Your plot is packed. Pick the hexagons to set it down on."));
                var ref=actor.getReference();
                if(ref!=null&&ref.isValid()){var store=ref.getStore();store.getExternalData().getWorld().execute(()->{if(ref.isValid())com.hexvane.eterniamod.claimmap.ClaimMapRoutes.claimNew(plugin,ref,store,actor,owner.kind()==Owner.Kind.GUILD);});}
            } else actor.sendMessage(Message.raw("Your plot is packed with everything on it. Set it down again from Home in your Journal."));
        }catch(RuntimeException failure){pending.remove(owner);actor.sendMessage(Message.raw(failure.getMessage()==null?"The plot could not be moved.":failure.getMessage()));}
    }
    /**
     * Moves a player standing on the plot onto the nearest road beside it, in the same world. Falls back to the hub
     * only when the world has no road to stand on.
     */
    private void stepOff(World world,com.hexvane.eterniamod.hub.HubPlotRecord plot,PlayerRef player){
        var ref=player.getReference();
        var transform=ref==null?null:ref.getStore().getComponent(ref,com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        var view=com.hexvane.eterniamod.hexplots.HexLand.view(plugin,world).orElse(null);
        if(transform==null||view==null||view.streetCells().isEmpty()){plugin.getTravel().returnHub(player);return;}
        var at=transform.getPosition();var lattice=view.lattice();
        com.hexvane.eterniamod.hexplots.HexCoord best=null;double bestDistance=Double.MAX_VALUE;
        for(var cell:view.streetCells()){double dx=lattice.centerX(cell)-at.x,dz=lattice.centerZ(cell)-at.z,d=dx*dx+dz*dz;if(d<bestDistance){bestDistance=d;best=cell;}}
        int x=(int)Math.floor(lattice.centerX(best)),z=(int)Math.floor(lattice.centerZ(best));
        int y=surface(world,x,z,(int)Math.floor(at.y));
        var point=new org.joml.Vector3d(x+.5,y,z+.5);
        plugin.getTravel().stepTo(player,world,point).thenAccept(ok->{if(!ok)plugin.getTravel().returnHub(player);});
    }
    /** The first free cell above solid ground near {@code fromY} at column (x, z). */
    private static int surface(World world,int x,int z,int fromY){
        for(int y=Math.min(fromY+24,318);y>Math.max(fromY-40,1);y--){
            var below=com.hexvane.eterniamod.world.ChunkSectionBlockUtil.blockType(world,x,y-1,z);
            if(below!=null&&below.getMaterial().name().equals("Solid")&&com.hexvane.eterniamod.world.ChunkSectionBlockUtil.blockId(world,x,y,z)==0)return y;
        }
        return fromY;
    }
    @Override public void close(){scheduler.shutdownNow();pending.clear();}
}
