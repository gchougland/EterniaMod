package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.hexplots.HexLandView;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Who is looking, for border colours: the player and their guild. The guild is a domain read, so it is loaded off
 * the world thread and cached; until it arrives the viewer is treated as guildless (their own plot is still teal).
 * Entries refresh every {@link #TTL_MILLIS} so joining or leaving a guild recolours borders within a minute.
 * Any thread.
 */
public final class HexViewers {
    private static final Logger LOG = Logger.getLogger(HexViewers.class.getName());
    static final long TTL_MILLIS = 45_000;
    private static final int MAX_ENTRIES = 10_000;
    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();
    private static volatile Function<UUID, UUID> guildLookup;

    private record Entry(UUID guild, long loadedAt) {}

    private HexViewers() {}

    /** Installs the guild lookup (plugin start). The lookup runs off the world thread and may do domain I/O. */
    public static void install(Function<UUID, UUID> lookup) { guildLookup = lookup; }

    /** The viewer as best known now (never blocks); schedules a refresh when the cache is missing or stale. */
    public static HexLandView.Viewer viewer(UUID player) {
        Objects.requireNonNull(player);
        Entry entry = ENTRIES.get(player);
        long now = System.currentTimeMillis();
        if (entry == null || now - entry.loadedAt > TTL_MILLIS) refresh(player);
        return HexLandView.Viewer.browsing(player, entry == null ? null : entry.guild);
    }

    /** True once the guild lookup for this player has answered at least once. */
    public static boolean known(UUID player) { return ENTRIES.containsKey(player); }

    /** Sets a viewer's guild directly (tests, or a flow that just changed membership). */
    public static void put(UUID player, UUID guild) {
        if (ENTRIES.size() >= MAX_ENTRIES) ENTRIES.clear();
        ENTRIES.put(player, new Entry(guild, System.currentTimeMillis()));
    }

    public static void forget(UUID player) { ENTRIES.remove(player); }

    public static void clear() { ENTRIES.clear(); LOADING.clear(); }

    private static void refresh(UUID player) {
        Function<UUID, UUID> lookup = guildLookup;
        if (lookup == null || !LOADING.add(player)) return;
        CompletableFuture.runAsync(() -> {
            try {
                put(player, lookup.apply(player));
            } catch (RuntimeException failure) {
                LOG.log(Level.FINE, "Guild lookup for border colours failed", failure);
                // Back off for one TTL instead of retrying every scan; keep a guild we already knew.
                Entry old = ENTRIES.get(player);
                put(player, old == null ? null : old.guild);
            } finally {
                LOADING.remove(player);
            }
        });
    }
}
