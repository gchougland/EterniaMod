package com.hexvane.eterniamod.hexplots.terraform;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hypixel.hytale.builtin.buildertools.tooloperations.SculptOperation;
import org.junit.jupiter.api.Test;

class TerraformFieldTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final HexCoord CELL = new HexCoord(0, 0);
    /** The sculpt brush's Normal heightmap kernel, built here because SculptOperation's own copy needs a running server. */
    private static final SculptOperation.Kernel BRUSH = new SculptOperation.Kernel(-1, 1.15, new int[] {0, 1, 2, 1, 0, 1, 3, 4, 3, 1, 2, 4, 2, 4, 2, 1, 3, 4, 3, 1, 0, 1, 2, 1, 0});

    /** A grid round one cell on a slope (height rises 1 per 2 blocks east), with the cell as own land and free land round it. */
    private static TerraformField.Grid slope(int margin) {
        var box = L.bounds(CELL);
        var g = new TerraformField.Grid(box.x() - margin, box.z() - margin, box.width() + 2 * margin, box.depth() + 2 * margin);
        for (int i = 0; i < g.size(); i++) {
            int x = g.x(i), z = g.z(i);
            g.role[i] = L.contains(CELL, x, z) ? TerraformField.OWN : TerraformField.FREE;
            g.natural[i] = 70 + Math.floorDiv(x - box.x(), 2);
        }
        return g;
    }

    @Test
    void plateauFlattensOnlyTheHexagonWithCrispEdges() {
        var g = slope(10);
        int[] t = TerraformField.plateau(g, 75);
        int own = 0;
        for (int i = 0; i < g.size(); i++) {
            if (g.role[i] == TerraformField.OWN) { assertEquals(75, t[i]); own++; }
            else assertEquals(TerraformField.UNKNOWN, t[i], "plateau never touches land outside the hexagon");
        }
        assertEquals(L.cellArea(), own);
        // A crisp step: the first column outside the east flat keeps its natural height.
        var box = L.bounds(CELL);
        int zMid = box.z() + L.cellDepth() / 2;
        assertEquals(75, t[g.index(box.endX() - 1, zMid)]);
        assertEquals(TerraformField.UNKNOWN, t[g.index(box.endX(), zMid)]);
    }

    @Test
    void smoothKeepsThePlotFlatAndBlendsTheSkirtBackToNature() {
        var g = slope(12);
        int level = 78, skirt = 8;
        int[] t = TerraformField.smooth(g, level, skirt, 5, 6, BRUSH);
        var box = L.bounds(CELL);
        for (int i = 0; i < g.size(); i++) if (g.role[i] == TerraformField.OWN) assertEquals(level, t[i], "no fixed neighbour, so the whole hexagon is flat");
        int zMid = box.z() + L.cellDepth() / 2;
        // West of the hexagon the land is lower than the level: the skirt steps down toward it without cliffs.
        int prev = t[g.index(box.x(), zMid)];
        for (int x = box.x() - 1; x >= box.x() - skirt; x--) {
            int h = t[g.index(x, zMid)];
            assertNotEquals(TerraformField.UNKNOWN, h);
            assertTrue(h <= prev, "skirt never rises away from the plot");
            assertTrue(prev - h <= 3, "skirt step at x=" + x + " is " + (prev - h));
            prev = h;
        }
        // Past the skirt nothing changes.
        assertEquals(TerraformField.UNKNOWN, t[g.index(box.x() - skirt - 1, zMid)]);
        // Every planned height lies between the level and the natural ground round it.
        for (int i = 0; i < g.size(); i++) if (t[i] != TerraformField.UNKNOWN) assertTrue(t[i] >= Math.min(level, g.natural[i]) - 1 && t[i] <= Math.max(level, g.natural[i]) + 1, "height " + t[i] + " outside the level and the ground at " + g.x(i));
    }

    @Test
    void smoothEasesTheEdgeTowardAFixedNeighbourButNeverTouchesIt() {
        var g = slope(12);
        var box = L.bounds(CELL);
        // East of the hexagon is another plot (fixed) at 90.
        for (int i = 0; i < g.size(); i++) if (g.x(i) >= box.endX()) { g.role[i] = TerraformField.FIXED; g.natural[i] = 90; }
        int level = 78, band = 5;
        int[] t = TerraformField.smooth(g, level, 8, band, 6, BRUSH);
        int zMid = box.z() + L.cellDepth() / 2;
        for (int i = 0; i < g.size(); i++) if (g.role[i] == TerraformField.FIXED) assertEquals(TerraformField.UNKNOWN, t[i], "fixed land is never planned");
        assertEquals(level, t[g.index(box.x() + L.cellWidth() / 2, zMid)], "the middle stays at the level");
        int edge = t[g.index(box.endX() - 1, zMid)];
        assertTrue(edge > level && edge <= 90, "edge eases up toward the neighbour: " + edge);
        for (int x = box.endX() - 1 - band - 1; x >= box.x() + 3; x--) assertEquals(level, t[g.index(x, zMid)], "flat beyond the band at x=" + x);
    }

    @Test
    void freeLandNearARoadIsNeverReshaped() {
        var g = slope(12);
        var box = L.bounds(CELL);
        boolean[] road = new boolean[g.size()];
        for (int i = 0; i < g.size(); i++) if (g.x(i) < box.x() - 6) { road[i] = true; g.role[i] = TerraformField.FIXED; g.natural[i] = 72; }
        g.fixNear(road, 3);
        int[] t = TerraformField.smooth(g, 78, 8, 5, 6, BRUSH);
        int zMid = box.z() + L.cellDepth() / 2;
        for (int x = box.x() - 9; x < box.x() - 3; x++) assertEquals(TerraformField.UNKNOWN, t[g.index(x, zMid)], "x=" + x + " is road or road clearance");
        assertNotEquals(TerraformField.UNKNOWN, t[g.index(box.x() - 2, zMid)]);
    }

    @Test
    void brushStepMatchesTheSculptBrushThresholds() {
        assertEquals(0, TerraformField.brushStep(0.3));
        assertEquals(1, TerraformField.brushStep(0.4));
        assertEquals(2, TerraformField.brushStep(2.7));
        assertEquals(0, TerraformField.brushStep(-0.7));
        assertEquals(-1, TerraformField.brushStep(-0.8));
        assertEquals(-3, TerraformField.brushStep(-3.2));
    }
}
