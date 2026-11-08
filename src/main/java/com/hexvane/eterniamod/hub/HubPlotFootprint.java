package com.hexvane.eterniamod.hub;

import com.google.gson.annotations.SerializedName;
import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.PlotRect;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class HubPlotFootprint {
    @SerializedName("minX")
    private int minX;

    @SerializedName("minY")
    private int minY;

    @SerializedName("minZ")
    private int minZ;

    @SerializedName("maxX")
    private int maxX;

    @SerializedName("maxY")
    private int maxY;

    @SerializedName("maxZ")
    private int maxZ;

    /** Y coordinate where the plot was created; outline is centered on this (± half visual height). */
    @SerializedName("visualCenterY")
    @Nullable
    private Integer visualCenterY;

    /** Hex plots only: lattice id the cells belong to ({@code hex:32:20:0:0}). Null for rectangle plots. */
    @SerializedName("lattice")
    @Nullable
    private String lattice;

    /** Hex plots only: the plot's cells as {@code q:r;q:r;...}. The min/max box above is derived from them. */
    @SerializedName("cells")
    @Nullable
    private String cells;

    @Nullable
    private transient PlotCells parsedCells;

    public HubPlotFootprint() {}

    public HubPlotFootprint(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this(minX, minY, minZ, maxX, maxY, maxZ, null);
    }

    public HubPlotFootprint(
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        @Nullable Integer visualCenterY
    ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.visualCenterY = visualCenterY;
    }

    @Nonnull
    public static HubPlotFootprint forCreate(
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int creationCenterY
    ) {
        int half = EterniaModConstants.HUB_PLOT_VISUAL_HEIGHT / 2;
        int visualMinY = creationCenterY - half;
        int visualMaxY = creationCenterY + half - 1;
        return new HubPlotFootprint(minX, visualMinY, minZ, maxX, visualMaxY, maxZ, creationCenterY);
    }

    /**
     * A hex plot footprint: the bounding box comes from the cells, the visual height from the ground the plot was
     * claimed on. Saved with {@code lattice} and {@code cells}; older readers simply see the box.
     */
    @Nonnull
    public static HubPlotFootprint forCells(@Nonnull PlotCells plotCells, int creationCenterY) {
        PlotRect box = plotCells.bounds();
        HubPlotFootprint footprint = forCreate(box.x(), box.endX() - 1, box.z(), box.endZ() - 1, creationCenterY);
        footprint.lattice = plotCells.latticeId();
        footprint.cells = plotCells.text();
        footprint.parsedCells = plotCells;
        return footprint;
    }

    /** Same plot height and centre, new cells (expansion). Rectangle footprints cannot gain cells. */
    @Nonnull
    public HubPlotFootprint withCells(@Nonnull PlotCells plotCells) {
        return forCells(plotCells, resolveVisualCenterY());
    }

    /** The plot's cells, or null for a rectangle plot. Fails loudly on corrupt saved cells rather than guessing. */
    @Nullable
    public PlotCells getCells() {
        if (cells == null || lattice == null) return null;
        PlotCells parsed = parsedCells;
        if (parsed == null) {
            try {
                parsed = PlotCells.parse(lattice, cells);
            } catch (RuntimeException corrupt) {
                throw new IllegalStateException("Plot cells are unreadable: " + lattice + " / " + cells, corrupt);
            }
            parsedCells = parsed;
        }
        return parsed;
    }

    public boolean isHex() {
        return cells != null && lattice != null;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public int resolveVisualCenterY() {
        if (visualCenterY != null) {
            return visualCenterY;
        }
        return (minY + maxY) / 2;
    }

    public int getVisualMinY() {
        return resolveVisualCenterY() - EterniaModConstants.HUB_PLOT_VISUAL_HEIGHT / 2;
    }

    public int getVisualMaxY() {
        return resolveVisualCenterY() + EterniaModConstants.HUB_PLOT_VISUAL_HEIGHT / 2 - 1;
    }

    public boolean containsBlock(int x, int y, int z) {
        return containsHorizontal(x, z) && y >= getVisualMinY() && y <= getVisualMaxY();
    }

    /** True when column (x, z) belongs to the plot: inside the box, and for hex plots inside one of its cells. */
    public boolean containsHorizontal(int x, int z) {
        if (!(x >= minX && x <= maxX && z >= minZ && z <= maxZ)) return false;
        PlotCells plotCells = getCells();
        return plotCells == null || plotCells.containsColumn(x, z);
    }

    /** Box test only, ignoring cells. For hex plots use {@link #containsHorizontal} or {@code HexFit}. */
    public boolean containsFootprint(@Nonnull HubPlotFootprint other) {
        return other.minX >= minX
            && other.maxX <= maxX
            && other.minY >= minY
            && other.maxY <= maxY
            && other.minZ >= minZ
            && other.maxZ <= maxZ;
    }

    /** True when {@code other} fits inside this plot's X/Z bounds (Y is unrestricted). Box only; hex plots need {@code HexFit}. */
    public boolean containsFootprintHorizontal(@Nonnull HubPlotFootprint other) {
        return other.minX >= minX && other.maxX <= maxX && other.minZ >= minZ && other.maxZ <= maxZ;
    }

    public int horizontalCenterX() {
        return (int) Math.round((minX + maxX) / 2.0);
    }

    public int horizontalCenterZ() {
        return (int) Math.round((minZ + maxZ) / 2.0);
    }
}
