package com.hexvane.eterniamod.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Which newly visible entities a player receives this tick, when more arrive than the budget allows. Players come
 * first, then the nearest. Hytale-free so it can be tested.
 *
 * <p>Why: the 0.7 client crashes (an IndexOutOfRangeException in its transparent entity pass) when thousands of
 * entities arrive at once, as when joining or teleporting into a busy area: the town square, or a home full of
 * decorations. Spreading arrivals over a few seconds avoids it whatever the area holds.
 */
public final class EntityArrival {
    /** New entities a player may receive per tick, normally. */
    public static final int BUDGET_PER_TICK = 40;
    /** New entities per tick in the first seconds after joining a world or teleporting, while the client settles. */
    public static final int SETTLING_BUDGET_PER_TICK = 10;

    /** One entity the player does not have yet. */
    public record Candidate<T>(T entity, double distanceSquared, boolean player) {}

    private EntityArrival() {}

    /** The entities to hold back this tick: everything past the {@code budget} best candidates. */
    public static <T> List<T> holdBack(List<Candidate<T>> candidates, int budget) {
        if (candidates.size() <= budget) return List.of();
        Comparator<Candidate<T>> better = Comparator.<Candidate<T>, Boolean>comparing(c -> !c.player()).thenComparingDouble(Candidate::distanceSquared);
        // Keep the best `budget` in a heap whose head is the worst kept, so each candidate costs log(budget).
        var kept = new PriorityQueue<Candidate<T>>(budget + 1, better.reversed());
        var held = new ArrayList<T>(candidates.size() - budget);
        for (var candidate : candidates) {
            if (budget == 0) { held.add(candidate.entity()); continue; }
            kept.add(candidate);
            if (kept.size() > budget) held.add(kept.poll().entity());
        }
        return held;
    }
}
