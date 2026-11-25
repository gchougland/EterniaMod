package com.hexvane.eterniamod.portals;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.PortalService;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Each online player's usable portals, kept in memory for the world map, which asks for markers per player many
 * times a second on the world thread. Reads never touch the store: a missing or out of date list is loaded on the
 * menu executor and the map shows the previous list meanwhile. Any portal change bumps one version so every list
 * reloads on its next read (portal changes are rare; a whole-list reload is one row and a batched lookup).
 */
public final class PortalIndex {
    private static final long MAX_AGE_MILLIS = 60_000;
    private record Entry(List<PortalService.Portal> portals, long version, long loadedAt) {}

    private final EterniaServices services;
    private final Map<UUID, Entry> lists = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final AtomicLong version = new AtomicLong();
    /** Who owns each pad, for the break guard. Filled at start and kept as pads come and go. */
    private final Map<String, Optional<UUID>> owners = new ConcurrentHashMap<>();

    PortalIndex(EterniaServices services) { this.services = services; }

    /** The player's usable portals in {@code world}, as last loaded. Starts a reload when out of date. */
    public List<PortalService.Portal> inWorld(UUID player, String world) {
        var entry = lists.get(player);
        long now = System.currentTimeMillis();
        if (entry == null || entry.version < version.get() || now - entry.loadedAt > MAX_AGE_MILLIS) reload(player);
        return entry == null ? List.of() : visible(entry.portals, world);
    }

    /** Which of a player's portals belong on the map of {@code world}. */
    static List<PortalService.Portal> visible(List<PortalService.Portal> known, String world) {
        return known.stream().filter(p -> p.world().equals(world)).toList();
    }

    /** Something about portals changed: every list reloads when next read. */
    public void changed() { version.incrementAndGet(); }

    /** A pad was placed, adopted or changed hands. */
    public void put(PortalService.Portal portal) { owners.put(portal.id(), Optional.ofNullable(portal.owner())); changed(); }
    public void removed(String id) { owners.remove(id); changed(); }

    /** The pad's owner when it has one and it is known here; empty for ownerless and not yet known pads. */
    public Optional<UUID> owner(String id) { return owners.getOrDefault(id, Optional.empty()); }

    /** Loads every pad's owner, once at start. */
    void loadOwners() {
        SocialUiBootstrap.supply(services, () -> services.portals().all()).whenComplete((all, error) -> {
            // Until this loads, the break guard knows no owners and lets anyone break a pad.
            if (error != null) { com.hexvane.eterniamod.EterniaModPlugin.get().getLogger().atWarning().withCause(error).log("Could not load portal owners; portals are unprotected until they are next used"); return; }
            all.forEach(p -> owners.putIfAbsent(p.id(), Optional.ofNullable(p.owner())));
        });
    }

    public void forget(UUID player) { lists.remove(player); loading.remove(player); }

    private void reload(UUID player) {
        if (!loading.add(player)) return;
        long asked = version.get();
        SocialUiBootstrap.supply(services, () -> services.portals().known(player)).whenComplete((portals, error) -> {
            loading.remove(player);
            if (error == null) lists.put(player, new Entry(portals, asked, System.currentTimeMillis()));
        });
    }
}
