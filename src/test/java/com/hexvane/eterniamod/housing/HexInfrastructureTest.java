package com.hexvane.eterniamod.housing;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandEvents;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotShape;
import com.hexvane.eterniamod.setup.InfrastructureRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HexInfrastructureTest {
    @TempDir Path temp;

    @AfterEach void clear() { HexLandEvents.clearListeners(); }

    private HousingInfrastructure load(String json) throws Exception {
        Path file = temp.resolve("housing-infrastructure.json");
        Files.writeString(file, json);
        var infrastructure = new HousingInfrastructure(file, world -> List.of());
        infrastructure.load();
        return infrastructure;
    }

    @Test void filesFromBeforeHexPlotsReadAsRectangles() throws Exception {
        var infrastructure = load("""
            {"version":1,"revision":4,"worlds":{"housing":{"role":"housing","roads":[{"x":0,"z":0,"width":4,"depth":64}],"portals":[],"housingEnabled":true}}}""");
        var plan = infrastructure.world("housing").orElseThrow();
        assertEquals(PlotShape.RECT, plan.shape());
        assertTrue(plan.hexLattice().isEmpty());
        assertFalse(plan.hexPlots());
        assertNull(plan.plotShape(), "old files are not rewritten with a shape");
    }

    @Test void latticeAndShapeRoundTripAndAnnounceChanges() throws Exception {
        var infrastructure = load("""
            {"version":1,"revision":0,"worlds":{"housing":{"role":"housing","roads":[],"portals":[],"housingEnabled":true,"areas":{}}}}""");
        var seen = new ArrayList<HexLandEvents.Change>();
        HexLandEvents.listen(seen::add);
        var snapshot = infrastructure.snapshot();
        var plan = snapshot.worlds().get("housing");
        var lattice = new HexLattice(32, 20, 5, -7);
        infrastructure.saveWorld(snapshot.revision(), "housing", plan.withPlots(PlotShape.HEX, lattice));
        assertEquals(HexLandEvents.Cause.LATTICE, seen.getLast().cause());
        String json = Files.readString(temp.resolve("housing-infrastructure.json"));
        assertTrue(json.contains("\"plotShape\": \"hex\""), json);

        infrastructure.load();
        var saved = infrastructure.world("housing").orElseThrow();
        assertEquals(PlotShape.HEX, saved.shape());
        assertEquals(lattice, saved.hexLattice().orElseThrow());
        assertTrue(saved.hexPlots());

        // A road edit announces exactly the street cells that changed, plus their neighbours.
        var road = new PlotRect(5 + 16, -7 + 18, 3, 3);
        var areas = new TreeMap<>(saved.areas());
        areas.put("main-road", new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD, road));
        infrastructure.saveWorld(infrastructure.snapshot().revision(), "housing", saved.withAreas(areas));
        var change = seen.getLast();
        assertEquals(HexLandEvents.Cause.ROADS, change.cause());
        assertTrue(change.cells().contains(HexCoord.ORIGIN));
        assertTrue(change.cells().containsAll(HexCoord.ORIGIN.neighbors()));
        assertEquals(7, change.cells().size());
        assertEquals(PlotShape.HEX, infrastructure.world("housing").orElseThrow().shape(), "area edits keep the plot shape");
    }

    @Test void aHexWorldNeedsALatticeAndNewHousingWorldsDefaultToHex() {
        var base = new HousingInfrastructure.WorldPlan("housing", List.of(), List.of(), null, true);
        assertThrows(IllegalArgumentException.class, () -> base.withPlots(PlotShape.HEX, null));
        var hex = base.withHousingDefaults(HexLattice.DEFAULT);
        assertEquals(PlotShape.HEX, hex.shape());
        assertEquals(HexLattice.DEFAULT, hex.lattice());
        var explicitRect = base.withPlots(PlotShape.RECT, null);
        assertEquals(explicitRect, explicitRect.withHousingDefaults(HexLattice.DEFAULT), "an explicit choice is kept");
        var adventure = new HousingInfrastructure.WorldPlan("adventure", List.of(), List.of(), null, false);
        assertEquals(adventure, adventure.withHousingDefaults(HexLattice.DEFAULT));
        assertEquals(PlotShape.HEX, hex.withRole("housing", true).withArrival(null).withDescription("Pine Hollow").shape());
    }

    @Test void theGridIsLockedWhileTheWorldHasPlots() {
        var before = new HousingInfrastructure.WorldPlan("housing", List.of(), List.of(), null, true).withHousingDefaults(HexLattice.DEFAULT);
        var moved = before.withPlots(PlotShape.HEX, HexLattice.DEFAULT.withOrigin(10, 0));
        var claim = new HousingRules.Anchor(UUID.randomUUID(), new PlotRect(500, 500, 32, 36), HousingRules.Scope.PUBLIC, null, false);
        var failure = assertThrows(IllegalArgumentException.class, () -> InfrastructureRules.validate(before, moved, List.of(claim), List.of()));
        assertTrue(failure.getMessage().contains("Pack or unclaim"));
        assertThrows(IllegalArgumentException.class, () -> InfrastructureRules.validate(before, before.withPlots(PlotShape.RECT, before.lattice()), List.of(claim), List.of()));
        assertDoesNotThrow(() -> InfrastructureRules.validate(before, moved, List.of(), List.of()));
        assertDoesNotThrow(() -> InfrastructureRules.validate(before, before.withDescription("Quiet"), List.of(claim), List.of()));
    }

    @Test void unknownJsonKeysStayHarmless() throws Exception {
        var infrastructure = load("""
            {"version":1,"revision":0,"worlds":{"w":{"role":"housing","roads":[],"portals":[],"housingEnabled":true,"plotShape":"hex",
             "lattice":{"cellWidth":32,"bodyDepth":20,"originX":0,"originZ":0}}}}""");
        assertEquals(Map.of(), infrastructure.world("w").orElseThrow().areas());
        assertTrue(infrastructure.world("w").orElseThrow().hexPlots());
    }
}
