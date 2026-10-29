package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.builtin.triggervolumes.component.IgnoreTriggerVolumes;
import com.hypixel.hytale.server.core.event.events.player.*;
import com.hypixel.hytale.server.core.universe.world.SpawnUtil;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.*;
import java.util.concurrent.*;

public final class PlayerLifecycle implements AutoCloseable {
    private final EterniaModPlugin plugin;private final Set<UUID> online=ConcurrentHashMap.newKeySet();private final Set<UUID> recoverOnReady=ConcurrentHashMap.newKeySet();
    private static final long TRIGGER_SETTLE_SECONDS=3;
    /** Entity range while a player enters a world; the full range returns {@link #TRIGGER_SETTLE_SECONDS} after they are ready. */
    private static final int ENTRY_ENTITY_RANGE_BLOCKS=32;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("eternia-account-heartbeat").factory());
    public PlayerLifecycle(EterniaModPlugin plugin) {
        this.plugin=plugin;
        plugin.getEventRegistry().registerGlobal(PlayerConnectEvent.class,event->{
            var player=event.getPlayerRef();
            // Reject stale backups before the loaded player can enter a world and move native items.
            try{plugin.getItemEscrow().recoverLoadedInventory(event.getHolder(),player);}
            catch(RuntimeException failure){plugin.getLogger().atWarning().withCause(failure).log("Player inventory recovery failed");return;}
            plugin.getRuntime().welcome(player.getUuid(),player.getUsername());online.add(player.getUuid());recoverOnReady.add(player.getUuid());
        });
        // A trigger zone effect (the town square's welcome title) that fires while the 0.7.4 client is still loading in
        // crashes it. On every world entry (login and travel between worlds) zones skip the player until a moment after
        // they are ready, then fire as if they had walked in.
        plugin.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class,event->{
            try{event.getHolder().putComponent(IgnoreTriggerVolumes.getComponentType(),IgnoreTriggerVolumes.INSTANCE);}
            catch(RuntimeException unavailable){plugin.getLogger().atWarning().withCause(unavailable).log("Could not hold trigger zones off during join");}
            // The 0.7.5 client skips resizing its occlusion results for about two seconds after a spawn, and its
            // transparent entity pass then reads past their end once enough entities arrive at once (the town square's
            // three thousand decorations). Entities stay close until the client has settled; chunks load as normal.
            var viewer=event.getHolder().getComponent(EntityTrackerSystems.EntityViewer.getComponentType());
            if(viewer!=null)viewer.viewRadiusBlocks=Math.min(viewer.viewRadiusBlocks,ENTRY_ENTITY_RANGE_BLOCKS);
        });
        // The join spot is placed on the loaded player before any world adds them: the engine spawns them there,
        // because a teleport sent while the client finishes joining crashes the 0.7.4 client.
        plugin.getEventRegistry().registerAsync(PlayerConnectEvent.class,pending->pending.thenCompose(event->
            plugin.getTravel().joinLanding(event.getPlayerRef().getUuid()).orTimeout(15,TimeUnit.SECONDS).handle((landing,failure)->{
                if(failure!=null)plugin.getLogger().atWarning().withCause(failure).log("Could not choose a join spot; the player keeps their saved position");
                else if(landing!=null){
                    var holder=event.getHolder();holder.ensureAndGetComponent(TransformComponent.getComponentType());
                    SpawnUtil.applyTransform(holder,landing.transform());event.setWorld(landing.world());
                }
                return event;
            })));
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class,event->{
            var ref=event.getPlayerRef();if(ref==null||!ref.isValid())return;var store=ref.getStore();
            var player=store.getComponent(ref,PlayerRef.getComponentType());if(player==null)return;
            var world=store.getExternalData().getWorld();
            timer.schedule(()->world.execute(()->{
                if(!ref.isValid())return;
                store.tryRemoveComponent(ref,IgnoreTriggerVolumes.getComponentType());
                var viewer=store.getComponent(ref,EntityTrackerSystems.EntityViewer.getComponentType());var playerComponent=store.getComponent(ref,Player.getComponentType());
                if(viewer!=null&&playerComponent!=null)viewer.viewRadiusBlocks=playerComponent.getViewRadius()*ChunkUtil.SIZE;
            }),TRIGGER_SETTLE_SECONDS,TimeUnit.SECONDS);
            if(recoverOnReady.contains(player.getUuid()))store.getExternalData().getWorld().execute(()->{
                if(!ref.isValid()||!recoverOnReady.remove(player.getUuid()))return;
                try{plugin.getItemEscrow().recoverLogin(ref,store,player);}
                catch(RuntimeException failure){plugin.getLogger().atWarning().withCause(failure).log("Player ready recovery failed");}
            });
        });
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,event->{var player=event.getPlayerRef();plugin.getItemEscrow().forgetSession(player);com.hexvane.eterniamod.inventory.ui.NativeShopTravel.forget(player.getUuid());UUID id=player.getUuid();recoverOnReady.remove(id);if(online.remove(id))plugin.getServices().accounts().recordDisconnect(id);});
        plugin.getEventRegistry().registerGlobal(StartWorldEvent.class,event->event.getWorld().execute(()->plugin.getClaims().recoverClaims(event.getWorld())));
        timer.scheduleAtFixedRate(()->{try{var ids=List.copyOf(online);for(int start=0;start<ids.size();start+=64)plugin.getServices().accounts().heartbeats(ids.subList(start,Math.min(start+64,ids.size())));}catch(RuntimeException e){plugin.getLogger().atWarning().withCause(e).log("Account heartbeat failed");}},30,30,TimeUnit.SECONDS);
    }
    @Override public void close(){timer.shutdownNow();for(UUID id:online)plugin.getServices().accounts().recordDisconnect(id);online.clear();recoverOnReady.clear();}
}
