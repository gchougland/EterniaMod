package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/**
 * The ranks a player holds and the title each one gave them. One {@code player_rank} row per player: each held rank's
 * id maps to the receipt of the title grant it made, so taking a rank revokes exactly that title. Rank ids and title ids
 * are passed in, keeping this free of the rank list itself ({@code ranks.Rank}).
 */
public final class RankService extends DomainSupport {
    static final String NAMESPACE = "player_rank";
    private final OwnershipService ownership;

    RankService(TransactionalStore store, Clock clock, Supplier<UUID> ids, OwnershipService ownership) {
        super(store, clock, ids);
        this.ownership = ownership;
    }

    /** The ids of the ranks a player holds. */
    public Set<String> ranks(UUID player) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> tx.find(NAMESPACE, player.toString()).map(row -> Set.copyOf(row.fields().keySet())).orElseGet(Set::of));
    }

    /** Gives a rank and its title. Returns false when the player already had it. */
    public boolean give(UUID player, String rankId, String titleId) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> giveIn(tx, player, rankId, titleId));
    }

    /** Takes a rank and revokes the title it gave. Returns false when the player did not have it. */
    public boolean take(UUID player, String rankId) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> takeIn(tx, player, rankId));
    }

    /**
     * Makes a player's staff ranks match their permission groups: {@code staff} maps every staff rank id to its title,
     * {@code held} is the staff rank ids they hold now. Titles are given for new ones and revoked for ones they lost.
     * Returns true when anything changed.
     */
    public boolean syncStaff(UUID player, Map<String, String> staff, Set<String> held) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> {
            var current = tx.find(NAMESPACE, player.toString()).map(row -> row.fields().keySet()).orElseGet(Set::of);
            boolean changed = false;
            for (var entry : staff.entrySet()) {
                boolean has = current.contains(entry.getKey());
                if (held.contains(entry.getKey()) && !has) changed |= giveIn(tx, player, entry.getKey(), entry.getValue());
                else if (!held.contains(entry.getKey()) && has) changed |= takeIn(tx, player, entry.getKey());
            }
            return changed;
        });
    }

    private boolean giveIn(TransactionalStore.Transaction tx, UUID player, String rankId, String titleId) {
        text(rankId, "rank", 40);
        row(tx, "account", player.toString());
        var existing = tx.find(NAMESPACE, player.toString());
        if (existing.map(row -> row.fields().containsKey(rankId)).orElse(false)) return false;
        // A fresh receipt each time, so a rank taken and given again grants its title again.
        String receipt = "rank:" + rankId + ":" + player + ":" + ids.get();
        ownership.grantIn(tx, new OwnershipService.GrantRequest(receipt, Owner.player(player), titleId, OwnershipService.Kind.UNLOCK, 1, null));
        var fields = new TreeMap<>(existing.map(TransactionalStore.Row::fields).orElseGet(Map::of));
        fields.put(rankId, receipt);
        tx.save(NAMESPACE, player.toString(), existing.map(TransactionalStore.Row::revision).orElse(0L), fields);
        return true;
    }

    private boolean takeIn(TransactionalStore.Transaction tx, UUID player, String rankId) {
        var existing = tx.find(NAMESPACE, player.toString());
        if (existing.isEmpty() || !existing.get().fields().containsKey(rankId)) return false;
        ownership.revokeIn(tx, existing.get().value(rankId));
        var fields = new TreeMap<>(existing.get().fields());
        fields.remove(rankId);
        tx.save(NAMESPACE, player.toString(), existing.get().revision(), fields);
        return true;
    }
}
