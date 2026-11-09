package com.hexvane.eterniamod.hexplots.terraform;

import com.hypixel.hytale.builtin.buildertools.tooloperations.SculptOperation;
import com.hypixel.hytale.builtin.buildertools.utils.Sample;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Plans the ground height of every column around newly claimed hexagons. Pure; safe on any thread.
 *
 * <p>Heights are the y of a column's top ground block. A column is one of:
 * <ul>
 *   <li>{@link #OWN}: inside the new hexagons and safe to reshape;</li>
 *   <li>{@link #FREE}: unclaimed natural land, safe to reshape (smooth mode blends into it);</li>
 *   <li>{@link #FIXED}: never changed (roads, plazas, other plots, the owner's older hexagons, builds, water).</li>
 * </ul>
 *
 * <p><b>Plateau:</b> every own column goes to the level and nothing else changes, so each hexagon is a crisp step.
 * <b>Smooth:</b> own columns go to the level, except a band along fixed neighbours (other plots, roads) that eases
 * toward their edge height; a skirt of free land outside the plot eases from the level back to the natural ground.
 * The ease starts as a straight ramp by distance and is then rounded with the sculpt brush's own heightmap smoothing
 * ({@link Sample#calcDeltaHeights} with {@link SculptOperation#NORMAL_HEIGHTMAP} and its raise and lower thresholds).
 */
public final class TerraformField {
    public static final byte FIXED = 0, OWN = 1, FREE = 2;
    public static final int UNKNOWN = TerraformLevel.UNKNOWN;
    /** The sculpt brush kernel reads two columns each way. */
    public static final int KERNEL_REACH = 2;

    private TerraformField() {}

    /** A rectangle of columns with a role and natural ground height each. Row major: index = (z - minZ) * width + (x - minX). */
    public static final class Grid {
        public final int minX, minZ, width, depth;
        public final byte[] role;
        public final int[] natural;

        public Grid(int minX, int minZ, int width, int depth) {
            if (width <= 0 || depth <= 0 || (long) width * depth > 4_000_000L) throw new IllegalArgumentException("Bad grid size");
            this.minX = minX; this.minZ = minZ; this.width = width; this.depth = depth;
            role = new byte[width * depth];
            natural = new int[width * depth];
            Arrays.fill(natural, UNKNOWN);
        }

        public int size() { return role.length; }
        public boolean in(int x, int z) { return x >= minX && z >= minZ && x < minX + width && z < minZ + depth; }
        public int index(int x, int z) { return (z - minZ) * width + (x - minX); }
        public int x(int i) { return minX + i % width; }
        public int z(int i) { return minZ + i / width; }
        public void set(int x, int z, byte r, int h) { int i = index(x, z); role[i] = r; natural[i] = h; }
        public byte role(int x, int z) { return in(x, z) ? role[index(x, z)] : FIXED; }

        /** Free land within {@code clearance} columns (any direction) of a column in {@code mask} becomes fixed. */
        public void fixNear(boolean[] mask, int clearance) {
            if (clearance <= 0) return;
            int[] dist = chebyshev(this, mask);
            for (int i = 0; i < size(); i++) if (role[i] == FREE && dist[i] <= clearance) role[i] = FIXED;
        }
    }

    /** Plateau mode: own columns go to {@code level}; every other column is {@link #UNKNOWN} (left alone). */
    public static int[] plateau(Grid g, int level) {
        int[] target = new int[g.size()];
        Arrays.fill(target, UNKNOWN);
        for (int i = 0; i < g.size(); i++) if (g.role[i] == OWN) target[i] = level;
        return target;
    }

    /**
     * Smooth mode. Returns the planned height per column; {@link #UNKNOWN} where nothing changes. Only own columns
     * and free columns within {@code skirt} of the plot are ever given a height. {@code kernel} is one of the sculpt
     * brush's 5 x 5 heightmap kernels; in game pass {@link SculptOperation#NORMAL_HEIGHTMAP} (it can only be read once
     * the server's block assets exist, so the planner takes it as an argument).
     */
    public static int[] smooth(Grid g, int level, int skirt, int band, int passes, SculptOperation.Kernel kernel) {
        int n = g.size();
        boolean[] fixedKnown = new boolean[n], own = new boolean[n];
        for (int i = 0; i < n; i++) { fixedKnown[i] = g.role[i] == FIXED && g.natural[i] != UNKNOWN; own[i] = g.role[i] == OWN; }
        int[] toFixed = chebyshev(g, fixedKnown), toOwn = chebyshev(g, own);

        // Free columns: the plot's edge band and the skirt of free land. Everything else holds its height.
        boolean[] free = new boolean[n];
        boolean anyCore = false;
        for (int i = 0; i < n; i++) {
            if (own[i] && toFixed[i] > band) anyCore = true;
            if (g.role[i] == FREE && toOwn[i] <= skirt && g.natural[i] != UNKNOWN && inside(g, i, KERNEL_REACH)) free[i] = true;
        }
        for (int i = 0; i < n; i++) if (own[i] && anyCore && toFixed[i] <= band && inside(g, i, KERNEL_REACH)) free[i] = true;

        int[] height = new int[n];
        boolean[] core = new boolean[n], anchor = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (own[i] && !free[i]) { height[i] = level; core[i] = true; }
            else if (!free[i]) { height[i] = g.natural[i]; anchor[i] = g.natural[i] != UNKNOWN; }
        }
        // First shape: a straight ramp from the level at the plot's flat core to the nearest natural or fixed height.
        int[] toCore = chebyshev(g, core);
        int[][] nearestAnchor = nearest(g, anchor);
        for (int i = 0; i < n; i++) {
            if (!free[i]) continue;
            int a = nearestAnchor[0][i];
            if (a < 0 || toCore[i] == Integer.MAX_VALUE) { height[i] = own[i] ? level : g.natural[i]; continue; }
            double dc = toCore[i], da = nearestAnchor[1][i];
            height[i] = (int) Math.round((level * da + height[a] * dc) / (dc + da));
        }
        // Then round it off with the sculpt brush's heightmap smoothing, applied to the free columns only.
        brushSmooth(g, height, free, passes, kernel);
        int[] target = new int[n];
        Arrays.fill(target, UNKNOWN);
        for (int i = 0; i < n; i++) if (own[i] || free[i]) target[i] = height[i];
        return target;
    }

    /**
     * {@code passes} rounds of the sculpt brush's 2D heightmap smoothing ("Heightmap" mode, Normal sampling, no erosion)
     * over the columns in {@code free}; the others are read but never changed. Every round reads the heights before it
     * (as one brush stroke does) and moves a column by the brush's rule.
     */
    static void brushSmooth(Grid g, int[] height, boolean[] free, int passes, SculptOperation.Kernel kernel) {
        if (passes <= 0) return;
        int[][] map = new int[g.width][g.depth];
        int[][][] none = new int[g.width][1][g.depth];
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < g.size(); i++) { int h = height[i]; map[i % g.width][i / g.width] = h == UNKNOWN || h < 0 ? -1 : h; }
            boolean changed = false;
            for (int i = 0; i < g.size(); i++) {
                if (!free[i] || map[i % g.width][i / g.width] < 0) continue;
                var result = Sample.calcDeltaHeights(i % g.width, 0, i / g.width, map, none, kernel.weights(), kernel.total(), 0, false);
                int step = brushStep(result.deltaHeight());
                if (step != 0) { height[i] += step; changed = true; }
            }
            if (!changed) break;
        }
    }

    /** The sculpt brush's heightmap move for one column: raise or lower by whole blocks past its thresholds. */
    static int brushStep(double delta) {
        if (delta > SculptOperation.HEIGHTMAP_RAISE_THRESHOLD) return Math.max((int) delta, 1);
        if (delta < SculptOperation.HEIGHTMAP_LOWER_THRESHOLD) return Math.min((int) delta, -1);
        return 0;
    }

    private static boolean inside(Grid g, int i, int margin) {
        int x = i % g.width, z = i / g.width;
        return x >= margin && z >= margin && x < g.width - margin && z < g.depth - margin;
    }

    /** Chebyshev distance (in columns) from every column to the nearest column in {@code mask}; MAX_VALUE when none. */
    static int[] chebyshev(Grid g, boolean[] mask) {
        return nearest(g, mask)[1];
    }

    /** [0]: index of the nearest column in {@code mask} (-1 when none); [1]: its Chebyshev distance. Breadth first. */
    static int[][] nearest(Grid g, boolean[] mask) {
        int n = g.size();
        int[] from = new int[n], dist = new int[n];
        Arrays.fill(from, -1);
        Arrays.fill(dist, Integer.MAX_VALUE);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (mask[i]) { from[i] = i; dist[i] = 0; queue.add(i); }
        while (!queue.isEmpty()) {
            int i = queue.poll(), x = i % g.width, z = i / g.width;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, nz = z + dz;
                if ((dx == 0 && dz == 0) || nx < 0 || nz < 0 || nx >= g.width || nz >= g.depth) continue;
                int j = nz * g.width + nx;
                if (dist[j] != Integer.MAX_VALUE) continue;
                dist[j] = dist[i] + 1; from[j] = from[i]; queue.add(j);
            }
        }
        return new int[][] {from, dist};
    }
}
