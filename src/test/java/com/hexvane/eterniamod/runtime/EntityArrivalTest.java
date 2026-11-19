package com.hexvane.eterniamod.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityArrivalTest {
    private static EntityArrival.Candidate<String> at(String id, double distance) { return new EntityArrival.Candidate<>(id, distance * distance, false); }

    @Test void nothingIsHeldBackWithinTheBudget() {
        assertEquals(List.of(), EntityArrival.holdBack(List.of(at("a", 5), at("b", 50)), 2));
    }

    @Test void theNearestArriveFirstAndPlayersBeforeAnything() {
        var candidates = List.of(at("far", 90), at("near", 3), new EntityArrival.Candidate<>("player", 80 * 80, true), at("middle", 20), at("farther", 120));
        var held = new HashSet<>(EntityArrival.holdBack(candidates, 2));
        assertEquals(new HashSet<>(List.of("far", "middle", "farther")), held, "the player and the nearest decoration come first");
    }

    @Test void aCrowdedAreaArrivesOverManyTicksAndEveryoneGetsThere() {
        // About the town square at the Hub arrival: 3,200 decorations.
        var remaining = new ArrayList<EntityArrival.Candidate<String>>();
        for (int i = 0; i < 3200; i++) remaining.add(at("deco" + i, (i * 37) % 200));
        int ticks = 0;
        double lastWorstSent = -1;
        while (!remaining.isEmpty()) {
            var held = new HashSet<>(EntityArrival.holdBack(remaining, EntityArrival.BUDGET_PER_TICK));
            var sent = remaining.stream().filter(c -> !held.contains(c.entity())).toList();
            assertTrue(sent.size() <= EntityArrival.BUDGET_PER_TICK, "never more than the budget in one tick");
            double best = sent.stream().mapToDouble(EntityArrival.Candidate::distanceSquared).min().orElseThrow();
            assertTrue(best >= lastWorstSent, "later ticks only bring entities at least as far away");
            lastWorstSent = sent.stream().mapToDouble(EntityArrival.Candidate::distanceSquared).max().orElseThrow();
            remaining.removeIf(c -> !held.contains(c.entity()));
            ticks++;
        }
        assertEquals(80, ticks, "3,200 at 40 a tick");
    }
}
