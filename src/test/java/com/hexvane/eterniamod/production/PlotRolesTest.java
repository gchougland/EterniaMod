package com.hexvane.eterniamod.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.production.PlotRoles.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlotRolesTest {
    private static final HexCoord HOME = new HexCoord(0, 0), EAST = new HexCoord(1, 0), WEST = new HexCoord(-1, 0), FAR = new HexCoord(2, 0);
    private static final int MAX_HOUSE = 4;

    private static PlotCells cells(HexCoord... cells) { return PlotCells.of(HexLattice.DEFAULT, List.of(cells)); }

    private static PlotRoles fresh(HexCoord... cells) { return PlotRoles.resolve(cells(cells), "", Set.of(), null); }

    @Test
    void aNewPlotIsAllHouseHexagons() {
        var one = fresh(HOME);
        assertEquals(1, one.count(Role.HOUSE));
        assertEquals(0, one.count(Role.PRODUCTION));
        var three = fresh(HOME, EAST, WEST);
        assertEquals(3, three.count(Role.HOUSE));
    }

    @Test
    void theFirstAddedHexagonIsTheFreeProductionOne() {
        var plot = fresh(HOME);
        assertNull(plot.canAddAs(EAST, Role.PRODUCTION, MAX_HOUSE));
        assertEquals("roleProductionFirst", plot.canAddAs(EAST, Role.HOUSE, MAX_HOUSE));
        assertEquals(Role.PRODUCTION, plot.suggested(EAST, MAX_HOUSE));
        var grown = PlotRoles.resolve(cells(HOME, EAST), plot.text(), Set.of(), null);
        assertEquals(Role.HOUSE, grown.of(HOME));
        assertEquals(Role.PRODUCTION, grown.of(EAST));
    }

    @Test
    void anAddedHexagonBecomesWhatThePlayerChoseAndStaysThat() {
        var two = PlotRoles.resolve(cells(HOME, EAST), fresh(HOME).text(), Set.of(), null);
        assertNull(two.canAddAs(WEST, Role.HOUSE, MAX_HOUSE));
        var three = PlotRoles.resolve(cells(HOME, EAST, WEST), two.text(), Set.of(), Map.of(WEST, Role.HOUSE));
        assertEquals(Role.HOUSE, three.of(WEST));
        assertEquals(Role.PRODUCTION, three.of(EAST));
        // Read again later, with nothing chosen: every hexagon keeps its kind.
        var again = PlotRoles.resolve(cells(HOME, EAST, WEST), three.text(), Set.of(), null);
        assertEquals(three.all(), again.all());
    }

    @Test
    void aHouseHexagonMustTouchAHouseHexagon() {
        var two = PlotRoles.resolve(cells(HOME, EAST), fresh(HOME).text(), Set.of(), null);
        // FAR touches the production hexagon at EAST only.
        assertEquals("roleHouseTouch", two.canAddAs(FAR, Role.HOUSE, MAX_HOUSE));
        assertNull(two.canAddAs(FAR, Role.PRODUCTION, MAX_HOUSE));
    }

    @Test
    void theTwoKindsHaveTheirOwnLimits() {
        // A house hexagon with six production hexagons in a row east of it.
        var list = new ArrayList<HexCoord>();
        for (int q = 0; q <= 6; q++) list.add(new HexCoord(q, 0));
        var full = PlotRoles.resolve(PlotCells.of(HexLattice.DEFAULT, list), fresh(HOME).text(), Set.of(), null);
        assertEquals(6, full.count(Role.PRODUCTION));
        assertEquals(1, full.count(Role.HOUSE));
        assertEquals("roleProductionFull", full.canAddAs(new HexCoord(7, 0), Role.PRODUCTION, MAX_HOUSE));
        // Production being full does not stop a house hexagon, and it is what gets suggested.
        assertNull(full.canAddAs(WEST, Role.HOUSE, MAX_HOUSE));
        assertEquals(Role.HOUSE, full.suggested(WEST, MAX_HOUSE));
        assertEquals("roleHouseFull", full.canAddAs(WEST, Role.HOUSE, 1));
    }

    @Test
    void rolesFollowAPlotThatMoved() {
        var before = PlotRoles.resolve(cells(HOME, EAST, WEST), fresh(HOME).text(), Set.of(), Map.of(EAST, Role.PRODUCTION, WEST, Role.HOUSE));
        HexCoord shift = new HexCoord(10, -4);
        var after = PlotRoles.resolve(cells(HOME.plus(shift), EAST.plus(shift), WEST.plus(shift)), before.text(), Set.of(), null);
        for (HexCoord cell : List.of(HOME, EAST, WEST)) assertEquals(before.of(cell), after.of(cell.plus(shift)));
    }

    @Test
    void aPlotKeepsOneHexagonOfEachKind() {
        var two = PlotRoles.resolve(cells(HOME, EAST), fresh(HOME).text(), Set.of(), null);
        assertEquals("roleLastHouse", two.canSell(HOME));
        assertEquals("roleLastProduction", two.canSell(EAST));
        var three = PlotRoles.resolve(cells(HOME, EAST, FAR), two.text(), Set.of(), null);
        assertNull(three.canSell(FAR));
    }

    @Test
    void whereTheHouseStandsIsAlwaysAHouseHexagon() {
        var two = PlotRoles.resolve(cells(HOME, EAST), fresh(HOME).text(), Set.of(), null);
        var healed = PlotRoles.resolve(cells(HOME, EAST), two.text(), Set.of(EAST), null);
        assertEquals(Role.HOUSE, healed.of(EAST));
    }
}
