package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hypixel.hytale.server.core.universe.Universe;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Durable 48-hour deadlines come from GuildService; this worker performs and acknowledges native packing. */
public final class GuildDepartureWorker implements AutoCloseable {
    private final EterniaModPlugin plugin;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"eternia-guild-departures");t.setDaemon(true);return t;});
    private final String worker="native-departures-"+UUID.randomUUID();
    private final AtomicBoolean running=new AtomicBoolean();
    private final Consumer<Throwable> errors;
    public GuildDepartureWorker(EterniaModPlugin plugin,Consumer<Throwable> errors){this.plugin=plugin;this.errors=errors;}
    public void start(){scheduler.scheduleWithFixedDelay(this::poll,0,60,TimeUnit.SECONDS);}
    public void poll(){
        if(!running.compareAndSet(false,true))return;
        try{
            var jobs=plugin.getServices().guilds().leaseDueDepartures(worker,1);
            if(jobs.isEmpty()){running.set(false);return;}
            var job=jobs.getFirst();Owner owner=Owner.player(job.playerId());
            var location=plugin.getServices().housing().location(owner).orElseThrow();
            var world=Universe.get().getWorld(location.worldId());
            if(world==null){running.set(false);return;} // Lease expires; do not fabricate completion for an offline world.
            world.execute(()->runJob(job,world,0));
        }catch(Throwable failure){running.set(false);errors.accept(failure);}
    }
    private void runJob(GuildService.DepartureJob job,com.hypixel.hytale.server.core.universe.world.World world,int attempt){
        try{
            Owner owner=Owner.player(job.playerId());var slot=plugin.getServices().housing().find(owner).orElseThrow();
            var plot=com.hexvane.eterniamod.hub.EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());
            boolean evacuating=false;
            if(plot!=null)for(var ref:NativeSnapshotStore.entities(world,NativeRelocationCoordinator.bounds(plot))){
                var player=ref.getStore().getComponent(ref,com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                if(player!=null){plugin.getTravel().returnHub(player);evacuating=true;}
            }
            if(evacuating){
                if(attempt>=30)throw new IllegalStateException("Guild departure waits for a configured, unobstructed hub arrival");
                scheduler.schedule(()->world.execute(()->runJob(job,world,attempt+1)),1,TimeUnit.SECONDS);return;
            }
            UUID operation=UUID.nameUUIDFromBytes(("guild-departure:"+job.id()).getBytes(StandardCharsets.UTF_8));
            new NativeRelocationCoordinator(plugin).pack(world,owner,operation,true);
            plugin.getServices().guilds().acknowledgeDeparture(job.id(),worker,operation);running.set(false);
        }catch(Throwable failure){running.set(false);errors.accept(failure);}
    }
    @Override public void close(){scheduler.shutdownNow();}
}
