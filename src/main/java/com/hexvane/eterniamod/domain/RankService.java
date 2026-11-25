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
    /** Receipt prefix of ranks given by a purchase. Ranks given by hand use {@code rank:} and are never taken by the sync. */
    static final String PAID = "paid-rank:";
    /** player:rank rows counting how many times a refund took that rank's rewards back. */
    static final String ROUNDS = "rank_reward_round";
    private final OwnershipService ownership;
    private final RankRewards rewards;

    RankService(TransactionalStore store, Clock clock, Supplier<UUID> ids, OwnershipService ownership) {
        this(store, clock, ids, ownership, RankRewards.NONE);
    }

    RankService(TransactionalStore store, Clock clock, Supplier<UUID> ids, OwnershipService ownership, RankRewards rewards) {
        super(store, clock, ids);
        this.ownership = ownership;
        this.rewards = Objects.requireNonNull(rewards);
    }

    /** The ids of the ranks a player holds. */
    public Set<String> ranks(UUID player) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> tx.find(NAMESPACE, player.toString()).map(row -> Set.copyOf(row.fields().keySet())).orElseGet(Set::of));
    }

    /** The supporter ranks a player holds through purchases, lowest first. Hand given ranks are not included. */
    public List<String> paid(UUID player) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> SupporterRanks.held(ownership, tx, player));
    }

    /** Gives a rank and its title. Returns false when the player already had it. */
    public boolean give(UUID player, String rankId, String titleId) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> { boolean given = giveIn(tx, player, rankId, titleId); rewardsIn(tx, player); return given; });
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

    /** Makes a player's purchased supporter ranks match what they own now ({@link SupporterRanks}). Returns true when anything changed. */
    public boolean syncPaid(UUID player) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> syncPaidIn(tx, player));
    }

    boolean syncPaidIn(TransactionalStore.Transaction tx, UUID player) {
        if (tx.find("account", player.toString()).isEmpty()) return false;
        var held = SupporterRanks.held(ownership, tx, player);
        var current = tx.find(NAMESPACE, player.toString()).map(TransactionalStore.Row::fields).orElseGet(Map::of);
        boolean changed = false;
        for (String rank : SupporterRanks.ALL) {
            String receipt = current.get(rank);
            if (held.contains(rank) && receipt == null) changed |= giveIn(tx, player, rank, SupporterRanks.titleId(rank), PAID);
            else if (!held.contains(rank) && receipt != null && receipt.startsWith(PAID)) changed |= takeIn(tx, player, rank);
        }
        return rewardsIn(tx, player) | changed;
    }

    /**
     * A rank purchase was refunded or charged back ({@code content}: what it had granted, already revoked). Every rank
     * that purchase was holding up and the player no longer holds loses its reward items and unused vouchers, and a
     * later purchase gives them again. Eternal simply ending is not a refund: its items stay.
     */
    void refundedIn(TransactionalStore.Transaction tx, UUID player, List<String> content) {
        var affected = new HashSet<String>();
        for (String c : content) {
            if (c.startsWith(SupporterRanks.STEP_PREFIX)) affected.addAll(SupporterRanks.LADDER);
            else if (c.startsWith(SupporterRanks.RANK_PREFIX)) affected.add(c.substring(SupporterRanks.RANK_PREFIX.length()));
        }
        if (affected.isEmpty()) return;
        // Held now that the refunded grant is revoked. A rank still held (another payment, or given by hand) keeps its set;
        // one no longer held loses the set it gave this round, even if it had already lapsed, as when a chargeback
        // arrives after an Eternal subscription ended.
        var after = SupporterRanks.withHandGiven(ownership, tx, player);
        for (String rank : SupporterRanks.ALL) {
            if (!affected.contains(rank) || after.contains(rank)) continue;
            int round = round(tx, player, rank);
            boolean taken = false;
            for (var reward : rewards.of(rank)) {
                if (reward.whileActive()) continue;
                String receipt = rewardReceipt(rank, reward.slot(), player, round);
                if (tx.find("grant", key(receipt)).isPresent()) { ownership.revokeIn(tx, receipt); taken = true; }
            }
            lapsedIn(tx, player, rank);
            if (!taken) continue;
            var row = tx.find(ROUNDS, player + ":" + rank);
            tx.save(ROUNDS, player + ":" + rank, row.map(TransactionalStore.Row::revision).orElse(0L), Map.of("round", Integer.toString(round + 1)));
        }
    }

    /** Takes back a rank's while active rewards (Eternal's pet and titles) once it is no longer held; they come back with it. */
    private void lapsedIn(TransactionalStore.Transaction tx, UUID player, String rank) {
        String id = player + ":" + rank + ":active";
        var row = tx.find(ROUNDS, id);
        int active = row.map(r -> Integer.parseInt(r.value("round"))).orElse(0);
        boolean taken = false;
        for (var reward : rewards.of(rank)) {
            if (!reward.whileActive()) continue;
            String receipt = activeReceipt(rank, reward.slot(), player, active);
            var grant = tx.find("grant", key(receipt));
            if (grant.isPresent()) { if (!grant.get().value("revoked").equals("true")) ownership.revokeIn(tx, receipt); taken = true; }
        }
        if (taken) tx.save(ROUNDS, id, row.map(TransactionalStore.Row::revision).orElse(0L), Map.of("round", Integer.toString(active + 1)));
        Integer months = null;
        for (var reward : rewards.of(rank)) {
            if (reward.keepAfter() == 0) continue;
            int trial = trialRound(tx, player, rank, reward.slot());
            String receipt = trialReceipt(rank, reward.slot(), player, trial);
            var grant = tx.find("grant", key(receipt));
            if (grant.isEmpty() || grant.get().value("revoked").equals("true")) continue;
            if (months == null) months = paidMonths(tx, player, rank);
            if (months >= reward.keepAfter()) continue; // kept for good
            oweIn(tx, player, reward, grant.get(), receipt);
            ownership.revokeIn(tx, receipt);
            String trialId = player + ":" + rank + ":" + reward.slot() + ":trial";
            tx.save(ROUNDS, trialId, tx.find(ROUNDS, trialId).map(TransactionalStore.Row::revision).orElse(0L), Map.of("round", Integer.toString(trial + 1)));
        }
    }

    /**
     * A trial voucher is being taken back. Unspent, it simply goes. Spent, a spare voucher of the same kind the player
     * holds pays for it instead; with none, the guild plot it paid for is marked owed, and the pack worker puts it away.
     */
    private void oweIn(TransactionalStore.Transaction tx, UUID player, RankRewards.Reward reward, TransactionalStore.Row grant, String receipt) {
        if (grant.number("available") >= 1) return;
        Owner me = Owner.player(player);
        String paidBy = null;
        if (ownership.availableIn(tx, me, reward.content()) >= 1) {
            var cover = ownership.reserveIn(tx, me, reward.content(), 1, "rank-trial-cover:" + receipt);
            ownership.finishReservationIn(tx, cover.id(), true);
            paidBy = cover.id();
        }
        for (var slot : tx.scan("housing_slot")) {
            String reservation = slot.value(HousingService.VOUCHER_RESERVATION);
            if (reservation.isEmpty()) continue;
            var spent = tx.find("quantity_reservation", reservation);
            if (spent.isEmpty() || !spent.get().fields().containsKey("grant." + grant.key())) continue;
            var fields = new TreeMap<>(slot.fields());
            if (paidBy != null) fields.put(HousingService.VOUCHER_RESERVATION, paidBy);
            else fields.put(HousingService.OWED, player.toString());
            tx.save(slot.namespace(), slot.key(), slot.revision(), fields);
        }
    }

    /** A guild plot owed by this player and not yet packed is paid for at once by a guild plot voucher they hold again. */
    private boolean settleOwedIn(TransactionalStore.Transaction tx, UUID player) {
        boolean settled = false;
        Owner me = Owner.player(player);
        for (var slot : tx.scan("housing_slot")) {
            if (!slot.value(HousingService.OWED).equals(player.toString()) || !slot.value("state").equals("ACTIVE")) continue;
            if (ownership.availableIn(tx, me, RankRewards.GUILD_PLOT) < 1) break;
            var paid = ownership.reserveIn(tx, me, RankRewards.GUILD_PLOT, 1, "owed-settle:" + slot.key() + ":" + slot.revision());
            ownership.finishReservationIn(tx, paid.id(), true);
            var fields = new TreeMap<>(slot.fields());
            fields.put(HousingService.OWED, "");
            fields.put(HousingService.VOUCHER_RESERVATION, paid.id());
            tx.save(slot.namespace(), slot.key(), slot.revision(), fields);
            settled = true;
        }
        return settled;
    }

    private static int trialRound(TransactionalStore.Transaction tx, UUID player, String rank, String slot) {
        return tx.find(ROUNDS, player + ":" + rank + ":" + slot + ":trial").map(r -> Integer.parseInt(r.value("round"))).orElse(0);
    }

    private static String trialReceipt(String rank, String slot, UUID player, int round) {
        return OwnershipService.TRIAL_PREFIX + rank + ":" + slot + ":" + player + ":" + round;
    }

    private static int activeRound(TransactionalStore.Transaction tx, UUID player, String rank) {
        return tx.find(ROUNDS, player + ":" + rank + ":active").map(r -> Integer.parseInt(r.value("round"))).orElse(0);
    }

    private static String activeReceipt(String rank, String slot, UUID player, int round) {
        return "rank-reward-active:" + rank + ":" + slot + ":" + player + ":" + round;
    }

    /** Paid, unrefunded months of a subscription rank: one delivered purchase per billing period. */
    private int paidMonths(TransactionalStore.Transaction tx, UUID player, String rank) {
        String owner = Owner.player(player).key(), content = SupporterRanks.content(rank);
        var isRank = new HashMap<String, Boolean>();
        int months = 0;
        for (var purchase : tx.scan("commerce_purchase")) {
            if (!purchase.value("recipient").equals(owner) || !purchase.value("state").equals("DELIVERED")) continue;
            String product = purchase.value("package") + ":" + purchase.value("productRevision");
            boolean match = isRank.computeIfAbsent(product, k -> tx.find("commerce_product", k)
                .map(r -> { for (int i = 0; i < r.number("count"); i++) if (r.value("b" + i + ".content").equals(content)) return true; return false; }).orElse(false));
            if (match) months++;
        }
        return months;
    }

    /** Which time a player's reward set for a rank is on: 0 at first, one more after each refund took it back. */
    private static int round(TransactionalStore.Transaction tx, UUID player, String rank) {
        return tx.find(ROUNDS, player + ":" + rank).map(r -> Integer.parseInt(r.value("round"))).orElse(0);
    }

    private static String rewardReceipt(String rank, String slot, UUID player, int round) {
        return "rank-reward:" + rank + ":" + slot + ":" + player + (round == 0 ? "" : ":" + round);
    }

    /**
     * Gives the reward set of every supporter rank the player holds (paid or by hand), each slot once per player ever
     * ({@link RankRewards}). Rewards stay when the rank goes: they are items. Returns true when anything was given.
     */
    boolean rewardsIn(TransactionalStore.Transaction tx, UUID player) {
        boolean given = false;
        var held = SupporterRanks.withHandGiven(ownership, tx, player);
        for (String rank : SupporterRanks.ALL) if (!held.contains(rank)) lapsedIn(tx, player, rank);
        // A rank given by hand has no paid months, so its months rewards come at once.
        var handGiven = tx.find(NAMESPACE, player.toString()).map(r -> r.fields()).orElseGet(Map::of);
        for (String rank : held) {
            int round = round(tx, player, rank), active = activeRound(tx, player, rank);
            Integer months = null;
            for (var reward : rewards.of(rank)) {
                if (!reward.decided()) continue;
                if (reward.months() > 1) {
                    boolean byHand = handGiven.containsKey(rank) && !handGiven.get(rank).startsWith(PAID);
                    if (!byHand) { if (months == null) months = paidMonths(tx, player, rank); if (months < reward.months()) continue; }
                }
                String receipt = reward.whileActive() ? activeReceipt(rank, reward.slot(), player, active) : rewardReceipt(rank, reward.slot(), player, round);
                if (reward.keepAfter() > 0 && !(handGiven.containsKey(rank) && !handGiven.get(rank).startsWith(PAID))) {
                    // On trial until kept for good; one already given for good (before trials existed) stands.
                    if (tx.find("grant", key(receipt)).isPresent()) continue;
                    receipt = trialReceipt(rank, reward.slot(), player, trialRound(tx, player, rank, reward.slot()));
                }
                if (tx.find("grant", key(receipt)).isPresent()) continue;
                ownership.grantIn(tx, new OwnershipService.GrantRequest(receipt, Owner.player(player), reward.content(), reward.kind(), reward.quantity(), null));
                given = true;
            }
        }
        return settleOwedIn(tx, player) | given;
    }

    private boolean giveIn(TransactionalStore.Transaction tx, UUID player, String rankId, String titleId) {
        return giveIn(tx, player, rankId, titleId, "rank:");
    }

    private boolean giveIn(TransactionalStore.Transaction tx, UUID player, String rankId, String titleId, String prefix) {
        text(rankId, "rank", 40);
        row(tx, "account", player.toString());
        var existing = tx.find(NAMESPACE, player.toString());
        if (existing.map(row -> row.fields().containsKey(rankId)).orElse(false)) return false;
        // A fresh receipt each time, so a rank taken and given again grants its title again.
        String receipt = prefix + rankId + ":" + player + ":" + ids.get();
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
