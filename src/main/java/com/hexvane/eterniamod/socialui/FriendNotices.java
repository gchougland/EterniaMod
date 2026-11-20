package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.runtime.WorldDisplayNames;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.UUID;
import java.util.concurrent.*;

/** Short chat lines about friends: requests, new friends, and friends coming online. */
public final class FriendNotices {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Eternia-friend-notices"); t.setDaemon(true); return t; });
    /** Players already announced this session; ready fires again on every world change. */
    private static final java.util.Set<UUID> ANNOUNCED = ConcurrentHashMap.newKeySet();
    private FriendNotices() {}

    public static void register(com.hexvane.eterniamod.EterniaModPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class, event -> {
            var ref = event.getPlayerRef();
            if (ref == null || !ref.isValid()) return;
            var player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            var services = plugin.getServices();
            if (player != null && services != null && ANNOUNCED.add(player.getUuid())) joined(services, player);
        });
        plugin.getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent.class,
            event -> { ANNOUNCED.remove(event.getPlayerRef().getUuid()); com.hexvane.eterniamod.runtime.TeleportRequests.forget(event.getPlayerRef().getUuid()); });
    }

    /** Sends a line to a player if they are online. */
    public static void tell(UUID player, String text) {
        var universe = Universe.get(); if (universe == null) return;
        PlayerRef ref = universe.getPlayer(player);
        if (ref != null) ref.sendMessage(Message.raw(text));
    }

    /** Every online player and the world they are in, read from the live server. */
    public static java.util.Map<UUID, String> onlineWorlds() {
        var out = new java.util.HashMap<UUID, String>();
        var universe = Universe.get(); if (universe == null) return out;
        for (PlayerRef p : java.util.List.copyOf(universe.getPlayers())) out.put(p.getUuid(), where(p.getUuid()));
        return out;
    }

    /** Where a player is, in words; null when offline. */
    public static String where(UUID player) {
        var universe = Universe.get(); if (universe == null) return null;
        PlayerRef ref = universe.getPlayer(player); if (ref == null) return null;
        UUID id = ref.getWorldUuid(); if (id == null) return "Travelling";
        var world = universe.getWorld(id); if (world == null) return "Travelling";
        return WorldDisplayNames.label(world.getName(), world.getWorldConfig().getDisplayName());
    }

    /** When a player joins: tell their friends who want alerts, and tell them about waiting requests. */
    public static void joined(EterniaServices services, PlayerRef player) {
        UUID id = player.getUuid(); String name = player.getUsername();
        WORK.execute(() -> {
            try {
                String world = where(id);
                for (var friend : services.friends().friends(id)) {
                    if (where(friend.id()) == null || !services.friends().alerts(friend.id())) continue;
                    tell(friend.id(), name + " is online" + (world == null ? "." : " in " + world + "."));
                }
                int waiting = services.friends().incoming(id).size();
                if (waiting > 0) tell(id, waiting == 1 ? "1 player wants to be your friend. Open Friends in your Journal." : waiting + " players want to be your friend. Open Friends in your Journal.");
            } catch (RuntimeException ignored) { /* notices are a courtesy; never break joining */ }
        });
    }
}
