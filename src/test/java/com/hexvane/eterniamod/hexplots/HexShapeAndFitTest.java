package com.hexvane.eterniamod.hexplots;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HexShapeAndFitTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final int K = HexLattice.HOUSE_SETBACK;

    @Test void coordinatesBasics() {
        HexCoord o = HexCoord.ORIGIN;
        assertEquals(6, Set.copyOf(o.neighbors()).size());
        for (HexCoord n : o.neighbors()) assertEquals(1, o.distance(n));
        assertEquals(3, new HexCoord(2, 1).distance(new HexCoord(-1, 1)));
        assertEquals(18, HexCoord.ring(o, 3).size());
        assertEquals(Set.copyOf(HexCoord.ring(o, 3)).size(), 18);
        for (HexCoord c : HexCoord.ring(new HexCoord(4, -2), 3)) assertEquals(3, c.distance(new HexCoord(4, -2)));
        assertEquals(19, Set.copyOf(HexCoord.disk(o, 2)).size());
        HexCoord c = new HexCoord(3, -5);
        HexCoord turned = c;
        for (int i = 0; i < 6; i++) { turned = turned.rotateClockwise(); assertEquals(c.distance(o), turned.distance(o)); }
        assertEquals(c, turned);
        assertEquals(c, c.mirror().mirror());
        assertEquals(new HexCoord(0, 1), new HexCoord(1, 0).rotateClockwise(), "east turns to south east");
        assertEquals(c, HexCoord.parse(c.toString()));
        assertThrows(IllegalArgumentException.class, () -> HexCoord.parse("3"));
    }

    @Test void catalogVariantsAreConnectedAndDistinct() {
        assertEquals(1, HexShape.SINGLE.variants().size());
        assertEquals(2, HexShape.TRIANGLE.variants().size());
        assertEquals(3, HexShape.DIAMOND.variants().size());
        assertEquals(1, HexShape.FLOWER.variants().size());
        for (HexShape s : HexShape.values())
            for (Set<HexCoord> v : s.variants()) { assertEquals(s.size(), v.size()); assertTrue(HexContiguity.isConnected(v)); }
    }

    @Test void todaysHouseSizesFitTheCatalogWithTodaysSetback() {
        assertEquals(14, HexFit.largestSquareInEveryVariant(L, HexShape.SINGLE, K));
        assertEquals(22, HexFit.largestSquareInEveryVariant(L, HexShape.TRIANGLE, K));
        assertEquals(54, HexFit.largestSquareInEveryVariant(L, HexShape.FLOWER, K));
        for (HexShape s : HexShape.values()) assertTrue(HexFit.largestSquare(L, s.cells(), K) >= s.houseSide(), s.name());
        // The diamond only holds the 38 keep in some turns; that is why the keep uses the flower.
        assertEquals(38, HexFit.largestSquare(L, HexShape.DIAMOND.cells(), K));
        assertTrue(HexFit.largestSquareInEveryVariant(L, HexShape.DIAMOND, K) < 38);
    }

    @Test void shapeForHouseSide() {
        assertEquals(HexShape.SINGLE, HexShape.forHouseSide(9));
        assertEquals(HexShape.SINGLE, HexShape.forHouseSide(14));
        assertEquals(HexShape.TRIANGLE, HexShape.forHouseSide(15));
        assertEquals(HexShape.TRIANGLE, HexShape.forHouseSide(22));
        assertEquals(HexShape.FLOWER, HexShape.forHouseSide(38));
        assertThrows(IllegalArgumentException.class, () -> HexShape.forHouseSide(39));
    }

    @Test void fitHonoursTheOuterBorderSetbackOnly() {
        Set<HexCoord> one = Set.of(HexCoord.ORIGIN);
        PlotRect house = HexFit.nearestFit(L, one, 14, 14, K).orElseThrow();
        assertTrue(HexFit.fits(L, one, house, K));
        assertFalse(HexFit.fits(L, one, new PlotRect(house.x(), house.z(), 15, 15), K));
        assertTrue(HexFit.nearestFit(L, one, 15, 15, K).isEmpty());
        // The shared side inside a plot needs no setback: a 22 house straddles all three triangle cells.
        Set<HexCoord> tri = HexShape.TRIANGLE.cells();
        PlotRect manor = HexFit.nearestFit(L, tri, 22, 22, K).orElseThrow();
        assertEquals(tri, HexFit.cellsUnder(L, manor));
        assertFalse(HexFit.fits(L, Set.of(HexCoord.ORIGIN, new HexCoord(1, 0)), manor, K));
        assertEquals(0, HexFit.largestSquare(L, Set.of(), K));
    }

    @Test void keepSitsInTheMiddleOfTheFlowerWithRoomToNudge() {
        Set<HexCoord> flower = HexShape.FLOWER.cells();
        PlotRect keep = HexFit.nearestFit(L, flower, 38, 38, K).orElseThrow();
        double cx = keep.x() + 19, cz = keep.z() + 19;
        assertTrue(Math.abs(cx - L.centerX(HexCoord.ORIGIN)) <= 1 && Math.abs(cz - L.centerZ(HexCoord.ORIGIN)) <= 1);
        int nudges = 0;
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++)
            if (HexFit.fits(L, flower, new PlotRect(keep.x() + dx, keep.z() + dz, 38, 38), K)) nudges++;
        assertTrue(nudges >= 25, "keep can move at least two blocks each way, got " + nudges);
    }

    @Test void placementsCoveringIncludeTheCell() {
        HexCoord target = new HexCoord(5, 2);
        List<Set<HexCoord>> placements = HexShape.TRIANGLE.placementsCovering(target);
        assertEquals(6, new HashSet<>(placements).size(), "six triangles meet at a cell");
        for (Set<HexCoord> p : placements) assertTrue(p.contains(target));
    }
}
