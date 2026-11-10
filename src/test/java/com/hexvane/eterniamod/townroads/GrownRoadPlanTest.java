package com.hexvane.eterniamod.townroads;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GrownRoadPlanTest {
    private static Map<HexCoord, GrownRoadPlan.Land> flat(int radius) {
        Map<HexCoord, GrownRoadPlan.Land> land = new HashMap<>();
        for (HexCoord c : HexCoord.disk(HexCoord.ORIGIN, radius)) land.put(c, new GrownRoadPlan.Land(true, true, 0));
        return land;
    }

    @Test
    void growsATreeFromTheSource() {
        var source = List.of(HexCoord.ORIGIN);
        var plan = GrownRoadPlan.grow(HexCoord.ORIGIN, flat(7), source, List.of(), (a, m, b) -> m == null ? 0.0 : null, GrownRoadPlan.Settings.DEFAULT);
        assertTrue(plan.cells().size() > 10, "a flat disk gets plenty of road");
        Set<HexCoord> seen = new HashSet<>(source);
        for (var step : plan.steps()) {
            assertTrue(seen.contains(step.from()), "every road leaves a street already there");
            assertTrue(step.from().adjacent(step.to()));
            assertTrue(seen.add(step.to()), "no hexagon is planned twice");
        }
    }

    @Test
    void leavesRoomForHomesBetweenRoads() {
        var plan = GrownRoadPlan.grow(HexCoord.ORIGIN, flat(7), List.of(HexCoord.ORIGIN), List.of(), (a, m, b) -> m == null ? 0.0 : null, GrownRoadPlan.Settings.DEFAULT);
        Set<HexCoord> road = new HashSet<>(plan.cells());
        road.add(HexCoord.ORIGIN);
        long free = HexCoord.disk(HexCoord.ORIGIN, 7).stream().filter(c -> !road.contains(c)).count();
        assertTrue(free > road.size(), "most land stays free for plots");
        // Every free hexagon in the inner rings touches a road: the plan reaches the land it was meant to open up.
        for (HexCoord c : HexCoord.disk(HexCoord.ORIGIN, 4)) {
            if (road.contains(c)) continue;
            boolean fronts = c.neighbors().stream().anyMatch(road::contains);
            assertTrue(fronts || c.neighbors().stream().anyMatch(n -> n.neighbors().stream().anyMatch(road::contains)), c + " is near a road");
        }
    }

    @Test
    void keepsOffBlockedLandAndBlockers() {
        var land = flat(6);
        HexCoord hill = new HexCoord(2, 0);
        land.put(hill, new GrownRoadPlan.Land(false, false, 40));
        var blockers = List.of(new HexCoord(-1, 0), new HexCoord(-1, 1));
        var plan = GrownRoadPlan.grow(HexCoord.ORIGIN, land, List.of(HexCoord.ORIGIN), blockers, (a, m, b) -> m == null ? 0.0 : null, GrownRoadPlan.Settings.DEFAULT);
        assertFalse(plan.cells().contains(hill));
        for (HexCoord b : blockers) assertFalse(plan.cells().contains(b));
        for (var step : plan.steps()) assertFalse(blockers.contains(step.from()), "a blocker never sprouts a road");
    }

    @Test
    void respectsEdges() {
        // No road may cross the q = 0 line.
        var plan = GrownRoadPlan.grow(new HexCoord(2, 0), flat(6), List.of(new HexCoord(2, 0)), List.of(), (a, m, b) -> m == null && a.q() > 0 == b.q() > 0 ? 0.0 : null, GrownRoadPlan.Settings.DEFAULT);
        assertTrue(plan.cells().stream().allMatch(c -> c.q() > 0));
    }

    @Test
    void bridgesCrossARiver() {
        // A river runs down q = 3: water hexagons the road may only cross on a bridge.
        var land = flat(7);
        for (HexCoord c : HexCoord.disk(HexCoord.ORIGIN, 7)) if (c.q() == 3) land.put(c, new GrownRoadPlan.Land(false, false, 0, true));
        var plan = GrownRoadPlan.grow(HexCoord.ORIGIN, land, List.of(HexCoord.ORIGIN), List.of(), (a, m, b) -> m == null ? 0.0 : 1.5, GrownRoadPlan.Settings.DEFAULT);
        assertTrue(plan.cells().stream().anyMatch(c -> c.q() > 3), "roads reach the far bank");
        var bridges = plan.steps().stream().filter(GrownRoadPlan.Step::bridge).toList();
        assertFalse(bridges.isEmpty());
        for (var b : bridges) { assertEquals(3, b.via().q()); assertEquals(2, b.from().distance(b.to())); }
        assertTrue(plan.cells().stream().noneMatch(c -> c.q() == 3), "no road hexagon stands in the river");
        var chains = GrownRoadPlan.chains(plan, List.of(HexCoord.ORIGIN), 6);
        for (var c : chains) if (c.bridge()) assertEquals(3, c.cells().size(), "a bridge is a road of its own: bank, water, bank");
        assertEquals(plan.cells(), GrownRoadPlan.cellsOf(chains));
    }

    @Test
    void chainsCoverThePlanInBuildOrder() {
        var sources = List.of(HexCoord.ORIGIN);
        var plan = GrownRoadPlan.grow(HexCoord.ORIGIN, flat(8), sources, List.of(), (a, m, b) -> m == null ? 0.0 : null, GrownRoadPlan.Settings.DEFAULT);
        var chains = GrownRoadPlan.chains(plan, sources, 4);
        Set<HexCoord> built = new HashSet<>(sources);
        for (var chain : chains) {
            assertTrue(built.contains(chain.start()), "a road starts where an earlier road or the plaza is");
            assertTrue(chain.cells().size() - 1 <= 4);
            for (int i = 1; i < chain.cells().size(); i++) assertTrue(chain.cells().get(i - 1).adjacent(chain.cells().get(i)));
            built.addAll(chain.cells());
        }
        assertEquals(plan.cells(), GrownRoadPlan.cellsOf(chains));
    }
}
