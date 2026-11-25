package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.util.*;

/**
 * Supporter ranks bought on Tebex. Nothing is stored here: a player's paid ranks are worked out from what they own.
 *
 * <ul>
 *   <li>The ladder (Wanderer, Settler, Steward, Elder; ids wanderer, starborn, celestial, cosmic) is bought in steps. Every Tebex package grants one step,
 *       {@code eternia:rank_step/<from>-<to>}, where {@code from} is {@code none} for a full price package or the rank
 *       below for a pay the difference upgrade. The player's ladder rank is the highest one reachable from
 *       {@code none} through steps they still own, so an upgrade bought without its base waits for the base, and a
 *       refunded base takes the upgrade down with it. Holding a ladder rank also holds every rank below it.</li>
 *   <li>Founder ({@code eternia:rank/founder}) is a one time rank beside the ladder.</li>
 *   <li>Eternal ({@code eternia:rank/eternal}) is the subscription: a leased grant, held only while paid.</li>
 * </ul>
 */
public final class SupporterRanks {
    public static final List<String> LADDER = List.of("wanderer", "starborn", "celestial", "cosmic");
    public static final String FOUNDER = "founder", ETERNAL = "eternal", NONE = "none";
    public static final String STEP_PREFIX = "eternia:rank_step/", RANK_PREFIX = "eternia:rank/";
    /** Every supporter rank a purchase can give, lowest first. */
    public static final List<String> ALL = List.of("wanderer", "starborn", "celestial", "cosmic", FOUNDER, ETERNAL);
    /** Season pass XP bonus in percent. The best ladder rank counts, and Founder's and Eternal's add on top. */
    private static final Map<String, Integer> XP_BONUS = Map.of("wanderer", 5, "starborn", 10, "celestial", 20, "cosmic", 30, ETERNAL, 50, FOUNDER, 20);

    private SupporterRanks() {}

    public static String step(String from, String to) {
        if (!(from.equals(NONE) || LADDER.contains(from)) || !LADDER.contains(to) || order(from) >= order(to))
            throw new IllegalArgumentException("Not a rank step: " + from + " to " + to);
        return STEP_PREFIX + from + "-" + to;
    }
    public static String content(String rank) { return RANK_PREFIX + rank; }
    /** Matches {@code ranks.Rank.titleId()}. */
    public static String titleId(String rank) { return "eternia:title/rank_" + rank; }
    public static boolean isLadder(String rank) { return LADDER.contains(rank); }

    /** The supporter ranks a player holds through purchases, lowest first. */
    public static List<String> held(OwnershipService ownership, TransactionalStore.Transaction tx, UUID player) {
        return held(ownership.activeContentIn(tx, Owner.player(player), "eternia:rank"));
    }

    /** Paid ranks plus supporter ranks given by hand with {@code /e rank give}. Used for perks such as the XP bonus. */
    public static Set<String> withHandGiven(OwnershipService ownership, TransactionalStore.Transaction tx, UUID player) {
        var out = new TreeSet<>(held(ownership, tx, player));
        tx.find(RankService.NAMESPACE, player.toString()).ifPresent(row -> row.fields().forEach((rank, receipt) -> {
            if (ALL.contains(rank) && !receipt.startsWith(RankService.PAID)) out.add(rank);
        }));
        return out;
    }

    /** The ranks given by a set of owned content ids. */
    static List<String> held(Set<String> owned) {
        int best = -1;
        // Walk up the ladder: a step counts only once its starting rank is reached.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int to = best + 1; to < LADDER.size(); to++) {
                for (int from = -1; from <= best; from++) {
                    String start = from < 0 ? NONE : LADDER.get(from);
                    if (owned.contains(STEP_PREFIX + start + "-" + LADDER.get(to))) { best = to; grew = true; }
                }
            }
        }
        var out = new ArrayList<String>(LADDER.subList(0, best + 1));
        if (owned.contains(content(FOUNDER))) out.add(FOUNDER);
        if (owned.contains(content(ETERNAL))) out.add(ETERNAL);
        return List.copyOf(out);
    }

    /** The highest ladder rank held, or {@code none}. */
    public static String ladderRank(Collection<String> held) {
        String best = NONE;
        for (String rank : LADDER) if (held.contains(rank)) best = rank;
        return best;
    }

    /** Season pass XP bonus in percent for a set of held ranks (paid or hand given). */
    public static int xpBonusPercent(Collection<String> held) {
        int best = 0;
        for (String rank : held) if (LADDER.contains(rank)) best = Math.max(best, XP_BONUS.get(rank));
        return best + (held.contains(FOUNDER) ? XP_BONUS.get(FOUNDER) : 0) + (held.contains(ETERNAL) ? XP_BONUS.get(ETERNAL) : 0);
    }

    private static int order(String rank) { return rank.equals(NONE) ? -1 : LADDER.indexOf(rank); }
}
