package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what one viewer sees of hex plot borders around them: which edges glow and in which colour. Pure, so the
 * colour rules are tested without a server; {@link BoundaryFogSystem} turns the samples into particles.
 */
public final class HexBorderPlanner {
    private HexBorderPlanner() {}

    /** The relation of a plot to a viewer (never {@link HexBorderRelation#CLAIMABLE}). */
    public static HexBorderRelation relation(HexLandView view, HexLandView.PlotInfo plot, HexLandView.Viewer viewer) {
        HexCoord any = plot.cells().cells().getFirst();
        HexBorderRelation relation = HexBorderRelation.of(view.cell(any, viewer, null).state());
        return relation == null ? HexBorderRelation.OTHER_PLAYER : relation;
    }

    /**
     * Plot border emitters near (x, z) for {@code viewer}: every unit edge closer than
     * {@link BoundaryGeometry#FAR_DISTANCE}, coloured by relation, at most {@link BoundaryGeometry#MAX_EMITTERS_PER_VIEWER}.
     */
    public static List<BoundaryGeometry.Sample> plotSamples(HexLandView view, HexBorderIndex index, HexLandView.Viewer viewer, double x, double z) {
        List<BoundaryGeometry.StyledBorder> borders = new ArrayList<>();
        for (HexBorderIndex.Nearby near : index.near(x, z, BoundaryGeometry.FAR_DISTANCE)) {
            HexLandView.PlotInfo plot = view.plots().get(near.plot().plotId());
            if (plot == null) continue;
            borders.add(new BoundaryGeometry.StyledBorder(near.edges(), near.plot().groundY(), relation(view, plot, viewer).ordinal()));
        }
        return BoundaryGeometry.nearbyEdges(borders, x, z, BoundaryGeometry.FAR_DISTANCE, 1, BoundaryGeometry.MAX_EMITTERS_PER_VIEWER);
    }

    /**
     * The hexagons to outline as claimable: {@code focus} when a flow set one, otherwise (deed in hand) the free
     * frontage cells in the viewer's cell and the ring around it. Street cells are never outlined.
     */
    public static Set<HexCoord> claimableCells(HexLandView view, HexLandView.Viewer viewer, double x, double z, Set<HexCoord> focus, boolean holdingDeed) {
        Set<HexCoord> out = new LinkedHashSet<>();
        if (focus != null) {
            for (HexCoord cell : focus) if (!view.isStreet(cell) && view.plotAt(cell) == null) out.add(cell);
            return out;
        }
        if (!holdingDeed) return out;
        HexLandView.Viewer claiming = new HexLandView.Viewer(viewer.player(), viewer.guild(), HexClaimRules.Kind.PUBLIC);
        HexCoord here = view.lattice().cellAt((int) Math.floor(x), (int) Math.floor(z));
        for (HexCoord cell : HexCoord.disk(here, 1))
            if (view.cell(cell, claiming, null).state() == HexLandView.CellState.FRONTAGE_FREE) out.add(cell);
        return out;
    }

    /**
     * Faint claimable outline emitters: each claimable hexagon's own outline, every {@link BoundaryGeometry#CLAIMABLE_STRIDE}
     * blocks, out to {@link BoundaryGeometry#CLAIMABLE_DISTANCE}, without edges a plot border already draws.
     */
    public static List<BoundaryGeometry.Sample> claimableSamples(HexLandView view, Collection<HexCoord> cells, int groundY,
                                                                 List<BoundaryGeometry.Sample> plotSamples, double x, double z) {
        if (cells.isEmpty()) return List.of();
        List<BoundaryGeometry.StyledBorder> borders = new ArrayList<>();
        for (HexCoord cell : cells)
            borders.add(new BoundaryGeometry.StyledBorder(HexBorderIndex.edgesOf(PlotCells.of(view.lattice(), List.of(cell))), groundY, HexBorderRelation.CLAIMABLE.ordinal()));
        Set<Long> taken = new java.util.HashSet<>();
        for (BoundaryGeometry.Sample s : plotSamples) taken.add(pointKey(s.x(), s.z()));
        List<BoundaryGeometry.Sample> out = new ArrayList<>();
        for (BoundaryGeometry.Sample s : BoundaryGeometry.nearbyEdges(borders, x, z, BoundaryGeometry.CLAIMABLE_DISTANCE,
            BoundaryGeometry.CLAIMABLE_STRIDE, BoundaryGeometry.MAX_CLAIMABLE_EMITTERS_PER_VIEWER + plotSamples.size())) {
            // The two block columns either side of the edge; a side that is a plot belongs to that plot's border.
            int bx = (int) Math.floor(s.x()), bz = (int) Math.floor(s.z());
            int ox = s.horizontal() ? bx : bx - 1, oz = s.horizontal() ? bz - 1 : bz;
            if (view.plotAtColumn(bx, bz) != null || view.plotAtColumn(ox, oz) != null) continue;
            if (taken.contains(pointKey(s.x(), s.z()))) continue;
            out.add(s);
            if (out.size() >= BoundaryGeometry.MAX_CLAIMABLE_EMITTERS_PER_VIEWER) break;
        }
        return out;
    }

    private static long pointKey(double x, double z) { return ((long) Math.floor(x * 2) << 32) ^ ((long) Math.floor(z * 2) & 0xffffffffL); }
}
