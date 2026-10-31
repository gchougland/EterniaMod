package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hypixel.hytale.builtin.adventure.worldevents.*;
import com.hypixel.hytale.builtin.adventure.worldevents.action.EventStartAction;
import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Real world-event cancellation in the disposable native acceptance world. */
public final class NativeHubSpawnsSmoke {
    private static final UUID GLOBAL_BREACH=UUID.fromString("b7d9e3a1-6c24-4f5b-8e17-9a3d2c40b8f6");
    private static final long GLOBAL_SETTLE_MILLIS=3000;
    private NativeHubSpawnsSmoke() {}
    public static void run(EterniaModPlugin plugin,World world) {
        if(!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))||!plugin.getRuntimeConfig().local()||plugin.getRuntimeConfig().postgres()||!world.getName().equals("eternia_smoke"))throw new IllegalStateException("Spawning smoke requires the isolated native fixture");
        var store=world.getEntityStore().getStore();store.assertThread();
        var plan=new HousingInfrastructure.WorldPlan("hub",List.of(),List.of(),null);
        var manager=store.getResource(WorldEventManager.getResourceType());
        HubNaturalSpawns.configure(plan,world,"on");
        var id=UUID.randomUUID();
        var breach=EventStartAction.startEvent(store,new WorldEventRecord(id,"Goblin_Breach_First_Event",true));
        if(breach==null||manager.get(id)==null)throw new IllegalStateException("Could not start the native breach test event");
        HubNaturalSpawns.configure(plan,world,"off");
        if(world.getWorldConfig().isSpawningNPC()||WorldEventsWorldConfig.isEnabled(world)||manager.get(id)!=null||store.getEntityCountFor(WorldEvent.getComponentType())!=0)throw new IllegalStateException("Hub spawning off left a running world event");
        WorldEventsPlugin.loadGameplayWorldEvents(world);
        if(store.getEntityCountFor(WorldEvent.getComponentType())!=0)throw new IllegalStateException("Disabled Hub restarted gameplay world events");
        var direct=UUID.randomUUID();
        EventStartAction.startEvent(store,new WorldEventRecord(direct,"Goblin_Breach_First_Event",true));
        if(manager.get(direct)!=null||store.getEntityCountFor(WorldEvent.getComponentType())!=0)throw new IllegalStateException("Disabled Hub kept a directly started breach");
        HubNaturalSpawns.configure(plan,world,"off");
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_HUB_SPAWNS_PASS: natural spawning disabled, active goblin breach cancelled, gameplay event restart and direct start blocked, repeated disable safe");
    }
    /** Start the real global breach as another world's bootstrap does. Call away from any world thread: its start is queued. */
    public static void globalRoute(EterniaModPlugin plugin,World hub)throws Exception {
        var other=Universe.get().getDefaultWorld();
        if(other==null||other==hub)throw new IllegalStateException("Global breach smoke needs a second world");
        var plan=new HousingInfrastructure.WorldPlan("hub",List.of(),List.of(),null);
        CompletableFuture.runAsync(()->HubNaturalSpawns.configure(plan,hub,"off"),hub).join();
        if(!GlobalWorldEventManager.startEvent(GLOBAL_BREACH))GlobalWorldEventManager.scheduleStartEventInAllWorlds(GLOBAL_BREACH);
        Thread.sleep(GLOBAL_SETTLE_MILLIS);
        int inHub=events(hub),inOther=events(other);
        GlobalWorldEventManager.stopEvent(GLOBAL_BREACH);
        Thread.sleep(GLOBAL_SETTLE_MILLIS);
        if(inHub!=0)throw new IllegalStateException("A global breach started in the disabled Hub: "+inHub);
        if(inOther==0)throw new IllegalStateException("A global breach did not start in the other world");
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_HUB_GLOBAL_BREACH_PASS: global breach blocked in disabled Hub, running in other world; events="+inOther);
    }
    private static int events(World world) {
        return CompletableFuture.supplyAsync(()->world.getEntityStore().getStore().getEntityCountFor(WorldEvent.getComponentType()),world).join();
    }
}
