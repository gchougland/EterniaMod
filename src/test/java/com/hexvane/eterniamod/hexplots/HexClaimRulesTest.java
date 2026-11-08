package com.hexvane.eterniamod.hexplots;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HexClaimRulesTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    /** An east/west road through the body of row 0: cells (0..3, 0) become street. */
    private static final List<PlotRect> ROAD = List.of(new PlotRect(0, 15, 128, 5));
    private static final HexCoord NORTH = new HexCoord(1, -1), SOUTH = new HexCoord(0, 1), FAR = new HexCoord(0, 3);

    private static HexClaimRules.Land land(Map<HexCoord, UUID> owners) { return HexClaimRules.Land.of(L, ROAD, List.of(), owners); }

    @Test void contiguity() {
        Set<HexCoord> line = Set.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(2, 0));
        assertTrue(HexContiguity.isConnected(line));
        assertFalse(HexContiguity.isConnected(Set.of(new HexCoord(0, 0), new HexCoord(2, 0))));
        assertFalse(HexContiguity.isConnected(Set.of()));
        assertTrue(HexContiguity.canAdd(line, new HexCoord(1, 1)));
        assertFalse(HexContiguity.canAdd(line, new HexCoord(5, 0)));
        assertFalse(HexContiguity.canRemove(line, new HexCoord(1, 0), Set.of()), "would split the plot");
        assertTrue(HexContiguity.canRemove(line, new HexCoord(2, 0), Set.of()));
        assertFalse(HexContiguity.canRemove(line, new HexCoord(2, 0), Set.of(new HexCoord(2, 0))), "house cell");
        assertEquals(12, HexContiguity.frontier(HexShape.FLOWER.cells()).size());
        assertEquals(6, HexContiguity.edgeCells(HexShape.FLOWER.cells()));
        assertEquals(1, HexContiguity.edgeCells(Set.of(HexCoord.ORIGIN)));
    }

    @Test void streetAndFrontage() {
        var land = land(Map.of());
        assertTrue(land.street().containsAll(Set.of(new HexCoord(0, 0), new HexCoord(3, 0))));
        assertTrue(land.frontage(NORTH));
        assertTrue(land.frontage(SOUTH));
        assertFalse(land.frontage(new HexCoord(0, 0)), "street is not frontage");
        assertFalse(land.frontage(FAR));
    }

    @Test void publicClaims() {
        var land = land(Map.of());
        assertTrue(HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, false, Set.of(SOUTH), Set.of()).valid());
        assertEquals("road", HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, false, Set.of(FAR), Set.of()).code());
        assertEquals("street", HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, false, Set.of(new HexCoord(1, 0)), Set.of()).code());
        assertEquals("size", HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, true, Set.of(SOUTH), Set.of()).code());
        Set<HexCoord> tri = Set.of(SOUTH, new HexCoord(1, 1), new HexCoord(0, 2));
        assertTrue(HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, true, tri, Set.of()).valid());
        Set<HexCoord> split = Set.of(SOUTH, new HexCoord(3, 1), new HexCoord(0, 2));
        assertEquals("contiguous", HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, true, split, Set.of()).code());
        var taken = land(Map.of(SOUTH, UUID.randomUUID()));
        assertEquals("taken", HexClaimRules.claim(taken, HexClaimRules.Kind.PUBLIC, false, Set.of(SOUTH), Set.of()).code());
    }

    @Test void guildEstateAndMembers() {
        var land = land(Map.of());
        HexCoord centre = new HexCoord(0, 2); // flower rim touches the street row through (0, 1) and (1, 1)
        Set<HexCoord> flower = Set.copyOf(HexCoord.disk(centre, 1));
        assertTrue(HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_ESTATE, false, flower, Set.of()).valid());
        Set<HexCoord> farFlower = Set.copyOf(HexCoord.disk(new HexCoord(0, 6), 1));
        assertEquals("road", HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_ESTATE, false, farFlower, Set.of()).code());
        HexCoord touching = new HexCoord(0, 4), away = new HexCoord(4, 5);
        assertTrue(HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_MEMBER, false, Set.of(touching), flower).valid());
        assertEquals("anchor", HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_MEMBER, false, Set.of(away), flower).code());
    }

    @Test void expansion() {
        UUID plot = UUID.randomUUID(), neighbour = UUID.randomUUID();
        Map<HexCoord, UUID> owners = new HashMap<>();
        owners.put(SOUTH, plot);
        owners.put(new HexCoord(1, 1), neighbour);
        var land = land(owners);
        assertTrue(HexClaimRules.expand(land, HexClaimRules.Kind.PUBLIC, plot, new HexCoord(0, 2)).valid());
        assertEquals("touch", HexClaimRules.expand(land, HexClaimRules.Kind.PUBLIC, plot, FAR).code());
        assertEquals("taken", HexClaimRules.expand(land, HexClaimRules.Kind.PUBLIC, plot, new HexCoord(1, 1)).code());
        assertEquals("street", HexClaimRules.expand(land, HexClaimRules.Kind.PUBLIC, plot, new HexCoord(0, 0)).code());
        assertEquals("owned", HexClaimRules.expand(land, HexClaimRules.Kind.PUBLIC, plot, SOUTH).code());
        assertEquals("noPlot", HexClaimRules.expand(land, HexClaimRules.Kind.PUBLIC, UUID.randomUUID(), FAR).code());
        Map<HexCoord, UUID> full = new HashMap<>();
        for (HexCoord c : HexCoord.disk(new HexCoord(0, 4), 1)) full.put(c, plot);
        assertEquals("limit", HexClaimRules.expand(land(full), HexClaimRules.Kind.PUBLIC, plot, new HexCoord(0, 6)).code());
        assertTrue(HexClaimRules.expand(land(full), HexClaimRules.Kind.GUILD_ESTATE, plot, new HexCoord(0, 6)).valid());
    }

    @Test void yardAllowanceIsPerCell() {
        Set<HexCoord> plotCells = Set.of(SOUTH, new HexCoord(0, 2));
        Map<HexCoord, Integer> used = Map.of(SOUTH, HexPlotSettings.DEFAULTS.propsPerCell());
        assertEquals("cellFull", HexClaimRules.placeProp(plotCells, used, SOUTH).code());
        assertTrue(HexClaimRules.placeProp(plotCells, used, new HexCoord(0, 2)).valid());
        assertEquals("outside", HexClaimRules.placeProp(plotCells, used, FAR).code());
        assertEquals(1, HexPlotSettings.DEFAULTS.startCells(HexClaimRules.Kind.PUBLIC, false));
        assertEquals(3, HexPlotSettings.DEFAULTS.startCells(HexClaimRules.Kind.GUILD_MEMBER, true));
        assertEquals(7, HexPlotSettings.DEFAULTS.startCells(HexClaimRules.Kind.GUILD_ESTATE, false));
    }

    // Ported from HousingRulesTest: the same intent, in cells.

    @Test void soloGuildTestingKeepsProductionThresholdAndPlacementRules() {
        var land = land(Map.of());
        Set<HexCoord> flower = Set.copyOf(HexCoord.disk(new HexCoord(0, 2), 1));
        var settings = HexPlotSettings.DEFAULTS;
        var solo = new HexClaimRules.Candidate(HexClaimRules.Kind.GUILD_ESTATE, false, 1, false, false);
        assertEquals(HexClaimRules.Reason.MEMBERS, HexClaimRules.claim(land, solo, flower, Set.of(), settings).reason());
        assertEquals(5, HexClaimRules.claim(land, solo, flower, Set.of(), settings).count());
        var local = new HexClaimRules.Candidate(HexClaimRules.Kind.GUILD_ESTATE, false, 1, false, true);
        assertTrue(HexClaimRules.claim(land, local, flower, Set.of(), settings).valid());
        Set<HexCoord> farFlower = Set.copyOf(HexCoord.disk(new HexCoord(0, 6), 1));
        assertEquals(HexClaimRules.Reason.ROAD, HexClaimRules.claim(land, local, farFlower, Set.of(), settings).reason(), "estates still need frontage");
        var already = new HexClaimRules.Candidate(HexClaimRules.Kind.GUILD_ESTATE, false, 9, true, false);
        assertEquals(HexClaimRules.Reason.GUILD_SLOT, HexClaimRules.claim(land, already, flower, Set.of(), settings).reason());
        var occupied = land(Map.of(new HexCoord(0, 2), UUID.randomUUID()));
        assertEquals(HexClaimRules.Reason.TAKEN, HexClaimRules.claim(occupied, local, flower, Set.of(), settings).reason());
    }

    @Test void startShapeMustBeTheEntitledShape() {
        var land = land(Map.of());
        Set<HexCoord> line = Set.of(SOUTH, new HexCoord(1, 1), new HexCoord(2, 1));
        assertEquals(HexClaimRules.Reason.SHAPE, HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, true, line, Set.of()).reason());
        Set<HexCoord> notFlower = Set.of(new HexCoord(0, 1), new HexCoord(1, 1), new HexCoord(0, 2), new HexCoord(-1, 2), new HexCoord(1, 2), new HexCoord(0, 3), new HexCoord(0, 4));
        assertEquals(HexClaimRules.Reason.SHAPE, HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_ESTATE, false, notFlower, Set.of()).reason(), "seven cells but not a flower");
        for (Set<HexCoord> turn : HexShape.TRIANGLE.variants()) {
            Set<HexCoord> placed = new java.util.HashSet<>();
            for (HexCoord c : turn) placed.add(c.plus(new HexCoord(0, 1)));
            if (placed.stream().noneMatch(land.street()::contains))
                assertTrue(HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, true, placed, Set.of()).valid(), "every turn of the triangle: " + placed);
        }
        assertTrue(HexClaimRules.sameShape(Set.of(HexCoord.ORIGIN, new HexCoord(1, 0)), Set.of(new HexCoord(5, 5), new HexCoord(6, 5))));
        assertFalse(HexClaimRules.sameShape(Set.of(HexCoord.ORIGIN, new HexCoord(1, 0)), Set.of(new HexCoord(5, 5), new HexCoord(5, 6))), "turned is not the same");
    }

    @Test void guildMemberChainCannotBeUsedByPublicOrDifferentGuild() {
        var land = land(Map.of());
        Set<HexCoord> estate = Set.copyOf(HexCoord.disk(new HexCoord(0, 2), 1));
        Set<HexCoord> joinedMember = Set.of(new HexCoord(0, 4));
        Set<HexCoord> floating = Set.of(new HexCoord(5, 6));
        Set<HexCoord> anchors = HexClaimRules.joinedGuildLand(estate, List.of(joinedMember, floating));
        assertTrue(anchors.containsAll(joinedMember));
        assertFalse(anchors.containsAll(floating), "a member plot not touching guild land does not extend it");
        HexCoord next = new HexCoord(0, 5);
        assertTrue(HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_MEMBER, false, Set.of(next), anchors).valid(), "touches a joined member plot");
        assertEquals(HexClaimRules.Reason.ANCHOR, HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_MEMBER, false, Set.of(next), HexClaimRules.joinedGuildLand(Set.of(), List.of(joinedMember))).reason(), "no estate, no chain");
        assertEquals(HexClaimRules.Reason.ROAD, HexClaimRules.claim(land, HexClaimRules.Kind.PUBLIC, false, Set.of(next), anchors).reason(), "public plots ignore guild land");
        assertEquals(HexClaimRules.Reason.ANCHOR, HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_MEMBER, false, Set.of(new HexCoord(5, 7)), anchors).reason());
    }

    @Test void roadsMayRunBesidePlotsButNeverStructures() {
        var plot = PlotCells.of(L, List.of(SOUTH));
        var box = L.bounds(SOUTH);
        var house = new PlotRect(box.x() + 9, box.z() + 11, 14, 14);
        assertTrue(HexClaimRules.structure(plot, house, ROAD, HexPlotSettings.DEFAULTS).valid(), "the 14 block house fits one cell with the setback");
        assertEquals(HexClaimRules.Reason.SETBACK, HexClaimRules.structure(plot, new PlotRect(box.x() + 9, box.z() + 11, 14, 15), ROAD, HexPlotSettings.DEFAULTS).reason());
        var roadBeside = List.of(new PlotRect(box.x() + 9, house.endZ() + 2, 14, 1));
        assertEquals(HexClaimRules.Reason.ROAD_SETBACK, HexClaimRules.structure(plot, house, roadBeside, HexPlotSettings.DEFAULTS).reason());
    }

    @Test void expansionUsesDataCapsAndReportsCount() {
        UUID plot = UUID.randomUUID();
        Map<HexCoord, UUID> owners = new HashMap<>();
        for (HexCoord c : List.of(SOUTH, new HexCoord(1, 1), new HexCoord(0, 2))) owners.put(c, plot);
        var tight = new HexPlotSettings(32, 20, null, 1, 19, 0, 0, 1000, 24, 2, 5, 5);
        assertEquals(3, tight.maxCells(HexClaimRules.Kind.PUBLIC), "a cap never drops below the largest start shape");
        var result = HexClaimRules.expand(land(owners), HexClaimRules.Kind.PUBLIC, plot, new HexCoord(-1, 2), tight);
        assertEquals(HexClaimRules.Reason.LIMIT, result.reason());
        assertEquals("Your plot is as large as it can grow.", result.message());
        assertEquals("You need 3,000 coins for this hexagon.", HexClaimRules.Result.of(HexClaimRules.Reason.COINS, 3000).message());
        assertTrue(HexClaimRules.expand(land(owners), HexClaimRules.Kind.PUBLIC, plot, new HexCoord(-1, 2), HexPlotSettings.DEFAULTS).valid());
    }
}
