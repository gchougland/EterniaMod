package com.hexvane.eterniamod.claimmap;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Account;
import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Mode;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLandView.CellState;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.HexShape;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ClaimMapTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final HexPlotSettings S = HexPlotSettings.DEFAULTS;

    // ---- cell to pixel mapping and hit testing ----

    @Test void windowStartsOnAnEvenRowAndCentresThePlayer() {
        for (int[] at : new int[][] {{0, 0}, {177, 41}, {-500, 333}, {1234, -987}}) {
            var g = ClaimMapGeometry.centredOn(L, at[0] + 0.5, at[1] + 0.5);
            assertEquals(0, g.row0() & 1);
            HexCoord here = L.cellAt(at[0], at[1]);
            assertTrue(g.shows(here));
            var p = g.centre(here);
            assertTrue(Math.abs(p.x() - g.pixelWidth() / 2.0) <= 80 + 40, "player cell near the middle across: " + p);
            assertTrue(Math.abs(p.y() - g.pixelHeight() / 2.0) <= 70 + 1, "player cell near the middle down: " + p);
        }
    }

    @Test void pictureIsElevenCellsSquareAtTwoAndAHalfPixelsABlock() {
        var g = ClaimMapGeometry.centredOn(L, 0, 0);
        assertEquals(880, g.pixelWidth());
        assertEquals(770, g.pixelHeight());
        assertEquals(new ClaimMapGeometry.Point(0, 0), g.pixel(g.worldX0(), g.worldZ0()));
        assertEquals(new ClaimMapGeometry.Point(80, 0), g.pixel(g.worldX0() + 32, g.worldZ0()));
    }

    @Test void bricksTileThePictureWithNoGapsOrOverlaps() {
        var g = ClaimMapGeometry.centredOn(L, 40, 90);
        var slots = g.slots();
        assertEquals(ClaimMapGeometry.slotCount(), slots.size());
        int[][] hits = new int[g.pixelHeight()][g.pixelWidth()];
        for (var s : slots) for (int y = s.top(); y < s.top() + s.height(); y++) for (int x = s.left(); x < s.left() + s.width(); x++) hits[y][x]++;
        for (int[] row : hits) for (int n : row) assertEquals(1, n);
    }

    @Test void everyBrickMeansTheCellUnderItsMiddleAndSlotIndexesRoundTrip() {
        var g = ClaimMapGeometry.centredOn(L, -300, 700);
        for (var s : g.slots()) {
            assertEquals(s.cell(), g.cellOfSlot(s.index()));
            double mx = s.left() + s.width() / 2.0, my = s.top() + s.height() / 2.0;
            // Half bricks: their middle is still inside their own cell.
            assertEquals(s.cell(), g.cellAtPixel(mx, my), "slot " + s);
            assertTrue(g.shows(s.cell()));
        }
        assertNull(g.cellOfSlot(-1));
        assertNull(g.cellOfSlot(ClaimMapGeometry.slotCount()));
    }

    @Test void hitTestingFollowsTheHexagonIncludingTips() {
        var g = ClaimMapGeometry.centredOn(L, 0, 0);
        for (HexCoord cell : g.visibleCells()) {
            var c = g.centre(cell);
            if (!g.onPicture(c.x(), c.y())) continue;
            assertEquals(cell, g.cellAtPixel(c.x(), c.y()));
            // Just inside the north tip (one block below the tip row) still belongs to the cell.
            assertEquals(cell, g.cellAtPixel(c.x(), c.y() - 45 + 2.5 * 1.5));
        }
    }

    @Test void panningKeepsTheButtonLayout() {
        var g = ClaimMapGeometry.centredOn(L, 0, 0);
        var moved = g.panned(ClaimMapGeometry.PAN_COLUMNS, -ClaimMapGeometry.PAN_ROWS);
        var a = g.slots();
        var b = moved.slots();
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).left(), b.get(i).left());
            assertEquals(a.get(i).top(), b.get(i).top());
            assertEquals(a.get(i).kind(), b.get(i).kind());
        }
        assertThrows(IllegalArgumentException.class, () -> g.panned(0, 1));
    }

    // ---- state to colour ----

    @Test void everyStateUsesTheSharedPalette() {
        for (CellState s : CellState.values()) {
            assertEquals(HexLandView.suggestedColor(s), ClaimMapPalette.color(s));
            assertTrue(ClaimMapPalette.fillAlpha(s) >= 0 && ClaimMapPalette.fillAlpha(s) <= 0.6f);
        }
        assertEquals("#6CC56C", ClaimMapPalette.hex(CellState.FRONTAGE_FREE));
        assertTrue(ClaimMapPalette.ringsCell(CellState.FRONTAGE_FREE));
        assertTrue(ClaimMapPalette.dimAlpha(CellState.FREE) > 0);
        assertTrue(ClaimMapPalette.fillAlpha(CellState.MINE) > ClaimMapPalette.fillAlpha(CellState.STREET));
    }

    @Test void paintedCellsShowTheirStateColour() {
        var g = ClaimMapGeometry.centredOn(L, 0, 0);
        HexCoord mine = g.cellAtPixel(440, 385);
        var scene = new ClaimMapRenderer.Scene(g, Map.of(mine, CellState.MINE), Map.of(mine, UUID.randomUUID()), List.of(), null, null);
        BufferedImage image = ClaimMapRenderer.render(scene, (x, z) -> flat(0xFF808080));
        var c = g.centre(mine);
        int rgb = image.getRGB((int) c.x(), (int) c.y());
        int g2 = (rgb >> 8) & 0xFF, r2 = (rgb >> 16) & 0xFF;
        assertTrue(g2 > r2 + 20, "teal tint over grey: " + Integer.toHexString(rgb));
    }

    // ---- PNG stitching ----

    @Test void tilesAreStitchedInPlaceAndMissingOnesShowUnexplored() throws Exception {
        var g = new ClaimMapGeometry(L, 0, 0);
        // West of x = 160 is red, east is blue, nothing south of z = 160.
        ClaimMapRenderer.TileSource tiles = (cx, cz) -> cz >= 5 ? null : flat(cx < 5 ? 0xFFC03030 : 0xFF3030C0);
        var scene = new ClaimMapRenderer.Scene(g, Map.of(), Map.of(), List.of(), null, null);
        BufferedImage image = ClaimMapRenderer.render(scene, tiles);
        var red = g.pixel(100, 60);
        var blue = g.pixel(220, 60);
        var none = g.pixel(220, 250);
        int r = image.getRGB((int) red.x(), (int) red.y()), b = image.getRGB((int) blue.x(), (int) blue.y()), n = image.getRGB((int) none.x(), (int) none.y());
        assertTrue(((r >> 16) & 0xFF) > (r & 0xFF) + 40, Integer.toHexString(r));
        assertTrue((b & 0xFF) > ((b >> 16) & 0xFF) + 40, Integer.toHexString(b));
        assertTrue(((n >> 16) & 0xFF) < 0x40 && (n & 0xFF) < 0x50, "unexplored is dark: " + Integer.toHexString(n));
        byte[] png = ClaimMapRenderer.png(image);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(880, back.getWidth());
        assertEquals(770, back.getHeight());
        assertTrue(png.length < 1_500_000);
    }

    @Test void mapPaletteColoursConvertFromRgba() {
        assertEquals(0xFF112233, ClaimMapTiles.toArgb(0x112233FF));
        assertEquals(0xFF112233, ClaimMapTiles.toArgb(0x11223300));
        assertNull(ClaimMapTiles.decode(null));
    }

    // ---- mode routing and rules ----

    @Test void newcomersClaimAndOwnersGrowAndPackedHomesAreSetDown() {
        UUID me = UUID.randomUUID();
        assertNull(ClaimMapLogic.accountReason(Account.newcomer(me), HousingRules.Scope.PUBLIC));
        var owner = new Account(me, null, false, 0, false, false, HousingService.State.ACTIVE, null, null, null);
        assertEquals(HexClaimRules.Reason.HAS_PLOT, ClaimMapLogic.accountReason(owner, HousingRules.Scope.PUBLIC).reason());
        var packed = new Account(me, null, false, 0, false, false, HousingService.State.PACKED, null, null, null);
        assertEquals(HexClaimRules.Reason.PACKED, ClaimMapLogic.accountReason(packed, HousingRules.Scope.PUBLIC).reason());
        var member = new Account(me, UUID.randomUUID(), false, 3, false, false, null, null, null, null);
        assertEquals(HexClaimRules.Reason.PERMISSION, ClaimMapLogic.accountReason(member, HousingRules.Scope.GUILD_ROOT).reason());
        assertNull(ClaimMapLogic.accountReason(member, HousingRules.Scope.GUILD_MEMBER));
        assertEquals(HexClaimRules.Reason.ANCHOR, ClaimMapLogic.accountReason(Account.newcomer(me), HousingRules.Scope.GUILD_MEMBER).reason());
        assertEquals(HexShape.TRIANGLE, ClaimMapLogic.startShape(new Account(me, null, false, 0, true, false, null, null, null, null), HousingRules.Scope.PUBLIC, S));
        assertEquals(HexShape.FLOWER, ClaimMapLogic.startShape(member, HousingRules.Scope.GUILD_ROOT, S));
    }

    @Test void viewerIntentFollowsTheMode() {
        var w = ClaimMapScenes.street();
        UUID me = UUID.randomUUID();
        var claim = ClaimMapScenes.situation(w, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, Account.newcomer(me), null, 0);
        assertEquals(HexClaimRules.Kind.PUBLIC, ClaimMapLogic.viewer(claim).intent());
        var owner = new Account(me, null, false, 0, false, false, HousingService.State.ACTIVE, null, null, null);
        assertNull(ClaimMapLogic.viewer(ClaimMapScenes.situation(w, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, owner, null, 0)).intent());
        assertNull(ClaimMapLogic.viewer(ClaimMapScenes.situation(w, Mode.EXPAND, HousingRules.Scope.PUBLIC, w.myPlot(), owner, null, 0)).intent());
    }

    @Test void frontageCellsAreGreenAndClaimableWithOneClick() {
        var w = ClaimMapScenes.street();
        var s = ClaimMapScenes.situation(w, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, Account.newcomer(UUID.randomUUID()), w.frontage(), 0);
        assertEquals(CellState.FRONTAGE_FREE, ClaimMapLogic.state(s, ClaimMapLogic.viewer(s), Set.of(), w.frontage()));
        assertEquals(Set.of(w.frontage()), ClaimMapLogic.chosen(s));
        var p = ClaimMapLogic.panel(s);
        assertTrue(p.claimEnabled());
        assertNull(p.shapeName(), "a single hexagon needs no layout picker");
        assertEquals(1, p.picks().size());
    }

    @Test void upgradedPlotsOfferEveryTriangleOverTheClickValidFirst() {
        var w = ClaimMapScenes.street();
        UUID me = UUID.randomUUID();
        var upgraded = new Account(me, null, false, 0, true, false, null, null, null, null);
        HexCoord cell = ClaimMapScenes.roomy(w, upgraded);
        var s = ClaimMapScenes.situation(w, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, upgraded, cell, 0);
        var options = ClaimMapLogic.options(s);
        assertEquals(HexShape.TRIANGLE.placementsCovering(cell).size(), options.size());
        boolean seenRefused = false;
        for (var o : options) {
            assertTrue(o.cells().contains(cell));
            if (!o.result().valid()) seenRefused = true;
            else assertFalse(seenRefused, "allowed layouts come first");
        }
        assertEquals(3, ClaimMapLogic.chosen(s).size());
    }

    @Test void packedHomesSlideWithoutTurning() {
        var w = ClaimMapScenes.street();
        UUID me = UUID.randomUUID();
        var packed = PlotCells.of(L, List.of(new HexCoord(40, 40), new HexCoord(41, 40), new HexCoord(40, 41)));
        var a = new Account(me, null, false, 0, false, false, HousingService.State.PACKED, null, packed, null);
        HexCoord cell = ClaimMapScenes.roomy(w, new Account(me, null, false, 0, true, false, null, null, null, null));
        var s = ClaimMapScenes.situation(w, Mode.PACKED, HousingRules.Scope.PUBLIC, null, a, cell, 0);
        var options = ClaimMapLogic.options(s);
        assertEquals(3, options.size(), "one slide per packed cell");
        for (var o : options) assertTrue(HexClaimRules.sameShape(o.cells(), packed.cells()) && o.cells().contains(cell));
        assertFalse(ClaimMapLogic.chosen(s).isEmpty(), "the packed slot itself does not block setting the home down");
    }

    @Test void takenAndStreetCellsCannotBeClaimed() {
        var w = ClaimMapScenes.street();
        var me = Account.newcomer(UUID.randomUUID());
        for (HexCoord cell : List.of(w.neighbour(), w.streetCell())) {
            var s = ClaimMapScenes.situation(w, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, me, cell, 0);
            assertTrue(ClaimMapLogic.chosen(s).isEmpty());
            assertFalse(ClaimMapLogic.panel(s).claimEnabled());
        }
    }

    @Test void expandModeHighlightsOnlyTheFrontier() {
        var w = ClaimMapScenes.street();
        UUID me = w.me();
        var owner = new Account(me, null, false, 0, false, false, HousingService.State.ACTIVE, null, null, null);
        var s = ClaimMapScenes.situation(w, Mode.EXPAND, HousingRules.Scope.PUBLIC, w.myPlot(), owner, null, 0);
        Set<HexCoord> growth = ClaimMapLogic.growth(s);
        assertFalse(growth.isEmpty());
        var viewer = ClaimMapLogic.viewer(s);
        var mine = w.view().plots().get(w.myPlot()).cells().asSet();
        for (HexCoord cell : s.shown().visibleCells()) {
            CellState state = ClaimMapLogic.state(s, viewer, growth, cell);
            if (state == CellState.FRONTAGE_FREE) {
                assertTrue(growth.contains(cell));
                assertTrue(cell.neighbors().stream().anyMatch(mine::contains));
            }
        }
        assertEquals(S.expansionPrice(mine.size()), ClaimMapLogic.price(s));
    }

    /**
     * Writes the page's real commands and real pictures for every scenario (build/claim-map-scenes, for ui_render) and
     * checks every selector exists in the page. Runs in a child JVM: the engine's command builder needs Hytale's log
     * manager, which the shared test JVM cannot take (same approach as GatehouseDeskTest).
     */
    @Test void scenarioCommandsAndPictures(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        String cp = String.join(java.io.File.pathSeparator,
            java.nio.file.Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            java.nio.file.Path.of(ClaimMapScreen.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            java.nio.file.Path.of(UICommandBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            java.nio.file.Path.of(com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var out = java.nio.file.Path.of("build", "claim-map-scenes");
        var log = temp.resolve("scenes.log");
        var failure = temp.resolve("failure.txt");
        String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", exe).toString(),
            "-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager", "-Djava.awt.headless=true",
            "-cp", cp, ClaimMapScenes.class.getName(), out.toString(), failure.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "claim map scenes timed out");
            assertEquals(0, process.exitValue(), java.nio.file.Files.exists(failure) ? java.nio.file.Files.readString(failure) : java.nio.file.Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertTrue(java.nio.file.Files.exists(out.resolve("claim-map.test.json")));
    }

    static ClaimMapRenderer.Tile flat(int argb) {
        int[] px = new int[32 * 32];
        java.util.Arrays.fill(px, argb);
        return new ClaimMapRenderer.Tile(32, 32, px);
    }

    static PlotCells cells(HexCoord... c) { return PlotCells.of(L, List.of(c)); }
}
