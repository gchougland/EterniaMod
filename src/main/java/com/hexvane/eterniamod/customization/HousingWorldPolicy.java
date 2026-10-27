package com.hexvane.eterniamod.customization;

import com.google.gson.*;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.protocol.packets.world.WorldGameplayConfig;
import com.hypixel.hytale.server.core.event.events.permissions.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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

/** Native build rules per player, with server-side plot checks and frozen housing fluids. */
public final class HousingWorldPolicy {
    public static final String GAMEPLAY="Eternia_Housing", BUILD_GAMEPLAY="Eternia_Housing_Build",FLUID_TAG="Fluid";
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
        // Native joining sends world defaults before adding the player entity. Apply the player's
        // own rules afterward, including when another administrator already has building enabled.
        plugin.getEntityStoreRegistry().registerSystem(new RefSystem<EntityStore>() {
            @Override public Query<EntityStore> getQuery(){return PlayerRef.getComponentType();}
            @Override public void onEntityAdded(Ref<EntityStore> ref,AddReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
                var policy=active;var world=store.getExternalData().getWorld();
                if(policy!=null&&plugin.getInfrastructure().isHousing(world.getName()))policy.sendRules(world,store.getComponent(ref,PlayerRef.getComponentType()));
            }
            @Override public void onEntityRemove(Ref<EntityStore> ref,RemoveReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands){}
        });
        var events=plugin.getEventRegistry();
        events.register(PlayerPermissionChangeEvent.PermissionsRemoved.class,event->refreshAll());
        events.register(PlayerPermissionChangeEvent.PermissionsAdded.class,event->refreshAll());
        events.register(PlayerGroupEvent.Removed.class,event->refreshAll());
        events.register(PlayerGroupEvent.Added.class,event->refreshAll());
        events.register(GroupPermissionChangeEvent.Removed.class,event->refreshAll());
        events.register(GroupPermissionChangeEvent.Added.class,event->refreshAll());
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
    public static void refresh(World world) {
        var policy=active;if(policy==null)throw new IllegalStateException("Housing policy is unavailable");
        policy.apply(world);
    }
    private synchronized void apply(World world) {
        String id=world.getWorldConfig().getUuid().toString();var config=world.getWorldConfig();var saved=previous.get(id);
        if(plugin.getInfrastructure().isHousing(world.getName())) {
            var gameplay=GameplayConfig.getAssetMap().getAsset(GAMEPLAY);
            if(gameplay==null||gameplay.getWorldConfig().isBlockPlacementAllowed()||gameplay.getWorldConfig().isBlockBreakingAllowed(null))throw new IllegalStateException("Eternia_Housing must disable native block placement and breaking");
            // Every bundled fluid, including Fire and child variants, has this inherited tag.
            // Reject a newly introduced ticking fluid without it instead of silently permitting spread.
            for(var fluid:Fluid.getAssetMap().getAssetMap().values()) {
                if(fluid.isUnknown()||"Empty".equals(fluid.getId())||fluid.getTicker()==null)continue;
                if(fluid.getData()==null||!fluid.getData().getRawTags().containsKey(FLUID_TAG))throw new IllegalStateException("Housing fluid policy requires Fluid tag on "+fluid.getId());
            }
            if(saved==null) {
                // A pre-existing policy ID cannot reveal the prior gameplay config: never invent one.
                if(GAMEPLAY.equals(config.getGameplayConfig())||BUILD_GAMEPLAY.equals(config.getGameplayConfig()))saved=new Previous(world.getName(),GAMEPLAY,config.getDisabledFluidTickers());
                else saved=new Previous(world.getName(),config.getGameplayConfig(),config.getDisabledFluidTickers());
                previous.put(id,saved);persist();
            }
            var disabled=new HashSet<>(config.getDisabledFluidTickers());disabled.add(FLUID_TAG);
            String selected=HubBuildMode.any(plugin,world)?BUILD_GAMEPLAY:GAMEPLAY;
            if(GameplayConfig.getAssetMap().getAsset(selected)==null)throw new IllegalStateException("Missing housing gameplay asset: "+selected);
            config.setDisabledFluidTickers(Set.copyOf(disabled));config.markChanged();
            changeGameplayConfig(config,selected,()->{for(var player:world.getPlayerRefs())sendRules(world,player);});
        }else if(saved!=null) {
            var restored=restore(saved,config.getGameplayConfig(),config.getDisabledFluidTickers());
            config.setDisabledFluidTickers(restored.disabled);config.markChanged();
            changeGameplayConfig(config,restored.gameplay,world::resendGameplayConfig);
            previous.remove(id);persist();
        }
    }
    private void sendRules(World world,PlayerRef player) {
        player.getPacketHandler().write(clientRules(world.getGameplayConfig().toPacket(),HubBuildMode.enabled(plugin,player.getUuid(),world)));
    }
    /** The asset creates a fresh packet per player; never mutate or share its cached configuration. */
    static WorldGameplayConfig clientRules(WorldGameplayConfig packet,boolean building) {
        packet.worldConfig.allowBlockBreaking=building;
        packet.worldConfig.allowBlockPlacement=building;
        packet.worldConfig.blockBreakingBypassTagIndex=com.hypixel.hytale.assetstore.AssetRegistry.TAG_NOT_FOUND;
        return packet;
    }
    /** Changing WorldConfig alone does not update connected clients' placement rules. */
    static void changeGameplayConfig(com.hypixel.hytale.server.core.universe.world.WorldConfig config,
                                     String gameplay,Runnable resend) {
        if(!gameplay.equals(config.getGameplayConfig())) {
            config.setGameplayConfig(gameplay);
            config.markChanged();
        }
        // A second builder can toggle without changing the shared server config.
        resend.run();
    }
    /** Restore each independent field only while it still equals the value this policy installed. */
    static Restore restore(Previous before,String currentGameplay,Set<String> currentDisabled) {
        String gameplay=(GAMEPLAY.equals(currentGameplay)||BUILD_GAMEPLAY.equals(currentGameplay))?before.gameplay:currentGameplay;
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
    static void stop(){HubBuildMode.SESSIONS.clear();active=null;}
}
