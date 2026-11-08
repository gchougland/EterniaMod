package com.hexvane.eterniamod.hexplots;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Plots are one connected piece: cells join through shared edges, never through corners alone. */
public final class HexContiguity {
    private HexContiguity() {}

    public static boolean isConnected(Collection<HexCoord> cells) {
        if (cells.isEmpty()) return false;
        Set<HexCoord> set = Set.copyOf(cells);
        Set<HexCoord> seen = new HashSet<>();
        ArrayDeque<HexCoord> queue = new ArrayDeque<>();
        HexCoord start = cells.iterator().next();
        queue.add(start); seen.add(start);
        while (!queue.isEmpty()) {
            for (HexCoord n : queue.remove().neighbors()) if (set.contains(n) && seen.add(n)) queue.add(n);
        }
        return seen.size() == set.size();
    }

    /** Unowned candidates for expansion: cells outside the plot that share an edge with it. */
    public static Set<HexCoord> frontier(Collection<HexCoord> plot) {
        Set<HexCoord> set = Set.copyOf(plot);
        Set<HexCoord> out = new LinkedHashSet<>();
        for (HexCoord c : plot) for (HexCoord n : c.neighbors()) if (!set.contains(n)) out.add(n);
        return out;
    }

    public static boolean canAdd(Collection<HexCoord> plot, HexCoord cell) {
        return !plot.contains(cell) && plot.stream().anyMatch(cell::adjacent);
    }

    /** A cell can be given up when it is not locked (house cells) and the rest stays in one piece. */
    public static boolean canRemove(Collection<HexCoord> plot, HexCoord cell, Collection<HexCoord> locked) {
        if (!plot.contains(cell) || locked.contains(cell) || plot.size() <= 1) return false;
        Set<HexCoord> rest = new HashSet<>(plot);
        rest.remove(cell);
        return isConnected(rest);
    }

    /** Count of plot cells that touch at least one cell outside the plot. */
    public static int edgeCells(Collection<HexCoord> plot) {
        Set<HexCoord> set = Set.copyOf(plot);
        return (int) plot.stream().filter(c -> c.neighbors().stream().anyMatch(n -> !set.contains(n))).count();
    }
}
