package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/**
 * Paid travel: random teleports in adventure worlds (the first one free, then coins) and the cooldown on teleport
 * requests between players. Each random trip is charged once per attempt id and refunded if the trip fails.
 */
public final class TravelService extends DomainSupport {
    public static final long RANDOM_PRICE = 100;
    public static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(30);
    static final String RANDOM = "travel_random", COOLDOWN = "travel_tpa";

    private final EconomyService economy;

    TravelService(TransactionalStore s, Clock c, Supplier<UUID> i, EconomyService economy) { super(s, c, i); this.economy = economy; }

    /** What the next random teleport costs this player: nothing while their free one is unused. */
    public long randomPrice(UUID player) {
        return store.transaction(tx -> freeUsed(tx, player) ? RANDOM_PRICE : 0L);
    }

    /** Pays for one random teleport. Returns what was charged (0 for the free one). A retried attempt pays once. */
    public long chargeRandom(UUID player, String attempt) {
        text(attempt, "attempt", 120);
        return store.transaction(tx -> {
            var done = tx.find(RANDOM, "attempt:" + attempt);
            if (done.isPresent()) return Long.parseLong(done.get().value("paid"));
            long paid;
            if (!freeUsed(tx, player)) {
                var old = tx.find(RANDOM, player.toString());
                tx.save(RANDOM, player.toString(), old.map(TransactionalStore.Row::revision).orElse(0L), fields("free", "used"));
                paid = 0;
            } else {
                try { economy.spendIn(tx, Owner.player(player), RANDOM_PRICE, "random-teleport:" + attempt); }
                catch (DomainException e) {
                    if (e.code() == INSUFFICIENT_BALANCE) throw new DomainException(INSUFFICIENT_BALANCE, "A random teleport costs " + RANDOM_PRICE + " Coins.");
                    throw e;
                }
                paid = RANDOM_PRICE;
            }
            tx.save(RANDOM, "attempt:" + attempt, 0, fields("player", player, "paid", paid, "state", "PAID"));
            return paid;
        });
    }

    /** Gives back a random teleport that could not happen: the coins, or the free trip. Once per attempt. */
    public void refundRandom(UUID player, String attempt) {
        store.transaction(tx -> {
            var row = tx.find(RANDOM, "attempt:" + attempt).filter(r -> r.value("state").equals("PAID") && r.value("player").equals(player.toString()));
            if (row.isEmpty()) return null;
            long paid = Long.parseLong(row.get().value("paid"));
            if (paid > 0) economy.creditIn(tx, Owner.player(player), paid, "random-teleport-refund:" + attempt);
            else tx.find(RANDOM, player.toString()).ifPresent(r -> save(tx, r, "free", ""));
            save(tx, row.get(), "state", "REFUNDED");
            return null;
        });
    }

    /** How long until this player may send another teleport request; zero when they may. */
    public Duration requestCooldown(UUID player) {
        Instant now = clock.instant();
        return store.transaction(tx -> tx.find(COOLDOWN, player.toString()).map(r -> {
            Duration left = Duration.between(now, Instant.parse(r.value("last")).plus(REQUEST_COOLDOWN));
            return left.isNegative() ? Duration.ZERO : left;
        }).orElse(Duration.ZERO));
    }

    /** Starts the cooldown after an accepted request; refuses when it is still running. */
    public void useRequest(UUID player) {
        store.transaction(tx -> {
            var old = tx.find(COOLDOWN, player.toString());
            if (old.isPresent()) require(!Instant.parse(old.get().value("last")).plus(REQUEST_COOLDOWN).isAfter(clock.instant()), CONFLICT, "Teleport requests are still cooling down.");
            tx.save(COOLDOWN, player.toString(), old.map(TransactionalStore.Row::revision).orElse(0L), fields("last", clock.instant()));
            return null;
        });
    }

    /** Takes back a cooldown when the teleport it was started for could not happen. */
    public void clearRequest(UUID player) {
        store.transaction(tx -> { tx.find(COOLDOWN, player.toString()).ifPresent(r -> save(tx, r, "last", Instant.EPOCH)); return null; });
    }

    private static boolean freeUsed(TransactionalStore.Transaction tx, UUID player) {
        return tx.find(RANDOM, player.toString()).map(r -> r.value("free").equals("used")).orElse(false);
    }
}
