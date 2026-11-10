package com.hexvane.eterniamod.townroads;

import com.hexvane.eterniamod.hexplots.HexCoord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The grown street plan from the road growth simulation, on the live hexagon lattice. Pure.
 *
 * <p>Starting from the streets already there, it keeps adding the road hexagon that brings the most new free land within
 * one hexagon of a road, disliking sharp turns, rough ground and distance from the centre, and never letting a new road
 * run alongside another. Roads end up about two homes apart and bend round hills and water. The result is a tree: every
 * new road hexagon hangs off exactly one earlier one, so it can be built outwards in order.
 */
public final class GrownRoadPlan {
    private GrownRoadPlan() {}

    /**
     * What the planner knows about one hexagon of land. {@code relief} is the height range inside it, in blocks;
     * {@code water} marks a hexagon that is mostly water, which a bridge may cross.
     */
    public record Land(boolean roadOk, boolean claimable, double relief, boolean water) {
        public Land(boolean roadOk, boolean claimable, double relief) { this(roadOk, claimable, relief, false); }
    }

    /**
     * A planned road step from {@code from} (already a street or planned) to {@code to}. {@code via} is the water
     * hexagon a bridge crosses between them, or null; {@code bridge} is true when the step crosses water at all.
     */
    public record Step(HexCoord from, HexCoord via, HexCoord to, boolean bridge) {
        public Step(HexCoord from, HexCoord to) { this(from, null, to, false); }
    }

    /**
     * Whether and at what cost a road may run from {@code from} to {@code to}, over {@code via} when it is not null
     * (then {@code to} is two hexagons away, across water). Null means no road; 0 a plain road; above 0 a bridge, the
     * more the longer.
     */
    @FunctionalInterface
    public interface Edges { Double cost(HexCoord from, HexCoord via, HexCoord to); }

    /**
     * The plan. {@code steps} are in the order they were chosen (each {@code from} is a source or an earlier
     * {@code to}); {@code cells} are the new road hexagons.
     */
    public record Result(List<Step> steps, Set<HexCoord> cells) {}

    /**
     * Settings. {@code roadGap} is how many new free hexagons a road step must open up (the simulation's 2);
     * {@code sourceBranches} is how many new roads may leave one source hexagon.
     */
    public record Settings(int roadGap, int sourceBranches, int maxCells, double distanceWeight) {
        public static final Settings DEFAULT = new Settings(2, 1, 400, 0.03);
    }

    /**
     * Grows a plan inside {@code land} (hexagons not in the map are off limits). {@code sources} are streets roads may
     * leave from; {@code blockers} are other streets (a plaza that is walled in) that count as road for spacing but
     * never sprout one. {@code edgeOk} says whether a road may run between two neighbouring hexagons (slope, water).
     */
    public static Result grow(HexCoord center, Map<HexCoord, Land> land, Collection<HexCoord> sources, Collection<HexCoord> blockers,
                              Edges edges, Settings settings) {
        Map<HexCoord, Integer> road = new HashMap<>(); // 1 source or planned road (may sprout), 2 bridge, 3 blocker
        Map<HexCoord, Integer> dir = new HashMap<>(), degree = new HashMap<>();
        Set<HexCoord> covered = new HashSet<>(), isSource = new HashSet<>(sources);
        for (HexCoord b : blockers) { road.put(b, 3); cover(b, covered); }
        for (HexCoord s : sources) { road.put(s, 1); cover(s, covered); }
        List<Step> steps = new ArrayList<>();
        Set<HexCoord> cells = new LinkedHashSet<>();
        // Candidate sprouting hexagons, in a stable order so the same land always gives the same plan.
        List<HexCoord> growing = new ArrayList<>(sources);
        growing.sort(HexCoord.READING_ORDER);
        while (cells.size() < settings.maxCells()) {
            Step best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (HexCoord s : growing) {
                int deg = degree.getOrDefault(s, 0);
                if (isSource.contains(s) && deg >= settings.sourceBranches()) continue;
                Set<HexCoord> near = new HashSet<>();
                near.add(s);
                for (HexCoord a : s.neighbors()) if (road.containsKey(a)) near.add(a);
                for (int d = 0; d < 6; d++) {
                    HexCoord t = s.neighbor(d), via = null;
                    if (road.containsKey(t)) continue;
                    Land l = land.get(t);
                    // Across a water hexagon to the land beyond it, on a bridge.
                    if (l != null && l.water()) { via = t; t = t.neighbor(d); if (road.containsKey(t)) continue; l = land.get(t); }
                    if (l == null || !l.roadOk()) continue;
                    Double cost = edges.cost(s, via, t);
                    if (cost == null) continue;
                    int crowd = 0, gain = l.claimable() && !covered.contains(t) ? -1 : 0;
                    for (HexCoord a : t.neighbors()) {
                        // A plaza or walled town is not a road: new roads may run beside it.
                        if (road.containsKey(a) && road.get(a) != 3 && !near.contains(a) && !a.equals(via)) crowd++;
                        Land la = land.get(a);
                        if (la != null && la.claimable() && !covered.contains(a) && !road.containsKey(a)) gain++;
                        // Free land just across water counts too, so roads reach the bank where a bridge can start.
                        else if (la != null && la.water() && via == null && !covered.contains(a) && acrossWater(land, covered, road, t, a)) gain++;
                    }
                    if (crowd > 0 || gain < settings.roadGap()) continue;
                    double turn;
                    if (isSource.contains(s)) turn = 0;
                    else if (deg >= 3) turn = 9;
                    else if (deg >= 2) turn = 1;
                    else turn = new double[] {0, 0.3, 2, 9}[turnSize(dir.getOrDefault(s, d), d)];
                    double score = gain - turn - l.relief() / 12.0 - settings.distanceWeight() * center.distance(t) - cost;
                    if (score > bestScore) { bestScore = score; best = new Step(s, via, t, via != null || cost > 0); }
                }
            }
            if (best == null) break;
            HexCoord s = best.from(), t = best.to();
            road.put(t, 1);
            if (best.via() != null) { road.put(best.via(), 2); cover(best.via(), covered); }
            dir.put(t, directionOf(s, best.via() != null ? best.via() : t));
            degree.merge(s, 1, Integer::sum);
            degree.merge(t, 1, Integer::sum);
            cover(t, covered);
            steps.add(best);
            cells.add(t);
            growing.add(t);
        }
        return new Result(List.copyOf(steps), Set.copyOf(cells));
    }

    /** Whether water hexagon {@code water}, beside {@code bank}, has free land on its far side. */
    private static boolean acrossWater(Map<HexCoord, Land> land, Set<HexCoord> covered, Map<HexCoord, Integer> road, HexCoord bank, HexCoord water) {
        HexCoord far = water.plus(water.minus(bank));
        Land l = land.get(far);
        return l != null && l.claimable() && !covered.contains(far) && !road.containsKey(far);
    }

    private static void cover(HexCoord c, Set<HexCoord> covered) { covered.add(c); covered.addAll(c.neighbors()); }

    static int directionOf(HexCoord from, HexCoord to) {
        HexCoord d = to.minus(from);
        int i = HexCoord.DIRECTIONS.indexOf(d);
        if (i < 0) throw new IllegalArgumentException(from + " and " + to + " are not neighbours");
        return i;
    }

    static int turnSize(int d0, int d1) { int t = Math.abs(d0 - d1) % 6; return Math.min(t, 6 - t); }

    /**
     * One road to build: from {@code cells[0]} (a source street or a junction an earlier road reached) through the rest.
     * {@code fromSource} is true when the first hexagon is an old street rather than a planned one.
     */
    public record Chain(List<HexCoord> cells, boolean fromSource, boolean bridge) {
        public Chain(List<HexCoord> cells, boolean fromSource) { this(cells, fromSource, false); }
        public HexCoord start() { return cells.getFirst(); }
        public HexCoord end() { return cells.getLast(); }
    }

    /**
     * Splits a plan into roads to build in order: each road leaves a source or a junction, runs through hexagons with
     * one way on, and stops at the next junction, a dead end, or after {@code maxLength} steps. A road's first hexagon is
     * always a source or the end or middle of an earlier road.
     */
    public static List<Chain> chains(Result plan, Collection<HexCoord> sources, int maxLength) {
        if (maxLength < 1) throw new IllegalArgumentException("Roads need at least one step");
        Map<HexCoord, List<HexCoord>> children = new HashMap<>();
        Map<HexCoord, Step> stepTo = new HashMap<>();
        for (Step s : plan.steps()) { children.computeIfAbsent(s.from(), k -> new ArrayList<>()).add(s.to()); stepTo.put(s.to(), s); }
        Set<HexCoord> sourceSet = new HashSet<>(sources);
        List<Chain> out = new ArrayList<>();
        // Breadth first from the sources in plan order: a road is listed only after the road it leaves from.
        var queue = new ArrayDeque<HexCoord>();
        List<HexCoord> starts = new ArrayList<>();
        for (Step s : plan.steps()) if (sourceSet.contains(s.from()) && !starts.contains(s.from())) starts.add(s.from());
        queue.addAll(starts);
        Set<HexCoord> started = new HashSet<>();
        while (!queue.isEmpty()) {
            HexCoord from = queue.poll();
            if (!started.add(from)) continue;
            for (HexCoord first : children.getOrDefault(from, List.of())) {
                Step firstStep = stepTo.get(first);
                List<HexCoord> cells = new ArrayList<>(List.of(from));
                if (firstStep.via() != null) cells.add(firstStep.via());
                cells.add(first);
                HexCoord at = first;
                // A bridge is a road of its own, so the land on either side is built by the Road Designer.
                boolean bridge = firstStep.bridge();
                while (!bridge && cells.size() - 1 < maxLength) {
                    var next = children.getOrDefault(at, List.of());
                    if (next.size() != 1 || stepTo.get(next.getFirst()).bridge()) break;
                    at = next.getFirst();
                    cells.add(at);
                }
                out.add(new Chain(List.copyOf(cells), sourceSet.contains(from), bridge));
                // Whatever grows on from here starts its own road once this one is built.
                if (!children.getOrDefault(at, List.of()).isEmpty()) queue.add(at);
                for (int i = 1; i < cells.size() - 1; i++) if (children.getOrDefault(cells.get(i), List.of()).size() > 1) queue.add(cells.get(i));
            }
        }
        return List.copyOf(out);
    }

    /** Every hexagon a chain list covers, for counting. */
    public static Set<HexCoord> cellsOf(Collection<Chain> chains) {
        Set<HexCoord> out = new LinkedHashSet<>();
        for (Chain c : chains) for (HexCoord cell : c.cells().subList(1, c.cells().size())) if (!c.bridge() || cell.equals(c.end())) out.add(cell);
        return out;
    }

    /** Hexagons sorted by distance from {@code center}, then reading order. */
    public static Comparator<HexCoord> byDistanceFrom(HexCoord center) {
        return Comparator.comparingInt((HexCoord c) -> c.distance(center)).thenComparing(HexCoord.READING_ORDER);
    }
}
