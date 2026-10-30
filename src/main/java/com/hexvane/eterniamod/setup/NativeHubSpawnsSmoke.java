package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hypixel.hytale.builtin.adventure.worldevents.*;
import com.hypixel.hytale.builtin.adventure.worldevents.action.EventStartAction;
import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.List;
import java.util.UUID;

/** Real world-event cancellation in the disposable native acceptance world. */
public final class NativeHubSpawnsSmoke {
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
        HubNaturalSpawns.configure(plan,world,"off");
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_HUB_SPAWNS_PASS: natural spawning disabled, active goblin breach cancelled, gameplay event restart blocked, repeated disable safe");
    }
}
