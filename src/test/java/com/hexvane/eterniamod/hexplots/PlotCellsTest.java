package com.hexvane.eterniamod.hexplots;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.housing.PlotRect;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlotCellsTest {
    private static final HexLattice L = new HexLattice(32, 20, 7, -3);

    @Test void textRoundTripIsSortedAndStable() {
        var cells = PlotCells.of(L, List.of(new HexCoord(1, 1), new HexCoord(0, 0), new HexCoord(1, 0)));
        assertEquals("0:0;1:0;1:1", cells.text());
        assertEquals("hex:32:20:7:-3", cells.latticeId());
        var back = PlotCells.parse(cells.latticeId(), cells.text());
        assertEquals(cells, back);
        assertEquals(cells.hashCode(), back.hashCode());
        assertEquals(L, HexLattice.parseId(L.id()));
        assertEquals(PlotCells.of(L, List.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(1, 1))), cells, "order of input does not matter");
    }

    @Test void badTextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlotCells.parse("hex:32:20:0:0", ""));
        assertThrows(IllegalArgumentException.class, () -> PlotCells.parse("rect", "0:0"));
        assertThrows(IllegalArgumentException.class, () -> PlotCells.parse("hex:30:20:0:0", "0:0"), "width must be a multiple of 4");
        assertThrows(RuntimeException.class, () -> PlotCells.parse("hex:32:20:0:0", "0;1"));
        assertThrows(IllegalArgumentException.class, () -> PlotCells.of(L, List.of()));
    }

    @Test void boundsAndColumnsComeFromTheCells() {
        var one = PlotCells.of(L, List.of(HexCoord.ORIGIN));
        assertEquals(new PlotRect(7, -3, 32, 36), one.bounds());
        // Centre column is inside; the bounding box corner is a neighbour's tip.
        assertTrue(one.containsColumn(7 + 16, -3 + 18));
        assertFalse(one.containsColumn(7, -3));
        var grown = one.with(new HexCoord(1, 0));
        assertEquals(2, grown.size());
        assertEquals(new PlotRect(7, -3, 64, 36), grown.bounds());
        assertEquals(Set.of(new HexCoord(2, 1), new HexCoord(3, 1)), grown.translate(new HexCoord(2, 1)).asSet());
    }

    @Test void latticeHelpers() {
        HexLattice moved = L.centredOn(100, 200);
        assertEquals(moved.cellAt(100, 200), moved.cellAt(100, 200));
        HexCoord here = moved.cellAt(100, 200);
        assertEquals(100.0, moved.centerX(here), 1.0);
        assertEquals(200.0, moved.centerZ(here), 1.0);
        assertEquals(new HexLattice(32, 20, 0, 0), L.withOrigin(0, 0));
    }

    @Test void cellIndexIsExactAndRefusesSharedCells() {
        var index = new PlotCellIndex();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        index.put(a, PlotCells.of(L, List.of(HexCoord.ORIGIN, new HexCoord(1, 0))));
        index.put(b, PlotCells.of(L, List.of(new HexCoord(0, 1))));
        assertEquals(a, index.plotAtColumn(7 + 16, -3 + 18));
        assertEquals(a, index.plotAt(new HexCoord(1, 0)));
        assertEquals(b, index.plotAt(new HexCoord(0, 1)));
        assertNull(index.plotAtColumn(7, -3), "box corner belongs to a neighbour");
        assertThrows(IllegalStateException.class, () -> index.put(b, PlotCells.of(L, List.of(new HexCoord(1, 0)))));
        assertThrows(IllegalStateException.class, () -> index.put(UUID.randomUUID(), PlotCells.of(HexLattice.DEFAULT, List.of(new HexCoord(9, 9)))));
        index.put(a, PlotCells.of(L, List.of(HexCoord.ORIGIN))); // shrink (replace) works
        assertNull(index.plotAt(new HexCoord(1, 0)));
        index.remove(a);
        index.remove(b);
        assertTrue(index.isEmpty());
        assertNull(index.lattice());
    }

    @Test void settingsComeFromDataWithSafeFallbacks() throws Exception {
        HexPlotSettings shipped = HexPlotSettings.load(getClass().getClassLoader());
        assertEquals(HexPlotSettings.DEFAULTS, shipped, "the shipped JSON matches the accepted plan");
        assertEquals(HexShape.TRIANGLE, shipped.startShape(HexClaimRules.Kind.PUBLIC, true));
        assertEquals(HexShape.FLOWER, shipped.startShape(HexClaimRules.Kind.GUILD_ESTATE, false));
        assertEquals(7, shipped.maxCells(HexClaimRules.Kind.PUBLIC));
        assertEquals(19, shipped.maxCells(HexClaimRules.Kind.GUILD_ESTATE));
        assertEquals(3000, shipped.expansionPrice(3));
        var odd = HexPlotSettings.parse(new StringReader("""
            {"StartShapes":{"Public":"Triangle","GuildEstate":"Nope"},"MaxCells":{"Personal":2},
             "ExpansionPrice":{"Coins":50,"CoinsPerOwnedCell":-5},"Lattice":{"CellWidth":30},"PerCell":{"Props":"many"}}"""));
        assertEquals(HexShape.TRIANGLE, odd.startShape(HexClaimRules.Kind.PUBLIC, false));
        assertEquals(HexShape.FLOWER, odd.startShape(HexClaimRules.Kind.GUILD_ESTATE, false), "unknown shape falls back");
        assertEquals(3, odd.maxCells(HexClaimRules.Kind.PUBLIC), "a cap never drops below the start shape");
        assertEquals(50, odd.expansionPrice(10), "negative per cell price clamps to zero");
        assertEquals(32, odd.latticeWidth(), "invalid lattice falls back");
        assertEquals(24, odd.propsPerCell());
        assertEquals(Map.copyOf(HexPlotSettings.DEFAULTS.startShapes()).size(), odd.startShapes().size());
    }
}
