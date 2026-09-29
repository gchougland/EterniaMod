package com.hexvane.eterniamod.customization;

import com.google.gson.*;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Housing permits checked Eternia edits only. Native hand placement and all fluid simulation stop. */
public final class HousingWorldPolicy {
    public static final String GAMEPLAY="Eternia_Housing",FLUID_TAG="Fluid";
    record Previous(String world,String gameplay,Set<String> disabled) {
        Previous {Objects.requireNonNull(world);Objects.requireNonNull(gameplay);disabled=Set.copyOf(disabled);}
    }
    record FileData(int version,Map<String,Previous> worlds){}
    record Restore(String gameplay,Set<String> disabled){}
    private static volatile HousingWorldPolicy active;
    private final EterniaModPlugin plugin;
    private final Path file;
    private final Map<String,Previous> previous=new TreeMap<>();
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private HousingWorldPolicy(EterniaModPlugin plugin) {
        this.plugin=plugin;file=plugin.getDataDirectory().resolve("housing-world-policy.json");
        try {
            if(Files.exists(file)) {
                if(Files.isSymbolicLink(file)||Files.size(file)>1_048_576)throw new IOException("Unsafe housing world policy journal");
                var data=JSON.fromJson(Files.readString(file),FileData.class);
                if(data==null||data.version!=1||data.worlds==null)throw new IOException("Invalid housing world policy journal");
                data.worlds.forEach((id,value)->{UUID.fromString(id);Objects.requireNonNull(value);previous.put(id,value);});
            }
        }catch(IOException|RuntimeException failure){throw new IllegalStateException("Housing world policy journal could not load",failure);}
    }
    static void register(EterniaModPlugin plugin) {
        active=new HousingWorldPolicy(plugin);
        plugin.getEventRegistry().registerGlobal(StartWorldEvent.class,event->{var policy=active;if(policy!=null)policy.apply(event.getWorld());});
    }
    /** Call after asset startup and after infrastructure reload; every mutation runs on its own world. */
    public static CompletableFuture<Void> refreshAll() {
        var policy=active;if(policy==null)return CompletableFuture.failedFuture(new IllegalStateException("Housing world policy is not registered"));
        var futures=new ArrayList<CompletableFuture<Void>>();
        for(var world:Universe.get().getWorlds().values()) {
            var done=new CompletableFuture<Void>();futures.add(done);
            world.execute(()->{try{policy.apply(world);done.complete(null);}catch(Throwable failure){done.completeExceptionally(failure);}});
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }
    private synchronized void apply(World world) {
        String id=world.getWorldConfig().getUuid().toString();var config=world.getWorldConfig();var saved=previous.get(id);
        if(plugin.getInfrastructure().isHousing(world.getName())) {
            var gameplay=GameplayConfig.getAssetMap().getAsset(GAMEPLAY);
            if(gameplay==null||gameplay.getWorldConfig().isBlockPlacementAllowed())throw new IllegalStateException("Eternia_Housing must disable native block placement");
            // Every bundled fluid, including Fire and child variants, has this inherited tag.
            // Reject a newly introduced ticking fluid without it instead of silently permitting spread.
            for(var fluid:Fluid.getAssetMap().getAssetMap().values()) {
                if(fluid.isUnknown()||"Empty".equals(fluid.getId())||fluid.getTicker()==null)continue;
                if(fluid.getData()==null||!fluid.getData().getRawTags().containsKey(FLUID_TAG))throw new IllegalStateException("Housing fluid policy requires Fluid tag on "+fluid.getId());
            }
            if(saved==null) {
                // A pre-existing policy ID cannot reveal the prior gameplay config: never invent one.
                if(GAMEPLAY.equals(config.getGameplayConfig()))saved=new Previous(world.getName(),GAMEPLAY,config.getDisabledFluidTickers());
                else saved=new Previous(world.getName(),config.getGameplayConfig(),config.getDisabledFluidTickers());
                previous.put(id,saved);persist();
            }
            var disabled=new HashSet<>(config.getDisabledFluidTickers());disabled.add(FLUID_TAG);
            config.setGameplayConfig(GAMEPLAY);config.setDisabledFluidTickers(Set.copyOf(disabled));config.markChanged();
        }else if(saved!=null) {
            var restored=restore(saved,config.getGameplayConfig(),config.getDisabledFluidTickers());
            config.setGameplayConfig(restored.gameplay);config.setDisabledFluidTickers(restored.disabled);config.markChanged();
            previous.remove(id);persist();
        }
    }
    /** Restore each independent field only while it still equals the value this policy installed. */
    static Restore restore(Previous before,String currentGameplay,Set<String> currentDisabled) {
        String gameplay=GAMEPLAY.equals(currentGameplay)?before.gameplay:currentGameplay;
        var expected=new HashSet<>(before.disabled);expected.add(FLUID_TAG);
        return new Restore(gameplay,currentDisabled.equals(expected)?before.disabled:Set.copyOf(currentDisabled));
    }
    private void persist() {
        try {
            Files.createDirectories(file.getParent());Path pending=file.resolveSibling(file.getFileName()+".pending");
            Files.writeString(pending,JSON.toJson(new FileData(1,Map.copyOf(previous))),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
            try{Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(pending,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException failure){throw new UncheckedIOException("Could not save housing world policy",failure);}
    }
    static void stop(){active=null;}
}
