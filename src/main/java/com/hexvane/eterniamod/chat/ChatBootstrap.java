package com.hexvane.eterniamod.chat;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Wires chat channels into the server. One call in plugin setup, one in shutdown. */
public final class ChatBootstrap {
    private static volatile ChatService service;
    private static volatile ChatDock dock;

    private ChatBootstrap() {}

    public static void register(EterniaModPlugin plugin) {
        var settings = ChatSettings.load(ChatBootstrap.class.getClassLoader());
        var chat = new ChatService(plugin.getServices(), settings, plugin.getLogger());
        service = chat;
        dock = new ChatDock(plugin.getLogger());
        // LATE: moderation (or anything else) at NORMAL or earlier can cancel first; collections' default formatter
        // at LAST sees ours and leaves it alone.
        plugin.getEventRegistry().registerAsyncGlobal(EventPriority.LATE, PlayerChatEvent.class,
            future -> future.thenCompose(chat::onChat));
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
            var ref = event.getPlayerRef();
            if (ref == null || !ref.isValid()) return;
            var player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            if (player == null) return;
            chat.onReady(player);
            var open = dock;
            if (open != null) open.onReady(player);
        });
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            chat.onDisconnect(event.getPlayerRef().getUuid());
            var open = dock;
            if (open != null) open.onDisconnect(event.getPlayerRef().getUuid());
        });
        plugin.getLogger().atInfo().log("Eternia chat channels ready (default %s, nearby %d blocks)",
            settings.defaultChannel().id(), settings.nearbyBlocks());
    }

    public static void close() {
        var chat = service;
        service = null;
        if (chat != null) chat.close();
        var open = dock;
        dock = null;
        if (open != null) open.close();
    }
}
