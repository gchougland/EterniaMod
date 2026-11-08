package com.hexvane.eterniamod.hexplots;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Rectangular house footprints against a set of cells. The setback is measured from the plot's outer
 * border only: the footprint grown by {@code setback} blocks on every side must stay inside the cells.
 * That is exactly today's {@code PlotRect.containsWithSetback}, generalised to any cell outline.
 */
public final class HexFit {
    private HexFit() {}

    public static boolean fits(HexLattice lattice, Collection<HexCoord> cells, PlotRect house, int setback) {
        if (setback < 0) throw new IllegalArgumentException("setback");
        Set<HexCoord> set = Set.copyOf(cells);
        long fromX = (long) house.x() - setback, toX = (long) house.endX() + setback;
        long fromZ = (long) house.z() - setback, toZ = (long) house.endZ() + setback;
        for (long z = fromZ; z < toZ; z++)
            for (long x = fromX; x < toX; x++)
                if (!set.contains(lattice.cellAt(Math.toIntExact(x), Math.toIntExact(z)))) return false;
        return true;
    }

    /** Cells a footprint stands on: the plot's "house cells", which hold no yard props allowance. */
    public static Set<HexCoord> cellsUnder(HexLattice lattice, PlotRect house) { return lattice.cellsTouching(house); }

    /** Side of the largest square house that fits anywhere in the cells. */
    public static int largestSquare(HexLattice lattice, Collection<HexCoord> cells, int setback) {
        if (cells.isEmpty()) return 0;
        Set<HexCoord> set = Set.copyOf(cells);
        PlotRect b = lattice.bounds(cells);
        int[] prev = new int[b.width() + 1], cur = new int[b.width() + 1];
        int best = 0;
        for (int z = 0; z < b.depth(); z++) {
            for (int x = 0; x < b.width(); x++) {
                cur[x + 1] = set.contains(lattice.cellAt(b.x() + x, b.z() + z)) ? 1 + Math.min(cur[x], Math.min(prev[x], prev[x + 1])) : 0;
                best = Math.max(best, cur[x + 1]);
            }
            int[] swap = prev; prev = cur; cur = swap;
        }
        return Math.max(0, best - 2 * setback);
    }

    /** Smallest {@link #largestSquare} over every turned variant of the shape. */
    public static int largestSquareInEveryVariant(HexLattice lattice, HexShape shape, int setback) {
        int worst = Integer.MAX_VALUE;
        // Cells are exact translates of each other, so one placement per variant covers every position.
        for (Set<HexCoord> variant : shape.variants()) worst = Math.min(worst, largestSquare(lattice, variant, setback));
        return worst;
    }

    /**
     * The valid footprint position closest to the cells' centre, as "Find valid spot" does today.
     * {@code width} runs along X and {@code depth} along Z.
     */
    public static Optional<PlotRect> nearestFit(HexLattice lattice, Collection<HexCoord> cells, int width, int depth, int setback) {
        if (cells.isEmpty()) return Optional.empty();
        double cx = cells.stream().mapToDouble(lattice::centerX).average().orElseThrow();
        double cz = cells.stream().mapToDouble(lattice::centerZ).average().orElseThrow();
        PlotRect b = lattice.bounds(cells);
        PlotRect best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int z = b.z() + setback; z + depth + setback <= b.endZ(); z++) {
            for (int x = b.x() + setback; x + width + setback <= b.endX(); x++) {
                double d = Math.hypot(x + width / 2.0 - cx, z + depth / 2.0 - cz);
                if (d >= bestDistance) continue;
                PlotRect candidate = new PlotRect(x, z, width, depth);
                if (fits(lattice, cells, candidate, setback)) { best = candidate; bestDistance = d; }
            }
        }
        return Optional.ofNullable(best);
    }
}
