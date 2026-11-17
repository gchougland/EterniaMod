package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/**
 * Alpha and beta playtests: who took part in the alpha, the starting allowance, and the wipe between alpha and beta.
 *
 * <p>Everything in the database is wiped for the beta except {@link #ALPHA_TESTERS}, the list of players who joined
 * during the alpha. That list is how they get the Alpha Tester title back in the beta. Configured content (season
 * passes, Crown Store items, products, collections) is not lost: it is loaded again from the data folder's files every
 * time the server starts.
 */
public final class PlaytestService extends DomainSupport {
    /** One row per player who joined during the alpha. The only thing that survives the beta wipe. */
    public static final String ALPHA_TESTERS = "alpha_tester";
    public static final String ALPHA_TITLE = "eternia:title/alpha_tester";
    static final String ALLOWANCE = "starting_allowance";

    /** What a wipe found or did: records per namespace, and how many alpha testers are kept. */
    public record Report(SortedMap<String, Integer> records, int alphaTesters) {
        public int total() { return records.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    private final PremiumService premium;
    private final EconomyService economy;

    PlaytestService(TransactionalStore store, Clock clock, Supplier<UUID> ids, PremiumService premium, EconomyService economy) {
        super(store, clock, ids);
        this.premium = premium;
        this.economy = economy;
    }

    /** Remembers that a player took part in the alpha. Recorded once; later joins change nothing. */
    public void recordAlphaTester(UUID player, String name) {
        Objects.requireNonNull(player);
        store.transaction(tx -> {
            if (tx.find(ALPHA_TESTERS, player.toString()).isEmpty())
                tx.save(ALPHA_TESTERS, player.toString(), 0, fields("name", name == null ? "" : name, "firstSeen", clock.instant()));
            return null;
        });
    }

    public boolean isAlphaTester(UUID player) {
        return store.transaction(tx -> tx.find(ALPHA_TESTERS, player.toString()).isPresent());
    }

    /**
     * Gives a player the starting allowance the first time they join. Given once per player, whatever the amounts are
     * later changed to; returns true when it was given now.
     */
    public boolean grantStartingAllowance(UUID player, long crowns, long coins) {
        Objects.requireNonNull(player);
        if (crowns <= 0 && coins <= 0) return false;
        return store.transaction(tx -> {
            row(tx, "account", player.toString());
            if (!receipt(tx, ALLOWANCE, player.toString(), fields("player", player))) return false;
            String source = "starting-allowance:" + player;
            if (crowns > 0) premium.creditIn(tx, player, crowns, source);
            if (coins > 0) economy.creditIn(tx, Owner.player(player), coins, source);
            return true;
        });
    }

    /** What a wipe would remove, without changing anything. */
    public static Report preview(TransactionalStore store) {
        return store.transaction(tx -> {
            var counts = new TreeMap<String, Integer>();
            for (String namespace : tx.namespaces()) if (!namespace.equals(ALPHA_TESTERS)) counts.put(namespace, tx.scan(namespace).size());
            var testers = new HashSet<String>();
            tx.scan(ALPHA_TESTERS).forEach(row -> testers.add(row.key()));
            tx.scan("account").forEach(row -> testers.add(row.key()));
            return new Report(counts, testers.size());
        });
    }

    /**
     * Wipes every record for the beta except the alpha tester list, in one transaction. Anyone who has an account but
     * was somehow never recorded as a tester (they joined before testers were recorded) is added to the list first, so
     * nobody who played the alpha loses the title.
     *
     * <p>Pass the store underneath any cache; the server must be restarted straight after.
     */
    public static Report wipeForBeta(TransactionalStore store, java.time.Instant now) {
        return store.transaction(tx -> {
            for (var account : tx.scan("account")) {
                if (tx.find(ALPHA_TESTERS, account.key()).isEmpty())
                    tx.save(ALPHA_TESTERS, account.key(), 0, Map.of("name", account.value("name"), "firstSeen", now.toString()));
            }
            var cleared = new TreeMap<String, Integer>();
            for (String namespace : tx.namespaces()) if (!namespace.equals(ALPHA_TESTERS)) cleared.put(namespace, tx.clear(namespace));
            return new Report(cleared, tx.scan(ALPHA_TESTERS).size());
        });
    }
}
