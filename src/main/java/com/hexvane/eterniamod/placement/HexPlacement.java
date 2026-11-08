package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexFit;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.HexShape;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3i;

/**
 * Placement rules on hex plots: houses and additions fit the plot's cells with the setback from its outer edges
 * ({@link HexFit}), houses need their shape's cell count (1, 3 or 7), and decorations and generators are limited per
 * hexagon ({@link HexClaimRules#placeProp} / {@link HexClaimRules#placeGenerator}).
 *
 * <p>Problems are placement codes the pages turn into text with {@link PlacementText}; codes that carry a number end
 * in {@code :<count>} ({@code "hexCellFull:24"}). The pure checks here take plain values so tests need no world.
 */
public final class HexPlacement {
    /** Code: the footprint leaves the plot's hexagons. */
    public static final String OUTSIDE = "outsidePlot";
    /** Code: inside the plot but closer than the setback to its outer edge. */
    public static final String EDGE = "hexEdge";
    /** Code: the plot has fewer hexagons than this house's shape. */
    public static final String HOUSE_SHAPE = "hexHouseShape";
    /** Code: the hexagon under the decoration holds its limit already. */
    public static final String CELL_FULL = "hexCellFull";
    /** Code: the hexagon under the generator holds its limit already. */
    public static final String GENERATORS_FULL = "hexGeneratorsFull";
    /** Code: within five blocks of a road. */
    public static final String ROAD = "roadSetback";

    /** Footprint centre offsets from a prefab's anchor, per prefab and turn, as twice the offset (exact halves). */
    private static final Map<String, int[]> CENTRES = new ConcurrentHashMap<>();

    private HexPlacement() {}

    /** Prop category that counts against the per hexagon generator limit instead of the decoration limit. */
    public static boolean isGenerator(PropDefinition def) { return "generator".equals(def.getCategory()); }

    /** Additions belong to the house; everything else placed in the yard is a decoration. */
    public static boolean isDecoration(PropDefinition def) { return !"addition".equals(def.getCategory()) && !isGenerator(def); }

    /** The rectangle of block columns a footprint covers. */
    public static PlotRect rect(HubPlotFootprint fp) {
        return new PlotRect(fp.getMinX(), fp.getMinZ(), fp.getMaxX() - fp.getMinX() + 1, fp.getMaxZ() - fp.getMinZ() + 1);
    }

    /** Smallest plot shape that holds a house of this footprint in every turn (cottage 1, manor 3, keep 7). */
    public static HexShape shapeFor(PlotRect house) {
        int side = Math.max(house.width(), house.depth());
        try {
            return HexShape.forHouseSide(side);
        } catch (IllegalArgumentException tooBig) {
            return HexShape.FLOWER;
        }
    }

    /**
     * A house or addition footprint on a hex plot, or null when it fits. Order matters for the player: outside the
     * plot, then too close to the edge, then too close to a road.
     */
    public static String structure(PlotCells cells, PlotRect building, List<PlotRect> roads, int setback) {
        if (!HexFit.fits(cells.lattice(), cells.cells(), building, 0)) return OUTSIDE;
        if (!HexFit.fits(cells.lattice(), cells.cells(), building, setback)) return EDGE + ":" + setback;
        if (roads.stream().anyMatch(r -> r.overlaps(building) || r.gap(building) < 5)) return ROAD;
        return null;
    }

    /** A house: needs its shape's number of hexagons first, then {@link #structure}. */
    public static String house(PlotCells cells, PlotRect building, List<PlotRect> roads, int setback) {
        HexShape needed = shapeFor(building);
        if (cells.size() < needed.size()) return HOUSE_SHAPE + ":" + needed.size();
        return structure(cells, building, roads, setback);
    }

    /** A decoration or generator footprint: inside the hexagons (no setback), then the per hexagon limit. */
    public static String prop(PlotCells cells, PlotRect footprint, HexCoord standing, boolean generator,
                              Map<HexCoord, Integer> perCell, HexPlotSettings settings) {
        if (!HexFit.fits(cells.lattice(), cells.cells(), footprint, 0)) return OUTSIDE;
        HexClaimRules.Result r = generator
            ? HexClaimRules.placeGenerator(cells.asSet(), perCell, standing, settings)
            : HexClaimRules.placeProp(cells.asSet(), perCell, standing, settings);
        if (r.valid()) return null;
        if (r.reason() == HexClaimRules.Reason.OUTSIDE) return OUTSIDE;
        return generator ? GENERATORS_FULL + ":" + settings.generatorsPerCell() : CELL_FULL + ":" + settings.propsPerCell();
    }

    /** The hexagon a footprint stands on: the one under its centre. */
    public static HexCoord standingCell(HexLattice lattice, PlotRect footprint) {
        return lattice.cellAt(footprint.x() + footprint.width() / 2, footprint.z() + footprint.depth() / 2);
    }

    /** The hexagon a placed prop stands on (centre of its blocks), using its catalog prefab. */
    public static HexCoord standingCell(HexLattice lattice, HubPlotProp prop, PropDefinition def) {
        int[] c = centreOffset(def, prop.resolveRotationYaw());
        int x = prop.getAnchorX() + Math.floorDiv(c[0], 2), z = prop.getAnchorZ() + Math.floorDiv(c[1], 2);
        return lattice.cellAt(x, z);
    }

    /** Placed decorations (or generators) per hexagon of a plot. Unknown props count as decorations where they are anchored. */
    public static Map<HexCoord, Integer> countPerCell(EterniaModPlugin plugin, HubPlotRecord plot, boolean generators) {
        PlotCells cells = plot.getCells();
        Map<HexCoord, Integer> out = new HashMap<>();
        if (cells == null) return out;
        for (HubPlotProp prop : plot.getProps()) {
            PropDefinition def = plugin.getPropCatalog().get(prop.getPropId());
            HexCoord cell;
            if (def == null) {
                if (generators) continue;
                cell = cells.lattice().cellAt(prop.getAnchorX(), prop.getAnchorZ());
            } else {
                if (generators ? !isGenerator(def) : !isDecoration(def)) continue;
                cell = standingCell(cells.lattice(), prop, def);
            }
            out.merge(cell, 1, Integer::sum);
        }
        return out;
    }

    /** Decorations still allowed on {@code cell} of a hex plot (never below zero). */
    public static int decorationsLeft(EterniaModPlugin plugin, HubPlotRecord plot, HexCoord cell) {
        return Math.max(0, HexPlotSettings.current().propsPerCell() - countPerCell(plugin, plot, false).getOrDefault(cell, 0));
    }

    /** Blocks the prefab covers when anchored at {@code anchor} with {@code yaw}, or null when its prefab is missing. */
    public static HubPlotFootprint footprint(String prefabPath, Vector3i anchor, Rotation yaw) {
        var buffer = PrefabResolveUtil.resolvePrefabBuffer(prefabPath);
        return buffer == null ? null : PlotFootprintUtil.computeFootprint(anchor, yaw, buffer);
    }

    private static int[] centreOffset(PropDefinition def, Rotation yaw) {
        return CENTRES.computeIfAbsent(def.getPrefabPath() + "|" + yaw.name(), key -> {
            HubPlotFootprint fp = footprint(def.getPrefabPath(), new Vector3i(), yaw);
            return fp == null ? new int[] {0, 0} : new int[] {fp.getMinX() + fp.getMaxX() + 1, fp.getMinZ() + fp.getMaxZ() + 1};
        });
    }

    /** Forget cached prefab centres (catalog reload). */
    public static void clearCaches() { CENTRES.clear(); }
}
