package com.hexvane.eterniamod.boundary;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A mixed street: road row r = 0, plots of all four relations to the viewer either side. */
class HexBorderPlannerTest {
    static final HexLattice L = HexLattice.DEFAULT;
    static final UUID ME = UUID.randomUUID(), NEIGHBOR = UUID.randomUUID(), FRIEND = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    static final UUID MY_GUILD = UUID.randomUUID(), RIVAL_GUILD = UUID.randomUUID();
    static final UUID P_MINE = UUID.randomUUID(), P_NEIGHBOR = UUID.randomUUID(), P_ESTATE = UUID.randomUUID(),
        P_MEMBER = UUID.randomUUID(), P_RIVAL = UUID.randomUUID();

    static HexLandView street() {
        Set<HexCoord> street = new HashSet<>();
        for (int q = -6; q <= 6; q++) street.add(new HexCoord(q, 0));
        List<HexLandView.PlotInfo> plots = new ArrayList<>();
        plots.add(new HexLandView.PlotInfo(P_MINE, HexLandView.OwnerKind.PLAYER, ME, null, cells(0, 1, 1, 1, 0, 2)));
        plots.add(new HexLandView.PlotInfo(P_NEIGHBOR, HexLandView.OwnerKind.PLAYER, NEIGHBOR, null, cells(2, 1)));
        plots.add(new HexLandView.PlotInfo(P_ESTATE, HexLandView.OwnerKind.GUILD, MY_GUILD, MY_GUILD, cells(-3, 1, -2, 1, -3, 2)));
        plots.add(new HexLandView.PlotInfo(P_MEMBER, HexLandView.OwnerKind.PLAYER, FRIEND, MY_GUILD, cells(-1, 1)));
        plots.add(new HexLandView.PlotInfo(P_RIVAL, HexLandView.OwnerKind.GUILD, RIVAL_GUILD, RIVAL_GUILD, cells(0, -1, 1, -1, 1, -2)));
        return new HexLandView("hub", L, street, Set.of(), Set.of(), plots);
    }

    static PlotCells cells(int... qr) {
        List<HexCoord> out = new ArrayList<>();
        for (int i = 0; i < qr.length; i += 2) out.add(new HexCoord(qr[i], qr[i + 1]));
        return PlotCells.of(L, out);
    }

    static final HexLandView.Viewer VIEWER = HexLandView.Viewer.browsing(ME, MY_GUILD);

    @Test void eachPlotGetsItsRelationAndPaletteColour() {
        var view = street();
        assertEquals(HexBorderRelation.MINE, HexBorderPlanner.relation(view, view.plots().get(P_MINE), VIEWER));
        assertEquals(HexBorderRelation.OTHER_PLAYER, HexBorderPlanner.relation(view, view.plots().get(P_NEIGHBOR), VIEWER));
        assertEquals(HexBorderRelation.MY_GUILD, HexBorderPlanner.relation(view, view.plots().get(P_ESTATE), VIEWER));
        assertEquals(HexBorderRelation.MY_GUILD, HexBorderPlanner.relation(view, view.plots().get(P_MEMBER), VIEWER));
        assertEquals(HexBorderRelation.OTHER_GUILD, HexBorderPlanner.relation(view, view.plots().get(P_RIVAL), VIEWER));
        // The same street seen by a stranger with no guild.
        var stranger = HexLandView.Viewer.browsing(STRANGER, null);
        assertEquals(HexBorderRelation.OTHER_PLAYER, HexBorderPlanner.relation(view, view.plots().get(P_MINE), stranger));
        assertEquals(HexBorderRelation.OTHER_GUILD, HexBorderPlanner.relation(view, view.plots().get(P_ESTATE), stranger));
        // Palette from the core, one colour each.
        assertEquals(0x2EC4B6, HexBorderRelation.MINE.color());
        assertEquals(0xE9B949, HexBorderRelation.MY_GUILD.color());
        assertEquals(0x8FB8DE, HexBorderRelation.OTHER_PLAYER.color());
        assertEquals(0x9B7ED9, HexBorderRelation.OTHER_GUILD.color());
        assertEquals(0x6CC56C, HexBorderRelation.CLAIMABLE.color());
        assertEquals(5, Set.of(HexBorderRelation.values()).stream().map(HexBorderRelation::color).distinct().count());
    }

    @Test void particleIdsFollowRelationAndStrength() {
        assertEquals("Eternia_Hex_Border_Mine", HexBorderRelation.MINE.particle(1));
        assertEquals("Eternia_Hex_Border_Guild_Soft", HexBorderRelation.MY_GUILD.particle(.5));
        assertEquals("Eternia_Hex_Border_Rival_Faint", HexBorderRelation.OTHER_GUILD.particle(.1));
        assertEquals("Eternia_Hex_Border_Neighbor", HexBorderRelation.OTHER_PLAYER.particle(.9));
        assertEquals("Eternia_Hex_Border_Claimable", HexBorderRelation.CLAIMABLE.particle(.1));
        assertNull(HexBorderRelation.of(HexLandView.CellState.STREET));
        assertNull(HexBorderRelation.of(HexLandView.CellState.FREE));
    }

    @Test void plotSamplesColourTheSharedSideWithTheViewersOwnPlot() {
        var view = street();
        var index = new HexBorderIndex(view.plots().values().stream().map(p -> new HexBorderIndex.PlotEntry(p.plotId(), p.cells(), 70)).toList());
        // The flat side between my (1,1) and the neighbour's (2,1).
        double sideX = L.minX(new HexCoord(2, 1)), midZ = L.centerZ(new HexCoord(2, 1));
        var samples = HexBorderPlanner.plotSamples(view, index, VIEWER, sideX, midZ);
        assertFalse(samples.isEmpty());
        assertTrue(samples.size() <= BoundaryGeometry.MAX_EMITTERS_PER_VIEWER);
        assertTrue(samples.stream().filter(s -> s.x() == sideX).allMatch(s -> s.style() == HexBorderRelation.MINE.ordinal()));
        // Standing deep in a road cell, nothing glows.
        assertTrue(HexBorderPlanner.plotSamples(view, index, VIEWER, L.centerX(new HexCoord(5, 0)), L.centerZ(new HexCoord(5, 0))).isEmpty());
    }

    @Test void claimableCellsAreFreeFrontageOnlyWithTheDeedAndNeverStreets() {
        var view = street();
        HexCoord free = new HexCoord(3, 1);
        double x = L.centerX(free), z = L.centerZ(free);
        assertTrue(HexBorderPlanner.claimableCells(view, VIEWER, x, z, null, false).isEmpty(), "no deed, no outlines");
        Set<HexCoord> cells = HexBorderPlanner.claimableCells(view, VIEWER, x, z, null, true);
        assertTrue(cells.contains(free));
        for (HexCoord c : cells) {
            assertFalse(view.isStreet(c));
            assertNull(view.plotAt(c));
            assertTrue(view.isFrontage(c));
        }
        // (3,2) is free but not beside the road: not a start cell.
        assertFalse(cells.contains(new HexCoord(3, 2)));
        // A flow's focus wins and still drops streets and owned cells.
        var focus = HexBorderPlanner.claimableCells(view, VIEWER, x, z, Set.of(new HexCoord(3, 2), new HexCoord(3, 0), new HexCoord(2, 1)), false);
        assertEquals(Set.of(new HexCoord(3, 2)), focus);
    }

    @Test void claimableOutlinesAreSparseAndNeverOnAPlotBorder() {
        var view = street();
        HexCoord free = new HexCoord(3, 1);
        double x = L.minX(free) + 2, z = L.centerZ(free);
        var samples = HexBorderPlanner.claimableSamples(view, Set.of(free), 70, List.of(), x, z);
        assertFalse(samples.isEmpty());
        assertTrue(samples.size() <= BoundaryGeometry.MAX_CLAIMABLE_EMITTERS_PER_VIEWER);
        assertTrue(samples.stream().allMatch(s -> s.style() == HexBorderRelation.CLAIMABLE.ordinal()));
        // The west side of (3,1) is the neighbour's plot border: no green dots there.
        assertTrue(samples.stream().noneMatch(s -> s.x() == L.minX(free)));
    }
}
