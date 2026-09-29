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
    public void request(World world,Owner owner,PlayerRef actor){
        if(!pending.add(owner)){actor.sendMessage(Message.raw("This plot is already preparing to move."));return;}
        step(world,owner,actor,UUID.randomUUID(),0);
    }
    private void step(World world,Owner owner,PlayerRef actor,UUID operation,int attempt){
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
                if(player!=null){plugin.getTravel().returnHub(player);occupied=true;}
            }
            if(occupied){
                if(attempt>=15)throw new IllegalStateException("The plot could not be cleared. Check the hub arrival point and try again.");
                if(attempt==0)actor.sendMessage(Message.raw("Returning everyone in the plot to the hub before packing."));
                scheduler.schedule(()->world.execute(()->step(world,owner,actor,operation,attempt+1)),1,TimeUnit.SECONDS);return;
            }
            plugin.getRelocation().pack(world,owner,operation,false);
            actor.sendMessage(Message.raw("Your furnished plot is packed. Use your plot deed in a world with housing enabled to restore it."));pending.remove(owner);
        }catch(RuntimeException failure){pending.remove(owner);actor.sendMessage(Message.raw(failure.getMessage()==null?"The plot could not be moved.":failure.getMessage()));}
    }
    @Override public void close(){scheduler.shutdownNow();pending.clear();}
}
