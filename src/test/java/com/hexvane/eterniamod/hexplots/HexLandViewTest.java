package com.hexvane.eterniamod.hexplots;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.pathtool.SplineGeometry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HexLandViewTest {
    private static final HexLattice L = HexLattice.DEFAULT;

    /** A curved spline road, registered the way the Road Designer registers it: exact columns merged into runs. */
    private static List<PlotRect> curvedRoad() {
        var nodes = List.of(new SplineGeometry.Node(10.5, 1, 18.5), new SplineGeometry.Node(70.5, 1, 30.5), new SplineGeometry.Node(130.5, 1, 18.5));
        var columns = SplineGeometry.footprint(nodes, 5).stream().map(SplineGeometry.Hint::column).toList();
        return SplineGeometry.rectangles(columns);
    }

    @AfterEach void clear() { HexLandEvents.clearListeners(); }

    @Test void streetCellsAreEveryCellTheRoadTouches() {
        var rects = curvedRoad();
        Set<HexCoord> street = HexClaimRules.streetCells(L, rects, List.of());
        Set<HexCoord> expected = new HashSet<>();
        for (PlotRect r : rects) for (int z = r.z(); z < r.endZ(); z++) for (int x = r.x(); x < r.endX(); x++) expected.add(L.cellAt(x, z));
        assertEquals(expected, street);
        var view = new HexLandView("w", L, street, Set.of(), Set.of(), List.of());
        for (HexCoord c : street) {
            assertTrue(view.isStreet(c));
            assertFalse(view.isFrontage(c));
            for (HexCoord n : c.neighbors()) if (!street.contains(n)) assertTrue(view.isFrontage(n), "beside the road is frontage");
        }
        assertFalse(view.isFrontage(new HexCoord(0, 8)), "far from the road");
    }

    @Test void statesForEachViewer() {
        Set<HexCoord> street = HexClaimRules.streetCells(L, List.of(new PlotRect(0, 15, 128, 5)), List.of());
        UUID me = UUID.randomUUID(), neighbour = UUID.randomUUID(), myGuild = UUID.randomUUID(), otherGuild = UUID.randomUUID();
        var mine = new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.PLAYER, me, null, PlotCells.of(L, List.of(new HexCoord(0, 1))));
        var theirs = new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.PLAYER, neighbour, null, PlotCells.of(L, List.of(new HexCoord(1, 1))));
        var estate = new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.GUILD, myGuild, myGuild, PlotCells.of(L, HexCoord.disk(new HexCoord(3, 2), 1)));
        var otherEstate = new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.GUILD, otherGuild, otherGuild, PlotCells.of(L, HexCoord.disk(new HexCoord(-3, 4), 1)));
        var view = new HexLandView("w", L, street, Set.of(new HexCoord(-1, 1)), Set.of(new HexCoord(-2, 1)), List.of(mine, theirs, estate, otherEstate));
        var viewer = new HexLandView.Viewer(me, myGuild, HexClaimRules.Kind.PUBLIC);
        assertEquals(HexLandView.CellState.MINE, view.cell(new HexCoord(0, 1), viewer, null).state());
        assertEquals(HexLandView.CellState.OTHER_PLAYER, view.cell(new HexCoord(1, 1), viewer, id -> id.equals(neighbour) ? "Nia" : null).state());
        assertEquals("Nia", view.cell(new HexCoord(1, 1), viewer, id -> id.equals(neighbour) ? "Nia" : null).ownerName());
        assertEquals(HexLandView.CellState.MY_GUILD, view.cell(new HexCoord(3, 2), viewer, null).state());
        assertEquals(HexLandView.CellState.OTHER_GUILD, view.cell(new HexCoord(-3, 4), viewer, null).state());
        assertEquals(HexLandView.CellState.STREET, view.cell(new HexCoord(1, 0), viewer, null).state());
        assertEquals(HexLandView.CellState.RESERVED, view.cell(new HexCoord(-2, 1), viewer, null).state());
        assertEquals(HexLandView.CellState.FREE, view.cell(new HexCoord(-1, 1), viewer, null).state(), "blocked by road work");
        assertEquals(HexLandView.CellState.FRONTAGE_FREE, view.cell(new HexCoord(2, -1), viewer, null).state());
        assertEquals(HexLandView.CellState.FREE, view.cell(new HexCoord(0, 6), viewer, null).state());
        assertEquals(HexLandView.CellState.FREE, view.cell(new HexCoord(2, -1), HexLandView.Viewer.browsing(me, myGuild), null).state(), "browsing never shows claimable");
        var member = new HexLandView.Viewer(UUID.randomUUID(), myGuild, HexClaimRules.Kind.GUILD_MEMBER);
        assertEquals(HexLandView.CellState.FRONTAGE_FREE, view.cell(new HexCoord(3, 4), member, null).state(), "touches the estate");
        assertEquals(HexLandView.CellState.FREE, view.cell(new HexCoord(0, 6), member, null).state());
        var disk = view.disk(new HexCoord(0, 2), 8, viewer, null);
        assertEquals(1 + 3 * 8 * 9, disk.size());
        assertEquals(new HexCoord(0, 2), disk.getFirst().cell());
        assertEquals(HexClaimRules.Kind.GUILD_ESTATE, HexLandView.kindOf(estate));
        assertEquals(Set.of(new HexCoord(-1, 2), new HexCoord(0, 2)), view.expansionOptions(mine.plotId(), HexClaimRules.Kind.PUBLIC, HexPlotSettings.DEFAULTS),
            "two neighbours are street, one is taken, one is blocked by road work");
    }

    @Test void everyReasonAndStateHasPlayerText() throws IOException {
        Properties lang = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("Server/Languages/en-US/eternia_hexplots.lang")) {
            assertNotNull(in);
            lang.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> missing = new ArrayList<>();
        for (var reason : HexClaimRules.Reason.values()) {
            String text = lang.getProperty(reason.langKey().substring("eternia_hexplots.".length()));
            if (text == null) missing.add(reason.langKey());
            else {
                assertEquals(reason.message(), text, "lang and fallback agree for " + reason);
                assertFalse(text.contains(" - ") || text.contains("—") || text.contains("–"), "no dashes in player text: " + text);
            }
        }
        for (var state : HexLandView.CellState.values()) if (lang.getProperty("state." + state.name().toLowerCase()) == null) missing.add(state.name());
        assertEquals(List.of(), missing);
    }

    @Test void eventsReachListenersAndSurviveAFailingOne() {
        AtomicReference<HexLandEvents.Change> seen = new AtomicReference<>();
        HexLandEvents.listen(change -> { throw new IllegalStateException("broken listener"); });
        Runnable stop = HexLandEvents.listen(seen::set);
        HexLandEvents.fire("w", Set.of(HexCoord.ORIGIN), HexLandEvents.Cause.CLAIM);
        assertEquals(new HexLandEvents.Change("w", Set.of(HexCoord.ORIGIN), HexLandEvents.Cause.CLAIM), seen.get());
        seen.set(null);
        HexLandEvents.fire("w", Set.of(), HexLandEvents.Cause.EXPAND);
        assertNull(seen.get(), "empty changes are skipped");
        HexLandEvents.fire("w", Set.of(), HexLandEvents.Cause.LATTICE);
        assertTrue(seen.get().wholeWorld());
        stop.run();
        seen.set(null);
        HexLandEvents.fire("w", Set.of(HexCoord.ORIGIN), HexLandEvents.Cause.CLAIM);
        assertNull(seen.get());
    }
}
