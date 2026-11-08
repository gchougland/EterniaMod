package com.hexvane.eterniamod.placement;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexFit;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.HexShape;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.PlotRect;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** House, addition and decoration rules on hex plots (port of the rect setback and containment tests). */
class HexPlacementTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final int SETBACK = 5;
    private static final PlotCells ONE = PlotCells.of(L, List.of(HexCoord.ORIGIN));
    private static final PlotCells MANOR = PlotCells.of(L, HexShape.TRIANGLE.cells());
    private static final PlotCells KEEP = PlotCells.of(L, HexShape.FLOWER.cells());

    private static PlotRect fit(PlotCells cells, int side) {
        return HexFit.nearestFit(L, cells.cells(), side, side, SETBACK).orElseThrow(() -> new AssertionError("no fit for " + side));
    }

    @Test void housesNeedTheirShapeOfHexagons() {
        assertEquals(HexShape.SINGLE, HexPlacement.shapeFor(new PlotRect(0, 0, 14, 12)));
        assertEquals(HexShape.TRIANGLE, HexPlacement.shapeFor(new PlotRect(0, 0, 22, 18)));
        assertEquals(HexShape.FLOWER, HexPlacement.shapeFor(new PlotRect(0, 0, 38, 38)));
        assertEquals(HexShape.FLOWER, HexPlacement.shapeFor(new PlotRect(0, 0, 60, 60)), "anything larger asks for the largest shape");

        assertNull(HexPlacement.house(ONE, fit(ONE, 14), List.of(), SETBACK), "a cottage fits one hexagon");
        assertEquals("hexHouseShape:3", HexPlacement.house(ONE, new PlotRect(0, 0, 22, 22), List.of(), SETBACK), "a manor never fits one hexagon");
        assertNull(HexPlacement.house(MANOR, fit(MANOR, 22), List.of(), SETBACK), "a manor fits the triangle");
        assertEquals("hexHouseShape:7", HexPlacement.house(MANOR, new PlotRect(0, 0, 38, 38), List.of(), SETBACK));
        assertNull(HexPlacement.house(KEEP, fit(KEEP, 38), List.of(), SETBACK), "a keep fits the flower");
    }

    @Test void edgeSetbackIsMeasuredFromTheOuterBorderOnly() {
        PlotRect centred = fit(ONE, 14);
        assertNull(HexPlacement.structure(ONE, centred, List.of(), SETBACK));
        PlotRect nudged = new PlotRect(centred.x() + 3, centred.z(), 14, 14);
        assertEquals("hexEdge:5", HexPlacement.structure(ONE, nudged, List.of(), SETBACK), "inside the hexagon but too close to its edge");
        PlotRect out = new PlotRect(centred.x() + 20, centred.z(), 14, 14);
        assertEquals("outsidePlot", HexPlacement.structure(ONE, out, List.of(), SETBACK));

        // Across an inner cell edge of the same plot no setback applies: the manor straddles three cells.
        PlotRect manor = fit(MANOR, 22);
        assertTrue(HexFit.cellsUnder(L, manor).size() > 1, "the manor stands on more than one hexagon");
        assertNull(HexPlacement.structure(MANOR, manor, List.of(), SETBACK));
    }

    @Test void housesKeepFiveBlocksFromRoads() {
        PlotRect house = fit(ONE, 14);
        var road = new PlotRect(house.endX() + 2, house.z() - 10, 2, 40);
        assertEquals("roadSetback", HexPlacement.structure(ONE, house, List.of(road), SETBACK));
        var far = new PlotRect(house.endX() + 5, house.z() - 10, 2, 40);
        assertNull(HexPlacement.structure(ONE, house, List.of(far), SETBACK));
    }

    @Test void decorationsAreLimitedPerHexagon() {
        var s = HexPlotSettings.DEFAULTS;
        PlotCells two = PlotCells.of(L, List.of(HexCoord.ORIGIN, new HexCoord(1, 0)));
        int cx = (int) L.centerX(HexCoord.ORIGIN), cz = (int) L.centerZ(HexCoord.ORIGIN);
        PlotRect lamp = new PlotRect(cx, cz, 1, 2);
        HexCoord standing = HexPlacement.standingCell(L, lamp);
        assertEquals(HexCoord.ORIGIN, standing);

        Map<HexCoord, Integer> counts = new HashMap<>();
        assertNull(HexPlacement.prop(two, lamp, standing, false, counts, s));
        counts.put(HexCoord.ORIGIN, s.propsPerCell() - 1);
        assertNull(HexPlacement.prop(two, lamp, standing, false, counts, s), "the 24th decoration still fits");
        counts.put(HexCoord.ORIGIN, s.propsPerCell());
        assertEquals("hexCellFull:24", HexPlacement.prop(two, lamp, standing, false, counts, s));
        PlotRect east = new PlotRect((int) L.centerX(new HexCoord(1, 0)), cz, 1, 1);
        assertNull(HexPlacement.prop(two, east, HexPlacement.standingCell(L, east), false, counts, s), "the next hexagon has room");

        Map<HexCoord, Integer> generators = new HashMap<>(Map.of(HexCoord.ORIGIN, s.generatorsPerCell()));
        assertEquals("hexGeneratorsFull:2", HexPlacement.prop(two, lamp, standing, true, generators, s));
        assertNull(HexPlacement.prop(two, lamp, standing, false, Map.of(), s), "generators and decorations are counted apart");
    }

    @Test void decorationsMustStayOnThePlotsHexagons() {
        var box = ONE.bounds();
        // The box corner belongs to a neighbour cell: never plot land, whatever the bounding box says.
        PlotRect corner = new PlotRect(box.x(), box.z(), 1, 1);
        assertFalse(ONE.containsColumn(box.x(), box.z()));
        assertEquals("outsidePlot", HexPlacement.prop(ONE, corner, L.cellAt(box.x(), box.z()), false, Map.of(), HexPlotSettings.DEFAULTS));
    }

    @Test void nearestFitPrefersTheMiddleOfThePlot() {
        PlotRect spot = fit(KEEP, 14);
        double cx = spot.x() + 7, cz = spot.z() + 7;
        assertEquals(L.centerX(HexCoord.ORIGIN), cx, 1.0);
        assertEquals(L.centerZ(HexCoord.ORIGIN), cz, 1.0);
        assertEquals(Set.of(HexCoord.ORIGIN), HexFit.cellsUnder(L, spot));
    }
}
