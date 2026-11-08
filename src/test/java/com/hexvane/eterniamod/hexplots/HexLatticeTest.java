package com.hexvane.eterniamod.hexplots;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HexLatticeTest {
    private static final HexLattice L = HexLattice.DEFAULT;

    @Test void defaultCellDimensions() {
        assertEquals(32, L.cellWidth());
        assertEquals(8, L.tipDepth());
        assertEquals(36, L.cellDepth());
        assertEquals(28, L.rowPitch());
        assertEquals(896, L.cellArea());
        int sum = L.spans(HexCoord.ORIGIN).stream().mapToInt(HexLattice.Span::length).sum();
        assertEquals(L.cellArea(), sum);
    }

    @Test void tipsAreCleanTwoToOneStairs() {
        List<HexLattice.Span> spans = L.spans(HexCoord.ORIGIN);
        assertEquals(2, spans.get(0).length());
        assertEquals(2, spans.get(spans.size() - 1).length());
        for (int i = 1; i < L.tipDepth(); i++) {
            assertEquals(2, spans.get(i - 1).fromX() - spans.get(i).fromX(), "west stair row " + i);
            assertEquals(2, spans.get(i).toX() - spans.get(i - 1).toX(), "east stair row " + i);
        }
        for (int i = L.tipDepth(); i < L.tipDepth() + L.bodyDepth(); i++) assertEquals(32, spans.get(i).length());
        // Mirror symmetric north/south and east/west.
        for (int i = 0; i < spans.size(); i++) assertEquals(spans.get(i).length(), spans.get(spans.size() - 1 - i).length());
        for (HexLattice.Span s : spans) assertEquals(16, (s.fromX() + s.toX()) / 2);
    }

    @Test void everyBlockBelongsToExactlyOneCellAndCellAtAgreesWithOutline() {
        for (HexLattice lattice : List.of(L, new HexLattice(28, 16, 7, -3), new HexLattice(8, 1, 0, 0))) {
            Map<HexCoord, Integer> counts = new HashMap<>();
            for (int z = -150; z < 150; z++) {
                for (int x = -150; x < 150; x++) {
                    HexCoord c = lattice.cellAt(x, z);
                    assertTrue(lattice.contains(c, x, z), () -> "outline disagrees at " + c);
                    for (HexCoord n : c.neighbors()) assertFalse(lattice.contains(n, x, z), "two owners");
                    counts.merge(c, 1, Integer::sum);
                }
            }
            // Cells wholly inside the sampled square have the full area.
            for (var e : counts.entrySet()) {
                PlotRect b = lattice.bounds(e.getKey());
                if (b.x() >= -150 && b.z() >= -150 && b.endX() <= 150 && b.endZ() <= 150) assertEquals(lattice.cellArea(), e.getValue());
            }
        }
    }

    @Test void cellsAreExactTranslatesSoPlotsCanMove() {
        Set<String> shapes = new HashSet<>();
        for (HexCoord c : HexCoord.disk(new HexCoord(-3, 5), 3)) {
            StringBuilder sb = new StringBuilder();
            for (HexLattice.Span s : L.spans(c)) sb.append(s.z() - L.minZ(c)).append(',').append(s.fromX() - L.minX(c)).append(',').append(s.length()).append(';');
            shapes.add(sb.toString());
            for (HexLattice.Span s : L.spans(c)) for (int x = s.fromX(); x < s.toX(); x++) assertEquals(c, L.cellAt(x, s.z()));
        }
        assertEquals(1, shapes.size());
    }

    @Test void neighboursShareAnEdgeAndNothingElseTouches() {
        HexCoord o = HexCoord.ORIGIN;
        int own = L.borderEdges(List.of(o)).size();
        // 40 flat side edges, 32 stair risers, and 32 blocks of tread facing north plus 32 facing south.
        assertEquals(136, own, "perimeter of one cell");
        for (HexCoord n : o.neighbors()) {
            int pair = L.borderEdges(List.of(o, n)).size();
            assertTrue(pair < 2 * own, "shared side for " + n);
            assertTrue(own * 2 - pair >= 2 * 16, "a real side, not a corner, for " + n);
        }
        HexCoord far = new HexCoord(2, -1);
        assertEquals(2 * own, L.borderEdges(List.of(o, far)).size());
    }

    @Test void directionsPointWhereTheirNamesSay() {
        HexCoord o = HexCoord.ORIGIN;
        double cx = L.centerX(o), cz = L.centerZ(o);
        String[] names = {"E", "NE", "NW", "W", "SW", "SE"};
        for (int i = 0; i < 6; i++) {
            HexCoord n = o.neighbor(i);
            double dx = L.centerX(n) - cx, dz = L.centerZ(n) - cz;
            String expected = names[i];
            assertEquals(expected.contains("E"), dx > 0, expected);
            assertEquals(expected.contains("W"), dx < 0, expected);
            assertEquals(expected.contains("S"), dz > 0, expected);
            assertEquals(expected.contains("N"), dz < 0, expected);
        }
    }

    @Test void negativeCoordinatesAndOriginOffset() {
        HexLattice shifted = new HexLattice(32, 20, 100, -40);
        assertEquals(HexCoord.ORIGIN, shifted.cellAt(116, -40));
        assertEquals(new PlotRect(100, -40, 32, 36), shifted.bounds(HexCoord.ORIGIN));
        HexCoord c = new HexCoord(-7, -9);
        for (HexLattice.Span s : shifted.spans(c)) assertEquals(c, shifted.cellAt(s.fromX(), s.z()));
    }

    @Test void roadStripTouchesTheCellsItCrosses() {
        Set<HexCoord> touched = L.cellsTouching(new PlotRect(0, 17, 96, 3));
        assertEquals(Set.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(2, 0)), touched);
    }

    @Test void rejectsUnstairableWidths() {
        assertThrows(IllegalArgumentException.class, () -> new HexLattice(30, 20, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new HexLattice(32, 0, 0, 0));
    }

    @Test void cornersFollowTheBlockOutline() {
        double[][] k = L.corners(HexCoord.ORIGIN);
        assertArrayEquals(new double[] {16, 0}, k[0]);
        assertArrayEquals(new double[] {32, 8}, k[1]);
        assertArrayEquals(new double[] {16, 36}, k[3]);
    }
}
