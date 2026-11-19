package com.hexvane.eterniamod.claimmap;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.TestLattices;
import org.junit.jupiter.api.Test;

/** Worlds keep their own hexagon size, so the map draws every hexagon the same width on screen. */
class ClaimMapSizeTest {
    @Test void bothHexagonSizesFitTheMapAndEveryButtonPicksItsOwnHexagon() {
        for (HexLattice lattice : new HexLattice[] {TestLattices.CLASSIC, HexLattice.DEFAULT, HexLattice.DEFAULT.withOrigin(-37, 91)}) {
            var g = ClaimMapGeometry.centredOn(lattice, 1234.5, -987.5);
            assertEquals(880, g.pixelWidth(), lattice.id());
            assertTrue(g.pixelHeight() >= 770 && g.pixelHeight() <= 780, lattice.id() + " is " + g.pixelHeight() + " tall");
            int bottom = 0;
            for (var slot : g.slots()) {
                bottom = Math.max(bottom, slot.top() + slot.height());
                assertTrue(slot.left() >= 0 && slot.left() + slot.width() <= g.pixelWidth(), "buttons stay on the map");
                if (slot.kind() == ClaimMapGeometry.SlotKind.FULL)
                    assertEquals(slot.cell(), g.cellAtPixel(slot.left() + slot.width() / 2.0, slot.top() + slot.height() / 2.0), "the middle of a button is its hexagon");
                var centre = g.centre(slot.cell());
                if (slot.kind() == ClaimMapGeometry.SlotKind.FULL) {
                    assertEquals(slot.left() + slot.width() / 2.0, centre.x(), 1.0, "the hexagon is drawn under its button");
                    assertEquals(slot.top() + slot.height() / 2.0, centre.y(), 1.0, "the hexagon is drawn under its button");
                }
            }
            assertEquals(g.pixelHeight(), bottom, "the buttons reach the bottom of the picture");
        }
        assertEquals(2.5, ClaimMapGeometry.centredOn(TestLattices.CLASSIC, 0, 0).scale(), 1e-9, "old worlds look as before");
    }
}
