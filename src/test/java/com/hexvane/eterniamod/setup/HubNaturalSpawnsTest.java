package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventsWorldConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class HubNaturalSpawnsTest {
    @Test void nativeSpawningSwitchAndSaveRoundTrip(@TempDir Path temp)throws Exception {
        // WorldConfig needs native provider registration and Hytale's logger from JVM startup.
        String cp=String.join(java.io.File.pathSeparator,
            Path.of(HubNaturalSpawnsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(HubNaturalSpawns.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(WorldConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path log=temp.resolve("spawning.log"),failure=temp.resolve("failure.txt");
        String java=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",java).toString(),
            "-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager",
            "-cp",cp,Probe.class.getName(),failure.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Native spawning test timed out");
            assertEquals(0,process.exitValue(),Files.exists(failure)?Files.readString(failure):Files.readString(log));
        }finally{if(process.isAlive())process.destroyForcibly();}
    }

    public static final class Probe {
        public static void main(String[] args) {
            try {
                com.hexvane.eterniamod.customization.HousingGameplaySyncTest.Probe.initializeNative();
                WorldConfig.PLUGIN_CODEC.register(WorldEventsWorldConfig.class,WorldEventsWorldConfig.ID,WorldEventsWorldConfig.CODEC);
                verify();
            }catch(Throwable failure){
                var text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));
                try{Files.writeString(Path.of(args[0]),text.toString());}catch(Exception ignored){}
                System.exit(1);
            }
        }
        private static HousingInfrastructure.WorldPlan plan(String role,boolean housing) {
            return new HousingInfrastructure.WorldPlan(role,List.of(),List.of(),null,housing);
        }
        private static void verify() {
            for(boolean housing:List.of(false,true)) {
                var config=new WorldConfig();config.consumeHasChanged();config.setIsSpawnMarkersEnabled(false);
                check(HubNaturalSpawns.configure(plan("hub",housing),config,"off").contains("is off."),"Report disabled spawning");
                check(!config.isSpawningNPC()&&config.consumeHasChanged(),"Disable spawning and schedule a world save");
                check(!WorldEventsWorldConfig.getOrCreate(config).isEnableWorldEvents(),"Disable breach world events too");
                check(!HubNaturalSpawns.eventsEnabled(config),"Block every event start while the switch is off");
                check(!config.isAllNPCFrozen()&&!config.isSpawnMarkersEnabled(),"Preserve freeze and marker settings");
                var saved=WorldConfig.CODEC.encode(config,new com.hypixel.hytale.codec.ExtraInfo());
                check(!saved.getBoolean("IsSpawningNPC").getValue(),"Persist the native spawning flag");
                // Round trip the spawning fields without loading unrelated gameplay asset registries.
                var fields=new org.bson.BsonDocument("IsSpawningNPC",saved.get("IsSpawningNPC"))
                    .append("IsSpawnMarkersEnabled",saved.get("IsSpawnMarkersEnabled"))
                    .append("Plugin",saved.get("Plugin"));
                var loaded=WorldConfig.CODEC.decode(fields,new com.hypixel.hytale.codec.ExtraInfo());
                check(!loaded.isSpawningNPC(),"Keep spawning disabled after loading a saved world config");
                check(!WorldEventsWorldConfig.getOrCreate(loaded).isEnableWorldEvents(),"Keep breach events disabled after loading");
                check(!HubNaturalSpawns.eventsEnabled(loaded),"Keep blocking event starts after loading");
                check(HubNaturalSpawns.configure(plan("hub",housing),loaded,"status").contains("is off."),"Status reads saved config");
                loaded.consumeHasChanged();
                HubNaturalSpawns.configure(plan("hub",housing),loaded,"off");
                HubNaturalSpawns.configure(plan("hub",housing),loaded,"status");
                check(!loaded.consumeHasChanged(),"Status and repeated settings must not dirty the world");
                HubNaturalSpawns.configure(plan("hub",housing),loaded,"ON");
                check(loaded.isSpawningNPC()&&loaded.consumeHasChanged(),"Reenable spawning and save");
                check(WorldEventsWorldConfig.getOrCreate(loaded).isEnableWorldEvents(),"Reenable world events");
                check(HubNaturalSpawns.eventsEnabled(loaded),"Allow event starts once the Hub setting allows them");
                check(!loaded.isSpawnMarkersEnabled(),"Keep independently disabled markers disabled");
            }
            var config=new WorldConfig();config.consumeHasChanged();
            check(HubNaturalSpawns.configure(plan("hub",false),config,"status").contains("breaches, are on."),"Report default world event setting");
            check(config.getPluginConfig().get(WorldEventsWorldConfig.class)==null&&!config.consumeHasChanged(),"Status must not create plugin configuration");
            check(HubNaturalSpawns.eventsEnabled(config),"Worlds that never set the switch keep their events");
            rejects(null,config,"off");
            for(String role:List.of("housing","adventure"))rejects(plan(role,false),config,"off");
            rejects(plan("hub",false),config,"false");
            check(config.isSpawningNPC()&&!config.consumeHasChanged(),"Rejected requests leave the world unchanged");
            config.setSpawningNPC(false);config.consumeHasChanged();
            HubNaturalSpawns.configure(plan("hub",false),config,"off");
            check(!WorldEventsWorldConfig.getOrCreate(config).isEnableWorldEvents()&&config.consumeHasChanged(),"An existing spawning ban must also disable and save breach events");
        }
        private static void rejects(HousingInfrastructure.WorldPlan plan,WorldConfig config,String mode) {
            try{HubNaturalSpawns.configure(plan,config,mode);}catch(IllegalArgumentException expected){return;}
            throw new AssertionError("Expected invalid world or mode to be refused");
        }
        private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    }
}
