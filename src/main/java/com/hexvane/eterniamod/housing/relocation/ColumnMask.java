package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.hexplots.PlotCells;

/**
 * Which block columns a snapshot may read or write. Rectangle plots use {@link #ALL} (the whole box); hex plots use
 * their cells, because the bounding boxes of neighbouring hex plots overlap where their tips interleave, and a box
 * snapshot would otherwise take, overwrite or verify a neighbour's corner.
 *
 * <p>{@code cells} are in the world frame the snapshot was taken in; {@code dx, dz} carry the mask with the snapshot
 * when it is applied somewhere else (cells are exact whole block translates, so the mask moves with its content).
 * Pure and immutable.
 */
public record ColumnMask(PlotCells cells, int dx, int dz) {
    public static final ColumnMask ALL = new ColumnMask(null, 0, 0);

    /** The cells of a plot, or {@link #ALL} for a rectangle plot ({@code cells == null}). */
    public static ColumnMask of(PlotCells cells) { return cells == null ? ALL : new ColumnMask(cells, 0, 0); }

    public boolean all() { return cells == null; }

    /** True when world column (x, z) is inside the mask. */
    public boolean contains(int x, int z) { return cells == null || cells.containsColumn(Math.subtractExact(x, dx), Math.subtractExact(z, dz)); }

    /** The same mask carried by a further block offset. */
    public ColumnMask shifted(int byX, int byZ) { return cells == null ? ALL : new ColumnMask(cells, Math.addExact(dx, byX), Math.addExact(dz, byZ)); }
}
