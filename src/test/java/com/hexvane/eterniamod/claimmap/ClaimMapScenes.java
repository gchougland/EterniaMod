package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Account;
import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Mode;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.pathtool.SplineGeometry;
import com.hexvane.eterniamod.ui.CurrencyUi;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Test worlds for the claim map: a curved high street with a crossing, a plaza, a river, plots of every kind, and an
 * empty edge of the world. Terrain tiles are painted procedurally in the world map's own format (32 pixels a chunk).
 * {@link #writeAll} writes each scenario's real picture (ClaimMapRenderer) and the exact commands the page sends
 * (ClaimMapScreen through ClaimMapLogic) as a test case for the Hytale MCP {@code ui_render}.
 */
final class ClaimMapScenes {
    static final HexLattice L = HexLattice.DEFAULT;
    static final String PROJECT = "_worktrees/EterniaMod-hex-map";
    static final String GUILD_NAME = "The Fellowship of the Northern Wildflower Meadows";

    record World(HexLandView view, ClaimMapGeometry window, UUID me, UUID myGuild, UUID myPlot, HexCoord frontage,
                 HexCoord neighbour, HexCoord streetCell, HexCoord far, List<PlotRect> roads, double playerX, double playerZ,
                 Map<UUID, String> names, ClaimMapRenderer.TileSource tiles) {}

    private ClaimMapScenes() {}

    /** A busy high street: every state on screen. */
    static World street() {
        double px = 182, pz = 64;
        List<PlotRect> roads = new ArrayList<>();
        // High street running east and west with a gentle bend, and a lane crossing it north to south.
        roads.addAll(curve(-280, 600, t -> 18 + 9 * Math.sin(t / 120.0), true));
        roads.addAll(curve(-280, 460, t -> 172 + 14 * Math.sin(t / 90.0), false));
        PlotRect plaza = new PlotRect(150, -4, 46, 44);
        Set<HexCoord> street = HexClaimRules.streetCells(L, roads, List.of(plaza));
        List<PlotRect> drawn = new ArrayList<>(roads);
        drawn.add(plaza);

        ClaimMapGeometry window = ClaimMapGeometry.centredOn(L, px, pz);
        HexCoord home = L.cellAt((int) px, (int) pz);
        UUID me = new UUID(1, 1), myGuild = new UUID(2, 2), otherGuild = new UUID(3, 3), bramble = new UUID(4, 4), wren = new UUID(5, 5), longName = new UUID(6, 6);
        Map<UUID, String> names = new HashMap<>();
        names.put(bramble, "Bramble");
        names.put(wren, "Wren");
        names.put(longName, "Sir Bartholomew Thistlewhistle the Third");
        names.put(myGuild, "Lanterns");
        names.put(otherGuild, GUILD_NAME);

        Set<HexCoord> taken = new HashSet<>(street);
        List<HexLandView.PlotInfo> plots = new ArrayList<>();
        // Another guild's estate (flower) north west, my guild's estate north east, a member plot beside it.
        Set<HexCoord> west = place(window, taken, Set.copyOf(HexCoord.disk(HexCoord.ORIGIN, 1)), home.plus(new HexCoord(-3, -3)), street);
        plots.add(new HexLandView.PlotInfo(new UUID(10, 1), HexLandView.OwnerKind.GUILD, otherGuild, otherGuild, PlotCells.of(L, west)));
        Set<HexCoord> east = place(window, taken, Set.copyOf(HexCoord.disk(HexCoord.ORIGIN, 1)), home.plus(new HexCoord(3, -4)), street);
        plots.add(new HexLandView.PlotInfo(new UUID(10, 2), HexLandView.OwnerKind.GUILD, myGuild, myGuild, PlotCells.of(L, east)));
        Set<HexCoord> member = place(window, taken, Set.of(HexCoord.ORIGIN), home.plus(new HexCoord(5, -3)), null);
        plots.add(new HexLandView.PlotInfo(new UUID(10, 3), HexLandView.OwnerKind.PLAYER, wren, myGuild, PlotCells.of(L, member)));
        // Neighbours along the street.
        Set<HexCoord> brambles = place(window, taken, Set.of(HexCoord.ORIGIN), home.plus(new HexCoord(-2, 1)), street);
        plots.add(new HexLandView.PlotInfo(new UUID(10, 4), HexLandView.OwnerKind.PLAYER, bramble, null, PlotCells.of(L, brambles)));
        Set<HexCoord> knight = place(window, taken, Set.of(HexCoord.ORIGIN, new HexCoord(1, 0), new HexCoord(0, 1)), home.plus(new HexCoord(-4, 3)), street);
        plots.add(new HexLandView.PlotInfo(new UUID(10, 5), HexLandView.OwnerKind.PLAYER, longName, null, PlotCells.of(L, knight)));
        // My own plot (shown in expand scenarios; in claim scenarios "me" is a newcomer, so it is someone else's).
        Set<HexCoord> mine = place(window, taken, Set.of(HexCoord.ORIGIN, new HexCoord(1, 0), new HexCoord(0, 1)), home.plus(new HexCoord(1, 2)), street);
        UUID myPlot = new UUID(10, 6);
        plots.add(new HexLandView.PlotInfo(myPlot, HexLandView.OwnerKind.PLAYER, me, null, PlotCells.of(L, mine)));
        // Someone is claiming two cells right now; builders hold one more.
        Set<HexCoord> reserved = place(window, taken, Set.of(HexCoord.ORIGIN, new HexCoord(1, 0)), home.plus(new HexCoord(3, 0)), street);
        Set<HexCoord> blocked = place(window, taken, Set.of(HexCoord.ORIGIN), home.plus(new HexCoord(-1, 4)), null);

        var view = new HexLandView("hub", L, street, blocked, reserved, plots);
        HexCoord frontage = nearest(window, home, c -> !taken.contains(c) && view.isFrontage(c) && view.isFree(c));
        HexCoord far = nearest(window, home.plus(new HexCoord(-1, 4)), c -> !taken.contains(c) && view.isFree(c) && !view.isFrontage(c));
        HexCoord streetCell = nearest(window, home, street::contains);
        return new World(view, window, me, myGuild, myPlot, frontage, brambles.iterator().next(), streetCell, far, drawn, px, pz, names,
            terrain(drawn, Integer.MAX_VALUE));
    }

    /** The empty edge of the world: no roads, no plots, and no map past the eastern edge. */
    static World edge() {
        double px = 2950, pz = -1200;
        ClaimMapGeometry window = ClaimMapGeometry.centredOn(L, px, pz);
        var view = new HexLandView("wilds", L, Set.of(), Set.of(), Set.of(), List.of());
        HexCoord here = L.cellAt((int) px, (int) pz);
        return new World(view, window, new UUID(1, 1), null, null, null, null, null, here, List.of(), px, pz, Map.of(),
            terrain(List.of(), 3000 / 32));
    }

    static ClaimMapLogic.Situation situation(World w, Mode mode, HousingRules.Scope scope, UUID plotId, Account account, HexCoord selected, int option) {
        return new ClaimMapLogic.Situation(mode, scope, plotId, account, w.view(), w.names()::get, w.window(), selected, option,
            false, false, null, false, picturePath("x"), false, HexPlotSettings.DEFAULTS, false, null, Set.of());
    }

    static String picturePath(String scene) { return "Icons/ItemsGenerated/EterniaMod_ClaimMap_preview_" + scene + ".png"; }

    // ------------------------------------------------------------------------------------------------------------

    record Scene(String name, String title, ClaimMapLogic.Situation situation) {}

    static List<Scene> scenes() {
        World full = street(), e = edge();
        // Claim scenes: "me" has no plot yet. Expand scenes: "me" owns the teal plot and belongs to the gold guild.
        World s = withoutMine(full);
        UUID me = s.me();
        Account newcomer = Account.newcomer(me);
        Account inGuild = new Account(me, s.myGuild(), true, 6, false, false, null, null, null, null);
        Account upgraded = new Account(me, null, false, 0, true, false, null, null, null, null);
        Account owner = new Account(me, s.myGuild(), false, 6, false, false, HousingService.State.ACTIVE, null, null, null);
        List<Scene> out = new ArrayList<>();
        out.add(new Scene("01-first-visit", "first visit, no plot, nothing picked", rename(situation(withoutMine(s), Mode.CLAIM, HousingRules.Scope.PUBLIC, null, newcomer, null, 0), "01-first-visit")));
        out.add(new Scene("02-busy-street", "busy street, every state, guild member", rename(situation(s, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, inGuild, null, 0), "02-busy-street")));
        out.add(new Scene("03-claimable", "claimable cell picked", rename(situation(s, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, newcomer, s.frontage(), 0), "03-claimable")));
        out.add(new Scene("04-confirm", "claimable cell, confirm step", confirming(rename(situation(s, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, newcomer, s.frontage(), 0), "03-claimable"))));
        out.add(new Scene("05-taken-long-name", "taken cell with a long owner name", rename(situation(s, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, inGuild,
            s.view().plots().get(new UUID(10, 1)).cells().cells().get(0), 0), "02-busy-street")));
        out.add(new Scene("06-triangle", "upgraded plot: triangle layout preview", rename(situation(s, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, upgraded, roomy(s, upgraded), 1), "03-claimable")));
        out.add(new Scene("07-too-far", "free cell too far from a road", rename(situation(s, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, upgraded, s.far(), 0), "03-claimable")));
        HexCoord grow = ClaimMapLogic.growth(situation(full, Mode.EXPAND, HousingRules.Scope.PUBLIC, s.myPlot(), owner, null, 0)).stream()
            .min(Comparator.comparing(HexCoord::r).thenComparing(HexCoord::q)).orElseThrow();
        out.add(new Scene("08-expand", "expand mode, frontier picked, confirm", confirming(rename(situation(full, Mode.EXPAND, HousingRules.Scope.PUBLIC, full.myPlot(), owner, grow, 0), "08-expand"))));
        out.add(new Scene("09-expand-result", "expand mode, dev commit answer", result(rename(situation(full, Mode.EXPAND, HousingRules.Scope.PUBLIC, full.myPlot(), owner, grow, 0), "08-expand"),
            Message.translation(HexClaimCommits.Development.FINISHING))));
        out.add(new Scene("10-edge-of-world", "empty edge of the world", rename(situation(e, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, newcomer, e.far(), 0), "10-edge-of-world")));
        out.add(new Scene("11-guild-tab", "guild member tab, cell beside guild land", rename(situation(s, Mode.CLAIM, HousingRules.Scope.GUILD_MEMBER, null, inGuild,
            nearestFree(s, s.view().plots().get(new UUID(10, 2)).cells().cells().get(0)), 0), "11-guild-tab")));
        var packedHome = PlotCells.of(L, List.of(new HexCoord(40, 40), new HexCoord(41, 40), new HexCoord(40, 41)));
        Account packed = new Account(me, null, false, 0, true, false, HousingService.State.PACKED, null, packedHome, null);
        out.add(new Scene("13-packed-home", "packed home: same shape, same turn", rename(situation(s, Mode.PACKED, HousingRules.Scope.PUBLIC, null, packed, roomy(s, upgraded), 0), "03-claimable")));
        out.add(new Scene("12-loading", "opening, picture still being drawn", loading(situation(withoutMine(s), Mode.CLAIM, HousingRules.Scope.PUBLIC, null, null, null, 0))));
        return out;
    }

    /**
     * Child JVM entry (the engine's command builder needs Hytale's log manager): writes every scenario to {@code args[0]}
     * and checks that each command addresses an element of the page. On failure, writes the reason to {@code args[1]}.
     */
    public static void main(String[] args) throws Exception {
        try {
            writeAll(Path.of(args[0]));
            checkSelectors();
        } catch (Throwable failure) {
            var text = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(text));
            Files.writeString(Path.of(args[1]), text.toString());
            System.exit(1);
        }
        System.exit(0);
    }

    static void checkSelectors() throws Exception {
        String ui = Files.readString(Path.of("src/main/resources/Common/UI/Custom/EterniaMod/ClaimMapPage.ui"));
        Set<String> ids = new HashSet<>();
        var m = java.util.regex.Pattern.compile("#([A-Za-z0-9]+)").matcher(ui);
        while (m.find()) ids.add(m.group(1));
        for (Scene scene : scenes()) {
            var c = new UICommandBuilder();
            ClaimMapScreen.open(c, scene.situation().shown() != null ? scene.situation().shown() : street().window());
            ClaimMapScreen.show(c, ClaimMapLogic.panel(scene.situation()));
            for (var command : c.getCommands()) {
                if (command.selector == null || command.selector.startsWith("#Cells[")) continue;
                String first = command.selector.replaceAll("^#([A-Za-z0-9]+).*", "$1");
                if (!ids.contains(first)) throw new AssertionError(scene.name() + ": page has no #" + first + " for " + command.selector);
            }
        }
        for (var b : ClaimMapScreen.ACTIONS) if (!ids.contains(b.selector().substring(1))) throw new AssertionError("no " + b.selector());
    }

    static void writeAll(Path dir) throws Exception {
        Files.createDirectories(dir);
        World s = street(), e = edge();
        StringBuilder json = new StringBuilder("{\n \"cases\": [\n");
        List<Scene> scenes = scenes();
        Set<String> pictures = new HashSet<>();
        for (int i = 0; i < scenes.size(); i++) {
            Scene scene = scenes.get(i);
            var sit = scene.situation();
            World w = sit.view().world().equals("wilds") ? e : s;
            String pictureName = sit.picture() == null ? null : sit.picture().replaceAll(".*preview_(.*)\\.png", "$1");
            if (pictureName != null && pictures.add(pictureName)) {
                List<PlotRect> roads = w.roads();
                var scenePicture = ClaimMapLogic.scene(sit, sit.shown(), roads, w.playerX(), w.playerZ());
                Files.write(dir.resolve("EterniaMod_ClaimMap_preview_" + pictureName + ".png"), ClaimMapRenderer.png(ClaimMapRenderer.render(scenePicture, w.tiles())));
            }
            var c = new UICommandBuilder();
            ClaimMapScreen.open(c, sit.shown() != null ? sit.shown() : w.window());
            c.append("#CurrencyWallet", "EterniaMod/Wallet.ui");
            CurrencyUi.wallet(c, 4_250L, 120L);
            ClaimMapScreen.show(c, ClaimMapLogic.panel(sit));
            json.append("  {\n   \"name\": \"claim map: ").append(scene.title()).append("\",\n   \"steps\": [ {\n    \"tool\": \"ui_render\",\n")
                .append("    \"name\": \"").append(scene.name()).append("\",\n    \"arguments\": {\n")
                .append("     \"root\": \"projects\",\n     \"path\": \"").append(PROJECT).append("/src/main/resources/Common/UI/Custom/EterniaMod/ClaimMapPage.ui\",\n")
                .append("     \"crop\": \"page\",\n     \"output\": \"").append(PROJECT).append("/notes/pictures/hex-map/").append(scene.name()).append(".png\",\n")
                .append("     \"commands\": ").append(commands(c)).append("\n    },\n    \"expect\": { \"valid\": true }\n   } ]\n  }")
                .append(i + 1 < scenes.size() ? ",\n" : "\n");
        }
        json.append(" ]\n}\n");
        Files.writeString(dir.resolve("claim-map.test.json"), json.toString(), StandardCharsets.UTF_8);
    }

    static String commands(UICommandBuilder c) {
        StringBuilder out = new StringBuilder("[");
        var all = c.getCommands();
        for (int i = 0; i < all.length; i++) {
            var cmd = all[i];
            out.append(i == 0 ? "\n      " : ",\n      ").append("{\"type\": \"").append(cmd.type.name()).append('"');
            if (cmd.selector != null) out.append(", \"selector\": ").append(quote(cmd.selector));
            if (cmd.text != null) out.append(", \"text\": ").append(quote(cmd.text));
            if (cmd.data != null) out.append(", \"data\": ").append(cmd.data);
            out.append('}');
        }
        return out.append("\n     ]").toString();
    }

    private static String quote(String s) { return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'; }

    // ------------------------------------------------------------------------------------------------------------

    private static ClaimMapLogic.Situation rename(ClaimMapLogic.Situation s, String picture) {
        return new ClaimMapLogic.Situation(s.mode(), s.scope(), s.plotId(), s.account(), s.view(), s.names(), s.shown(), s.selected(), s.option(),
            s.confirming(), s.working(), s.result(), s.resultOk(), picturePath(picture), false, s.settings(), s.localTesting(), s.packedRule(), s.packedSpots());
    }

    private static ClaimMapLogic.Situation confirming(ClaimMapLogic.Situation s) {
        return new ClaimMapLogic.Situation(s.mode(), s.scope(), s.plotId(), s.account(), s.view(), s.names(), s.shown(), s.selected(), s.option(),
            true, false, null, false, s.picture(), false, s.settings(), s.localTesting(), s.packedRule(), s.packedSpots());
    }

    private static ClaimMapLogic.Situation result(ClaimMapLogic.Situation s, Message m) {
        return new ClaimMapLogic.Situation(s.mode(), s.scope(), s.plotId(), s.account(), s.view(), s.names(), s.shown(), s.selected(), s.option(),
            false, false, m, false, s.picture(), false, s.settings(), s.localTesting(), s.packedRule(), s.packedSpots());
    }

    private static ClaimMapLogic.Situation loading(ClaimMapLogic.Situation s) {
        return new ClaimMapLogic.Situation(s.mode(), s.scope(), s.plotId(), s.account(), s.view(), s.names(), null, null, 0,
            false, false, null, false, null, true, s.settings(), s.localTesting(), s.packedRule(), s.packedSpots());
    }

    /** The street without "my" plot, for a newcomer's first look. */
    private static World withoutMine(World w) {
        List<HexLandView.PlotInfo> plots = new ArrayList<>(w.view().plots().values());
        plots.removeIf(p -> p.plotId().equals(w.myPlot()));
        var view = new HexLandView(w.view().world(), L, w.view().streetCells(), w.view().blockedCells(), w.view().reservedCells(), plots);
        return new World(view, w.window(), w.me(), w.myGuild(), w.myPlot(), w.frontage(), w.neighbour(), w.streetCell(), w.far(), w.roads(), w.playerX(), w.playerZ(), w.names(), w.tiles());
    }

    /** A frontage cell with room for an upgraded triangle plot around it. */
    static HexCoord roomy(World w, Account upgraded) {
        HexCoord home = L.cellAt((int) w.playerX(), (int) w.playerZ());
        return nearest(w.window(), home, c -> w.view().isFree(c) && w.view().isFrontage(c)
            && ClaimMapLogic.options(situation(w, Mode.CLAIM, HousingRules.Scope.PUBLIC, null, upgraded, c, 0)).stream().filter(o -> o.result().valid()).count() >= 2);
    }

    private static HexCoord nearestFree(World w, HexCoord near) {
        return nearest(w.window(), near, c -> w.view().isFree(c) && c.neighbors().stream().anyMatch(w.view().guildLand(w.myGuild())::contains));
    }

    private static HexCoord nearest(ClaimMapGeometry g, HexCoord from, java.util.function.Predicate<HexCoord> ok) {
        return g.slots().stream().map(ClaimMapGeometry.Slot::cell).filter(c -> c.r() > g.row0() && c.r() < g.row0() + ClaimMapGeometry.ROWS - 1)
            .filter(ok).min(Comparator.comparingInt((HexCoord c) -> c.distance(from)).thenComparing(HexCoord::r).thenComparing(HexCoord::q)).orElseThrow();
    }

    /** Puts a shape as close as possible to {@code near} on free cells; touching the street when {@code street} is given. */
    private static Set<HexCoord> place(ClaimMapGeometry g, Set<HexCoord> taken, Set<HexCoord> shape, HexCoord near, Set<HexCoord> street) {
        List<HexCoord> anchors = new ArrayList<>(g.visibleCells());
        anchors.sort(Comparator.comparingInt((HexCoord c) -> c.distance(near)).thenComparing(HexCoord::r).thenComparing(HexCoord::q));
        for (HexCoord a : anchors) {
            Set<HexCoord> cells = new HashSet<>();
            for (HexCoord c : shape) cells.add(c.plus(a));
            if (cells.stream().anyMatch(taken::contains)) continue;
            if (street != null && cells.stream().noneMatch(c -> c.neighbors().stream().anyMatch(street::contains))) continue;
            taken.addAll(cells);
            return Set.copyOf(cells);
        }
        throw new IllegalStateException("No room for a shape near " + near);
    }

    /** A road along a curve, cut into Road Designer sized pieces (nodes 80 blocks apart, each piece under 512). */
    private static List<PlotRect> curve(int from, int to, java.util.function.DoubleUnaryOperator across, boolean eastWest) {
        List<PlotRect> out = new ArrayList<>();
        for (int start = from; start < to; start += 320) {
            List<SplineGeometry.Node> nodes = new ArrayList<>();
            for (int t = start; t <= Math.min(to, start + 320); t += 80) {
                double a = across.applyAsDouble(t);
                nodes.add(eastWest ? new SplineGeometry.Node(t + 0.5, 64, a) : new SplineGeometry.Node(a, 64, t + 0.5));
            }
            if (nodes.size() >= 2) out.addAll(road(nodes, 5));
        }
        return out;
    }

    private static List<PlotRect> road(List<SplineGeometry.Node> nodes, int width) {
        var columns = SplineGeometry.footprint(nodes, width).stream().map(SplineGeometry.Hint::column).toList();
        return SplineGeometry.rectangles(columns);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Procedural terrain in the world map's style: flat colours, soft relief shading, water, sand, trees and paths.
    // ------------------------------------------------------------------------------------------------------------

    static ClaimMapRenderer.TileSource terrain(List<PlotRect> roads, int lastChunkX) {
        return (cx, cz) -> {
            if (cx > lastChunkX) return null;
            int[] px = new int[32 * 32];
            for (int y = 0; y < 32; y++)
                for (int x = 0; x < 32; x++) {
                    int bx = cx * 32 + x, bz = cz * 32 + y;
                    px[y * 32 + x] = colour(bx, bz, roads);
                }
            return new ClaimMapRenderer.Tile(32, 32, px);
        };
    }

    private static int colour(int x, int z, List<PlotRect> roads) {
        for (PlotRect r : roads) if (r.contains(x, z)) return shade(0xB8A47C, 0.95 + 0.08 * hash(x, z));
        double river = 300 + 46 * Math.sin(x / 95.0) + 18 * Math.sin(x / 31.0);
        double d = Math.abs(z - river);
        if (d < 11) return shade(0x3C77A8, 0.92 + 0.10 * noise(x / 18.0, z / 18.0));
        if (d < 15) return shade(0xCDBB8B, 0.95 + 0.05 * hash(x, z));
        double h = noise(x / 70.0, z / 70.0) * 0.7 + noise(x / 23.0, z / 23.0) * 0.3;
        double shadeLight = 0.88 + 0.22 * (noise((x - 3) / 40.0, (z - 3) / 40.0) - noise(x / 40.0, z / 40.0) + 0.5);
        int base = h > 0.62 ? 0x3E6B33 : h > 0.38 ? 0x5F9142 : 0x76A84E;
        if (h > 0.62 && hash(x / 3, z / 3) > 0.55) base = 0x2F5A2A;
        if (h < 0.25 && hash(x, z) > 0.97) base = 0xD9C76A;
        return shade(base, shadeLight * (0.96 + 0.06 * hash(x, z)));
    }

    private static int shade(int rgb, double k) {
        int r = clamp((int) (((rgb >> 16) & 0xFF) * k)), g = clamp((int) (((rgb >> 8) & 0xFF) * k)), b = clamp((int) ((rgb & 0xFF) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static double hash(int x, int z) {
        long h = x * 374761393L + z * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
    }

    private static double noise(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        double a = hash(x0, z0), b = hash(x0 + 1, z0), c = hash(x0, z0 + 1), d = hash(x0 + 1, z0 + 1);
        return (a + (b - a) * sx) * (1 - sz) + (c + (d - c) * sx) * sz;
    }
}
