package com.hexvane.eterniamod.hexplots.terraform;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerraformLevelTest {
    private static final HexCoord A = new HexCoord(0, 0), B = new HexCoord(1, 0), C = new HexCoord(2, 0), FAR = new HexCoord(6, 0);
    private static final UUID ALICE = UUID.randomUUID(), BOB = UUID.randomUUID(), GUILD = UUID.randomUUID();
    private static final int[] NONE = {};

    private static TerraformLevel.GroupPlot plot(String group, Integer level, boolean estate, HexCoord... cells) {
        return new TerraformLevel.GroupPlot(UUID.randomUUID(), group, Set.of(cells), level, estate);
    }

    @Test
    void joinedLandOfTheSameOwnerSetsTheLevel() {
        var own = plot(TerraformLevel.playerGroup(ALICE), 64, false, A);
        var other = plot(TerraformLevel.playerGroup(BOB), 90, false, C);
        var d = TerraformLevel.decide(TerraformLevel.playerGroup(ALICE), List.of(B), null, List.of(own, other), p -> 0, new int[] {70}, new int[] {80});
        assertEquals(new TerraformLevel.Decision(64, TerraformLevel.Source.GROUP, own.plotId()), d, "the owner's joined plot beats the road");
    }

    @Test
    void anotherOwnersPlotNextDoorDoesNotSetTheLevel() {
        var bobs = plot(TerraformLevel.playerGroup(BOB), 90, false, A);
        var d = TerraformLevel.decide(TerraformLevel.playerGroup(ALICE), List.of(B), null, List.of(bobs), p -> 0, new int[] {71, 69, 70}, new int[] {80});
        assertEquals(TerraformLevel.Source.ROAD, d.source());
        assertEquals(70, d.level(), "median road height");
    }

    @Test
    void sameOwnerLandFarAwayIsNotJoined() {
        var own = plot(TerraformLevel.playerGroup(ALICE), 64, false, FAR);
        var d = TerraformLevel.decide(TerraformLevel.playerGroup(ALICE), List.of(B), null, List.of(own), p -> 0, new int[] {72}, NONE);
        assertEquals(new TerraformLevel.Decision(72, TerraformLevel.Source.ROAD, null), d);
    }

    @Test
    void guildMemberPlotsShareTheEstateLevel() {
        String guild = TerraformLevel.guildGroup(GUILD);
        var estate = plot(guild, 66, true, A);
        var member = plot(guild, 75, false, C);
        var d = TerraformLevel.decide(guild, List.of(B), null, List.of(member, estate), p -> 0, new int[] {70}, NONE);
        assertEquals(estate.plotId(), d.plotId(), "a new member plot touching both takes the estate's level");
        assertEquals(66, d.level());
    }

    @Test
    void aGrowingPlotKeepsItsOwnLevel() {
        String guild = TerraformLevel.guildGroup(GUILD);
        var estate = plot(guild, 66, true, A);
        var member = plot(guild, 75, false, C);
        var d = TerraformLevel.decide(guild, List.of(B), member.plotId(), List.of(estate, member), p -> 0, NONE, NONE);
        assertEquals(75, d.level());
    }

    @Test
    void aPlotFromBeforeTerraformingIsMeasured() {
        var legacy = plot(TerraformLevel.playerGroup(ALICE), null, false, A);
        var d = TerraformLevel.decide(TerraformLevel.playerGroup(ALICE), List.of(B), legacy.plotId(), List.of(legacy), p -> 61, NONE, NONE);
        assertEquals(new TerraformLevel.Decision(61, TerraformLevel.Source.GROUP, legacy.plotId()), d);
        var unmeasured = TerraformLevel.decide(TerraformLevel.playerGroup(ALICE), List.of(B), legacy.plotId(), List.of(legacy), p -> TerraformLevel.UNKNOWN, new int[] {70}, NONE);
        assertEquals(TerraformLevel.Source.ROAD, unmeasured.source(), "an unreadable group falls back to the road");
    }

    @Test
    void withNoRoadTheGroundDecides() {
        var d = TerraformLevel.decide(TerraformLevel.playerGroup(ALICE), List.of(B), null, List.of(), p -> 0, new int[] {TerraformLevel.UNKNOWN}, new int[] {60, 90, 62});
        assertEquals(new TerraformLevel.Decision(62, TerraformLevel.Source.GROUND, null), d);
        assertFalse(TerraformLevel.decide("x", List.of(B), null, List.of(), p -> 0, NONE, NONE).known());
    }

    @Test
    void groupKeysFollowTheEstateAndAttachedGuild() {
        var cells = PlotCells.of(HexLattice.DEFAULT, List.of(A));
        assertEquals("guild:" + GUILD, TerraformLevel.group(new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.GUILD, GUILD, GUILD, cells)));
        assertEquals("guild:" + GUILD, TerraformLevel.group(new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.PLAYER, ALICE, GUILD, cells)));
        assertEquals("player:" + ALICE, TerraformLevel.group(new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.PLAYER, ALICE, null, cells)));
    }

    @Test
    void settingsParseAndFallBack() {
        var s = TerraformSettings.parse(new StringReader("{\"Mode\":\"plateau\",\"SkirtWidth\":100,\"PlateauBlock\":\"Rock_Basalt\"}"));
        assertEquals(TerraformSettings.Mode.PLATEAU, s.mode());
        assertEquals(24, s.skirtWidth(), "clamped");
        assertEquals(TerraformSettings.DEFAULTS.blendWidth(), s.blendWidth());
        assertEquals(TerraformSettings.DEFAULTS, TerraformSettings.parse(new StringReader("not json")));
        assertEquals(TerraformSettings.Mode.SMOOTH, TerraformSettings.Mode.parse("bogus", TerraformSettings.Mode.SMOOTH));
    }
    @Test
    void theRoadNearestTheFrontageCounts() {
        var lattice = HexLattice.DEFAULT;
        var box = lattice.bounds(A);
        int north = box.z() - 1;
        // Road one row north of the cell's tip at 70; a far road beside it, 12 rows further north, at 90.
        List<int[]> roads = new java.util.ArrayList<>();
        for (int x = box.x(); x < box.endX(); x++) { roads.add(new int[] {x, north - 8, 70}); roads.add(new int[] {x, north - 20, 90}); }
        int[] near = TerraformLevel.nearestRoad(roads, lattice, List.of(A), 4);
        assertTrue(near.length > 0);
        for (int y : near) assertEquals(70, y, "only the road nearest the hexagon counts");
        assertEquals(0, TerraformLevel.nearestRoad(List.of(), lattice, List.of(A), 4).length);
    }
}
