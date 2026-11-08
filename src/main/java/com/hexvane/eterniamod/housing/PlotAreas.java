package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * A plot's land as rectangles, for code that thinks in {@link PlotRect} (route planners, paving, service placement).
 * A rectangle plot is its box. A hex plot is the exact block rows of its cells, so the corners of its box, which
 * belong to neighbouring hexagons, are never treated as part of the plot. Pure, any thread.
 */
public final class PlotAreas {
    private PlotAreas() {}

    /** The plot's columns as rectangles: one box, or one strip per block row of each cell. */
    public static List<PlotRect> rects(HubPlotRecord plot) {
        PlotCells cells = plot.getCells();
        return cells == null ? List.of(NativeHousingChecks.rect(plot.getFootprint())) : rects(cells);
    }

    /** A durable claim location's columns as rectangles. */
    public static List<PlotRect> rects(HousingService.ClaimLocation location) {
        return location.cells() == null ? List.of(location.box()) : rects(location.cells());
    }

    public static List<PlotRect> rects(PlotCells cells) {
        List<PlotRect> out = new ArrayList<>(cells.size() * cells.lattice().cellDepth());
        for (HexCoord cell : cells.cells())
            for (var span : cells.lattice().spans(cell)) if (span.length() > 0) out.add(new PlotRect(span.fromX(), span.z(), span.length(), 1));
        return List.copyOf(out);
    }

    /**
     * Where a player arrives on a plot's edge: two and a half blocks inside its north west corner, and the area a
     * landing search may use. For a hex plot the box corner belongs to a neighbour, so this is inside the plot's
     * first hexagon (north most row, then west), just below its north tip on the straight west side.
     */
    public record Arrival(double x, double z, PlotRect area) {}

    public static Arrival arrival(HubPlotRecord plot) {
        PlotCells cells = plot.getCells();
        if (cells == null) {
            var fp = plot.getFootprint();
            return new Arrival(fp.getMinX() + 2.5, fp.getMinZ() + 2.5, NativeHousingChecks.rect(fp));
        }
        var lattice = cells.lattice();
        HexCoord first = cells.cells().getFirst();
        return new Arrival(lattice.minX(first) + 2.5, lattice.minZ(first) + lattice.tipDepth() + 2.5, new PlotRect(lattice.minX(first), lattice.minZ(first) + lattice.tipDepth(), lattice.cellWidth(), lattice.bodyDepth()));
    }

    /** True when {@code rect} shares at least one block column with the plot (cell exact for hex plots). */
    public static boolean overlaps(HubPlotRecord plot, PlotRect rect) {
        PlotRect box = NativeHousingChecks.rect(plot.getFootprint());
        if (!box.overlaps(rect)) return false;
        PlotCells cells = plot.getCells();
        return cells == null || overlaps(cells, rect);
    }

    /** True when {@code rect} shares at least one block column with the location (cell exact for hex plots). */
    public static boolean overlaps(HousingService.ClaimLocation location, PlotRect rect) {
        if (!location.box().overlaps(rect)) return false;
        return location.cells() == null || overlaps(location.cells(), rect);
    }

    public static boolean overlaps(PlotCells cells, PlotRect rect) {
        PlotRect box = cells.bounds();
        int minX = Math.max(box.x(), rect.x()), minZ = Math.max(box.z(), rect.z());
        int maxX = Math.min(box.endX(), rect.endX()), maxZ = Math.min(box.endZ(), rect.endZ());
        for (int z = minZ; z < maxZ; z++) for (int x = minX; x < maxX; x++) if (cells.containsColumn(x, z)) return true;
        return false;
    }
}
