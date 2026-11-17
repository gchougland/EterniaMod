package com.hexvane.eterniamod.production;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** The rules of production as plain functions over {@link StationProgress}. No server types, so it is unit tested. */
public final class ProductionMath {
    private ProductionMath() {}

    /**
     * Everything outside the level that bends a station: tools, talents, album, Renown, the Estate tree and boosts,
     * folded into a few multipliers. Built by {@link Professions#mods}.
     */
    public static final class Mods {
        public double output = 1, cap = 1, pace = 1, luck = 1, veinChance = 1, rareValue = 1, hand = 1, firstCollect = 1;
        public int veinCycles;
        public double rushSeconds;
    }

    /** What happened while time passed, for the effects layer. */
    public record Tick(int cycles, double made, boolean veinStruck, List<String> finds, boolean becameFull) {}

    /** One collect: the total, how it was earned and the pieces that fly out, smallest first. */
    public record Haul(double amount, double base, double fill, double fullCart, double streak, boolean doubled,
                       List<String> finds, List<Piece> pieces, boolean record) {}

    /** One flying piece. {@code size} 0 is a single good, 1 a sack, 2 a crate, 3 a chest. */
    public record Piece(double value, int size, int tier) {}

    // ---- levels and tiers ----

    public static int milestonesPassed(ProductionRules r, int level) {
        int n = 0;
        for (int m : r.milestones()) if (level >= m) n++;
        return n;
    }

    public static int tier(ProductionRules r, int level) { return 1 + milestonesPassed(r, level); }

    /** The next milestone level above {@code level}, or 0 past the last one. */
    public static int nextMilestone(ProductionRules r, int level) {
        for (int m : r.milestones()) if (level < m) return m;
        return 0;
    }

    public static int previousMilestone(ProductionRules r, int level) {
        int at = 1;
        for (int m : r.milestones()) if (level >= m) at = m;
        return at;
    }

    /** Goods to go from {@code level} to the next. */
    public static double levelCost(ProductionRules r, int level) {
        double cost = Math.ceil(levelMinutes(r, level) * plainPerMinute(r, level));
        // A new tier starts with shorter waits, but its first levels never cost less than the level before them.
        int from = previousMilestone(r, level);
        return from > 1 && level >= from ? Math.max(cost, levelCost(r, from - 1)) : cost;
    }

    /** What a station with no boosts makes each minute at {@code level}: six loads of the level, doubled per milestone. */
    public static double plainPerMinute(ProductionRules r, int level) {
        return 6.0 * level * Math.pow(2, milestonesPassed(r, level));
    }

    /**
     * Minutes of plain work the step from {@code level} to the next should take. Each tier's levels share that tier's
     * hours ({@link ProductionRules#tierHours}), the wait growing evenly so the tier's last level takes
     * {@code tierSpread} times as long as its first. Past the last milestone the wait keeps growing at the last
     * tier's pace.
     */
    public static double levelMinutes(ProductionRules r, int level) {
        int[] milestones = r.milestones();
        double[] hours = r.tierHours();
        int tier = Math.min(milestonesPassed(r, level), Math.min(milestones.length, hours.length) - 1);
        int from = tier == 0 ? 1 : milestones[tier - 1], to = milestones[tier];
        int steps = Math.max(1, to - from);
        double grow = steps <= 1 ? 1 : Math.pow(Math.max(1, r.tierSpread()), 1.0 / (steps - 1));
        double sum = 0;
        for (int i = 0; i < steps; i++) sum += Math.pow(grow, i);
        return hours[tier] * 60 / sum * Math.pow(grow, Math.max(1, level) - from);
    }

    public static double levelsCost(ProductionRules r, int level, int count) {
        double total = 0;
        for (int i = 0; i < count; i++) total += levelCost(r, level + i);
        return total;
    }

    public static int affordableLevels(ProductionRules r, int level, double purse) {
        int n = 0;
        double left = purse;
        while (n < 10_000) {
            double cost = levelCost(r, level + n);
            if (cost > left) break;
            left -= cost;
            n++;
        }
        return n;
    }

    /** Talent choices owed: one per milestone ever reached, less the cards already held. */
    public static int talentPicksOwed(ProductionRules r, StationProgress s) {
        return Math.max(0, milestonesPassed(r, s.peakLevel) - s.talents.size());
    }

    public static double toolCost(ProductionRules r, int rank) { return r.toolCostBase() * Math.pow(r.toolCostGrowth(), rank); }

    // ---- output ----

    public static double cycleSeconds(Profession p, Mods m) { return Math.max(p.cycleSeconds() * 0.25, p.cycleSeconds() * m.pace); }

    /** Goods from one ordinary cycle: the level, doubled per milestone, times every multiplier. */
    public static double perCycle(ProductionRules r, StationProgress s, Mods m) {
        return s.level * Math.pow(2, milestonesPassed(r, s.level)) * m.output;
    }

    public static double perMinute(ProductionRules r, Profession p, StationProgress s, Mods m) {
        return perCycle(r, s, m) * 60 / cycleSeconds(p, m);
    }

    public static double cap(ProductionRules r, Profession p, StationProgress s, Mods m) {
        return Math.ceil(perMinute(r, p, s, m) * r.stockpileMinutes() * m.cap);
    }

    /** Seconds until the stockpile is full at the ordinary rate, or 0 when it already is. */
    public static double secondsToFull(ProductionRules r, Profession p, StationProgress s, Mods m) {
        double left = cap(r, p, s, m) - s.stockpile;
        return left <= 0 ? 0 : left / perMinute(r, p, s, m) * 60 / speed(r, s);
    }

    /** How much faster than normal the station runs right now (Rush from hand work). */
    public static double speed(ProductionRules r, StationProgress s) { return 1 + (r.rushBoost() - 1) * Math.clamp(s.rush, 0, 1); }

    /** Lets {@code seconds} pass. A full stockpile makes nothing; Rush and Frenzy still run down. */
    public static Tick advance(ProductionRules r, Profession p, StationProgress s, Mods m, double seconds, RandomGenerator rng) {
        double cap = cap(r, p, s, m), cycle = cycleSeconds(p, m), made = 0;
        boolean wasFull = s.stockpile >= cap, vein = false;
        var finds = new ArrayList<String>();
        int done = 0;
        if (!wasFull) {
            s.cycleSeconds += seconds * speed(r, s);
            while (s.cycleSeconds >= cycle && s.stockpile < cap && done < 2_000) {
                s.cycleSeconds -= cycle;
                s.cycles++;
                done++;
                double goods = perCycle(r, s, m);
                if (s.veinLeft > 0) { goods *= r.veinBoost(); s.veinLeft--; }
                else if (rng.nextDouble() < r.veinChance() * m.veinChance) { s.veinLeft = r.veinCycles() + m.veinCycles; vein = true; }
                if (s.frenzySeconds > 0) goods *= r.frenzyBoost();
                double room = cap - s.stockpile;
                goods = Math.min(goods, room);
                s.stockpile += goods;
                made += goods;
                if (!p.finds().isEmpty() && rng.nextDouble() < r.findsPerHour() * m.luck * cycle / 3600) {
                    finds.add(p.finds().get(rng.nextInt(p.finds().size())).id());
                }
            }
            if (s.stockpile >= cap) s.cycleSeconds = 0;
        }
        s.waitingFinds.addAll(finds);
        s.rush = Math.max(0, s.rush - seconds / Math.max(1, r.rushSeconds() + m.rushSeconds));
        s.frenzySeconds = Math.max(0, s.frenzySeconds - seconds);
        s.full = s.stockpile >= cap;
        return new Tick(done, made, vein, finds, s.full && !wasFull);
    }

    /** Working the station by hand: a share of a cycle straight to the purse, and Rush. Returns the goods popped. */
    public static double handWork(ProductionRules r, StationProgress s, Mods m) {
        double goods = Math.max(1, Math.round(perCycle(r, s, m) * r.handShare() * m.hand));
        s.purse += goods;
        s.lifetime += goods;
        s.rush = Math.min(1, s.rush + r.rushStep());
        return goods;
    }

    // ---- collecting ----

    /**
     * Empties the stockpile into the purse. {@code streakDays} is the plot's streak after today's collect counted;
     * {@code firstToday} is true for the plot's first collect of the day.
     */
    public static Haul collect(ProductionRules r, Profession p, StationProgress s, Mods m, int streakDays, boolean firstToday) {
        double cap = cap(r, p, s, m), base = Math.floor(s.stockpile);
        double fill = cap <= 0 ? 0 : Math.clamp(s.stockpile / cap, 0, 1);
        double fullCart = r.fullCartBonus() * fill;
        double streak = r.streakStep() * Math.clamp(streakDays, 0, r.streakDays());
        boolean doubled = firstToday && m.firstCollect > 1;
        double amount = Math.floor(base * (1 + fullCart) * (1 + streak) * (doubled ? m.firstCollect : 1));
        var finds = new ArrayList<>(s.waitingFinds);
        for (String find : finds) amount += Math.floor(perCycle(r, s, m) * 30 * m.rareValue);
        boolean record = amount > s.bestHaul && s.bestHaul > 0;
        s.stockpile = 0;
        s.full = false;
        s.waitingFinds.clear();
        s.finds.addAll(finds);
        s.purse += amount;
        s.lifetime += amount;
        s.bestHaul = Math.max(s.bestHaul, amount);
        return new Haul(amount, base, fill, fullCart, streak, doubled, finds, pieces(amount, tier(r, s.level)), record);
    }

    /**
     * Splits a haul into the pieces that fly out: few for a small haul, never more than 36, each a little bigger than
     * the one before so the shower builds. Values add up to {@code amount} exactly.
     */
    public static List<Piece> pieces(double amount, int tier) {
        var out = new ArrayList<Piece>();
        if (amount < 1) return out;
        int count = (int) Math.clamp(Math.round(4 + 6 * Math.log10(amount + 1)), 1, 36);
        count = (int) Math.min(count, amount);
        double[] weights = new double[count];
        double sum = 0;
        for (int i = 0; i < count; i++) sum += weights[i] = Math.pow(1.13, i);
        double given = 0;
        for (int i = 0; i < count; i++) {
            double value = i == count - 1 ? amount - given : Math.max(1, Math.floor(amount * weights[i] / sum));
            if (value <= 0) continue;
            given += value;
            int size = value >= 1000 ? 3 : value >= 100 ? 2 : value >= 10 ? 1 : 0;
            int goodTier = 1 + (int) Math.min(tier - 1, (long) i * tier / count);
            out.add(new Piece(value, size, goodTier));
        }
        return out;
    }

    public static String haulWord(Haul haul) {
        return haul.record() ? "Record haul" : haul.fill() >= 0.9 ? "Bountiful haul" : haul.fill() >= 0.5 ? "Great haul" : "Nice haul";
    }

    // ---- upgrades ----

    /** Buys up to {@code count} levels. Returns how many were bought. */
    public static int buyLevels(ProductionRules r, StationProgress s, int count) {
        int n = Math.min(count, affordableLevels(r, s.level, s.purse));
        if (n <= 0) return 0;
        s.purse -= levelsCost(r, s.level, n);
        s.level += n;
        s.peakLevel = Math.max(s.peakLevel, s.level);
        return n;
    }

    public static boolean buyTool(ProductionRules r, StationProgress s, int tool) {
        if (tool < 0 || tool > 3 || s.tools[tool] >= r.toolRanks()) return false;
        double cost = toolCost(r, s.tools[tool]);
        if (cost > s.purse) return false;
        s.purse -= cost;
        s.tools[tool]++;
        return true;
    }

    public static boolean canRebuild(ProductionRules r, StationProgress s) { return s.level >= r.renownLevel(); }

    /** Renown: back to level 1 for a star. Tools, talents and the album stay; the stockpile is kept too. */
    public static boolean rebuild(ProductionRules r, StationProgress s) {
        if (!canRebuild(r, s)) return false;
        s.level = 1;
        s.renown++;
        s.veinLeft = 0;
        return true;
    }

    // ---- words ----

    /** 1,284 then 12.4K, 3.21M and so on: short enough for a HUD, exact while it still matters. */
    public static String format(double value) {
        double v = Math.floor(value);
        if (v < 10_000) return String.format("%,d", (long) v);
        String[] units = {"K", "M", "B", "T", "Qa", "Qi"};
        int unit = -1;
        while (v >= 1000 && unit < units.length - 1) { v /= 1000; unit++; }
        return (v >= 100 ? String.format("%.0f", Math.floor(v)) : v >= 10 ? String.format("%.1f", Math.floor(v * 10) / 10)
            : String.format("%.2f", Math.floor(v * 100) / 100)) + units[unit];
    }

    public static String duration(double seconds) {
        long s = (long) Math.ceil(seconds);
        if (s < 60) return s + " seconds";
        if (s < 3600) { long m = Math.round(s / 60.0); return m + (m == 1 ? " minute" : " minutes"); }
        long h = s / 3600, m = (s % 3600) / 60;
        return h + (h == 1 ? " hour" : " hours") + (m >= 5 ? " " + m + " minutes" : "");
    }
}
