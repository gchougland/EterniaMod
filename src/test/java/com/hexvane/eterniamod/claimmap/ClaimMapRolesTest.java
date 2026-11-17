package com.hexvane.eterniamod.claimmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Account;
import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Mode;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hexvane.eterniamod.production.PlotRoles;
import com.hexvane.eterniamod.production.PlotRoles.Role;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** House and production hexagons on the Claim Map: the marks on the picture and the chooser in the side panel. */
class ClaimMapRolesTest {
    private static final int MAX_HOUSE = 4;

    private static ClaimMapLogic.Situation expanding(ClaimMapScenes.World w, HexCoord selected) {
        Account owner = new Account(w.me(), w.myGuild(), false, 6, false, false, HousingService.State.ACTIVE, null, null, null);
        return ClaimMapScenes.situation(w, Mode.EXPAND, HousingRules.Scope.PUBLIC, w.myPlot(), owner, selected, 0);
    }

    /** The sample plot with its first hexagon as the house and the rest as production. */
    private static PlotRoles roles(ClaimMapScenes.World w) {
        var cells = w.view().plots().get(w.myPlot()).cells();
        Map<HexCoord, Role> wanted = new HashMap<>();
        for (HexCoord c : cells.cells()) wanted.put(c, Role.PRODUCTION);
        var first = com.hexvane.eterniamod.hexplots.PlotCells.of(cells.lattice(), java.util.List.of(cells.cells().getFirst()));
        return PlotRoles.resolve(cells, PlotRoles.resolve(first, "", Set.of(), null).text(), Set.of(), wanted);
    }

    @Test
    void thePictureMarksHouseAndProductionHexagons() throws Exception {
        var w = ClaimMapScenes.street();
        var sit = expanding(w, null);
        var scene = ClaimMapLogic.scene(sit, sit.shown(), w.roads(), w.playerX(), w.playerZ());
        var plain = ClaimMapRenderer.render(scene, w.tiles());
        var marked = ClaimMapRenderer.render(scene, w.tiles());
        var roles = roles(w);
        ClaimMapRoleArt.paint(marked, sit.shown(), roles.all());
        for (var entry : roles.all().entrySet()) {
            var centre = sit.shown().centre(entry.getKey());
            if (!sit.shown().onPicture(centre.x(), centre.y())) continue;
            assertNotEquals(plain.getRGB((int) centre.x(), (int) centre.y() - 6), marked.getRGB((int) centre.x(), (int) centre.y() - 6), "no mark on " + entry);
        }
        Path out = Path.of("build", "claim-map-roles.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(marked, "png", out.toFile());
    }

    @Test
    void addingAHexagonAsksWhichKind() {
        var w = ClaimMapScenes.street();
        var grow = ClaimMapLogic.growth(expanding(w, null)).iterator().next();
        var roles = roles(w);
        var view = ClaimMapRoles.view(expanding(w, grow), roles, null, MAX_HOUSE);
        assertTrue(view.box());
        assertEquals(roles.suggested(grow, MAX_HOUSE), view.picked());
        assertEquals(roles.canAddAs(grow, view.picked(), MAX_HOUSE) != null, view.blocked());
        // Picking a kind that is not allowed here keeps the button off and says why.
        Role other = view.picked() == Role.PRODUCTION ? Role.HOUSE : Role.PRODUCTION;
        var picked = ClaimMapRoles.view(expanding(w, grow), roles, other, MAX_HOUSE);
        assertEquals(other, picked.picked());
        assertEquals(roles.canAddAs(grow, other, MAX_HOUSE) != null, picked.blocked());
    }

    @Test
    void anOwnHexagonSaysWhatItIs() {
        var w = ClaimMapScenes.street();
        var roles = roles(w);
        HexCoord own = roles.cells().cells().getFirst();
        var view = ClaimMapRoles.view(expanding(w, own), roles, null, MAX_HOUSE);
        assertFalse(view.box());
        assertTrue(view.title() != null && view.body() != null && view.own() != null);
        // Nothing picked: no chooser, the panel keeps its own title.
        var idle = ClaimMapRoles.view(expanding(w, null), roles, null, MAX_HOUSE);
        assertFalse(idle.box());
        assertNull(idle.title());
    }
}
