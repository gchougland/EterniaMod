package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.GuildService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.domain.WildLandService;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who owns which hexagon in the adventure worlds, kept in memory so block guards answer without touching storage.
 * Rebuilt whole after every change (claims are few) and swapped in at once, so readers on any thread see either the
 * old land or the new, never half of it.
 */
public final class WildLandIndex {
    /** One claim as the guards see it. {@code trust} maps a player id, or {@code *}, to what they may do. */
    public record Land(UUID id, String world, Owner owner, String ownerName, Set<HexCoord> cells, Map<String, Set<WildPerm>> trust, Map<HexCoord, Long> paid) {
        public Land { cells = Set.copyOf(cells); trust = Map.copyOf(trust); paid = Map.copyOf(paid); }
    }

    private record Snapshot(HexLattice lattice, Map<String, Map<HexCoord, Land>> byWorld, Map<UUID, Land> byId) {}

    private static volatile Snapshot snapshot = new Snapshot(WildLandSettings.DEFAULTS.lattice(), Map.of(), Map.of());
    private static final Map<UUID, Cached> GUILDS = new ConcurrentHashMap<>();
    private static final long GUILD_TTL_MILLIS = 10_000;

    private record Cached(Optional<GuildService.Standing> standing, long at) {}

    private WildLandIndex() {}

    /** Reads every claim again. Any thread but the world's; storage work only. */
    public static synchronized void reload(EterniaServices services) {
        HexLattice lattice = WildLandSettings.current().lattice();
        var trusts = services.wildLand().allTrusts();
        Map<UUID, String> playerNames = new HashMap<>();
        Map<String, Map<HexCoord, Land>> byWorld = new HashMap<>();
        Map<UUID, Land> byId = new HashMap<>();
        List<WildLandService.Claim> claims = services.wildLand().all();
        var accounts = services.accounts().findAll(claims.stream().filter(c -> c.owner().kind() == Owner.Kind.PLAYER).map(c -> c.owner().id()).toList());
        accounts.forEach((id, account) -> playerNames.put(id, account.displayName()));
        for (var claim : claims) {
            // Claims drawn on another hexagon size stay saved but are not shown or protected until the size matches again.
            if (!claim.cells().latticeId().equals(lattice.id())) continue;
            String name = claim.owner().kind() == Owner.Kind.GUILD
                ? services.guilds().find(claim.owner().id()).map(GuildService.Guild::name).orElse("A guild")
                : playerNames.getOrDefault(claim.owner().id(), "Someone");
            Land land = new Land(claim.id(), claim.world(), claim.owner(), name, claim.cells().asSet(), trusts.getOrDefault(claim.id(), Map.of()), claim.paid());
            byId.put(claim.id(), land);
            var cells = byWorld.computeIfAbsent(claim.world(), w -> new HashMap<>());
            for (HexCoord cell : land.cells()) cells.put(cell, land);
        }
        Map<String, Map<HexCoord, Land>> frozen = new HashMap<>();
        byWorld.forEach((w, m) -> frozen.put(w, Map.copyOf(m)));
        snapshot = new Snapshot(lattice, Map.copyOf(frozen), Map.copyOf(byId));
        GUILDS.clear();
    }

    public static HexLattice lattice() { return snapshot.lattice(); }

    /** The claim covering block column {@code x, z}, or null. */
    public static Land at(String world, int x, int z) {
        Snapshot s = snapshot;
        var cells = s.byWorld().get(world);
        return cells == null ? null : cells.get(s.lattice().cellAt(x, z));
    }

    public static Land at(String world, HexCoord cell) {
        var cells = snapshot.byWorld().get(world);
        return cells == null ? null : cells.get(cell);
    }

    public static Land byId(UUID id) { return snapshot.byId().get(id); }

    /** Every claimed hexagon in a world. */
    public static Map<HexCoord, Land> world(String world) { return snapshot.byWorld().getOrDefault(world, Map.of()); }

    /** A player's guild and role permissions, read at most every few seconds. */
    public static Optional<GuildService.Standing> standing(EterniaServices services, UUID player) {
        long now = System.currentTimeMillis();
        Cached c = GUILDS.get(player);
        if (c != null && now - c.at() < GUILD_TTL_MILLIS) return c.standing();
        Optional<GuildService.Standing> loaded;
        try { loaded = services.guilds().standing(player); } catch (RuntimeException unavailable) { loaded = c == null ? Optional.empty() : c.standing(); }
        GUILDS.put(player, new Cached(loaded, now));
        return loaded;
    }

    /** Forget a player's cached guild standing (they joined, left or changed role). */
    public static void forget(UUID player) { GUILDS.remove(player); }
}
