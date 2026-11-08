package com.hexvane.eterniamod.pathtool;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoadLatticePreviewTest {
    private static final HexLattice L = HexLattice.DEFAULT;

    private static List<int[]> straightRoad(double z, int fromX, int toX, int width) {
        List<int[]> out = new ArrayList<>();
        int top = (int) Math.floor(z) - width / 2;
        for (int x = fromX; x < toX; x++) for (int dz = 0; dz < width; dz++) out.add(new int[] {x, top + dz});
        return out;
    }

    @Test void aRoadAlongARowMiddleUsesOneRowAndOnASeamTwo() {
        double middle = RoadLatticePreview.rowMiddle(L, 1);
        var centred = RoadLatticePreview.of(L, Set.of(), Set.of(), straightRoad(middle, 16, 112, 5), 64, middle, 1);
        assertTrue(centred.cells().stream().filter(c -> c.tone() == RoadLatticePreview.Tone.NEW_STREET).allMatch(c -> c.cell().r() == 1), "one row");
        assertEquals(3, centred.newStreet());
        // Along the seam between rows 1 and 2 (the tips interleave there) the road eats both rows.
        double seam = L.minZ(new HexCoord(0, 2)) + 4;
        var seamRoad = RoadLatticePreview.of(L, Set.of(), Set.of(), straightRoad(seam, 16, 112, 5), 64, seam, 1);
        assertTrue(seamRoad.newStreet() > centred.newStreet());
        assertEquals("Hexagon grid: turns 3 hexagons into street.", centred.summary());
    }

    @Test void existingStreetIsNotCountedAndClaimedHexagonsAreFlagged() {
        double middle = RoadLatticePreview.rowMiddle(L, 1);
        var road = straightRoad(middle, 16, 112, 5);
        var touched = new java.util.LinkedHashSet<HexCoord>();
        for (int[] c : road) touched.add(L.cellAt(c[0], c[1]));
        var first = touched.iterator().next();
        var already = RoadLatticePreview.of(L, touched, Set.of(), road, 64, middle, 1);
        assertEquals(0, already.newStreet());
        assertEquals("Hexagon grid: no new street.", already.summary());
        var clash = RoadLatticePreview.of(L, Set.of(), Set.of(first), road, 64, middle, 1);
        assertEquals(1, clash.claimedHits());
        assertTrue(clash.cells().contains(new RoadLatticePreview.Cell(first, RoadLatticePreview.Tone.CLAIMED)));
        assertEquals("Crosses a claimed hexagon. Move the road.", clash.summary());
    }

    @Test void theGridAroundTheBuilderIsAlwaysDrawnWithinTheBudget() {
        var empty = RoadLatticePreview.of(L, Set.of(), Set.of(new HexCoord(0, 0)), List.of(), 16, 18, 2);
        assertEquals(19, empty.cells().size());
        assertTrue(empty.cells().contains(new RoadLatticePreview.Cell(new HexCoord(0, 0), RoadLatticePreview.Tone.PLOT)));
        assertEquals(5, empty.rowMiddles().size());
        var long_ = RoadLatticePreview.of(L, Set.of(), Set.of(), straightRoad(RoadLatticePreview.rowMiddle(L, 0), -2000, 2000, 3), 0, 18, 2);
        assertEquals(RoadLatticePreview.MAX_CELLS, long_.cells().size());
    }
}
