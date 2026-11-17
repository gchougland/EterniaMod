package com.hexvane.eterniamod.production;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything one station remembers. Plain mutable data: {@link ProductionMath} holds the rules that change it, so the
 * rules can be tested without a server. Goods are doubles because late stations count in the billions.
 */
public final class StationProgress {
    public String profession = "miner";
    public int level = 1;
    /** Highest level ever reached. Talents outlive a Renown rebuild, so picks are counted against this. */
    public int peakLevel = 1;
    public int renown;
    public double purse;
    public double stockpile;
    /** Total value ever collected this season: the leaderboard score. Spending never lowers it. */
    public double lifetime;
    public double bestHaul;
    public final int[] tools = new int[4];
    public final List<String> talents = new ArrayList<>();
    public int reshuffles;
    /** Album: every rare find ever discovered here. Kept across seasons. */
    public final Set<String> finds = new LinkedHashSet<>();
    /** Rare finds waiting in the stockpile, shown off by the next collect. */
    public final List<String> waitingFinds = new ArrayList<>();
    public long cycles;
    public double cycleSeconds;
    public int veinLeft;
    /** 0 to 1. Filled by hand work, drains on its own. */
    public double rush;
    public double frenzySeconds;
    public boolean full;

    public StationProgress copy() {
        var c = new StationProgress();
        c.profession = profession; c.level = level; c.peakLevel = peakLevel; c.renown = renown; c.purse = purse;
        c.stockpile = stockpile; c.lifetime = lifetime; c.bestHaul = bestHaul;
        System.arraycopy(tools, 0, c.tools, 0, 4);
        c.talents.addAll(talents); c.reshuffles = reshuffles; c.finds.addAll(finds); c.waitingFinds.addAll(waitingFinds);
        c.cycles = cycles; c.cycleSeconds = cycleSeconds; c.veinLeft = veinLeft; c.rush = rush;
        c.frenzySeconds = frenzySeconds; c.full = full;
        return c;
    }
}
