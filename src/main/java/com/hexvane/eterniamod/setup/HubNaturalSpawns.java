package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventsPlugin;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventsWorldConfig;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import java.util.Locale;

/** Saved native controls for population spawning, automatic spawn markers and breach world events. */
public final class HubNaturalSpawns {
    private HubNaturalSpawns() {}

    /** Match the native world-event command so active breaches are cancelled as well as future starts. */
    public static String configure(HousingInfrastructure.WorldPlan plan,World world,String mode) {
        world.getEntityStore().getStore().assertThread();
        String message=configure(plan,world.getWorldConfig(),mode);
        switch(mode.toLowerCase(Locale.ROOT)) {
            case "off" -> WorldEventsPlugin.stopAllWorldEvents(world);
            case "on" -> WorldEventsPlugin.loadGameplayWorldEvents(world);
            default -> { }
        }
        return message;
    }

    /** Caller must check current setup permission and run on the world's thread. */
    public static String configure(HousingInfrastructure.WorldPlan plan,WorldConfig config,String mode) {
        if(plan==null||!plan.role().equals("hub"))throw new IllegalArgumentException("Stand in the configured Hub to change its natural spawning.");
        switch(mode.toLowerCase(Locale.ROOT)) {
            case "on", "off" -> {
                boolean enabled=mode.equalsIgnoreCase("on");
                if(config.isSpawningNPC()!=enabled) {
                    config.setSpawningNPC(enabled);
                    config.markChanged();
                }
                var events=WorldEventsWorldConfig.getOrCreate(config);
                if(events.isEnableWorldEvents()!=enabled) {
                    events.setEnableWorldEvents(enabled);
                    config.markChanged();
                }
            }
            case "status" -> { }
            default -> throw new IllegalArgumentException("Choose on, off or status.");
        }
        var events=config.getPluginConfig().get(WorldEventsWorldConfig.class);
        boolean eventsEnabled=events==null||events.isEnableWorldEvents();
        return "Natural enemy and animal spawning in the Hub is "+(config.isSpawningNPC()?"on.":"off.")
            +" World events, including goblin breaches, are "+(eventsEnabled?"on.":"off.")
            +" Hub characters and summoned companions remain available.";
    }
}
