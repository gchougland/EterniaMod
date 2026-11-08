package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Border edges of every hex plot in one world, bucketed by 16 block tiles so a viewer only looks at the edges
 * around them. Edges are computed once per distinct set of cells and shared ({@link #edgesOf}), so rebuilding the
 * index after one claim reuses every other plot's border. Pure; build on any thread, read on any thread.
 */
public final class HexBorderIndex {
    private static final int TILE = 16;
    private static final int MAX_CACHED_SHAPES = 4096;
    private static final Map<PlotCells, List<HexLattice.Edge>> EDGES = new ConcurrentHashMap<>();

    /** One plot: id, cells and the ground height its glow searches from. */
    public record PlotEntry(UUID plotId, PlotCells cells, int groundY) {}

    /** A plot's edges near a point, with the plot they belong to. */
    public record Nearby(PlotEntry plot, List<HexLattice.Edge> edges) {}

    private final Map<Long, List<Hit>> buckets = new HashMap<>();
    private final int plotCount;
    private final int edgeCount;

    private record Hit(PlotEntry plot, HexLattice.Edge edge) {}

    public HexBorderIndex(Collection<PlotEntry> plots) {
        int edges = 0;
        for (PlotEntry plot : plots) {
            for (HexLattice.Edge e : edgesOf(plot.cells())) {
                buckets.computeIfAbsent(key(Math.floorDiv(e.x(), TILE), Math.floorDiv(e.z(), TILE)), k -> new ArrayList<>()).add(new Hit(plot, e));
                edges++;
            }
        }
        this.plotCount = plots.size();
        this.edgeCount = edges;
    }

    /** The unit staircase border of a set of cells, cached by value. */
    public static List<HexLattice.Edge> edgesOf(PlotCells cells) {
        List<HexLattice.Edge> found = EDGES.get(cells);
        if (found != null) return found;
        if (EDGES.size() >= MAX_CACHED_SHAPES) EDGES.clear();
        List<HexLattice.Edge> edges = List.copyOf(cells.lattice().borderEdges(cells.cells()));
        EDGES.put(cells, edges);
        return edges;
    }

    public int plotCount() { return plotCount; }

    public int edgeCount() { return edgeCount; }

    /** Edges within {@code radius} blocks (tile granularity, so a little more) of (x, z), grouped by plot. */
    public List<Nearby> near(double x, double z, double radius) {
        int minX = Math.floorDiv((int) Math.floor(x - radius), TILE), maxX = Math.floorDiv((int) Math.ceil(x + radius), TILE);
        int minZ = Math.floorDiv((int) Math.floor(z - radius), TILE), maxZ = Math.floorDiv((int) Math.ceil(z + radius), TILE);
        Map<UUID, List<HexLattice.Edge>> byPlot = new LinkedHashMap<>();
        Map<UUID, PlotEntry> plots = new HashMap<>();
        for (int tx = minX; tx <= maxX; tx++) for (int tz = minZ; tz <= maxZ; tz++) {
            List<Hit> hits = buckets.get(key(tx, tz));
            if (hits == null) continue;
            for (Hit hit : hits) {
                byPlot.computeIfAbsent(hit.plot.plotId(), k -> new ArrayList<>()).add(hit.edge);
                plots.putIfAbsent(hit.plot.plotId(), hit.plot);
            }
        }
        List<Nearby> out = new ArrayList<>(byPlot.size());
        byPlot.forEach((id, edges) -> out.add(new Nearby(plots.get(id), edges)));
        return out;
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    /** Test helper. */
    static void clearShapeCache() { EDGES.clear(); }

    static int cachedShapes() { return EDGES.size(); }
}
