package com.hexvane.eterniamod.town;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandEvents;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.terraform.TerraformBlocks;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import com.hexvane.eterniamod.pathtool.SplineGeometry;
import com.hexvane.eterniamod.pathtool.TownRoadAuthor;
import com.hexvane.eterniamod.setup.ManagedHubServices;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.townroads.GrownRoadPlan;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Grows a town's road network around a centre, the grown street plan of the road growth simulation
 * ({@link GrownRoadPlan}), built road by road through the Road Designer's own review and confirm ({@link TownRoadAuthor}),
 * with wooden bridges where a road has to cross water.
 *
 * <p>Two centres: the plaza an administrator stands in or nearest to (the Hub square; roads grow from the streets that
 * already leave it and never cross it), or a new portal town: the town's spawn platform pasted where the administrator
 * stands, with a world portal in its middle, registered as a public plaza, roads growing from its edges.
 *
 * <p>The land is surveyed once and saved with the network. A network can be built whole, or set to grow: then roads are
 * added a few at a time as homes are claimed, whenever the free land beside the roads runs low, planned again from the
 * saved land and the claims made since. Every network is recorded so {@code /e admin town roads undo} takes away the
 * nearest one: its roads, bridges, portal and platform.
 */
public final class TownRoadNetwork {
    private TownRoadNetwork() {}

    public enum Mode { PLAZA, PORTAL }

    public static final int DEFAULT_RADIUS = 7, MAX_RADIUS = 12, WIDTH = 5;
    /** Hexagons per road at most: shorter roads clear less at once and fail alone. */
    static final int MAX_CHAIN = 6;
    /** Height change per block of road between two hexagon centres, and the height range inside a hexagon, a road may take. */
    static final double MAX_GRADE = 0.28;
    static final int MAX_ROAD_RELIEF = 14, MAX_PLOT_RELIEF = 22;
    /** The longest stretch of water a bridge crosses, in blocks. */
    static final int MAX_BRIDGE = 40;
    /** A growing network keeps at least this many free hexagons beside its roads. */
    static final int LEAD = 12;
    /** The spawn platform: the town's centre piece, 65 x 65 blocks round its anchor. */
    static final String PLATFORM = "EterniaMod/Hub/Eternia_Town_Center";
    static final int PLATFORM_HALF = 32, SKIRT = 8, PLATFORM_HEIGHT = 46;
    static final int SAMPLE = 4;
    static final String DECK = "Wood_Darkwood_Planks", RAIL = "Wood_Darkwood_Fence", POST = "Wood_Darkwood_Trunk";

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
    private static final ExecutorService JOBS = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "Eternia town roads"); t.setDaemon(true); return t; });
    private static final Set<String> GROWTH_QUEUED = ConcurrentHashMap.newKeySet();
    private static volatile boolean busy;
    private static Runnable stopListening;

    /** A planned network waiting for the administrator's yes. */
    public record Preview(UUID actor, String world, Mode mode, int[] anchor, Saved land, List<Road> roads, int cells, String summary, Instant expires, byte[] picture) {
        public PlotRect plaza() { return land.plazaRect(); }
    }
    /** One road to build: its hexagons and the points it runs through (x, ground y or NaN, z). */
    public record Road(GrownRoadPlan.Chain chain, List<double[]> nodes) {}

    // ---- the saved land ------------------------------------------------------------------------------------------------
    /**
     * Everything the planner needs, saved with the network so it can plan again after claims without a new survey.
     * {@code cells}: q, r, roadOk, claimable, water, relief, height. {@code edges}: from q, r, via q, r (or NaN), to q, r,
     * cost. {@code attach}: road columns (x, z, y) of streets that were there before, by hexagon.
     */
    public static final class Saved {
        String mode;
        int[] plaza, center;
        int radius;
        List<double[]> cells = new ArrayList<>(), edges = new ArrayList<>();
        Map<String, List<int[]>> attach = new HashMap<>();

        PlotRect plazaRect() { return new PlotRect(plaza[0], plaza[1], plaza[2], plaza[3]); }
        Mode modeValue() { return Mode.valueOf(mode); }
        HexCoord centerCell() { return new HexCoord(center[0], center[1]); }
    }

    /** The saved land unpacked for planning. */
    static final class Model {
        final Saved saved;
        final HexLattice lattice;
        final Mode mode;
        final PlotRect plaza;
        final HexCoord center;
        final Map<HexCoord, GrownRoadPlan.Land> land = new HashMap<>();
        final Map<HexCoord, Double> height = new HashMap<>();
        final Map<String, Double> edges = new HashMap<>();
        final Map<HexCoord, List<int[]>> attach = new HashMap<>();
        final Set<HexCoord> plazaCells;
        final List<HexCoord> disk;

        Model(Saved s, HexLattice lattice) {
            this.saved = s;
            this.lattice = lattice;
            this.mode = s.modeValue();
            this.plaza = s.plazaRect();
            this.center = s.centerCell();
            this.disk = HexCoord.disk(center, s.radius);
            this.plazaCells = lattice.cellsTouching(plaza);
            for (double[] c : s.cells) {
                var cell = new HexCoord((int) c[0], (int) c[1]);
                land.put(cell, new GrownRoadPlan.Land(c[2] > 0, c[3] > 0, c[5], c[4] > 0));
                height.put(cell, c[6]);
            }
            for (double[] e : s.edges) edges.put(key(new HexCoord((int) e[0], (int) e[1]), Double.isNaN(e[2]) ? null : new HexCoord((int) e[2], (int) e[3]), new HexCoord((int) e[4], (int) e[5])), e[6]);
            s.attach.forEach((k, v) -> attach.put(HexCoord.parse(k), v));
        }
    }

    static String key(HexCoord a, HexCoord via, HexCoord b) { return a + "|" + (via == null ? "-" : via) + "|" + b; }

    // ---- the record of what was built ----------------------------------------------------------------------------------
    static final class Network {
        String id, world, mode, state, plazaArea, portal, actor;
        int[] center, anchor;
        long created;
        boolean growing;
        /** Free hexagons beside the roads a growing network keeps; 0 means the default. */
        int lead;
        Saved land;
        List<String> roads = new ArrayList<>(), entities = new ArrayList<>(), areas = new ArrayList<>();
        /** Hexagons this network's roads run through, and road steps that could not be built. */
        List<String> built = new ArrayList<>(), failed = new ArrayList<>(), backedUp = new ArrayList<>();
        List<TownBuilder.Backup> backups = new ArrayList<>();
    }
    static final class Ledger { List<Network> networks = new ArrayList<>(); }

    static Path ledgerPath(EterniaModPlugin plugin, String world) { return TownBuilder.folder(plugin).resolve("roads-" + world.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json"); }
    static synchronized Ledger ledger(EterniaModPlugin plugin, String world) throws IOException {
        var path = ledgerPath(plugin, world);
        if (!Files.exists(path)) return new Ledger();
        var l = JSON.fromJson(Files.readString(path), Ledger.class);
        return l == null || l.networks == null ? new Ledger() : l;
    }
    static synchronized void save(EterniaModPlugin plugin, String world, Ledger ledger) throws IOException {
        Files.createDirectories(TownBuilder.folder(plugin));
        var path = ledgerPath(plugin, world);
        var tmp = path.resolveSibling(path.getFileName() + ".pending");
        Files.writeString(tmp, JSON.toJson(ledger));
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    private static void update(EterniaModPlugin plugin, String world, Network n) throws IOException {
        var l = ledger(plugin, world);
        l.networks.removeIf(o -> o.id.equals(n.id));
        l.networks.add(n);
        save(plugin, world, l);
    }

    // ---- growing as homes are claimed ---------------------------------------------------------------------------------
    /** Listens for claims: a growing network near them adds roads when its free land beside roads runs low. */
    public static void register(EterniaModPlugin plugin) {
        if (stopListening != null) stopListening.run();
        stopListening = HexLandEvents.listen(change -> {
            if (change.cause() != HexLandEvents.Cause.CLAIM && change.cause() != HexLandEvents.Cause.EXPAND) return;
            if (!GROWTH_QUEUED.add(change.world())) return;
            JOBS.execute(() -> {
                GROWTH_QUEUED.remove(change.world());
                var world = Universe.get().getWorld(change.world());
                if (world == null) return;
                try {
                    for (var n : ledger(plugin, change.world()).networks)
                        if (n.growing && n.land != null && ("built".equals(n.state) || "growing".equals(n.state))) grow(plugin, world, n, false, null);
                } catch (Exception e) { plugin.getLogger().atWarning().withCause(e).log("Town roads could not grow in %s", change.world()); }
            });
        });
    }

    /** What the land looks like now: streets and taken hexagons inside the network. World thread. */
    private record Now(Set<HexCoord> street, Set<HexCoord> taken) {}

    private static Now now(EterniaModPlugin plugin, World world, Model m) {
        var view = HexLand.view(plugin, world).orElseThrow(() -> new IllegalStateException("This world has no hexagon plots"));
        Set<HexCoord> street = new HashSet<>(), taken = new HashSet<>();
        for (HexCoord c : m.disk) {
            if (view.isStreet(c)) street.add(c);
            if (view.plotAt(c) != null || view.reservedCells().contains(c) || view.blockedCells().contains(c)) taken.add(c);
        }
        return new Now(street, taken);
    }

    /** Free hexagons beside a road, the land a newcomer could claim right now. */
    static int frontage(Model m, Now now) {
        int count = 0;
        for (HexCoord c : m.disk) {
            var l = m.land.get(c);
            if (l == null || !l.claimable() || now.street().contains(c) || now.taken().contains(c)) continue;
            if (c.neighbors().stream().anyMatch(now.street()::contains)) count++;
        }
        return count;
    }

    /** Plans from the saved land and the land as it is now: built roads and old streets sprout, claims are off limits. */
    static List<Road> plan(Model m, Now now, Set<HexCoord> built, Set<String> failed, int[] reach) {
        Map<HexCoord, GrownRoadPlan.Land> land = new HashMap<>();
        m.land.forEach((c, l) -> {
            boolean off = now.street().contains(c) || now.taken().contains(c);
            land.put(c, off ? new GrownRoadPlan.Land(false, false, l.relief(), l.water() && !now.taken().contains(c)) : l);
        });
        Set<HexCoord> sources = new LinkedHashSet<>(built);
        if (m.mode == Mode.PORTAL) sources.addAll(m.plazaCells);
        for (HexCoord c : m.attach.keySet()) if (now.street().contains(c)) sources.add(c);
        sources.retainAll(new HashSet<>(m.disk));
        Set<HexCoord> blockers = new LinkedHashSet<>();
        for (HexCoord c : m.disk) if ((now.street().contains(c) || m.plazaCells.contains(c)) && !sources.contains(c)) blockers.add(c);
        if (sources.isEmpty()) return List.of();
        var settings = new GrownRoadPlan.Settings(2, m.mode == Mode.PORTAL ? 2 : 1, 400, 0.03);
        var result = GrownRoadPlan.grow(m.center, land, sources, blockers, (a, via, b) -> failed.contains(key(a, via, b)) ? null : m.edges.get(key(a, via, b)), settings);
        if (reach != null) reach[0] = result.cells().size();
        List<Road> roads = new ArrayList<>();
        for (var chain : GrownRoadPlan.chains(result, sources, MAX_CHAIN)) roads.add(new Road(chain, nodes(m, chain, built)));
        return roads;
    }

    /**
     * Adds roads to a network: all of the plan when {@code all}, otherwise just enough to keep {@link #LEAD} free
     * hexagons beside the roads. Background thread.
     */
    private static int grow(EterniaModPlugin plugin, World world, Network n, boolean all, PlayerRef player) throws Exception {
        var lattice = plugin.getInfrastructure().world(world.getName()).orElseThrow().lattice();
        var m = new Model(n.land, lattice);
        Set<HexCoord> built = new LinkedHashSet<>();
        n.built.forEach(c -> built.add(HexCoord.parse(c)));
        Set<String> failed = new HashSet<>(n.failed);
        int added = 0, skipped = 0, target = all ? Integer.MAX_VALUE : n.lead > 0 ? n.lead : LEAD;
        for (int round = 0; round < 200; round++) {
            Now now = TownBuilder.supply(world, () -> now(plugin, world, m));
            if (frontage(m, now) >= target) break;
            var roads = plan(m, now, built, failed, null);
            Road next = roads.stream().filter(r -> r.chain().fromSource() || built.contains(r.chain().start())).findFirst().orElse(null);
            if (next == null) break;
            var chain = next.chain();
            try {
                if (!chain.bridge()) prepare(plugin, world, n, lattice, next);
                List<UUID> ids = chain.bridge() ? buildBridge(plugin, world, UUID.fromString(n.actor), n, next) : List.of(retrying(() -> buildRoad(world, UUID.fromString(n.actor), next)));
                ids.forEach(id -> n.roads.add(id.toString()));
                for (int i = 1; i < chain.cells().size(); i++) if (!chain.bridge() || i == chain.cells().size() - 1) built.add(chain.cells().get(i));
                added++;
                if (player != null && added % 5 == 0) TownBuilder.tell(player, "Built " + added + " roads...");
            } catch (Exception e) {
                skipped++;
                failed.add(key(chain.start(), chain.bridge() && chain.cells().size() == 3 ? chain.cells().get(1) : null, chain.bridge() ? chain.end() : chain.cells().get(1)));
                plugin.getLogger().atInfo().log("Town road from %s to %s skipped: %s", chain.start(), chain.end(), TownBuilder.cause(e));
            }
            n.built = built.stream().map(HexCoord::toString).toList();
            n.failed = List.copyOf(failed);
            update(plugin, world.getName(), n);
        }
        if (added > 0 && !all && player == null) plugin.getLogger().atInfo().log("Town roads grew by %d roads in %s as homes were claimed", added, world.getName());
        if (player != null) TownBuilder.tell(player, added == 0 && !all ? "There is still free land beside the roads; more roads come as homes are claimed."
            : "Town roads done: " + added + " roads built" + (skipped > 0 ? ", " + skipped + " left out where the land or a building was in the way." : "."));
        return added;
    }

    @FunctionalInterface interface Attempt<T> { T run() throws Exception; }

    /** Retries while chunks are still being saved or loaded, as town placement does. */
    static <T> T retrying(Attempt<T> attempt) throws Exception {
        for (int i = 1; ; i++) {
            try { return attempt.run(); }
            catch (Exception e) {
                String why = TownBuilder.cause(e).toLowerCase(Locale.ROOT);
                if (i >= 40 || !(why.contains("in flight") || why.contains("retry") || why.contains("loading"))) throw e;
                Thread.sleep(250);
            }
        }
    }

    // ---- planning ------------------------------------------------------------------------------------------------------
    /**
     * Surveys the land and plans the network. {@code x}, {@code z} is where the administrator stands: the new platform's
     * middle in portal mode, or the point the nearest plaza is found from.
     */
    public static CompletableFuture<Preview> preview(EterniaModPlugin plugin, World world, UUID actor, Mode mode, int x, int z, int radius, PlayerRef player) {
        if (busy) return CompletableFuture.failedFuture(new IllegalStateException("Town roads are already being built or removed"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                SetupAccess.require(actor);
                return planNew(plugin, world, actor, mode, x, z, Math.max(2, Math.min(MAX_RADIUS, radius)), player);
            } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e.getMessage(), e); }
        }, JOBS);
    }

    private static Preview planNew(EterniaModPlugin plugin, World world, UUID actor, Mode mode, int x, int z, int radius, PlayerRef player) throws Exception {
        var worldPlan = plugin.getInfrastructure().world(world.getName()).orElseThrow(() -> new IllegalStateException("Register this world in /e admin setup first"));
        if (!worldPlan.supportsHousing() || worldPlan.lattice() == null) throw new IllegalStateException("Town roads grow on the housing hexagons. Give this world housing in /e admin setup first");
        HexLattice lattice = worldPlan.lattice();
        PlotRect plaza;
        int[] anchor = null;
        if (mode == Mode.PLAZA) {
            plaza = worldPlan.portals().stream().min(Comparator.comparingDouble(r -> gap(r, x, z)))
                .orElseThrow(() -> new IllegalStateException("This world has no plaza. Register one in /e admin setup, or place a portal town"));
            if (gap(plaza, x, z) > 300) throw new IllegalStateException("Stand in or near the plaza the roads should grow around");
        } else {
            plaza = new PlotRect(x - PLATFORM_HALF, z - PLATFORM_HALF, 2 * PLATFORM_HALF + 1, 2 * PLATFORM_HALF + 1);
            var around = new PlotRect(plaza.x() - SKIRT, plaza.z() - SKIRT, plaza.width() + 2 * SKIRT, plaza.depth() + 2 * SKIRT);
            TownBuilder.tell(player, "Checking the land for the platform...");
            TownBuilder.loadArea(world, around.x(), around.z(), around.endX() - 1, around.endZ() - 1);
            int[] heights = new int[1];
            TownBuilder.on(world, () -> {
                var view = HexLand.view(plugin, world).orElse(null);
                List<Integer> ground = new ArrayList<>();
                for (int cx = around.x(); cx < around.endX(); cx++) for (int cz = around.z(); cz < around.endZ(); cz++) {
                    if (plugin.getInfrastructure().protectedColumn(world.getName(), cx, cz) || (view != null && view.plotAtColumn(cx, cz) != null))
                        throw new IllegalStateException("The platform would cover a road, plaza or plot near " + cx + ", " + cz + ". Stand somewhere clear");
                    if (plaza.contains(cx, cz)) { int n = TownBuilder.natural(world, cx, cz); if (n != TownTerrainPlanner.UNKNOWN) ground.add(n); }
                }
                if (ground.isEmpty()) throw new IllegalStateException("No ground found under the platform");
                Collections.sort(ground);
                heights[0] = ground.get(ground.size() / 2);
            }).join();
            // The platform's floor is its layer one below the anchor, so the anchor sits one above the ground it replaces.
            anchor = new int[] {x, heights[0] + 1, z};
        }
        HexCoord center = lattice.cellAt((int) Math.floor(plaza.x() + plaza.width() / 2.0), (int) Math.floor(plaza.z() + plaza.depth() / 2.0));
        List<HexCoord> disk = HexCoord.disk(center, radius);
        TownBuilder.tell(player, "Surveying " + disk.size() + " hexagons of land...");
        var saved = new Saved();
        saved.mode = mode.name();
        saved.plaza = new int[] {plaza.x(), plaza.z(), plaza.width(), plaza.depth()};
        saved.center = new int[] {center.q(), center.r()};
        saved.radius = radius;
        survey(plugin, world, worldPlan, lattice, disk, saved);
        var m = new Model(saved, lattice);
        Now now = TownBuilder.supply(world, () -> now(plugin, world, m));
        int[] reach = new int[1];
        var roads = plan(m, now, Set.of(), Set.of(), reach);
        if (roads.isEmpty()) {
            long open = m.land.values().stream().filter(GrownRoadPlan.Land::roadOk).count();
            throw new IllegalStateException("There is no room for new roads here: the land is taken, too steep or under water ("
                + m.land.size() + " of " + disk.size() + " hexagons read, " + open + " open for roads)");
        }
        int blocks = 0, bridges = 0;
        for (var r : roads) {
            if (r.chain().bridge()) bridges++;
            for (int i = 1; i < r.nodes().size(); i++) blocks += (int) Math.round(Math.hypot(r.nodes().get(i)[0] - r.nodes().get(i - 1)[0], r.nodes().get(i)[2] - r.nodes().get(i - 1)[2]));
        }
        String where = mode == Mode.PORTAL ? "A portal town with its spawn platform and a portal in the middle goes here. " : "Roads grow out from the plaza's streets and never cross the plaza. ";
        String summary = where + "The full plan is " + roads.size() + " roads" + (bridges > 0 ? " with " + bridges + (bridges == 1 ? " bridge" : " bridges") : "") + ", about " + blocks
            + " blocks long, reaching " + reach[0] + " hexagons within " + radius + " of the middle. Build it all now, or let it grow: roads are then added a few at a time as homes are claimed, "
            + "whenever free land beside the roads runs low. Each road levels and clears its own hexagons first. /e admin town roads undo takes it all away again.";
        TownBuilder.tell(player, "Drawing the plan...");
        // The newest plan picture is also kept beside the town records, to look at outside the game.
        byte[] picture = null;
        try {
            var frame = TownRoadPicture.frame(lattice, disk);
            picture = TownRoadPicture.paint(lattice, disk, frame, TownRoadPicture.tiles(world, frame), worldPlan.roads(), worldPlan.portals(), plaza, roads);
            try { Files.createDirectories(TownBuilder.folder(plugin)); Files.write(TownBuilder.folder(plugin).resolve("last-plan.png"), picture); } catch (IOException ignored) { /* Only a convenience copy. */ }
        } catch (RuntimeException e) { plugin.getLogger().atWarning().withCause(e).log("Town road plan picture failed"); }
        return new Preview(actor, world.getName(), mode, anchor, saved, List.copyOf(roads), reach[0], summary, Instant.now().plusSeconds(600), picture);
    }

    private static double gap(PlotRect r, int x, int z) {
        double dx = Math.max(0, Math.max(r.x() - x, x - (r.endX() - 1))), dz = Math.max(0, Math.max(r.z() - z, z - (r.endZ() - 1)));
        return Math.hypot(dx, dz);
    }

    /** Ground, water and keep-off facts on a 4 block grid while surveying. */
    static final class Columns {
        final Map<Long, int[]> columns = new HashMap<>(); // key -> {ground, wet(0/1), blocked(0/1)}
        int[] at(int x, int z) {
            int bx = Math.floorDiv(x, SAMPLE) * SAMPLE, bz = Math.floorDiv(z, SAMPLE) * SAMPLE;
            int[] c = columns.get(TownBuilder.key(bx, bz));
            if (c != null) return c;
            for (int dx = 0; dx <= SAMPLE; dx += SAMPLE) for (int dz = 0; dz <= SAMPLE; dz += SAMPLE) { c = columns.get(TownBuilder.key(bx + dx, bz + dz)); if (c != null) return c; }
            return null;
        }
    }

    /** Where a road from s towards t starts: the nearest old road column in s, a point just inside the platform, or s's middle. y is the ground there, or NaN. */
    static double[] start(HexLattice lattice, Mode mode, PlotRect plaza, Map<HexCoord, List<int[]>> attach, Map<HexCoord, Double> height, Set<HexCoord> built, HexCoord s, HexCoord t) {
        double tx = lattice.centerX(t), tz = lattice.centerZ(t);
        var roads = attach.get(s);
        if (roads != null && !roads.isEmpty() && !built.contains(s)) {
            int[] best = null; double d = Double.MAX_VALUE;
            for (int[] c : roads) { double e = Math.hypot(c[0] + .5 - tx, c[1] + .5 - tz); if (e < d) { d = e; best = c; } }
            return new double[] {best[0] + .5, best[2], best[1] + .5};
        }
        if (mode == Mode.PORTAL && lattice.cellsTouching(plaza).contains(s) && !built.contains(s)) {
            double x = Math.max(plaza.x() + 2.5, Math.min(plaza.endX() - 2.5, tx)), z = Math.max(plaza.z() + 2.5, Math.min(plaza.endZ() - 2.5, tz));
            return new double[] {x, Double.NaN, z};
        }
        Double h = height.get(s);
        return new double[] {lattice.centerX(s), h == null ? Double.NaN : h, lattice.centerZ(s)};
    }

    /** The cost of a road from s to t (over via): null when it may not run, 0 on dry land, more for a longer bridge. */
    static Double cost(HexLattice lattice, Mode mode, PlotRect plaza, Map<HexCoord, List<int[]>> attach, Map<HexCoord, Double> height, Columns cols, HexCoord s, HexCoord via, HexCoord t) {
        double[] a = start(lattice, mode, plaza, attach, height, Set.of(), s, t), b = {lattice.centerX(t), 0, lattice.centerZ(t)};
        Double ht = height.get(t);
        double len = Math.hypot(b[0] - a[0], b[2] - a[2]);
        if (ht == null || len < 1) return null;
        if (!Double.isNaN(a[1]) && Math.abs(a[1] - ht) / len > MAX_GRADE) return null;
        int steps = (int) Math.ceil(len / 2), wet = 0;
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            int x = (int) Math.floor(a[0] + (b[0] - a[0]) * f), z = (int) Math.floor(a[2] + (b[2] - a[2]) * f);
            if (mode == Mode.PORTAL && plaza.contains(x, z)) continue;
            int[] c = cols.at(x, z);
            if (c == null || c[2] == 1) return null;
            if (c[1] == 1) wet++;
        }
        double water = wet * len / Math.max(1, steps);
        if (water == 0) return via == null ? 0.0 : null;
        if (water > MAX_BRIDGE) return null;
        return 1 + water / 10;
    }

    /** Reads the land of every hexagon, then the cost of every road step between them, into {@code saved}. */
    private static void survey(EterniaModPlugin plugin, World world, HousingInfrastructure.WorldPlan worldPlan, HexLattice lattice, List<HexCoord> disk, Saved saved) {
        var mode = saved.modeValue();
        var plaza = saved.plazaRect();
        HexLandView[] views = new HexLandView[1];
        TownBuilder.on(world, () -> views[0] = HexLand.view(plugin, world).orElseThrow(() -> new IllegalStateException("This world has no hexagon plots"))).join();
        var view = views[0];
        var portals = worldPlan.portals();
        Set<HexCoord> inDisk = new HashSet<>(disk);
        // Old road columns outside every plaza, by hexagon: where new roads join the old ones.
        Map<HexCoord, List<int[]>> attach = new HashMap<>();
        Set<Long> roadSet = new HashSet<>();
        for (var rect : worldPlan.roads()) for (int x = rect.x(); x < rect.endX(); x++) for (int z = rect.z(); z < rect.endZ(); z++) {
            int fx = x, fz = z;
            if (portals.stream().anyMatch(p -> p.contains(fx, fz))) continue;
            roadSet.add(TownBuilder.key(x, z));
            HexCoord c = lattice.cellAt(x, z);
            if (inDisk.contains(c)) attach.computeIfAbsent(c, k -> new ArrayList<>()).add(new int[] {x, z, 0});
        }
        var cols = new Columns();
        Map<HexCoord, GrownRoadPlan.Land> land = new HashMap<>();
        Map<HexCoord, Double> height = new HashMap<>();
        List<HexCoord> order = new ArrayList<>(disk);
        order.sort(HexCoord.READING_ORDER);
        for (int i = 0; i < order.size(); i += 6) {
            var batch = order.subList(i, Math.min(order.size(), i + 6));
            var box = lattice.bounds(batch);
            TownBuilder.loadArea(world, box.x() - 2, box.z() - 2, box.endX() + 2, box.endZ() + 2);
            TownBuilder.on(world, () -> {
                for (HexCoord c : batch) readCell(plugin, world, view, lattice, c, cols, roadSet, attach, land, height, plaza);
            }).join();
        }
        // Every road step between neighbours, and every bridge over a water hexagon.
        for (HexCoord s : disk) for (int d = 0; d < 6; d++) {
            HexCoord t = s.neighbor(d);
            if (!inDisk.contains(t)) continue;
            Double c = cost(lattice, mode, plaza, attach, height, cols, s, null, t);
            if (c != null) saved.edges.add(new double[] {s.q(), s.r(), Double.NaN, Double.NaN, t.q(), t.r(), c});
            var lt = land.get(t);
            HexCoord t2 = t.neighbor(d);
            if (lt != null && lt.water() && inDisk.contains(t2)) {
                Double b = cost(lattice, mode, plaza, attach, height, cols, s, t, t2);
                if (b != null) saved.edges.add(new double[] {s.q(), s.r(), t.q(), t.r(), t2.q(), t2.r(), b});
            }
        }
        land.forEach((c, l) -> saved.cells.add(new double[] {c.q(), c.r(), l.roadOk() ? 1 : 0, l.claimable() ? 1 : 0, l.water() ? 1 : 0, l.relief(), height.getOrDefault(c, 0.0)}));
        attach.forEach((c, v) -> saved.attach.put(c.toString(), v));
    }

    private static void readCell(EterniaModPlugin plugin, World world, HexLandView view, HexLattice lattice, HexCoord c, Columns cols, Set<Long> roadSet,
                                 Map<HexCoord, List<int[]>> attach, Map<HexCoord, GrownRoadPlan.Land> land, Map<HexCoord, Double> height, PlotRect plaza) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, wet = 0, count = 0;
        long sum = 0;
        boolean blocked = false;
        for (var span : lattice.spans(c)) {
            if (Math.floorMod(span.z(), SAMPLE) != 0) continue;
            for (int x = span.fromX(); x < span.toX(); x++) {
                if (Math.floorMod(x, SAMPLE) != 0) continue;
                int z = span.z();
                int g = TownBuilder.natural(world, x, z);
                boolean water = g != TownTerrainPlanner.UNKNOWN && hasFluid(world, x, g + 1, z);
                boolean keepOff = !roadSet.contains(TownBuilder.key(x, z)) && plugin.getInfrastructure().protectedColumn(world.getName(), x, z) || view.plotAtColumn(x, z) != null
                    || ManagedHubServices.intersectsManaged(world.getName(), new PlotRect(x, z, 1, 1));
                cols.columns.put(TownBuilder.key(x, z), new int[] {g, water || g == TownTerrainPlanner.UNKNOWN ? 1 : 0, keepOff ? 1 : 0});
                if (g == TownTerrainPlanner.UNKNOWN) { blocked = true; continue; }
                count++;
                if (water) wet++;
                if (keepOff && !plaza.contains(x, z)) blocked = true;
                min = Math.min(min, g); max = Math.max(max, g); sum += g;
            }
        }
        var roads = attach.get(c);
        if (roads != null) for (int[] r : roads) {
            int g = TownBuilder.natural(world, r[0], r[1]);
            r[2] = g == TownTerrainPlanner.UNKNOWN ? (count == 0 ? 0 : (int) (sum / Math.max(1, count))) : g;
        }
        if (count == 0) return;
        double relief = max - min, mean = sum / (double) count;
        height.put(c, mean);
        int[] middle = cols.at((int) lattice.centerX(c), (int) lattice.centerZ(c));
        boolean centerWet = middle != null && middle[1] == 1;
        boolean owned = view.plotAt(c) != null || view.reservedCells().contains(c) || view.blockedCells().contains(c);
        boolean street = view.isStreet(c);
        boolean claimable = !street && !owned && wet * 5 < count && relief <= MAX_PLOT_RELIEF;
        boolean roadOk = !street && !owned && !blocked && !centerWet && wet * 4 <= count && relief <= MAX_ROAD_RELIEF;
        boolean water = !owned && !blocked && (centerWet || wet * 2 >= count);
        land.put(c, new GrownRoadPlan.Land(roadOk, claimable, relief, water));
    }

    static boolean hasFluid(World world, int x, int y, int z) {
        if (y < 0 || y >= NativeSnapshotStore.MAX_Y) return false;
        var section = ChunkSectionBlockUtil.sectionRefAt(world, x, y, z);
        if (section == null) return false;
        var fluids = world.getChunkStore().getStore().getComponent(section, FluidSection.getComponentType());
        return fluids != null && fluids.getFluidId(x, y, z) != Fluid.EMPTY_ID;
    }

    /** The points a road runs through: its start, then each hexagon's middle, with a midpoint between. A bridge is just its two ends. */
    private static List<double[]> nodes(Model m, GrownRoadPlan.Chain chain, Set<HexCoord> built) {
        List<double[]> out = new ArrayList<>();
        var cells = chain.cells();
        HexCoord second = chain.bridge() ? chain.end() : cells.get(1);
        out.add(start(m.lattice, m.mode, m.plaza, m.attach, m.height, built, cells.get(0), second));
        if (chain.bridge()) {
            out.add(new double[] {m.lattice.centerX(chain.end()), m.height.getOrDefault(chain.end(), Double.NaN), m.lattice.centerZ(chain.end())});
            return out;
        }
        for (int i = 1; i < cells.size(); i++) {
            double[] prev = out.getLast();
            HexCoord c = cells.get(i);
            double[] next = {m.lattice.centerX(c), m.height.getOrDefault(c, Double.NaN), m.lattice.centerZ(c)};
            out.add(new double[] {(prev[0] + next[0]) / 2, Double.NaN, (prev[2] + next[2]) / 2});
            out.add(next);
        }
        return out;
    }

    // ---- preview drawing ----------------------------------------------------------------------------------------------
    /** Draws the planned roads over the land for a minute: green lines from hexagon to hexagon, blue for bridges. World thread. */
    public static void draw(PlayerRef player, Preview preview) {
        var green = new Vector3f(.45f, .85f, .5f);
        var blue = new Vector3f(.4f, .65f, .95f);
        double fallback = preview.anchor() != null ? preview.anchor()[1] : preview.roads().stream().flatMap(r -> r.nodes().stream()).mapToDouble(n -> n[1]).filter(y -> !Double.isNaN(y)).findFirst().orElse(64);
        for (var road : preview.roads()) {
            var n = road.nodes();
            for (int i = 1; i < n.size(); i++) {
                double y0 = Double.isNaN(n.get(i - 1)[1]) ? fallback : n.get(i - 1)[1];
                double y1 = Double.isNaN(n.get(i)[1]) ? y0 : n.get(i)[1];
                line(player, n.get(i - 1)[0], y0 + 2.5, n.get(i - 1)[2], n.get(i)[0], y1 + 2.5, n.get(i)[2], road.chain().bridge() ? blue : green);
            }
        }
        var p = preview.plaza();
        double y = fallback + 2;
        var gold = new Vector3f(.9f, .75f, .35f);
        line(player, p.x(), y, p.z(), p.endX(), y, p.z(), gold); line(player, p.endX(), y, p.z(), p.endX(), y, p.endZ(), gold);
        line(player, p.endX(), y, p.endZ(), p.x(), y, p.endZ(), gold); line(player, p.x(), y, p.endZ(), p.x(), y, p.z(), gold);
    }

    private static void line(PlayerRef player, double x, double y, double z, double ex, double ey, double ez, Vector3f color) {
        double len = Math.sqrt((ex - x) * (ex - x) + (ey - y) * (ey - y) + (ez - z) * (ez - z));
        var matrix = DebugLineCylinderUtil.segmentMatrix(x, y, z, ex, ey, ez, .12, len);
        if (matrix != null) player.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder, Matrix4dUtil.asFloatData(matrix), color, 60f, (byte) DebugUtils.FLAG_NO_WIREFRAME, null, .9f));
    }

    // ---- building ------------------------------------------------------------------------------------------------------
    /**
     * Builds a previewed network: the platform and portal first in portal mode, then roads outwards from the middle,
     * all of them when {@code all}, otherwise the first few, the rest following as homes are claimed.
     */
    public static void build(EterniaModPlugin plugin, World world, Preview preview, boolean all, int lead, PlayerRef player) {
        if (Instant.now().isAfter(preview.expires())) throw new IllegalStateException("The plan expired. Run the command again");
        if (busy) throw new IllegalStateException("Town roads are already being built or removed");
        busy = true;
        JOBS.execute(() -> {
            var n = new Network();
            n.id = UUID.randomUUID().toString();
            n.world = world.getName();
            n.mode = preview.mode().name();
            n.state = "building";
            n.created = Instant.now().toEpochMilli();
            n.actor = preview.actor().toString();
            n.land = preview.land();
            n.growing = !all;
            n.lead = Math.max(0, Math.min(200, lead));
            var lattice = plugin.getInfrastructure().world(world.getName()).orElseThrow().lattice();
            var center = preview.land().centerCell();
            n.center = new int[] {(int) lattice.centerX(center), (int) lattice.centerZ(center)};
            n.anchor = preview.anchor();
            try {
                SetupAccess.require(preview.actor());
                update(plugin, world.getName(), n);
                if (preview.mode() == Mode.PORTAL) platform(plugin, world, preview, n, player);
                grow(plugin, world, n, all, player);
                n.state = n.growing ? "growing" : "built";
                update(plugin, world.getName(), n);
                if (n.growing) TownBuilder.tell(player, "This town's roads now grow as homes are claimed.");
            } catch (Exception e) {
                n.state = "stopped";
                n.growing = false;
                try { update(plugin, world.getName(), n); } catch (IOException ignored) {}
                TownBuilder.tell(player, "Town roads stopped: " + TownBuilder.cause(e) + ". /e admin town roads undo takes away what was built.");
                plugin.getLogger().atWarning().withCause(e).log("Town roads failed");
            } finally { busy = false; }
        });
    }

    /** Loads the road's land, fills in ground heights, and builds it through the Road Designer. Background thread. */
    private static UUID buildRoad(World world, UUID actor, Road road) throws Exception {
        return buildRoad(world, actor, road.nodes());
    }

    private static UUID buildRoad(World world, UUID actor, List<double[]> nodes) throws Exception { return buildRoad(world, actor, nodes, WIDTH); }

    private static UUID buildRoad(World world, UUID actor, List<double[]> nodes, int width) throws Exception {
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] p : nodes) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); }
        TownBuilder.loadArea(world, (int) minX - 12, (int) minZ - 12, (int) maxX + 12, (int) maxZ + 12);
        return TownBuilder.supply(world, () -> {
            List<double[]> ground = new ArrayList<>();
            for (double[] p : nodes) {
                int g = surface(world, (int) Math.floor(p[0]), (int) Math.floor(p[2]));
                if (g == TownTerrainPlanner.UNKNOWN) throw new IllegalStateException("No ground at " + (int) p[0] + ", " + (int) p[2]);
                ground.add(new double[] {p[0], g, p[2]});
            }
            return TownRoadAuthor.author(world, actor, ground, width);
        });
    }

    /** The top of the ground or paving at a column, under plants and trees. */
    static int surface(World world, int x, int z) {
        int top = ChunkSectionBlockUtil.columnHeight(world, x, z);
        for (int y = Math.min(top, NativeSnapshotStore.MAX_Y - 1); y >= Math.max(0, top - 64); y--) {
            var type = ChunkSectionBlockUtil.blockType(world, x, y, z);
            if (type != null && type.isCubeDrawType() && type.getMaterial().name().equals("Solid") && !TerraformBlocks.isGrowth(type.getId())) return y;
        }
        return TownTerrainPlanner.UNKNOWN;
    }

    // ---- getting a road's hexagons ready -----------------------------------------------------------------------------
    /** Blocks either side of a road's middle that are levelled to the road's height; past it the ground eases back. */
    static final double BED = 3.0, EASE = 5.0;
    /** The steepest a levelled road climbs, in blocks per block. */
    static final double CLIMB = 0.34;

    /**
     * Gets a road's hexagons ready before it is paved: backs each one up (once per network), levels a smooth bed along
     * the road with soil the Road Designer accepts, eases the ground either side back into the land, and fells every
     * tree standing in those hexagons. Roads, plazas, plots and water are never touched. Background thread.
     */
    private static void prepare(EterniaModPlugin plugin, World world, Network n, HexLattice lattice, Road road) throws Exception {
        var chain = road.chain();
        List<HexCoord> cells = new ArrayList<>(chain.cells());
        if (chain.fromSource()) cells.removeFirst();
        if (cells.isEmpty()) return;
        Set<HexCoord> mine = new HashSet<>(cells);
        var box = lattice.bounds(cells);
        TownBuilder.loadArea(world, box.x() - 16, box.z() - 16, box.endX() + 16, box.endZ() + 16);
        backup(plugin, world, n, lattice, cells);
        List<SplineGeometry.Node> nodes = new ArrayList<>();
        for (double[] p : road.nodes()) nodes.add(new SplineGeometry.Node(p[0], 64, p[2]));
        List<SplineGeometry.Node> curve = SplineGeometry.sample(nodes);
        TownBuilder.on(world, () -> {
            var view = HexLand.view(plugin, world).orElse(null);
            level(plugin, world, view, lattice, mine, curve, BED, EASE);
            fell(plugin, world, view, lattice, mine);
        }).join();
    }

    /**
     * Lays one road for town placement (a gate's road): levels its bed with plain soil first, so paving left by the
     * town or rock the Road Designer refuses never stops it, then builds it. World thread for the levelling only.
     */
    public static UUID layTownRoad(EterniaModPlugin plugin, World world, UUID actor, List<double[]> nodes, int width) throws Exception {
        List<SplineGeometry.Node> points = new ArrayList<>();
        for (double[] p : nodes) points.add(new SplineGeometry.Node(p[0], 64, p[2]));
        var curve = SplineGeometry.sample(points);
        var lattice = plugin.getInfrastructure().world(world.getName()).map(HousingInfrastructure.WorldPlan::lattice).orElse(null);
        if (lattice != null) {
            double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (double[] p : nodes) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); }
            var area = new PlotRect((int) minX - 8, (int) minZ - 8, (int) (maxX - minX) + 17, (int) (maxZ - minZ) + 17);
            Set<HexCoord> around = lattice.cellsTouching(area);
            TownBuilder.loadArea(world, area.x() - 8, area.z() - 8, area.endX() + 8, area.endZ() + 8);
            TownBuilder.on(world, () -> level(plugin, world, HexLand.view(plugin, world).orElse(null), lattice, around, curve, width / 2.0 + 0.5, width / 2.0 + 3)).join();
        }
        return retrying(() -> buildRoad(world, actor, nodes, width));
    }

    /** Backs up each hexagon not yet backed up by this network, cut to the hexagon so undo never touches a neighbour. */
    private static void backup(EterniaModPlugin plugin, World world, Network n, HexLattice lattice, List<HexCoord> cells) throws Exception {
        var store = TownBuilder.store(plugin, "roads-" + n.id);
        for (HexCoord c : cells) {
            if (n.backedUp.contains(c.toString())) continue;
            var rect = lattice.bounds(c);
            int[] range = TownBuilder.supply(world, () -> {
                int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                for (var span : lattice.spans(c)) for (int x = span.fromX(); x < span.toX(); x += 2) {
                    int g = TownBuilder.natural(world, x, span.z());
                    if (g != TownTerrainPlanner.UNKNOWN) low = Math.min(low, g);
                    high = Math.max(high, ChunkSectionBlockUtil.columnHeight(world, x, span.z()));
                }
                return new int[] {low, high};
            });
            if (range[0] == Integer.MAX_VALUE) continue;
            var cellMask = com.hexvane.eterniamod.hexplots.PlotCells.of(lattice, List.of(c));
            var bounds = new NativeSnapshotStore.Bounds(rect.x(), Math.max(1, range[0] - 4), rect.z(), rect.endX(), Math.min(NativeSnapshotStore.MAX_Y, Math.max(range[1], range[0]) + 3), rect.endZ());
            org.bson.BsonDocument doc = null;
            for (int attempt = 1; doc == null; attempt++) {
                try { doc = TownBuilder.supply(world, () -> store.capture(world, bounds, com.hexvane.eterniamod.housing.relocation.ColumnMask.of(cellMask), Set.of(), false, false)); }
                catch (java.util.concurrent.CompletionException e) {
                    if (!(e.getCause() instanceof NativeSnapshotStore.StorageBusy) || attempt >= TownBuilder.BUSY_RETRIES) throw e;
                    Thread.sleep(TownBuilder.BUSY_WAIT_MS);
                }
            }
            var captured = doc;
            TownBuilder.on(world, () -> {
                var saved = store.save("cell-" + c.q() + "_" + c.r() + ".json", bounds, cellMask, captured);
                var backup = new TownBuilder.Backup();
                backup.name = saved.file().reference(); backup.sha = saved.file().sha256();
                backup.min = new int[] {bounds.minX(), bounds.minY(), bounds.minZ()}; backup.max = new int[] {bounds.maxX(), bounds.maxY(), bounds.maxZ()};
                n.backups.add(backup);
            }).join();
            n.backedUp.add(c.toString());
            update(plugin, world.getName(), n);
        }
    }

    /** A column the road preparation may change: free land in this road's hexagons. World thread. */
    private static boolean workable(EterniaModPlugin plugin, World world, HexLandView view, HexLattice lattice, Set<HexCoord> mine, int x, int z) {
        return mine.contains(lattice.cellAt(x, z)) && free(plugin, world, view, x, z);
    }

    /** Free land anywhere: not a road, plaza, service or plot. The road's own bed may reach just past its hexagons. */
    private static boolean free(EterniaModPlugin plugin, World world, HexLandView view, int x, int z) {
        if (plugin.getInfrastructure().protectedColumn(world.getName(), x, z)) return false;
        if (ManagedHubServices.intersectsManaged(world.getName(), new PlotRect(x, z, 1, 1))) return false;
        return view == null || view.plotAtColumn(x, z) == null;
    }

    /** Levels a smooth bed along the road's curve and eases the land either side back to its own height. World thread. */
    private static void level(EterniaModPlugin plugin, World world, HexLandView view, HexLattice lattice, Set<HexCoord> mine, List<SplineGeometry.Node> curve, double bedWidth, double easeWidth) {
        // The ground under the road's middle, about one sample per block.
        List<double[]> line = new ArrayList<>(); // x, z, ground
        double walked = 0;
        for (int i = 0; i < curve.size(); i++) {
            var p = curve.get(i);
            if (i > 0) walked += Math.hypot(p.x() - curve.get(i - 1).x(), p.z() - curve.get(i - 1).z());
            if (i > 0 && i < curve.size() - 1 && walked < 1) continue;
            walked = 0;
            int g = surface(world, (int) Math.floor(p.x()), (int) Math.floor(p.z()));
            line.add(new double[] {p.x(), p.z(), g == TownTerrainPlanner.UNKNOWN ? Double.NaN : g});
        }
        if (line.size() < 2) return;
        // Fill gaps, smooth over about sixteen blocks, start exactly where the road it leaves is, and cap the climb.
        double last = Double.NaN;
        for (double[] s : line) { if (Double.isNaN(s[2])) s[2] = last; else last = s[2]; }
        for (int i = line.size() - 1; i >= 0; i--) { if (Double.isNaN(line.get(i)[2])) line.get(i)[2] = last; else last = line.get(i)[2]; }
        if (Double.isNaN(line.get(0)[2])) return;
        double[] smooth = new double[line.size()];
        for (int i = 0; i < line.size(); i++) {
            double sum = 0; int count = 0;
            for (int k = Math.max(0, i - 8); k <= Math.min(line.size() - 1, i + 8); k++) { sum += line.get(k)[2]; count++; }
            smooth[i] = sum / count;
        }
        double start = line.get(0)[2];
        for (int i = 0; i < Math.min(8, smooth.length); i++) smooth[i] = start + (smooth[i] - start) * (i / 8.0);
        smooth[0] = start;
        for (int i = 1; i < smooth.length; i++) smooth[i] = Math.max(smooth[i - 1] - CLIMB, Math.min(smooth[i - 1] + CLIMB, smooth[i]));
        // Every column near the road: its distance to the road and the road height there.
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] s : line) { minX = Math.min(minX, s[0]); maxX = Math.max(maxX, s[0]); minZ = Math.min(minZ, s[1]); maxZ = Math.max(maxZ, s[1]); }
        int dirt = BlockType.getAssetMap().getIndex("Soil_Dirt"), grass = BlockType.getAssetMap().getIndex("Soil_Grass");
        var sel = new BlockSelection();
        sel.setPosition(0, 0, 0);
        sel.setAnchor(0, 0, 0);
        int reach = (int) Math.ceil(easeWidth) + 1;
        for (int x = (int) Math.floor(minX) - reach; x <= (int) Math.ceil(maxX) + reach; x++) for (int z = (int) Math.floor(minZ) - reach; z <= (int) Math.ceil(maxZ) + reach; z++) {
            boolean inside = mine.contains(lattice.cellAt(x, z));
            double best = Double.MAX_VALUE; int at = 0;
            for (int i = 0; i < line.size(); i++) { double d = Math.hypot(x + .5 - line.get(i)[0], z + .5 - line.get(i)[1]); if (d < best) { best = d; at = i; } }
            if (best > (inside ? easeWidth : bedWidth + 0.5) || !free(plugin, world, view, x, z)) continue;
            int n = surface(world, x, z);
            if (n == TownTerrainPlanner.UNKNOWN || hasFluid(world, x, n + 1, z)) continue;
            double road = smooth[at];
            int t = (int) Math.round(best <= bedWidth ? road : road + (n - road) * ((best - bedWidth) / (easeWidth - bedWidth)));
            boolean bed = best <= bedWidth;
            // Above the new top: everything goes in the bed; outside it, only what the cut removes and plants or trees.
            for (int y = t + 1; y <= Math.max(n, t) + (bed ? 12 : 30) && y < NativeSnapshotStore.MAX_Y; y++) {
                int id = ChunkSectionBlockUtil.blockId(world, x, y, z);
                if (id == BlockType.EMPTY_ID) continue;
                var type = BlockType.getAssetMap().getAsset(id);
                if (bed || y <= n || type != null && TerraformBlocks.isGrowth(type.getId())) sel.addEmptyAtWorldPos(x, y, z);
            }
            for (int y = n + 1; y < t; y++) sel.addBlockAtWorldPos(x, y, z, dirt, 0, FillerBlockUtil.NO_FILLER, 0);
            var top = ChunkSectionBlockUtil.blockType(world, x, t, z);
            boolean plain = top != null && t <= n && (top.getId().startsWith("Soil_") || top.getId().startsWith("Grass_") || top.getId().startsWith("Sand_") || top.getId().equals("Rock_Stone"));
            if (t > n || !plain) sel.addBlockAtWorldPos(x, t, z, grass, 0, FillerBlockUtil.NO_FILLER, 0);
        }
        sel.placeNoReturn(world, new Vector3i(0, 0, 0), world.getEntityStore().getStore());
    }

    /** Fells every tree standing in the road's hexagons: its wood wherever it reaches, and the leaves round it. World thread. */
    private static void fell(EterniaModPlugin plugin, World world, HexLandView view, HexLattice lattice, Set<HexCoord> mine) {
        Set<Vector3i> removed = new HashSet<>();
        var wood = new ArrayDeque<Vector3i>();
        List<Vector3i> woodAll = new ArrayList<>();
        for (HexCoord c : mine) for (var span : lattice.spans(c)) for (int x = span.fromX(); x < span.toX(); x++) {
            int z = span.z();
            if (!workable(plugin, world, view, lattice, mine, x, z)) continue;
            int top = Math.min(ChunkSectionBlockUtil.columnHeight(world, x, z), NativeSnapshotStore.MAX_Y - 1);
            for (int y = Math.max(1, top - 40); y <= top; y++) {
                var type = ChunkSectionBlockUtil.blockType(world, x, y, z);
                if (type == null) continue;
                if (TerraformBlocks.isTreeWood(type.getId()) || TerraformBlocks.isLeaves(type.getId())) {
                    var at = new Vector3i(x, y, z);
                    if (removed.add(at) && TerraformBlocks.isTreeWood(type.getId())) { wood.add(at); woodAll.add(at); }
                }
            }
        }
        if (removed.isEmpty()) return;
        var box = lattice.bounds(mine);
        int reachMinX = box.x() - 16, reachMaxX = box.endX() + 16, reachMinZ = box.z() - 16, reachMaxZ = box.endZ() + 16;
        java.util.function.Predicate<Vector3i> free = at -> at.x >= reachMinX && at.x < reachMaxX && at.z >= reachMinZ && at.z < reachMaxZ && at.y > 0 && at.y < NativeSnapshotStore.MAX_Y
            && !plugin.getInfrastructure().protectedColumn(world.getName(), at.x, at.z) && (view == null || view.plotAtColumn(at.x, at.z) == null);
        while (!wood.isEmpty() && removed.size() < 20_000) {
            var at = wood.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                var next = new Vector3i(at.x + dx, at.y + dy, at.z + dz);
                if (removed.contains(next) || !free.test(next)) continue;
                var type = ChunkSectionBlockUtil.blockType(world, next.x, next.y, next.z);
                if (type == null || !TerraformBlocks.isTreeWood(type.getId())) continue;
                removed.add(next); wood.add(next); woodAll.add(next);
            }
        }
        var leaves = new ArrayDeque<>(removed);
        while (!leaves.isEmpty() && removed.size() < 30_000) {
            var at = leaves.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                var next = new Vector3i(at.x + dx, at.y + dy, at.z + dz);
                if (removed.contains(next) || !free.test(next)) continue;
                var type = ChunkSectionBlockUtil.blockType(world, next.x, next.y, next.z);
                if (type == null || !TerraformBlocks.isLeaves(type.getId())) continue;
                boolean near = false;
                for (var w : woodAll) if (Math.abs(w.x - next.x) <= 6 && Math.abs(w.y - next.y) <= 6 && Math.abs(w.z - next.z) <= 6) { near = true; break; }
                if (!near) continue;
                removed.add(next); leaves.add(next);
            }
        }
        var sel = new BlockSelection();
        sel.setPosition(0, 0, 0);
        sel.setAnchor(0, 0, 0);
        for (var at : removed) sel.addEmptyAtWorldPos(at.x, at.y, at.z);
        sel.placeNoReturn(world, new Vector3i(0, 0, 0), world.getEntityStore().getStore());
    }

    // ---- bridges -------------------------------------------------------------------------------------------------------
    /** One sample along a bridge's line: where it is, the river bed, and the water's top (or MIN when dry). */
    private record Sample(double x, double z, int bed, int waterTop) { boolean wet() { return waterTop != Integer.MIN_VALUE; } }

    /**
     * Builds a road that crosses water: a Road Designer road to the near bank, a wooden bridge over the water (a plank
     * deck arching clear of it, fence rails and posts down to the bed), and a road on from the far bank. The bridge is
     * backed up first and registered as public road. Background thread.
     */
    private static List<UUID> buildBridge(EterniaModPlugin plugin, World world, UUID actor, Network n, Road road) throws Exception {
        double[] p = road.nodes().getFirst(), q = road.nodes().getLast();
        TownBuilder.loadArea(world, (int) Math.min(p[0], q[0]) - 12, (int) Math.min(p[2], q[2]) - 12, (int) Math.max(p[0], q[0]) + 12, (int) Math.max(p[2], q[2]) + 12);
        double len = Math.hypot(q[0] - p[0], q[2] - p[2]);
        int count = (int) Math.ceil(len * 2);
        List<Sample> line = TownBuilder.supply(world, () -> {
            List<Sample> out = new ArrayList<>();
            for (int i = 0; i <= count; i++) {
                double f = i / (double) count, x = p[0] + (q[0] - p[0]) * f, z = p[2] + (q[2] - p[2]) * f;
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z), bed = TownBuilder.natural(world, bx, bz), top = Integer.MIN_VALUE;
                if (bed != TownTerrainPlanner.UNKNOWN) for (int y = bed + 1; y < NativeSnapshotStore.MAX_Y && hasFluid(world, bx, y, bz); y++) top = y;
                out.add(new Sample(x, z, bed, top));
            }
            return out;
        });
        int first = -1, last = -1;
        for (int i = 0; i < line.size(); i++) if (line.get(i).wet()) { if (first < 0) first = i; last = i; }
        if (first < 0) return List.of(retrying(() -> buildRoad(world, actor, List.of(p, q))));
        // The deck runs four blocks onto each bank.
        int a = Math.max(0, first - 8), b = Math.min(line.size() - 1, last + 8);
        Sample sa = line.get(a), sb = line.get(b);
        List<UUID> ids = new ArrayList<>();
        if (Math.hypot(sa.x() - p[0], sa.z() - p[2]) >= 3) ids.add(retrying(() -> buildRoad(world, actor, List.of(p, new double[] {sa.x(), Double.NaN, sa.z()}))));
        deck(plugin, world, n, line, a, b);
        if (Math.hypot(q[0] - sb.x(), q[2] - sb.z()) >= 3) ids.add(retrying(() -> buildRoad(world, actor, List.of(new double[] {sb.x(), Double.NaN, sb.z()}, q))));
        return ids;
    }

    /** Lays the bridge deck from sample a to b, backed up first and registered as public road. Background thread. */
    private static void deck(EterniaModPlugin plugin, World world, Network n, List<Sample> line, int a, int b) throws Exception {
        Sample sa = line.get(a), sb = line.get(b);
        int ga = TownBuilder.supply(world, () -> surface(world, (int) Math.floor(sa.x()), (int) Math.floor(sa.z())));
        int gb = TownBuilder.supply(world, () -> surface(world, (int) Math.floor(sb.x()), (int) Math.floor(sb.z())));
        if (ga == TownTerrainPlanner.UNKNOWN || gb == TownTerrainPlanner.UNKNOWN) throw new IllegalStateException("No bank to rest the bridge on");
        // Raise the middle enough to clear the water by two blocks, easing up from each bank.
        double lift = 0;
        for (int i = a; i <= b; i++) {
            var s = line.get(i);
            if (!s.wet()) continue;
            double t = (i - a) / (double) Math.max(1, b - a), base = ga + (gb - ga) * t;
            double ease = Math.min(1, 2.5 * Math.sin(Math.PI * t));
            if (ease > 0) lift = Math.max(lift, (s.waterTop() + 2 - base) / ease);
        }
        double dx = sb.x() - sa.x(), dz = sb.z() - sa.z(), length = Math.max(1e-6, Math.hypot(dx, dz)), ux = dx / length, uz = dz / length;
        Map<Long, int[]> deck = new HashMap<>(); // column -> {y, edge(0/1), wetNearby(0/1), bed}
        int wetFrom = line.indexOf(line.stream().filter(Sample::wet).findFirst().orElseThrow()), wetTo = wetFrom;
        for (int i = 0; i < line.size(); i++) if (line.get(i).wet()) wetTo = i;
        for (int i = a; i <= b; i++) {
            var s = line.get(i);
            double t = (i - a) / (double) Math.max(1, b - a), base = ga + (gb - ga) * t;
            int y = (int) Math.round(base + Math.max(0, lift) * Math.min(1, 2.5 * Math.sin(Math.PI * t)));
            boolean overWater = i >= wetFrom - 4 && i <= wetTo + 4;
            for (int off = -2; off <= 2; off++) {
                int x = (int) Math.floor(s.x() - uz * off), z = (int) Math.floor(s.z() + ux * off);
                long k = TownBuilder.key(x, z);
                int[] prior = deck.get(k);
                if (prior != null && prior[0] >= y) continue;
                deck.put(k, new int[] {y, Math.abs(off) == 2 ? 1 : 0, overWater ? 1 : 0, s.wet() ? s.bed() : Integer.MIN_VALUE});
            }
        }
        // Columns a road already owns stay that road's: the road is the bridge's approach there.
        Set<Long> owned = TownBuilder.supply(world, () -> {
            Set<Long> out = new HashSet<>();
            for (long k : deck.keySet()) if (plugin.getInfrastructure().protectedColumn(world.getName(), (int) (k >> 32), (int) k)) out.add(k);
            return out;
        });
        deck.keySet().removeAll(owned);
        if (deck.isEmpty()) return;
        // Back up the box the bridge touches.
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (var e : deck.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            int[] v = e.getValue();
            minY = Math.min(minY, v[3] == Integer.MIN_VALUE ? v[0] - 1 : v[3]); maxY = Math.max(maxY, v[0] + 6);
        }
        var store = TownBuilder.store(plugin, "roads-" + n.id);
        var bounds = new NativeSnapshotStore.Bounds(minX, Math.max(1, minY - 1), minZ, maxX + 1, Math.min(NativeSnapshotStore.MAX_Y, maxY), maxZ + 1);
        var doc = TownBuilder.captureWhenIdle(world, store, bounds);
        TownBuilder.on(world, () -> {
            var saved = store.save("bridge-" + bounds.minX() + "_" + bounds.minZ() + "_" + bounds.minY() + ".json", bounds, doc);
            var backup = new TownBuilder.Backup();
            backup.name = saved.file().reference(); backup.sha = saved.file().sha256();
            backup.min = new int[] {bounds.minX(), bounds.minY(), bounds.minZ()}; backup.max = new int[] {bounds.maxX(), bounds.maxY(), bounds.maxZ()};
            n.backups.add(backup);
        }).join();
        update(plugin, world.getName(), n);
        // Build it.
        int plank = PlotTerraformerBlocks.index(DECK, "Wood_Oak_Planks"), rail = PlotTerraformerBlocks.index(RAIL, DECK), post = PlotTerraformerBlocks.index(POST, DECK);
        TownBuilder.on(world, () -> {
            var sel = new BlockSelection();
            sel.setPosition(0, 0, 0);
            sel.setAnchor(0, 0, 0);
            int along = 0;
            for (var e : deck.entrySet()) {
                int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
                int[] v = e.getValue();
                int y = v[0];
                sel.addBlockAtWorldPos(x, y, z, plank, 0, FillerBlockUtil.NO_FILLER, 0);
                for (int h = 1; h <= 4; h++) if (ChunkSectionBlockUtil.blockId(world, x, y + h, z) != BlockType.EMPTY_ID) sel.addEmptyAtWorldPos(x, y + h, z);
                if (v[1] == 1 && v[2] == 1) {
                    sel.addBlockAtWorldPos(x, y + 1, z, rail, 0, FillerBlockUtil.NO_FILLER, 0);
                    // Posts down to the bed every few blocks along each edge.
                    if (v[3] != Integer.MIN_VALUE && Math.floorMod(x * 7 + z * 13, 5) == 0)
                        for (int py = y - 1; py > v[3]; py--) sel.addBlockAtWorldPos(x, py, z, post, 0, FillerBlockUtil.NO_FILLER, 0);
                }
                along++;
            }
            sel.placeNoReturn(world, new Vector3i(0, 0, 0), world.getEntityStore().getStore());
        }).join();
        // Register the deck as public road, so nobody claims it and the hexagons beside it face a street.
        List<SplineGeometry.Column> columns = new ArrayList<>();
        for (long k : deck.keySet()) columns.add(new SplineGeometry.Column((int) (k >> 32), (int) k));
        var rects = SplineGeometry.rectangles(columns);
        String prefix = "bridge-" + n.id.substring(0, 8) + "-" + n.areas.size() + "-";
        TownBuilder.on(world, () -> {
            var infra = plugin.getInfrastructure();
            var snapshot = infra.snapshot();
            var before = snapshot.worlds().get(world.getName());
            var areas = new TreeMap<>(before.areas());
            for (int i = 0; i < rects.size(); i++) { areas.put(prefix + i, new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD, rects.get(i))); n.areas.add(prefix + i); }
            infra.saveWorld(snapshot.revision(), world.getName(), before.withAreas(areas));
        }).join();
        update(plugin, world.getName(), n);
    }

    /** Block indices by id, with a fallback. */
    static final class PlotTerraformerBlocks {
        static int index(String id, String fallback) {
            int i = BlockType.getAssetMap().getIndex(id);
            if (i < 0) i = BlockType.getAssetMap().getIndex(fallback);
            if (i < 0) throw new IllegalStateException("Unknown block " + id);
            return i;
        }
    }

    // ---- the portal town's platform ------------------------------------------------------------------------------------
    /** Backs up the land, levels it, pastes the spawn platform, registers it as a plaza and sets a portal in the middle. */
    private static void platform(EterniaModPlugin plugin, World world, Preview preview, Network n, PlayerRef player) throws Exception {
        int ax = preview.anchor()[0], ay = preview.anchor()[1], az = preview.anchor()[2];
        var p = preview.plaza();
        var around = new PlotRect(p.x() - SKIRT, p.z() - SKIRT, p.width() + 2 * SKIRT, p.depth() + 2 * SKIRT);
        TownBuilder.tell(player, "Backing up the land under the platform...");
        var store = TownBuilder.store(plugin, "roads-" + n.id);
        for (int tx = around.x(); tx < around.endX(); tx += TownBuilder.TILE) for (int tz = around.z(); tz < around.endZ(); tz += TownBuilder.TILE) {
            int x1 = Math.min(around.endX(), tx + TownBuilder.TILE), z1 = Math.min(around.endZ(), tz + TownBuilder.TILE), fx = tx, fz = tz;
            TownBuilder.loadArea(world, tx, tz, x1 - 1, z1 - 1);
            int[] low = {Integer.MAX_VALUE};
            TownBuilder.on(world, () -> { for (int x = fx; x < x1; x++) for (int z = fz; z < z1; z++) { int g = TownBuilder.natural(world, x, z); if (g != TownTerrainPlanner.UNKNOWN) low[0] = Math.min(low[0], g); } }).join();
            int minY = Math.max(1, Math.min(low[0] == Integer.MAX_VALUE ? ay : low[0], ay) - 3);
            var b = new NativeSnapshotStore.Bounds(tx, minY, tz, x1, Math.min(NativeSnapshotStore.MAX_Y, Math.max(ay, low[0] == Integer.MAX_VALUE ? ay : low[0]) + PLATFORM_HEIGHT), z1);
            var doc = TownBuilder.captureWhenIdle(world, store, b);
            TownBuilder.on(world, () -> {
                var saved = store.save("tile-" + b.minX() + "_" + b.minZ() + ".json", b, doc);
                var backup = new TownBuilder.Backup();
                backup.name = saved.file().reference(); backup.sha = saved.file().sha256();
                backup.min = new int[] {b.minX(), b.minY(), b.minZ()}; backup.max = new int[] {b.maxX(), b.maxY(), b.maxZ()};
                n.backups.add(backup);
            }).join();
            update(plugin, world.getName(), n);
        }
        TownBuilder.tell(player, "Levelling the ground...");
        for (int tx = around.x(); tx < around.endX(); tx += TownBuilder.TILE) for (int tz = around.z(); tz < around.endZ(); tz += TownBuilder.TILE) {
            int x1 = Math.min(around.endX(), tx + TownBuilder.TILE), z1 = Math.min(around.endZ(), tz + TownBuilder.TILE), fx = tx, fz = tz;
            TownBuilder.on(world, () -> level(world, p, fx, fz, x1, z1, ay)).join();
        }
        TownBuilder.tell(player, "Building the spawn platform...");
        var pasted = new TownBuilder.Manifest();
        TownBuilder.paste(world, PLATFORM, new Vector3i(ax, ay, az), pasted);
        TownBuilder.on(world, () -> liftPlayers(world, around)).join();
        n.entities.addAll(pasted.entities);
        update(plugin, world.getName(), n);
        n.plazaArea = "portal-town-" + n.id.substring(0, 8);
        TownBuilder.on(world, () -> {
            var infra = plugin.getInfrastructure();
            var snapshot = infra.snapshot();
            var before = snapshot.worlds().get(world.getName());
            var areas = new TreeMap<>(before.areas());
            areas.put(n.plazaArea, new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL, p));
            infra.saveWorld(snapshot.revision(), world.getName(), before.withAreas(areas));
        }).join();
        update(plugin, world.getName(), n);
        TownBuilder.on(world, () -> n.portal = portal(plugin, world, preview.actor(), ax, ay, az).toString()).join();
        update(plugin, world.getName(), n);
        world.execute(com.hexvane.eterniamod.customization.HousingWorldPolicy::refreshAll);
    }

    /** Moves anyone the platform was built over up onto its surface, so nobody is left inside it. World thread. */
    private static void liftPlayers(World world, PlotRect area) {
        var id = world.getWorldConfig().getUuid();
        for (var player : Universe.get().getPlayers()) {
            if (!id.equals(player.getWorldUuid())) continue;
            var ref = player.getReference();
            if (ref == null || !ref.isValid()) continue;
            var store = ref.getStore();
            var transform = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if (transform == null) continue;
            var p = transform.getPosition();
            int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
            if (!area.contains(x, z)) continue;
            int top = ChunkSectionBlockUtil.columnHeight(world, x, z);
            if (top < 0 || p.y > top + 1) continue;
            store.putComponent(ref, com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.getComponentType(),
                com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.createForPlayer(world, new org.joml.Vector3d(p.x, top + 1, p.z), new com.hypixel.hytale.math.vector.Rotation3f()));
        }
    }

    /** Sets a portal on the platform's middle podium, or the nearest clear spot to it. World thread. */
    private static UUID portal(EterniaModPlugin plugin, World world, UUID actor, int ax, int ay, int az) throws Exception {
        Exception last = null;
        for (int ring = 0; ring <= 12; ring++) for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
            for (int dy = 1; dy <= 5; dy++) {
                int x = ax + dx, y = ay + dy, z = az + dz;
                if (!clearSpot(world, x, y, z)) continue;
                try { return ManagedHubServices.addPortal(plugin, world, actor, x, y, z, 0f); } catch (Exception e) { last = e; }
            }
        }
        throw new IllegalStateException("No clear spot for the portal on the platform" + (last == null ? "" : ": " + last.getMessage()));
    }

    private static boolean clearSpot(World world, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var ground = ChunkSectionBlockUtil.blockType(world, x + dx, y - 1, z + dz);
            if (ground == null || !ground.isCubeDrawType()) return false;
            for (int k = 0; k < 3; k++) if (ChunkSectionBlockUtil.blockId(world, x + dx, y + k, z + dz) != BlockType.EMPTY_ID) return false;
        }
        return true;
    }

    /** Flattens the platform's footprint to its foundation and eases the ground round it. World thread. */
    private static void level(World world, PlotRect p, int x0, int z0, int x1, int z1, int ay) {
        var sel = new BlockSelection();
        sel.setPosition(0, 0, 0);
        sel.setAnchor(0, 0, 0);
        int stone = BlockType.getAssetMap().getIndex("Rock_Stone"), dirt = BlockType.getAssetMap().getIndex("Soil_Dirt"), grass = BlockType.getAssetMap().getIndex("Soil_Grass");
        for (int x = x0; x < x1; x++) for (int z = z0; z < z1; z++) {
            int n = TownBuilder.natural(world, x, z);
            if (n == TownTerrainPlanner.UNKNOWN) continue;
            int out = Math.max(Math.max(p.x() - x, x - (p.endX() - 1)), Math.max(p.z() - z, z - (p.endZ() - 1)));
            if (out <= 0) {
                for (int y = ay; y <= Math.max(n, ay) + PLATFORM_HEIGHT && y < NativeSnapshotStore.MAX_Y; y++) if (ChunkSectionBlockUtil.blockId(world, x, y, z) != BlockType.EMPTY_ID) sel.addEmptyAtWorldPos(x, y, z);
                for (int y = n + 1; y < ay; y++) sel.addBlockAtWorldPos(x, y, z, stone, 0, FillerBlockUtil.NO_FILLER, 0);
                continue;
            }
            int target = (int) Math.round(ay - 1 + (n - (ay - 1)) * (out / (double) (SKIRT + 1)));
            for (int y = Math.min(n, target) + 1; y <= Math.max(n, target) + 24 && y < NativeSnapshotStore.MAX_Y; y++) {
                int id = ChunkSectionBlockUtil.blockId(world, x, y, z);
                if (id == BlockType.EMPTY_ID) continue;
                var type = BlockType.getAssetMap().getAsset(id);
                if (y <= n || type != null && TerraformBlocks.isGrowth(type.getId())) sel.addEmptyAtWorldPos(x, y, z);
            }
            for (int y = n + 1; y <= target; y++) sel.addBlockAtWorldPos(x, y, z, y == target ? grass : dirt, 0, FillerBlockUtil.NO_FILLER, 0);
            if (target < n && target > 0) sel.addBlockAtWorldPos(x, target, z, grass, 0, FillerBlockUtil.NO_FILLER, 0);
        }
        sel.placeNoReturn(world, new Vector3i(0, 0, 0), world.getEntityStore().getStore());
    }

    // ---- undo ----------------------------------------------------------------------------------------------------------
    /** Takes away the network nearest to (x, z): its roads, newest first, then its bridges, portal, plaza and platform. */
    public static void undo(EterniaModPlugin plugin, World world, UUID actor, int x, int z, PlayerRef player) {
        if (busy) throw new IllegalStateException("Town roads are already being built or removed");
        busy = true;
        JOBS.execute(() -> {
            try {
                SetupAccess.require(actor);
                var ledger = ledger(plugin, world.getName());
                var n = ledger.networks.stream().filter(o -> !"undone".equals(o.state))
                    .min(Comparator.comparingDouble(o -> Math.hypot(o.center[0] - x, o.center[1] - z)))
                    .orElseThrow(() -> new IllegalStateException("No town roads have been built in this world"));
                n.growing = false;
                update(plugin, world.getName(), n);
                TownBuilder.tell(player, "Taking away " + n.roads.size() + " town roads...");
                // Newest first, in passes: a road another road joins onto waits until that road is gone.
                Exception last = null;
                boolean loaded = false;
                while (!n.roads.isEmpty()) {
                    int before = n.roads.size();
                    for (var id : List.copyOf(n.roads).reversed()) {
                        try { retrying(() -> { TownBuilder.on(world, () -> TownRoadAuthor.remove(world, actor, UUID.fromString(id))).join(); return null; }); }
                        catch (Exception e) {
                            last = e;
                            if (!loaded) { TownBuilder.loadArea(world, n.center[0] - 450, n.center[1] - 450, n.center[0] + 450, n.center[1] + 450); loaded = true; }
                            continue;
                        }
                        n.roads.remove(id);
                        update(plugin, world.getName(), n);
                    }
                    if (n.roads.size() == before) {
                        // Stuck: something else rests on these roads. Half built leftovers are put back; anything drawn by hand is named.
                        boolean cleared = false;
                        Set<String> outside = new LinkedHashSet<>();
                        for (var id : List.copyOf(n.roads)) {
                            var joiners = TownBuilder.supply(world, () -> TownRoadAuthor.joiners(world, UUID.fromString(id)));
                            for (var j : joiners) {
                                if (j.pending()) { TownBuilder.on(world, () -> TownRoadAuthor.recover(world, actor, j.id())).join(); cleared = true; }
                                else if (!n.roads.contains(j.id().toString())) outside.add(j.x() + ", " + j.z());
                            }
                        }
                        if (cleared) continue;
                        if (!outside.isEmpty()) throw new IllegalStateException("Roads drawn with the Road Designer join this town's roads near " + String.join(" and ", outside) + ". Remove them, then run undo again");
                        throw last;
                    }
                }
                if (n.portal != null) {
                    TownBuilder.loadArea(world, n.anchor[0] - 40, n.anchor[2] - 40, n.anchor[0] + 40, n.anchor[2] + 40);
                    TownBuilder.on(world, () -> ManagedHubServices.removeById(plugin, world, actor, UUID.fromString(n.portal))).join();
                    n.portal = null;
                    update(plugin, world.getName(), n);
                }
                List<String> areas = new ArrayList<>(n.areas);
                if (n.plazaArea != null) areas.add(n.plazaArea);
                if (!areas.isEmpty()) {
                    TownBuilder.on(world, () -> {
                        var infra = plugin.getInfrastructure();
                        var snapshot = infra.snapshot();
                        var before = snapshot.worlds().get(world.getName());
                        if (before == null) return;
                        var next = new TreeMap<>(before.areas());
                        areas.forEach(next::remove);
                        infra.saveWorld(snapshot.revision(), world.getName(), before.withAreas(next));
                    }).join();
                    n.areas.clear();
                    n.plazaArea = null;
                    update(plugin, world.getName(), n);
                }
                if (!n.entities.isEmpty()) {
                    TownBuilder.on(world, () -> {
                        var store = world.getEntityStore().getStore();
                        for (var id : n.entities) { var ref = world.getEntityStore().getRefFromUUID(UUID.fromString(id)); if (ref != null && ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE); }
                    }).join();
                    n.entities.clear();
                }
                if (!n.backups.isEmpty()) {
                    TownBuilder.tell(player, "Putting the land back...");
                    var store = TownBuilder.store(plugin, "roads-" + n.id);
                    for (var b : n.backups.reversed()) {
                        var bounds = new NativeSnapshotStore.Bounds(b.min[0], b.min[1], b.min[2], b.max[0], b.max[1], b.max[2]);
                        TownBuilder.loadArea(world, bounds.minX(), bounds.minZ(), bounds.maxX() - 1, bounds.maxZ() - 1);
                        var snapshot = store.load(new SnapshotFiles.Saved(b.name, b.sha));
                        TownBuilder.on(world, () -> store.apply(world, snapshot, bounds.origin())).join();
                    }
                }
                n.state = "undone";
                update(plugin, world.getName(), n);
                world.execute(com.hexvane.eterniamod.customization.HousingWorldPolicy::refreshAll);
                TownBuilder.tell(player, "Town roads taken away" + ("PORTAL".equals(n.mode) ? ", with the platform and portal." : "."));
            } catch (Exception e) {
                TownBuilder.tell(player, "Undo stopped: " + TownBuilder.cause(e));
                plugin.getLogger().atWarning().withCause(e).log("Town roads undo failed");
            } finally { busy = false; }
        });
    }
}
