package com.hexvane.eterniamod.hexplots;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A world wide lattice of pointy top hexagons cut exactly from Hytale's block grid.
 *
 * <p>Every block column belongs to exactly one cell, so cells tile with no gaps and no shared blocks.
 * The two flat sides run north/south (constant X). The four slanted sides are clean 2:1 stairs: every
 * row the cell grows or shrinks by two blocks on each side. A cell is {@code cellWidth} blocks across
 * the flats and {@code bodyDepth + 2 * tipDepth()} blocks tip to tip; its tips are two blocks wide.
 * Every cell is an exact integer translate of every other, so a plot's blocks can move between cells.
 *
 * <p>Rows step {@link #rowPitch()} blocks south; odd rows are shifted half a cell east (odd-r offset).
 * Block coordinates are column indices (block {@code x} spans {@code [x, x + 1)}).
 *
 * <p>Pure value type: safe on any thread. Each housing world saves one lattice in its infrastructure plan
 * ({@code HousingInfrastructure.WorldPlan#lattice()}).
 */
public record HexLattice(int cellWidth, int bodyDepth, int originX, int originZ) {
    /** Live size (accepted plan): one cell holds today's 14 x 14 free house with today's five block setback. */
    public static final HexLattice DEFAULT = new HexLattice(32, 20, 0, 0);
    /** Today's border setback for houses, kept unchanged and measured from the plot's outer border only. */
    public static final int HOUSE_SETBACK = 5;

    public HexLattice {
        if (cellWidth < 8 || cellWidth > 256 || cellWidth % 4 != 0) throw new IllegalArgumentException("cellWidth must be a multiple of 4 in [8, 256]");
        if (bodyDepth < 1 || bodyDepth > 256) throw new IllegalArgumentException("bodyDepth must be in [1, 256]");
        if (Math.abs((long) originX) > 30_000_000 || Math.abs((long) originZ) > 30_000_000) throw new IllegalArgumentException("Lattice origin is outside the world");
    }

    /**
     * Stable text id of this lattice, saved next to every plot's cells ({@code hex:32:20:0:0}). Two lattices with
     * the same id are the same lattice, so cells saved under one id always mean the same block columns.
     */
    public String id() { return "hex:" + cellWidth + ":" + bodyDepth + ":" + originX + ":" + originZ; }

    /** Inverse of {@link #id()}. */
    public static HexLattice parseId(String id) {
        if (id == null || !id.startsWith("hex:")) throw new IllegalArgumentException("Not a hex lattice id: " + id);
        String[] parts = id.substring(4).split(":");
        if (parts.length != 4) throw new IllegalArgumentException("Not a hex lattice id: " + id);
        return new HexLattice(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    /** The same cell size with another origin. */
    public HexLattice withOrigin(int x, int z) { return new HexLattice(cellWidth, bodyDepth, x, z); }

    /**
     * The same cell size, shifted so the cell holding block column (x, z) is centred on that column (as near as
     * whole blocks allow). Used by the admin "lattice here" preview to line cells up with an existing road.
     */
    public HexLattice centredOn(int x, int z) { return new HexLattice(cellWidth, bodyDepth, x - cellWidth / 2, z - cellDepth() / 2); }

    /** Rows in each pointed tip; the 2:1 stair needs width / 4 rows to close. */
    public int tipDepth() { return cellWidth / 4; }

    /** North to south extent of one cell, tip to tip. */
    public int cellDepth() { return bodyDepth + 2 * tipDepth(); }

    /** Distance between the tops of consecutive rows. */
    public int rowPitch() { return bodyDepth + tipDepth(); }

    /** Block columns in one cell. */
    public int cellArea() { return cellWidth * rowPitch(); }

    /** The cell that owns block column (x, z). */
    public HexCoord cellAt(int x, int z) {
        long lz = (long) z - originZ, lx = (long) x - originX;
        int pitch = rowPitch(), w = cellWidth, half = w / 2, tip = tipDepth();
        long row = Math.floorDiv(lz, pitch);
        long zr = lz - row * pitch;
        long shifted = lx - ((row & 1) == 1 ? half : 0);
        long col = Math.floorDiv(shifted, w);
        long xr = shifted - col * w;
        if (zr < tip && !(Math.abs(2 * xr + 1 - w) < 2 * (2 * zr + 1))) {
            // Outside this row's top tip: the block is in the bottom tip of a cell one row north.
            boolean left = xr < half;
            boolean odd = (row & 1) == 1;
            col = odd ? (left ? col : col + 1) : (left ? col - 1 : col);
            row = row - 1;
        }
        return new HexCoord(Math.toIntExact(col - Math.floorDiv(row, 2)), Math.toIntExact(row));
    }

    /** West edge of the cell's flat sides. */
    public int minX(HexCoord cell) {
        long col = (long) cell.q() + Math.floorDiv(cell.r(), 2);
        return Math.toIntExact(originX + col * cellWidth + ((cell.r() & 1) == 1 ? cellWidth / 2 : 0));
    }

    /** North tip row of the cell. */
    public int minZ(HexCoord cell) { return Math.toIntExact(originZ + (long) cell.r() * rowPitch()); }

    /** Cell centre in block edge coordinates (the shared corner of four blocks). */
    public double centerX(HexCoord cell) { return minX(cell) + cellWidth / 2.0; }

    public double centerZ(HexCoord cell) { return minZ(cell) + cellDepth() / 2.0; }

    /** Bounding rectangle of one cell. */
    public PlotRect bounds(HexCoord cell) { return new PlotRect(minX(cell), minZ(cell), cellWidth, cellDepth()); }

    /** Bounding rectangle of several cells. */
    public PlotRect bounds(Collection<HexCoord> cells) {
        if (cells.isEmpty()) throw new IllegalArgumentException("No cells");
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (HexCoord c : cells) {
            minX = Math.min(minX, minX(c)); minZ = Math.min(minZ, minZ(c));
            maxX = Math.max(maxX, minX(c) + cellWidth); maxZ = Math.max(maxZ, minZ(c) + cellDepth());
        }
        return new PlotRect(minX, minZ, maxX - minX, maxZ - minZ);
    }

    /**
     * Half width of the cell's block row {@code localZ} (0 is the north tip row), counted from the
     * centre line. Tip rows are 2, 6, 10 ... blocks wide; body rows are the full width.
     */
    public int halfWidthAt(int localZ) {
        int tip = tipDepth(), depth = cellDepth();
        if (localZ < 0 || localZ >= depth) return 0;
        if (localZ < tip) return 2 * localZ + 1;
        if (localZ >= tip + bodyDepth) return 2 * (depth - 1 - localZ) + 1;
        return cellWidth / 2;
    }

    /** One row of a cell: block columns {@code [fromX, toX)} at {@code z}. */
    public record Span(int z, int fromX, int toX) {
        public int length() { return toX - fromX; }
    }

    /** The cell's block rows, north to south. */
    public List<Span> spans(HexCoord cell) {
        int x0 = minX(cell), z0 = minZ(cell), mid = x0 + cellWidth / 2;
        List<Span> out = new ArrayList<>(cellDepth());
        for (int lz = 0; lz < cellDepth(); lz++) {
            int hw = halfWidthAt(lz);
            out.add(new Span(z0 + lz, mid - hw, mid + hw));
        }
        return out;
    }

    /** True when block column (x, z) lies in {@code cell}, computed from the cell outline. */
    public boolean contains(HexCoord cell, int x, int z) {
        int lz = z - minZ(cell);
        int hw = halfWidthAt(lz);
        int mid = minX(cell) + cellWidth / 2;
        return hw > 0 && x >= mid - hw && x < mid + hw;
    }

    /** Cells that own at least one block column of any of {@code rects}. */
    public Set<HexCoord> cellsTouching(Collection<PlotRect> rects) {
        Set<HexCoord> out = new LinkedHashSet<>();
        for (PlotRect r : rects) out.addAll(cellsTouching(r));
        return out;
    }

    /** Cells that own at least one block column of {@code rect}. */
    public Set<HexCoord> cellsTouching(PlotRect rect) {
        Set<HexCoord> out = new LinkedHashSet<>();
        // A cell is never narrower than two blocks, so sampling every block is exact and still cheap for road strips.
        for (int z = rect.z(); z < rect.endZ(); z++) for (int x = rect.x(); x < rect.endX(); x++) out.add(cellAt(x, z));
        return out;
    }

    /** Unit block edge on the plot border: from (x, z) one block east if {@code alongX}, otherwise one block south. */
    public record Edge(int x, int z, boolean alongX) {}

    /**
     * Outer border of a set of cells as unit block edges, the shape the boundary aurora and claim grid
     * would draw. Edges shared by two cells of the same set are not part of the border.
     */
    public List<Edge> borderEdges(Collection<HexCoord> cells) {
        Set<HexCoord> set = Set.copyOf(cells);
        List<Edge> out = new ArrayList<>();
        for (HexCoord cell : cells) {
            for (Span s : spans(cell)) {
                if (!set.contains(cellAt(s.fromX() - 1, s.z()))) out.add(new Edge(s.fromX(), s.z(), false));
                if (!set.contains(cellAt(s.toX(), s.z()))) out.add(new Edge(s.toX(), s.z(), false));
                for (int x = s.fromX(); x < s.toX(); x++) {
                    if (!set.contains(cellAt(x, s.z() - 1))) out.add(new Edge(x, s.z(), true));
                    if (!set.contains(cellAt(x, s.z() + 1))) out.add(new Edge(x, s.z() + 1, true));
                }
            }
        }
        return out;
    }

    /** Six corner points of the ideal outline in block edge coordinates, clockwise from the north tip. */
    public double[][] corners(HexCoord cell) {
        double x0 = minX(cell), z0 = minZ(cell), w = cellWidth, t = tipDepth(), b = bodyDepth;
        return new double[][] {
            {x0 + w / 2, z0}, {x0 + w, z0 + t}, {x0 + w, z0 + t + b},
            {x0 + w / 2, z0 + 2 * t + b}, {x0, z0 + t + b}, {x0, z0 + t}};
    }
}
