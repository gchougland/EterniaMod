package com.hexvane.eterniamod.domain;

import static com.hexvane.eterniamod.domain.DomainException.Code.*;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/**
 * A guild's own Crowns. Members give Crowns they own, or buy Crowns straight for the guild; they never come back out.
 * Members whose role has {@link #SPEND} spend them on guild things (more guild plots and hexagons). One
 * {@code guild_crowns} row per guild holds the balance; every change has a one time receipt, so a retry never pays twice.
 *
 * <p>Housing pays through {@link #spendIn}/{@link #refundIn} for any owner: a player's own Crowns or a guild's.
 */
public final class GuildCrownService extends DomainSupport {
    /** The guild role capability to spend the guild's Crowns. Leader and officer have it by default. */
    public static final String SPEND = "treasury.crowns.spend";
    static final String WALLET = "guild_crowns", LEDGER = "guild_crowns_ledger";
    /**
     * The store benefit that buys Crowns for a guild: a store product with this content id (kind QUANTITY) credits the
     * buyer's current guild, or the guild itself when it is the recipient. A buyer in no guild gets the Crowns themself.
     */
    public static final String CONTENT = "eternia:currency/guild_crowns";

    /** One entry in the guild's Crown history. {@code delta} is positive for gifts and purchases, negative for spending. */
    public record Entry(String receipt, UUID guild, UUID member, long delta, String reason, java.time.Instant at) {}

    GuildCrownService(TransactionalStore store, Clock clock, Supplier<UUID> ids) { super(store, clock, ids); }

    public long balance(UUID guild) { return store.transaction(tx -> balanceIn(tx, guild)); }

    static long balanceIn(TransactionalStore.Transaction tx, UUID guild) {
        return tx.find(WALLET, guild.toString()).map(r -> r.number("balance")).orElse(0L);
    }

    /**
     * A member gives {@code amount} of their own Crowns to their guild. {@code requestId} makes a retry safe. Any member
     * may give; the Crowns cannot be taken back.
     */
    public long give(UUID member, long amount, String requestId) {
        positive(amount);
        text(requestId, "request", 160);
        return store.transaction(tx -> {
            var membership = GuildService.currentMember(tx, member);
            UUID guild = UUID.fromString(membership.value("guild"));
            String receipt = "give:" + member + ":" + requestId;
            if (tx.find(LEDGER, receipt).isPresent()) return balanceIn(tx, guild);
            require(PremiumService.balanceIn(tx, member) >= amount, INSUFFICIENT_BALANCE, "You need more Crowns.");
            PremiumService.housingIn(tx, member, -amount, "guild-gift:" + receipt);
            return adjustIn(tx, guild, member, amount, receipt, "gift");
        });
    }

    /** Crowns bought for the guild in the store, credited once per verified purchase. */
    static void purchasedIn(TransactionalStore.Transaction tx, UUID guild, UUID buyer, long amount, String source) {
        positive(amount);
        String receipt = "buy:" + source;
        if (tx.find(LEDGER, receipt).isPresent()) return;
        adjustIn(tx, guild, buyer, amount, receipt, "purchase");
    }

    /**
     * A refunded or charged back purchase of guild Crowns: takes back what the guild still has of them (never below
     * zero; Crowns it already spent stay spent). Returns true when the receipt was a guild Crown purchase.
     */
    static boolean reversePurchaseIn(TransactionalStore.Transaction tx, String source) {
        var bought = tx.find(LEDGER, "buy:" + source);
        if (bought.isEmpty()) return false;
        if (tx.find(LEDGER, "reverse:" + source).isPresent()) return true;
        UUID guild = UUID.fromString(bought.get().value("guild"));
        long back = Math.min(balanceIn(tx, guild), bought.get().number("delta"));
        if (back > 0) adjustIn(tx, guild, null, -back, "reverse:" + source, "reversed");
        else tx.save(LEDGER, "reverse:" + source, 0, fields("guild", guild, "member", null, "delta", 0, "reason", "reversed", "at", java.time.Instant.now()));
        return true;
    }

    /** The guild a store purchase for {@code recipient} credits: the guild itself, or a player's current guild. */
    static Optional<UUID> guildFor(TransactionalStore.Transaction tx, Owner recipient) {
        if (recipient.kind() == Owner.Kind.GUILD) return Optional.of(recipient.id());
        if (recipient.kind() != Owner.Kind.PLAYER) return Optional.empty();
        return tx.find("guild_member", recipient.id().toString()).filter(r -> r.value("active").equals("true")).map(r -> UUID.fromString(r.value("guild")));
    }

    /** The guild's Crown history, newest first. */
    public List<Entry> history(UUID guild) {
        return store.transaction(tx -> tx.scan(LEDGER).stream().filter(r -> r.value("guild").equals(guild.toString()))
            .map(r -> new Entry(r.key(), guild, r.value("member").isEmpty() ? null : UUID.fromString(r.value("member")), r.number("delta"), r.value("reason"), java.time.Instant.parse(r.value("at"))))
            .sorted(Comparator.comparing(Entry::at).reversed()).toList());
    }

    // ---- Paying for housing: a player's own Crowns, or a guild's (with the spend permission) ---------------------------

    /** Crowns {@code payer} can spend now. */
    static long availableIn(TransactionalStore.Transaction tx, Owner payer) {
        return payer.kind() == Owner.Kind.GUILD ? balanceIn(tx, payer.id()) : PremiumService.balanceIn(tx, payer.id());
    }

    /** A guild payer needs {@code actor} to hold {@link #SPEND}; checked before any balance is looked at. */
    static void requireSpenderIn(TransactionalStore.Transaction tx, Owner payer, UUID actor) {
        if (payer.kind() != Owner.Kind.GUILD) return;
        require(actor != null, FORBIDDEN, "Only a guild member can spend guild Crowns");
        GuildService.requireCapability(tx, actor, payer.id(), SPEND);
    }

    /**
     * Spends {@code price} from {@code payer} once per {@code receipt}. A guild payer needs {@code actor} to hold
     * {@link #SPEND} in that guild. Returns false when the receipt was already used.
     */
    static boolean spendIn(TransactionalStore.Transaction tx, Owner payer, UUID actor, long price, String receipt) {
        if (price <= 0) return false;
        if (payer.kind() == Owner.Kind.GUILD) {
            require(actor != null, FORBIDDEN, "Only a guild member can spend guild Crowns");
            GuildService.requireCapability(tx, actor, payer.id(), SPEND);
            if (tx.find(LEDGER, "spend:" + receipt).isPresent()) return false;
            require(balanceIn(tx, payer.id()) >= price, INSUFFICIENT_BALANCE, "Your guild needs more Crowns.");
            adjustIn(tx, payer.id(), actor, -price, "spend:" + receipt, "spend");
            return true;
        }
        return PremiumService.housingIn(tx, payer.id(), -price, receipt);
    }

    /** Gives back {@code amount} to {@code payee} once per {@code receipt} (a sold hexagon). */
    static void refundIn(TransactionalStore.Transaction tx, Owner payee, long amount, String receipt) {
        if (amount <= 0) return;
        if (payee.kind() == Owner.Kind.GUILD) {
            if (tx.find(LEDGER, "refund:" + receipt).isPresent()) return;
            adjustIn(tx, payee.id(), null, amount, "refund:" + receipt, "refund");
        } else PremiumService.housingIn(tx, payee.id(), amount, receipt);
    }

    private static long adjustIn(TransactionalStore.Transaction tx, UUID guild, UUID member, long delta, String receipt, String reason) {
        var r = tx.find(WALLET, guild.toString());
        long next = Math.addExact(r.map(row -> row.number("balance")).orElse(0L), delta);
        require(next >= 0, INSUFFICIENT_BALANCE, "Your guild needs more Crowns.");
        tx.save(WALLET, guild.toString(), r.map(TransactionalStore.Row::revision).orElse(0L), fields("balance", next));
        tx.save(LEDGER, receipt, 0, fields("guild", guild, "member", member, "delta", delta, "reason", reason, "at", java.time.Instant.now()));
        return next;
    }
}
