package com.hexvane.eterniamod.boundary;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexShape;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HexBorderGeometryTest {
    private static final HexLattice L = HexLattice.DEFAULT;

    private static PlotCells cells(HexShape shape) { return PlotCells.of(L, shape.cells()); }

    /** Budget numbers quoted in notes/hex-borders.md. */
    @Test void borderEdgesAndLineCountsPerShape() {
        // Unit edges = perimeter in blocks; staircase lines = merged runs; straight = hexagon sides facing outside.
        record Expect(HexShape shape, int edges, int staircase, int straight) {}
        for (Expect e : List.of(new Expect(HexShape.SINGLE, 136, 68, 6), new Expect(HexShape.TRIANGLE, 272, 136, 12),
                new Expect(HexShape.DIAMOND, 320, 168, 14), new Expect(HexShape.FLOWER, 408, 204, 18))) {
            var c = cells(e.shape());
            var edges = HexBorderIndex.edgesOf(c);
            assertEquals(e.edges(), edges.size(), e.shape() + " edges");
            var stairs = BoundaryGeometry.staircase(edges);
            assertEquals(e.staircase(), stairs.size(), e.shape() + " staircase lines");
            assertEquals(edges.size(), stairs.stream().mapToDouble(BoundaryGeometry.Line::length).sum(), 1e-9, "runs cover every edge once");
            assertEquals(e.straight(), BoundaryGeometry.straight(L, c.cells()).size(), e.shape() + " straight lines");
        }
    }

    @Test void edgesAreTheOuterBorderOnlyAndFollowTheStaircase() {
        var flower = HexBorderIndex.edgesOf(cells(HexShape.FLOWER));
        // No edge of the flower's centre cell is on the outer border.
        var centre = L.spans(HexCoord.ORIGIN);
        for (var e : flower) {
            int bx = e.x(), bz = e.z();
            int ix = e.alongX() ? bx : bx - 1, iz = e.alongX() ? bz - 1 : bz;
            boolean a = HexShape.FLOWER.cells().contains(L.cellAt(bx, bz)), b = HexShape.FLOWER.cells().contains(L.cellAt(ix, iz));
            assertNotEquals(a, b, "every edge separates inside from outside");
        }
        assertFalse(centre.isEmpty());
        // Slanted sides are 2:1 stairs: in a single cell, every horizontal run on a slanted side is two blocks.
        var single = BoundaryGeometry.staircase(HexBorderIndex.edgesOf(cells(HexShape.SINGLE)));
        long twoBlockSteps = single.stream().filter(l -> l.z1() == l.z2() && l.length() == 2).count();
        // Each slanted side steps two blocks per row; the two tips are two blocks wide; the last step into the body is one.
        assertEquals(30, twoBlockSteps);
        assertEquals(4, single.stream().filter(l -> l.z1() == l.z2() && l.length() == 1).count());
    }

    @Test void straightOutlineUsesTheSixCornersAndSkipsInnerSides() {
        var lines = BoundaryGeometry.straight(L, List.of(HexCoord.ORIGIN));
        double[][] c = L.corners(HexCoord.ORIGIN);
        for (int i = 0; i < 6; i++) {
            var l = lines.get(i);
            assertEquals(c[i][0], l.x1()); assertEquals(c[i][1], l.z1());
            assertEquals(c[(i + 1) % 6][0], l.x2()); assertEquals(c[(i + 1) % 6][1], l.z2());
        }
        // Two neighbours: the shared side is drawn by neither.
        var pair = BoundaryGeometry.straight(L, List.of(HexCoord.ORIGIN, new HexCoord(1, 0)));
        assertEquals(10, pair.size());
        assertTrue(pair.stream().noneMatch(l -> l.x1() == 32 && l.x2() == 32));
    }

    @Test void nearbyEdgesAreBudgetedDedupedAndOwnColourWins() {
        var mine = HexBorderIndex.edgesOf(PlotCells.of(L, List.of(HexCoord.ORIGIN)));
        var theirs = HexBorderIndex.edgesOf(PlotCells.of(L, List.of(new HexCoord(1, 0))));
        // Stand on the shared east side of the origin cell (x = 32).
        var samples = BoundaryGeometry.nearbyEdges(List.of(
            new BoundaryGeometry.StyledBorder(theirs, 70, HexBorderRelation.OTHER_PLAYER.ordinal()),
            new BoundaryGeometry.StyledBorder(mine, 70, HexBorderRelation.MINE.ordinal())), 32, 18, 8, 1, 32);
        assertEquals(16, samples.size(), "the flat side within 8 blocks");
        assertEquals(samples.size(), new HashSet<>(samples.stream().map(s -> s.x() + ":" + s.z()).toList()).size());
        assertTrue(samples.stream().filter(s -> s.x() == 32).allMatch(s -> s.style() == HexBorderRelation.MINE.ordinal()));
        assertTrue(samples.stream().allMatch(s -> s.distance() < 8));
        // Stride 2 keeps every second block only.
        var sparse = BoundaryGeometry.nearbyEdges(List.of(new BoundaryGeometry.StyledBorder(mine, 70, 0)), 32, 18, 12, 2, 100);
        assertTrue(sparse.stream().allMatch(s -> Math.floorMod((int) Math.floor(s.horizontal() ? s.x() : s.z()), 2) == 0));
    }

    @Test void indexReturnsOnlyPlotsNearTheViewerAndSharesShapes() {
        HexBorderIndex.clearShapeCache();
        var a = new HexBorderIndex.PlotEntry(UUID.randomUUID(), PlotCells.of(L, List.of(HexCoord.ORIGIN)), 70);
        var far = new HexBorderIndex.PlotEntry(UUID.randomUUID(), PlotCells.of(L, List.of(new HexCoord(10, 0))), 70);
        var index = new HexBorderIndex(List.of(a, far));
        assertEquals(2, index.plotCount());
        assertEquals(272, index.edgeCount());
        Set<UUID> near = new HashSet<>();
        index.near(-100, 18, 8).forEach(n -> near.add(n.plot().plotId()));
        assertEquals(Set.of(), near, "nothing near open land");
        index.near(31, 18, 8).forEach(n -> near.add(n.plot().plotId()));
        assertEquals(Set.of(a.plotId()), near);
        assertEquals(2, HexBorderIndex.cachedShapes());
        new HexBorderIndex(List.of(a, far));
        assertEquals(2, HexBorderIndex.cachedShapes(), "rebuilding reuses each plot's edges");
    }
}
