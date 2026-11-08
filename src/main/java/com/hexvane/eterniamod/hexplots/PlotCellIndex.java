package com.hexvane.eterniamod.hexplots;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which plot owns each cell of one world: an O(1) lookup used by protection and {@code HexLand}. Not thread safe;
 * {@code HubPlotManager} owns one and touches it only on the world thread.
 *
 * <p>A world has one lattice. Plots saved on another lattice (which setup forbids while claims exist) are refused
 * with an exception so a mismatch is found at load, never silently ignored by protection.
 */
public final class PlotCellIndex {
    private final Map<HexCoord, UUID> owners = new HashMap<>();
    private final Map<UUID, PlotCells> byPlot = new HashMap<>();
    private HexLattice lattice;

    /** The lattice of the indexed plots, or null while no hex plot is indexed. */
    public HexLattice lattice() { return lattice; }

    /** Adds or replaces a plot's cells. Throws if any cell already belongs to a different plot. */
    public void put(UUID plotId, PlotCells cells) {
        if (lattice != null && !lattice.equals(cells.lattice()) && !(byPlot.size() == 1 && byPlot.containsKey(plotId)))
            throw new IllegalStateException("Plot " + plotId + " uses lattice " + cells.latticeId() + " but this world uses " + lattice.id());
        for (HexCoord c : cells.cells()) {
            UUID other = owners.get(c);
            if (other != null && !other.equals(plotId)) throw new IllegalStateException("Cell " + c + " belongs to both " + other + " and " + plotId);
        }
        remove(plotId);
        byPlot.put(plotId, cells);
        lattice = cells.lattice();
        for (HexCoord c : cells.cells()) owners.put(c, plotId);
    }

    /** Removes a plot's cells; no effect when the plot is not indexed. Returns the removed cells or null. */
    public PlotCells remove(UUID plotId) {
        PlotCells old = byPlot.remove(plotId);
        if (old != null) for (HexCoord c : old.cells()) owners.remove(c, plotId);
        if (byPlot.isEmpty()) lattice = null;
        return old;
    }

    public void clear() {
        owners.clear();
        byPlot.clear();
        lattice = null;
    }

    /** Plot owning {@code cell}, or null. */
    public UUID plotAt(HexCoord cell) { return owners.get(cell); }

    /** Plot owning block column (x, z), or null (also null while no hex plot is indexed). */
    public UUID plotAtColumn(int x, int z) {
        return lattice == null ? null : owners.get(lattice.cellAt(x, z));
    }

    public PlotCells cellsOf(UUID plotId) { return byPlot.get(plotId); }

    /** Read-only view of every claimed cell and its plot. */
    public Map<HexCoord, UUID> owners() { return Collections.unmodifiableMap(owners); }

    public Set<UUID> plots() { return Collections.unmodifiableSet(byPlot.keySet()); }

    public boolean isEmpty() { return byPlot.isEmpty(); }
}
