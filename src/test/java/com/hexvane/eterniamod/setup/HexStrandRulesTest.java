package com.hexvane.eterniamod.setup;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.hexplots.PlotShape;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hexvane.eterniamod.housing.PlotRect;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Road and plaza edits in a hex world: never pave a claimed hexagon, never cut a plot off from its road. */
class HexStrandRulesTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    /** Along the body of row 0 (z 8 to 27), cells q 0..3. */
    private static final PlotRect MAIN = new PlotRect(0, 15, 128, 4);

    private static HousingInfrastructure.WorldPlan plan(Map<String, PlotRect> roads) {
        var areas = new TreeMap<String, HousingInfrastructure.Area>();
        roads.forEach((id, rect) -> areas.put(id, new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD, rect)));
        return new HousingInfrastructure.WorldPlan("housing", List.of(), List.of(), null, true).withAreas(areas).withPlots(PlotShape.HEX, L);
    }

    private static InfrastructureRules.HexClaim plot(String label, HousingRules.Scope scope, HexCoord... cells) {
        return new InfrastructureRules.HexClaim(UUID.randomUUID(), label, PlotCells.of(L, List.of(cells)), scope, null);
    }

    @Test void aRoadMayNotTouchAClaimedHexagonAndTheMessageNamesThePlot() {
        var before = plan(Map.of("main", MAIN));
        var mira = plot("Mira's plot at hexagon 1:1", HousingRules.Scope.PUBLIC, new HexCoord(1, 1));
        // A side road running south into row 1 at x 60 touches cell 1:1.
        var after = plan(Map.of("main", MAIN, "side", new PlotRect(60, 19, 3, 30)));
        var error = assertThrows(IllegalArgumentException.class, () -> InfrastructureRules.validateHex(before, after, List.of(mira)));
        assertTrue(error.getMessage().contains("Mira's plot at hexagon 1:1"), error.getMessage());
        assertTrue(error.getMessage().contains("1:1"));
        // The same road a cell further west only touches free hexagons.
        assertDoesNotThrow(() -> InfrastructureRules.validateHex(before, plan(Map.of("main", MAIN, "side", new PlotRect(-30, 19, 3, 6))), List.of(mira)));
    }

    @Test void removingTheOnlyRoadBesideAPlotIsRefusedButOtherPlotsKeepWhatTheyHad() {
        var before = plan(Map.of("main", MAIN));
        var mira = plot("Mira's plot at hexagon 1:1", HousingRules.Scope.PUBLIC, new HexCoord(1, 1));
        var error = assertThrows(IllegalArgumentException.class, () -> InfrastructureRules.validateHex(before, plan(Map.of()), List.of(mira)));
        assertTrue(error.getMessage().contains("no hexagon beside a road"), error.getMessage());
        var estate = plot("Oak guild estate at hexagon 0:4", HousingRules.Scope.GUILD_ROOT, new HexCoord(0, 4));
        assertThrows(IllegalArgumentException.class, () -> InfrastructureRules.validateHex(plan(Map.of("south", new PlotRect(0, 100, 64, 4))), plan(Map.of()), List.of(estate)));
        // A plot that never had a road (far away) does not block an unrelated edit.
        var hermit = plot("Ivo's plot at hexagon 0:8", HousingRules.Scope.PUBLIC, new HexCoord(0, 8));
        assertDoesNotThrow(() -> InfrastructureRules.validateHex(before, plan(Map.of("main", MAIN, "far", new PlotRect(400, 15, 10, 4))), List.of(hermit)));
        // Guild member plots hang off guild land, not roads.
        var member = plot("Noor's plot at hexagon 1:1", HousingRules.Scope.GUILD_MEMBER, new HexCoord(1, 1));
        assertDoesNotThrow(() -> InfrastructureRules.validateHex(before, plan(Map.of()), List.of(member)));
    }

    @Test void replacingTheRoadWithAnotherBesideThePlotIsAllowed() {
        var before = plan(Map.of("main", MAIN));
        var mira = plot("Mira's plot at hexagon 1:1", HousingRules.Scope.PUBLIC, new HexCoord(1, 1));
        // Row 2 body is z 64 to 83: a road there makes cells of row 2 street, beside 1:1.
        assertDoesNotThrow(() -> InfrastructureRules.validateHex(before, plan(Map.of("south", new PlotRect(0, 70, 128, 4))), List.of(mira)));
    }

    @Test void hexPlotsLockTheGridAndHousingThroughTheFullValidate() {
        var before = plan(Map.of("main", MAIN));
        var mira = plot("Mira's plot at hexagon 1:1", HousingRules.Scope.PUBLIC, new HexCoord(1, 1));
        var rect = before.withPlots(PlotShape.RECT, L);
        assertThrows(IllegalArgumentException.class, () -> InfrastructureRules.validate(before, rect, List.of(), List.of(mira), List.of(), null, null));
        var noHousing = before.withRole("adventure", false);
        assertThrows(IllegalArgumentException.class, () -> InfrastructureRules.validate(before, noHousing, List.of(), List.of(mira), List.of(), null, null));
    }
}
