package com.hexvane.eterniamod.hexplots;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The land of one hex plot: the lattice it was cut from plus its cells, kept sorted in
 * {@link HexCoord#READING_ORDER}. Immutable and safe on any thread.
 *
 * <p>Saved form: {@link #latticeId()} (for example {@code hex:32:20:0:0}) and {@link #text()} ({@code q:r;q:r;...}).
 * The plot's bounding rectangle is always derived from the cells ({@link #bounds()}); it is kept on records only as
 * a fast reject and for code that still thinks in rectangles.
 */
public final class PlotCells {
    /** Hard ceiling on cells per plot, above every data cap; guards saved data against corruption. */
    public static final int MAX_CELLS = 64;

    private final HexLattice lattice;
    private final List<HexCoord> cells;
    private final Set<HexCoord> set;

    private PlotCells(HexLattice lattice, Collection<HexCoord> cells) {
        this.lattice = Objects.requireNonNull(lattice, "lattice");
        TreeSet<HexCoord> sorted = new TreeSet<>(HexCoord.READING_ORDER);
        for (HexCoord c : cells) sorted.add(Objects.requireNonNull(c, "cell"));
        if (sorted.isEmpty()) throw new IllegalArgumentException("A plot needs at least one cell");
        if (sorted.size() > MAX_CELLS) throw new IllegalArgumentException("A plot has at most " + MAX_CELLS + " cells");
        this.cells = List.copyOf(sorted);
        this.set = Set.copyOf(sorted);
    }

    public static PlotCells of(HexLattice lattice, Collection<HexCoord> cells) { return new PlotCells(lattice, cells); }

    /** Reads the saved pair written by {@link #latticeId()} and {@link #text()}. */
    public static PlotCells parse(String latticeId, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("No cells");
        List<HexCoord> out = new ArrayList<>();
        for (String part : text.split(";")) if (!part.isBlank()) out.add(HexCoord.parse(part));
        return new PlotCells(HexLattice.parseId(latticeId), out);
    }

    public HexLattice lattice() { return lattice; }

    public String latticeId() { return lattice.id(); }

    /** Cells in reading order (north row first, then west to east). */
    public List<HexCoord> cells() { return cells; }

    public Set<HexCoord> asSet() { return set; }

    public int size() { return cells.size(); }

    public boolean contains(HexCoord cell) { return set.contains(cell); }

    /** True when block column (x, z) lies in one of the cells. */
    public boolean containsColumn(int x, int z) { return set.contains(lattice.cellAt(x, z)); }

    /** Bounding rectangle of all cells, in block columns. */
    public PlotRect bounds() { return lattice.bounds(cells); }

    /** This plot with one more cell. */
    public PlotCells with(HexCoord cell) {
        List<HexCoord> next = new ArrayList<>(cells);
        next.add(cell);
        return new PlotCells(lattice, next);
    }

    /** Same cells moved by a whole number of cells (cells are exact translates of each other). */
    public PlotCells translate(HexCoord offset) {
        return new PlotCells(lattice, cells.stream().map(c -> c.plus(offset)).toList());
    }

    /** Saved text form, {@code q:r;q:r;...}, in reading order. */
    public String text() {
        StringBuilder out = new StringBuilder();
        for (HexCoord c : cells) {
            if (!out.isEmpty()) out.append(';');
            out.append(c);
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlotCells other && lattice.equals(other.lattice) && cells.equals(other.cells);
    }

    @Override
    public int hashCode() { return 31 * lattice.hashCode() + cells.hashCode(); }

    @Override
    public String toString() { return latticeId() + "/" + text(); }
}
