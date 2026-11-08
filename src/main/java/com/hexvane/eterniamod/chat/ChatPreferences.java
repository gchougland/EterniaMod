package com.hexvane.eterniamod.chat;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's chosen channel, kept across logins in the {@code chat_channel} namespace.
 * Reads are cached while the player is online; a store transaction only happens on first use and on a switch.
 * Never call from the world thread: the first read may reach the database.
 */
public final class ChatPreferences {
    static final String NAMESPACE = "chat_channel";
    private final TransactionalStore store;
    private final Map<UUID, ChatChannel> cache = new ConcurrentHashMap<>();
    private volatile ChatChannel defaultChannel = ChatChannel.SERVER;

    public ChatPreferences(TransactionalStore store) { this.store = store; }

    public ChatChannel defaultChannel() { return defaultChannel; }
    public void setDefaultChannel(ChatChannel channel) { defaultChannel = channel; }

    /** The player's channel, or the default when they never chose one or the saved value is unknown. */
    public ChatChannel current(UUID player) {
        var cached = cache.get(player);
        if (cached != null) return cached;
        ChatChannel loaded = store.transaction(tx -> tx.find(NAMESPACE, player.toString())
            .flatMap(row -> ChatChannel.parse(row.value("channel"))).orElse(null));
        if (loaded == null) return defaultChannel;
        cache.putIfAbsent(player, loaded);
        return cache.get(player);
    }

    /** Remembers the choice. Returns the previous channel. */
    public ChatChannel select(UUID player, ChatChannel channel) {
        ChatChannel previous = current(player);
        store.transaction(tx -> {
            var row = tx.find(NAMESPACE, player.toString());
            tx.save(NAMESPACE, player.toString(), row.map(TransactionalStore.Row::revision).orElse(0L), Map.of("channel", channel.id()));
            return null;
        });
        cache.put(player, channel);
        return previous;
    }

    /** Drops the cached value when the player leaves; the saved choice stays. */
    public void forget(UUID player) { cache.remove(player); }
}
