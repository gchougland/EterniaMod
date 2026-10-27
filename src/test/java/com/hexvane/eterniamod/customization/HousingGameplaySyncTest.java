package com.hexvane.eterniamod.customization;

import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.codec.lookup.Priority;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.*;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.*;
import com.hypixel.hytale.server.core.universe.world.storage.provider.*;
import com.hypixel.hytale.server.core.universe.world.storage.resources.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HousingGameplaySyncTest {
    @Test void connectedClientsReceiveBuildModeAndRestoredRules(@TempDir Path temp)throws Exception {
        // Native WorldConfig requires Hytale's JUL manager from JVM startup.
        String cp=String.join(java.io.File.pathSeparator,
            Path.of(HousingGameplaySyncTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(HousingWorldPolicy.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(WorldConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path log=temp.resolve("gameplay.log"),failure=temp.resolve("failure.txt");
        String java=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",java).toString(),
            "-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager",
            "-cp",cp,Probe.class.getName(),failure.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Native gameplay test timed out");
            assertEquals(0,process.exitValue(),Files.exists(failure)?Files.readString(failure):Files.readString(log));
        } finally {if(process.isAlive())process.destroyForcibly();}
    }

    public static final class Probe {
        public static void main(String[] args) {
            try {checkGameplaySync();}catch(Throwable failure){
                var text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));
                try{Files.writeString(Path.of(args[0]),text.toString());}catch(Exception ignored){}
                System.exit(1);
            }
        }
        private static void checkGameplaySync() throws Exception {
            com.hypixel.hytale.server.core.Options.parse(new String[0]);
            IWorldGenProvider.CODEC.register(Priority.DEFAULT,VoidWorldGenProvider.ID,VoidWorldGenProvider.class,VoidWorldGenProvider.CODEC);
            IWorldMapProvider.CODEC.register(Priority.DEFAULT,DisabledWorldMapProvider.ID,DisabledWorldMapProvider.class,DisabledWorldMapProvider.CODEC);
            IChunkStorageProvider.CODEC.register(Priority.DEFAULT,EmptyChunkStorageProvider.ID,EmptyChunkStorageProvider.class,EmptyChunkStorageProvider.CODEC);
            IResourceStorageProvider.CODEC.register(Priority.DEFAULT,EmptyResourceStorageProvider.ID,EmptyResourceStorageProvider.class,EmptyResourceStorageProvider.CODEC);
            // GameplayConfig's default combat settings look up this registry during initialization.
            var events=new EventBus(false);
            AssetRegistry.register(new HytaleAssetStore<>(HytaleAssetStore.builder(EntityEffect.class,
                new IndexedLookupTableAssetMap<>(EntityEffect[]::new)).setPath("Entity/Effects")
                .setCodec(EntityEffect.CODEC).setKeyFunction(EntityEffect::getId).setReplaceOnRemove(EntityEffect::new)) {
                    @Override protected EventBus getEventBus(){return events;}
                });
            var config=new WorldConfig();
            config.setGameplayConfig(HousingWorldPolicy.GAMEPLAY);
            var sent=new ArrayList<String>();
            Runnable resend=()->sent.add(config.getGameplayConfig());
            HousingWorldPolicy.changeGameplayConfig(config,HousingWorldPolicy.BUILD_GAMEPLAY,resend);
            check(sent.equals(List.of(HousingWorldPolicy.BUILD_GAMEPLAY)),
                "Connected clients must receive the new rules after the server config changes");
            HousingWorldPolicy.changeGameplayConfig(config,HousingWorldPolicy.BUILD_GAMEPLAY,resend);
            check(sent.size()==2,"A second builder toggling must receive their own rules even when the shared config is unchanged");
            HousingWorldPolicy.changeGameplayConfig(config,HousingWorldPolicy.GAMEPLAY,resend);
            check(sent.equals(List.of(HousingWorldPolicy.BUILD_GAMEPLAY,HousingWorldPolicy.BUILD_GAMEPLAY,HousingWorldPolicy.GAMEPLAY)),
                "Disabling the last builder must restore the client's placement lock");

            var before=new HousingWorldPolicy.Previous("Hub","CustomGameplay",Set.of());
            config.setGameplayConfig(HousingWorldPolicy.BUILD_GAMEPLAY);
            var restored=HousingWorldPolicy.restore(before,config.getGameplayConfig(),Set.of("Fluid"));
            HousingWorldPolicy.changeGameplayConfig(config,restored.gameplay(),resend);
            check(sent.getLast().equals("CustomGameplay"),"Leaving housing must restore the clients' original rules");
        }
        private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    }
}
