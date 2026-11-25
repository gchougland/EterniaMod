package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.HousingService;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Packs away guild plots paid for with an Eternal guild plot voucher that was taken back, because the subscription ended
 * before it was kept for good ({@code RankService}, {@link HousingService#owedToPack()}). Every minute it takes one owed
 * plot whose world is loaded, sends anyone standing on it to the hub and packs it. A failed pack is simply tried
 * again on the next pass with the same operation id, so it finishes by itself.
 */
public final class OwedPlotWorker implements AutoCloseable {
    private final EterniaModPlugin plugin;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"eternia-owed-plots");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    private final Consumer<Throwable> errors;

    public OwedPlotWorker(EterniaModPlugin plugin,Consumer<Throwable> errors){this.plugin=plugin;this.errors=errors;}
    public void start(){scheduler.scheduleWithFixedDelay(this::poll,30,60,TimeUnit.SECONDS);}

    public void poll(){
        if(!running.compareAndSet(false,true))return;
        try{
            var housing=plugin.getServices().housing();
            for(var slot:housing.owedToPack()){
                var location=housing.property(slot.propertyId()).map(HousingService.Property::location).orElse(null);
                if(location==null)continue;
                var world=Universe.get().getWorld(location.worldId());
                if(world==null)continue; // Waits until its world is loaded.
                world.execute(()->runJob(slot,world,0));
                return;
            }
            running.set(false);
        }catch(Throwable failure){running.set(false);errors.accept(failure);}
    }

    private void runJob(HousingService.Slot slot,com.hypixel.hytale.server.core.universe.world.World world,int attempt){
        try{
            var now=plugin.getServices().housing().slot(slot.propertyId()).orElse(null);
            if(now==null||now.state()!=HousingService.State.ACTIVE||!plugin.getServices().housing().owed(slot.propertyId())){running.set(false);return;}
            var plot=com.hexvane.eterniamod.hub.EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());
            boolean evacuating=false;
            if(plot!=null)for(var ref:NativeSnapshotStore.entities(world,NativeRelocationCoordinator.bounds(plot))){
                var player=ref.getStore().getComponent(ref,PlayerRef.getComponentType());
                if(player!=null){plugin.getTravel().returnHub(player);evacuating=true;}
            }
            if(evacuating&&attempt<30){scheduler.schedule(()->world.execute(()->runJob(slot,world,attempt+1)),1,TimeUnit.SECONDS);return;}
            UUID operation=UUID.nameUUIDFromBytes(("owed-pack:"+now.operationId()).getBytes(StandardCharsets.UTF_8));
            plugin.getRelocation().pack(world,slot.propertyId(),operation,true);
            plugin.getLogger().atInfo().log("Packed guild plot %s: its Eternal guild plot voucher was taken back",slot.propertyId());
            running.set(false);
        }catch(Throwable failure){running.set(false);errors.accept(failure);}
    }

    @Override public void close(){scheduler.shutdownNow();}
}
