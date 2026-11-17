package com.hexvane.eterniamod.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hexvane.eterniamod.production.ProductionMath.Mods;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ProductionMathTest {
    private static final ProductionRules R = ProductionRules.DEFAULT;
    private static final Profession MINER = Profession.load(ProductionMathTest.class.getClassLoader(), "Miner");

    @Test
    void theMinerFileIsWhole() {
        assertEquals(8, MINER.goods().size());
        assertEquals(java.util.List.of("Stone", "Copper", "Iron", "Silver", "Thorium", "Cobalt", "Gold", "Adamantite"), MINER.goods().stream().map(Profession.Good::name).toList());
        assertEquals(R.tiers(), MINER.layout().prefabs().size());
        for (int milestone = 1; milestone <= R.milestones().length; milestone++) {
            var dealt = MINER.deal(milestone);
            assertEquals(3, dealt.size(), "milestone " + milestone);
            assertEquals(Profession.PATHS, dealt.stream().map(Profession.Card::path).toList());
        }
        var s = new StationProgress();
        for (var card : MINER.cards()) s.talents.add(card.id());
        assertNotNull(Professions.mods(R, MINER, s, new int[3], 1));
    }

    @Test
    void tiersFollowMilestones() {
        assertEquals(1, ProductionMath.tier(R, 9));
        assertEquals(2, ProductionMath.tier(R, 10));
        assertEquals(8, ProductionMath.tier(R, 200));
        assertEquals(25, ProductionMath.nextMilestone(R, 10));
        assertEquals(0, ProductionMath.nextMilestone(R, 200));
    }

    @Test
    void eachTierTakesItsHoursOfPlainWork() {
        // Buying every level of a tier the moment it can be afforded takes that tier's hours.
        int from = 1;
        for (int tier = 0; tier < R.milestones().length; tier++) {
            double minutes = 0;
            for (int level = from; level < R.milestones()[tier]; level++) minutes += ProductionMath.levelCost(R, level) / ProductionMath.plainPerMinute(R, level);
            assertEquals(R.tierHours()[tier] * 60, minutes, R.tierHours()[tier] * 60 * 0.05 + 2, "tier " + (tier + 1));
            from = R.milestones()[tier];
        }
        // The first upgrade is a short wait, and prices never fall as the level climbs.
        assertTrue(ProductionMath.levelCost(R, 1) <= 12);
        for (int level = 1; level < 260; level++) assertTrue(ProductionMath.levelCost(R, level + 1) >= ProductionMath.levelCost(R, level), "level " + level);
    }

    @Test
    void buyingLevelsSpendsExactlyTheirCost() {
        var s = new StationProgress();
        s.purse = 1000;
        int can = ProductionMath.affordableLevels(R, 1, 1000);
        double cost = ProductionMath.levelsCost(R, 1, can);
        assertEquals(can, ProductionMath.buyLevels(R, s, 9999));
        assertEquals(1000 - cost, s.purse, 1e-6);
        assertEquals(1 + can, s.level);
        assertTrue(ProductionMath.levelCost(R, s.level) > s.purse);
    }

    @Test
    void aStationFillsAndThenStops() {
        var s = new StationProgress();
        var m = new Mods();
        double cap = ProductionMath.cap(R, MINER, s, m);
        var tick = ProductionMath.advance(R, MINER, s, m, 60 * 60 * 5, new Random(1));
        assertTrue(tick.becameFull());
        assertEquals(cap, s.stockpile, 1e-6);
        var later = ProductionMath.advance(R, MINER, s, m, 600, new Random(1));
        assertEquals(0, later.made(), 1e-9);
        assertFalse(later.becameFull());
    }

    @Test
    void aMilestoneDoublesOutput() {
        var s = new StationProgress();
        var m = new Mods();
        s.level = 9;
        double before = ProductionMath.perCycle(R, s, m) / 9;
        s.level = 10;
        assertEquals(before * 2, ProductionMath.perCycle(R, s, m) / 10, 1e-9);
    }

    @Test
    void collectingEmptiesTheStockpileIntoThePurse() {
        var s = new StationProgress();
        var m = new Mods();
        s.level = 30;
        s.stockpile = ProductionMath.cap(R, MINER, s, m);
        double base = s.stockpile;
        var haul = ProductionMath.collect(R, MINER, s, m, 7, true);
        assertEquals(0, s.stockpile, 1e-9);
        assertEquals(Math.floor(base * 1.10 * 1.21), haul.amount(), 1);
        assertEquals(haul.amount(), s.purse, 1e-6);
        assertEquals(haul.amount(), haul.pieces().stream().mapToDouble(ProductionMath.Piece::value).sum(), 1e-6);
        assertTrue(haul.pieces().size() <= 36);
        assertEquals("Bountiful haul", ProductionMath.haulWord(haul));
    }

    @Test
    void piecesAlwaysAddUp() {
        for (double amount : new double[] {1, 2, 7, 43, 999, 12_345, 9.9e9}) {
            var pieces = ProductionMath.pieces(amount, 4);
            assertEquals(amount, pieces.stream().mapToDouble(ProductionMath.Piece::value).sum(), amount * 1e-9, "amount " + amount);
            assertTrue(pieces.stream().allMatch(p -> p.value() >= 1 && p.tier() >= 1 && p.tier() <= 4));
        }
        assertTrue(ProductionMath.pieces(0.4, 1).isEmpty());
    }

    @Test
    void renownKeepsEverythingButTheLevel() {
        var s = new StationProgress();
        s.level = s.peakLevel = 120;
        s.tools[1] = 4;
        s.talents.add("wide_tunnels");
        assertTrue(ProductionMath.rebuild(R, s));
        assertEquals(1, s.level);
        assertEquals(1, s.renown);
        assertEquals(4, s.tools[1]);
        assertEquals(ProductionMath.milestonesPassed(R, 120) - 1, ProductionMath.talentPicksOwed(R, s));
        assertFalse(ProductionMath.rebuild(R, s));
    }

    @Test
    void threeCardsOfAPathGiveItsSet() {
        var s = new StationProgress();
        s.talents.addAll(java.util.List.of("wide_tunnels", "deep_carts"));
        double two = Professions.mods(R, MINER, s, null, 1).cap;
        s.talents.add("sturdy_props");
        assertEquals(two * 1.5, Professions.mods(R, MINER, s, null, 1).cap, 1e-9);
    }

    @Test
    void aMissedDayCostsTwoDaysOfStreak() {
        var plot = new PlotProduction();
        for (long day = 100; day < 105; day++) assertTrue(plot.countCollect(day));
        assertEquals(5, plot.streak);
        assertFalse(plot.countCollect(104));
        plot.countCollect(106);
        assertEquals(4, plot.streak);
        plot.countCollect(200);
        assertEquals(1, plot.streak);
    }

    @Test
    void ordersPaySealsOnce() {
        var plot = new PlotProduction();
        plot.dealOrders(10, 100);
        assertEquals(3, plot.orders.size());
        assertEquals(2, plot.progress("collect", 1e9));
        assertEquals(0, plot.progress("collect", 1e9));
        assertEquals(2, plot.seals);
        assertTrue(plot.buyEstate(0));
        assertEquals(1, plot.seals);
    }

    @Test
    void numbersStayShort() {
        assertEquals("1,284", ProductionMath.format(1284.9));
        assertEquals("12.3K", ProductionMath.format(12_345));
        assertEquals("3.21M", ProductionMath.format(3_210_000));
        assertEquals("22 minutes", ProductionMath.duration(22 * 60));
    }
}
