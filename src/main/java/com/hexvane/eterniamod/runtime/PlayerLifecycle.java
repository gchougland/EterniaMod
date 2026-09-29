package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.event.events.player.*;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class PlayerLifecycle implements AutoCloseable {
    private final EterniaModPlugin plugin;private final Set<UUID> online=ConcurrentHashMap.newKeySet();private final Map<UUID,Boolean> homeOnReady=new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("eternia-account-heartbeat").factory());
    public PlayerLifecycle(EterniaModPlugin plugin) {
        this.plugin=plugin;
        plugin.getEventRegistry().registerGlobal(PlayerConnectEvent.class,event->{
            var player=event.getPlayerRef();
            // Reject stale backups before the loaded player can enter a world and move native items.
            try{plugin.getItemEscrow().recoverLoadedInventory(event.getHolder(),player);}
            catch(RuntimeException failure){plugin.getLogger().atWarning().withCause(failure).log("Player inventory recovery failed");return;}
            var previous=plugin.getServices().accounts().find(player.getUuid()).orElse(null);
            boolean home=previous==null||returnHomeAfter(previous.lastDisconnect(),previous.lastSeen(),Instant.now());
            plugin.getRuntime().welcome(player.getUuid(),player.getUsername());online.add(player.getUuid());homeOnReady.put(player.getUuid(),home);
        });
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class,event->{
            var ref=event.getPlayerRef();if(ref==null||!ref.isValid())return;var store=ref.getStore();
            var player=store.getComponent(ref,PlayerRef.getComponentType());if(player==null)return;
            if(homeOnReady.containsKey(player.getUuid()))store.getExternalData().getWorld().execute(()->{
                if(!ref.isValid())return;Boolean home=homeOnReady.remove(player.getUuid());if(home==null)return;
                try{plugin.getItemEscrow().recoverLogin(ref,store,player);if(home)plugin.getTravel().returnHome(player);}
                catch(RuntimeException failure){plugin.getLogger().atWarning().withCause(failure).log("Player ready recovery failed");}
            });
        });
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,event->{var player=event.getPlayerRef();plugin.getItemEscrow().forgetSession(player);com.hexvane.eterniamod.inventory.ui.NativeShopTravel.forget(player.getUuid());UUID id=player.getUuid();homeOnReady.remove(id);if(online.remove(id))plugin.getServices().accounts().recordDisconnect(id);});
        plugin.getEventRegistry().registerGlobal(StartWorldEvent.class,event->event.getWorld().execute(()->plugin.getClaims().recoverClaims(event.getWorld())));
        timer.scheduleAtFixedRate(()->{for(UUID id:online)try{plugin.getServices().accounts().heartbeat(id);}catch(RuntimeException e){plugin.getLogger().atWarning().withCause(e).log("Account heartbeat failed");}},30,30,TimeUnit.SECONDS);
    }
    public static boolean returnHomeAfter(Instant disconnect,Instant lastSeen,Instant now) {
        Instant last=disconnect==null||lastSeen!=null&&lastSeen.isAfter(disconnect)?lastSeen:disconnect;
        return last==null||last.isBefore(now.minus(Duration.ofMinutes(30)));
    }
    @Override public void close(){timer.shutdownNow();for(UUID id:online)plugin.getServices().accounts().recordDisconnect(id);online.clear();homeOnReady.clear();}
}
