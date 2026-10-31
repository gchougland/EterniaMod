package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.builtin.triggervolumes.component.IgnoreTriggerVolumes;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.event.events.player.*;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.SpawnUtil;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class PlayerLifecycle implements AutoCloseable {
    private final EterniaModPlugin plugin;private final Set<UUID> online=ConcurrentHashMap.newKeySet();private final Set<UUID> recoverOnReady=ConcurrentHashMap.newKeySet();
    private final Set<UUID> resumeSaved=ConcurrentHashMap.newKeySet();
    private static final Duration RESUME_WINDOW=Duration.ofMinutes(10);
    private static final long TRIGGER_SETTLE_SECONDS=3;
    /** Entity range while a player enters a world; the full range returns {@link #TRIGGER_SETTLE_SECONDS} after they are ready. */
    private static final int ENTRY_ENTITY_RANGE_BLOCKS=32;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("eternia-join-settle").factory());
    private final Map<UUID,Long> latestSettle=new ConcurrentHashMap<>();private final AtomicLong settleTurn=new AtomicLong();
    public PlayerLifecycle(EterniaModPlugin plugin) {
        this.plugin=plugin;
        plugin.getEntityStoreRegistry().registerSystem(new TeleportEntityRange());
        plugin.getEventRegistry().registerGlobal(PlayerConnectEvent.class,event->{
            var player=event.getPlayerRef();
            // Reject stale backups before the loaded player can enter a world and move native items.
            try{plugin.getItemEscrow().recoverLoadedInventory(event.getHolder(),player);}
            catch(RuntimeException failure){plugin.getLogger().atWarning().withCause(failure).log("Player inventory recovery failed");return;}
            // The engine has already dropped the position if the saved world is gone.
            var previous=plugin.getServices().accounts().find(player.getUuid()).orElse(null);
            if(previous!=null&&event.getHolder().getComponent(TransformComponent.getComponentType())!=null&&resumesWhereLeft(previous.lastDisconnect(),Instant.now()))resumeSaved.add(player.getUuid());
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
        // The join spot is placed on the loaded player before any world adds them, so the engine spawns them there with no
        // hop. Global, like the handler above, so it runs after that handler has checked for a quick return.
        plugin.getEventRegistry().registerAsyncGlobal(PlayerConnectEvent.class,pending->pending.thenCompose(event->
            resumeSaved.remove(event.getPlayerRef().getUuid())?CompletableFuture.completedFuture(event):
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
            settle(ref,store,player.getUuid());
            if(recoverOnReady.contains(player.getUuid()))store.getExternalData().getWorld().execute(()->{
                if(!ref.isValid()||!recoverOnReady.remove(player.getUuid()))return;
                try{plugin.getItemEscrow().recoverLogin(ref,store,player);}
                catch(RuntimeException failure){plugin.getLogger().atWarning().withCause(failure).log("Player ready recovery failed");}
            });
        });
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,event->{var player=event.getPlayerRef();plugin.getItemEscrow().forgetSession(player);com.hexvane.eterniamod.inventory.ui.NativeShopTravel.forget(player.getUuid());UUID id=player.getUuid();recoverOnReady.remove(id);resumeSaved.remove(id);latestSettle.remove(id);if(online.remove(id))plugin.getServices().accounts().recordDisconnect(id);});
        plugin.getEventRegistry().registerGlobal(StartWorldEvent.class,event->{
            var world=event.getWorld();
            // A new world's time settings stay blank until its first tick. A player who finishes joining before that (a
            // new instance whose world thread is busy) is sent a moon phase count of zero, and the client divides by it.
            // A tick of no time fills them in now, on the world thread, before any join can finish.
            if(world.isInThread())fillTimeSettings(world);else world.execute(()->fillTimeSettings(world));
            world.execute(()->plugin.getClaims().recoverClaims(world));
        });
    }
    private void fillTimeSettings(com.hypixel.hytale.server.core.universe.world.World world) {
        try{var store=world.getEntityStore().getStore();store.getResource(WorldTimeResource.getResourceType()).tick(0f,store);}
        catch(RuntimeException failure){plugin.getLogger().atWarning().withCause(failure).log("Could not prepare time settings for world %s",world.getName());}
    }
    /** Lifts the entry limits after the settle delay. Only the newest call for a player counts, so a second teleport keeps its full delay. */
    private void settle(Ref<EntityStore> ref,Store<EntityStore> store,UUID id) {
        long turn=settleTurn.incrementAndGet();latestSettle.put(id,turn);var world=store.getExternalData().getWorld();
        timer.schedule(()->world.execute(()->{
            if(!latestSettle.remove(id,turn)||!ref.isValid())return;
            store.tryRemoveComponent(ref,IgnoreTriggerVolumes.getComponentType());
            var viewer=store.getComponent(ref,EntityTrackerSystems.EntityViewer.getComponentType());var playerComponent=store.getComponent(ref,Player.getComponentType());
            if(viewer!=null&&playerComponent!=null)viewer.viewRadiusBlocks=playerComponent.getViewRadius()*ChunkUtil.SIZE;
        }),TRIGGER_SETTLE_SECONDS,TimeUnit.SECONDS);
    }
    /** A teleport restarts the client's occlusion warm-up too, even inside one world, where no world entry event fires. */
    private final class TeleportEntityRange extends RefChangeSystem<EntityStore,Teleport> {
        private final Query<EntityStore> query=Query.and(PlayerRef.getComponentType(),EntityTrackerSystems.EntityViewer.getComponentType());
        @Override public Query<EntityStore> getQuery(){return query;}
        @Override public ComponentType<EntityStore,Teleport> componentType(){return Teleport.getComponentType();}
        @Override public void onComponentAdded(Ref<EntityStore> ref,Teleport teleport,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){hold(ref,store,buffer);}
        @Override public void onComponentSet(Ref<EntityStore> ref,Teleport previous,Teleport teleport,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){hold(ref,store,buffer);}
        @Override public void onComponentRemoved(Ref<EntityStore> ref,Teleport teleport,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){}
        private void hold(Ref<EntityStore> ref,Store<EntityStore> store,CommandBuffer<EntityStore> buffer) {
            var viewer=buffer.getComponent(ref,EntityTrackerSystems.EntityViewer.getComponentType());var player=buffer.getComponent(ref,PlayerRef.getComponentType());
            if(viewer==null||player==null)return;
            viewer.viewRadiusBlocks=Math.min(viewer.viewRadiusBlocks,ENTRY_ENTITY_RANGE_BLOCKS);settle(ref,store,player.getUuid());
        }
    }
    public static boolean resumesWhereLeft(Instant disconnect,Instant now) {
        return disconnect!=null&&!disconnect.isBefore(now.minus(RESUME_WINDOW));
    }
    @Override public void close(){timer.shutdownNow();for(UUID id:online)plugin.getServices().accounts().recordDisconnect(id);online.clear();recoverOnReady.clear();}
}
