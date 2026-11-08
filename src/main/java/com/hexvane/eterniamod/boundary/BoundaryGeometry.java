package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure bounded sampling; rectangles use half-open block bounds. Hex plots sample the unit staircase edges of
 * {@link HexLattice#borderEdges}, so the rune line and rising lights follow the real block border of the cells.
 */
public final class BoundaryGeometry {
    public static final double FULL_DISTANCE = 3.0;
    public static final double FAR_DISTANCE = 8.0;
    public static final int MAX_EMITTERS_PER_VIEWER = 32;
    private BoundaryGeometry() {}

    public record Rectangle(int minX, int minZ, int maxX, int maxZ, int groundY) {}
    /** One emitter on a border. {@code style} is the {@link HexBorderRelation} ordinal for hex plots, 0 otherwise. */
    public record Sample(double x, double z, int groundY, double distance, boolean horizontal, int style) {
        public Sample(double x, double z, int groundY, double distance) { this(x, z, groundY, distance, true, 0); }
        public Sample(double x, double z, int groundY, double distance, boolean horizontal) { this(x, z, groundY, distance, horizontal, 0); }
    }

    public static double edgeDistance(double x, double z, Rectangle rectangle) {
        double dx = Math.max(Math.max(rectangle.minX - x, 0), x - rectangle.maxX);
        double dz = Math.max(Math.max(rectangle.minZ - z, 0), z - rectangle.maxZ);
        if (dx != 0 || dz != 0) return Math.hypot(dx, dz);
        return Math.min(Math.min(x - rectangle.minX, rectangle.maxX - x), Math.min(z - rectangle.minZ, rectangle.maxZ - z));
    }

    public static double intensity(double distance) {
        double t = Math.max(0, Math.min(1, (distance - FULL_DISTANCE) / (FAR_DISTANCE - FULL_DISTANCE)));
        return 1 - t * t * (3 - 2 * t);
    }

    public static List<Sample> nearby(List<Rectangle> rectangles, double x, double z) {
        Map<String, Sample> points = new LinkedHashMap<>();
        for (Rectangle rectangle : rectangles) {
            if (rectangle.minX >= rectangle.maxX || rectangle.minZ >= rectangle.maxZ
                || edgeDistance(x, z, rectangle) >= FAR_DISTANCE) continue;
            sampleHorizontal(points, rectangle.minX, rectangle.maxX, rectangle.minZ, rectangle.groundY, x, z);
            sampleHorizontal(points, rectangle.minX, rectangle.maxX, rectangle.maxZ, rectangle.groundY, x, z);
            sampleVertical(points, rectangle.minZ, rectangle.maxZ, rectangle.minX, rectangle.groundY, x, z);
            sampleVertical(points, rectangle.minZ, rectangle.maxZ, rectangle.maxX, rectangle.groundY, x, z);
        }
        List<Sample> sorted = new ArrayList<>(points.values());
        sorted.sort(Comparator.comparingDouble(Sample::distance).thenComparingDouble(Sample::x).thenComparingDouble(Sample::z));
        return List.copyOf(sorted.subList(0, Math.min(MAX_EMITTERS_PER_VIEWER, sorted.size())));
    }

    private static void sampleHorizontal(Map<String, Sample> points, int min, int max, int fixed, int ground, double x, double z) {
        long start = Math.max((long) min, (long) Math.floor(x - FAR_DISTANCE));
        long end = Math.min((long) max - 1, (long) Math.ceil(x + FAR_DISTANCE));
        for (long i = start; i <= end; i++) add(points, i + .5, fixed, ground, x, z,true);
    }

    private static void sampleVertical(Map<String, Sample> points, int min, int max, int fixed, int ground, double x, double z) {
        long start = Math.max((long) min, (long) Math.floor(z - FAR_DISTANCE));
        long end = Math.min((long) max - 1, (long) Math.ceil(z + FAR_DISTANCE));
        for (long i = start; i <= end; i++) add(points, fixed, i + .5, ground, x, z,false);
    }

    private static void add(Map<String, Sample> points, double px, double pz, int ground, double x, double z, boolean horizontal) {
        double distance = Math.hypot(px - x, pz - z);
        if (distance < FAR_DISTANCE) points.putIfAbsent(px + ":" + pz, new Sample(px, pz, ground, distance,horizontal));
    }

    // ---- Hex plots ----------------------------------------------------------------------------------------------

    /** Claimable outlines reach a little further than plot borders, but only every {@link #CLAIMABLE_STRIDE} blocks. */
    public static final double CLAIMABLE_DISTANCE = 12.0;
    public static final int CLAIMABLE_STRIDE = 2;
    public static final int MAX_CLAIMABLE_EMITTERS_PER_VIEWER = 20;

    /** A straight line on the ground, in block edge coordinates. */
    public record Line(double x1, double z1, double x2, double z2) {
        public double length() { return Math.hypot(x2 - x1, z2 - z1); }
    }

    /** A plot's (or a claimable cell's) border edges with the ground height to search from and a style. */
    public record StyledBorder(List<HexLattice.Edge> edges, int groundY, int style) {}

    /** The unit staircase border of a plot's cells (cached per distinct set of cells). */
    public static List<HexLattice.Edge> hexEdges(com.hexvane.eterniamod.hexplots.PlotCells cells) { return HexBorderIndex.edgesOf(cells); }

    /**
     * A hex plot's outline as ground lines: the exact block staircase ({@code staircase}) or the six line hexagon
     * sides. Used by the plot outline overlay and the house and decoration placement previews.
     */
    public static List<Line> outline(com.hexvane.eterniamod.hexplots.PlotCells cells, boolean staircase) {
        return staircase ? staircase(hexEdges(cells)) : straight(cells.lattice(), cells.cells());
    }

    /** Neighbour direction (index into {@link HexCoord#DIRECTIONS}) across each side of {@link HexLattice#corners}. */
    private static final int[] SIDE_DIRECTION = {1, 0, 5, 4, 3, 2};

    /**
     * The staircase border merged into straight runs: consecutive unit edges on one line become one line. This is
     * the exact block outline with the fewest debug lines (a single cell is 68 lines, see the notes).
     */
    public static List<Line> staircase(Collection<HexLattice.Edge> edges) {
        Map<Integer, List<Integer>> rows = new TreeMap<>(), columns = new TreeMap<>();
        for (HexLattice.Edge e : edges) {
            if (e.alongX()) rows.computeIfAbsent(e.z(), k -> new ArrayList<>()).add(e.x());
            else columns.computeIfAbsent(e.x(), k -> new ArrayList<>()).add(e.z());
        }
        List<Line> out = new ArrayList<>();
        rows.forEach((z, xs) -> runs(xs, (a, b) -> out.add(new Line(a, z, b, z))));
        columns.forEach((x, zs) -> runs(zs, (a, b) -> out.add(new Line(x, a, x, b))));
        return out;
    }

    private interface RunSink { void run(int from, int to); }

    private static void runs(List<Integer> starts, RunSink sink) {
        int[] sorted = starts.stream().mapToInt(Integer::intValue).distinct().sorted().toArray();
        int i = 0;
        while (i < sorted.length) {
            int from = sorted[i], to = from + 1;
            while (++i < sorted.length && sorted[i] == to) to++;
            sink.run(from, to);
        }
    }

    /**
     * The ideal outline as straight lines: one line per cell side that faces a cell outside the set. A single cell
     * is six lines, a seven cell flower eighteen. Cheap enough for far plots and claimable hints.
     */
    public static List<Line> straight(HexLattice lattice, Collection<HexCoord> cells) {
        Set<HexCoord> set = Set.copyOf(cells);
        List<Line> out = new ArrayList<>();
        for (HexCoord cell : cells) {
            double[][] c = lattice.corners(cell);
            for (int side = 0; side < 6; side++) {
                if (set.contains(cell.neighbor(SIDE_DIRECTION[side]))) continue;
                double[] a = c[side], b = c[(side + 1) % 6];
                out.add(new Line(a[0], a[1], b[0], b[1]));
            }
        }
        return out;
    }

    /**
     * Emitters on hex borders near (x, z): one per unit edge (or every {@code stride} blocks), closer than
     * {@code maxDistance}, nearest first, at most {@code budget}. Where two borders share an edge the lower style
     * wins, so the viewer's own plot keeps its colour on a side shared with a neighbour.
     */
    public static List<Sample> nearbyEdges(Collection<StyledBorder> borders, double x, double z, double maxDistance, int stride, int budget) {
        Map<Long, Sample> points = new HashMap<>();
        for (StyledBorder border : borders) {
            for (HexLattice.Edge e : border.edges()) {
                int along = e.alongX() ? e.x() : e.z();
                if (stride > 1 && Math.floorMod(along, stride) != 0) continue;
                double px = e.alongX() ? e.x() + .5 : e.x();
                double pz = e.alongX() ? e.z() : e.z() + .5;
                double distance = Math.hypot(px - x, pz - z);
                if (distance >= maxDistance) continue;
                long key = ((long) Math.floor(px * 2) << 32) ^ ((long) Math.floor(pz * 2) & 0xffffffffL);
                Sample old = points.get(key);
                if (old == null || border.style() < old.style())
                    points.put(key, new Sample(px, pz, border.groundY(), distance, e.alongX(), border.style()));
            }
        }
        List<Sample> sorted = new ArrayList<>(points.values());
        sorted.sort(Comparator.comparingDouble(Sample::distance).thenComparingDouble(Sample::x).thenComparingDouble(Sample::z));
        return List.copyOf(sorted.subList(0, Math.min(budget, sorted.size())));
    }
}
