package com.hexvane.eterniamod.town;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.terraform.TerraformBlocks;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Grows a town's road network around a centre, the grown street plan of the road growth simulation
 * ({@link GrownRoadPlan}), built road by road through the Road Designer's own review and confirm ({@link TownRoadAuthor}).
 *
 * <p>Two centres: the plaza an administrator stands in or nearest to (the Hub square; roads grow from the streets that
 * already leave it and never cross it), or a new portal town: the town's spawn platform pasted where the administrator
 * stands, with a world portal in its middle, registered as a public plaza, roads growing from its edges. Every network is
 * recorded so {@code /e admin town roads undo} takes away the nearest one, its roads, portal and platform.
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
    /** The spawn platform: the town's centre piece, 65 x 65 blocks round its anchor. */
    static final String PLATFORM = "EterniaMod/Hub/Eternia_Town_Center";
    static final int PLATFORM_HALF = 32, SKIRT = 8, PLATFORM_HEIGHT = 46;
    static final int SAMPLE = 4;

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService JOBS = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "Eternia town roads"); t.setDaemon(true); return t; });
    private static volatile boolean busy;

    /** A planned network waiting for the administrator's yes. */
    public record Preview(UUID actor, String world, Mode mode, HexCoord center, int radius, PlotRect plaza, int[] anchor,
                          List<Road> roads, String summary, Instant expires) {}
    /** One road to build: its hexagons and the points it runs through (x, ground y, z). */
    public record Road(GrownRoadPlan.Chain chain, List<double[]> nodes) {}

    // ---- the record of what was built ----------------------------------------------------------------------------------
    static final class Network {
        String id, world, mode, state, plazaArea, portal;
        int[] center, anchor;
        long created;
        List<String> roads = new ArrayList<>(), entities = new ArrayList<>();
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
                return plan(plugin, world, actor, mode, x, z, Math.max(2, Math.min(MAX_RADIUS, radius)), player);
            } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e.getMessage(), e); }
        }, JOBS);
    }

    private static Preview plan(EterniaModPlugin plugin, World world, UUID actor, Mode mode, int x, int z, int radius, PlayerRef player) throws Exception {
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
        var survey = survey(plugin, world, worldPlan, lattice, disk, plaza, mode);

        // Sources: plaza hexagons in portal mode; elsewhere hexagons where a road already leaves the plaza.
        Set<HexCoord> sources = new LinkedHashSet<>(), blockers = new LinkedHashSet<>();
        Set<HexCoord> plazaCells = lattice.cellsTouching(plaza);
        for (HexCoord c : disk) {
            boolean street = survey.street.contains(c);
            if (mode == Mode.PORTAL && plazaCells.contains(c)) sources.add(c);
            else if (survey.roadColumns.containsKey(c)) sources.add(c);
            else if (street || plazaCells.contains(c)) blockers.add(c);
        }
        if (sources.isEmpty()) throw new IllegalStateException("No road leaves this plaza yet. Build one road out of it with the Road Designer first");
        var settings = new GrownRoadPlan.Settings(2, mode == Mode.PORTAL ? 2 : 1, 400, 0.03);
        var result = GrownRoadPlan.grow(center, survey.land, sources, blockers, (s, t) -> survey.edgeOk(lattice, s, t, plaza, mode), settings);
        var chains = GrownRoadPlan.chains(result, sources, MAX_CHAIN);
        if (chains.isEmpty()) {
            long open = survey.land.values().stream().filter(GrownRoadPlan.Land::roadOk).count();
            throw new IllegalStateException("There is no room for new roads here: the land is taken, too steep or under water ("
                + survey.land.size() + " of " + disk.size() + " hexagons read, " + open + " open for roads, " + sources.size() + " streets to grow from)");
        }
        List<Road> roads = new ArrayList<>();
        for (var chain : chains) roads.add(new Road(chain, nodes(lattice, survey, chain, plaza, mode)));
        int blocks = 0;
        for (var r : roads) for (int i = 1; i < r.nodes().size(); i++) blocks += (int) Math.round(Math.hypot(r.nodes().get(i)[0] - r.nodes().get(i - 1)[0], r.nodes().get(i)[2] - r.nodes().get(i - 1)[2]));
        String where = mode == Mode.PORTAL ? "A portal town with its spawn platform and a portal in the middle goes here. " : "Roads grow out from the plaza's streets and never cross the plaza. ";
        String summary = where + roads.size() + " roads, about " + blocks + " blocks long, reach " + result.cells().size() + " hexagons within " + radius
            + " hexagons of the middle. The plan is drawn in the world for a minute. /e admin town roads undo takes it away again.";
        return new Preview(actor, world.getName(), mode, center, radius, plaza, anchor, List.copyOf(roads), summary, Instant.now().plusSeconds(600));
    }

    private static double gap(PlotRect r, int x, int z) {
        double dx = Math.max(0, Math.max(r.x() - x, x - (r.endX() - 1))), dz = Math.max(0, Math.max(r.z() - z, z - (r.endZ() - 1)));
        return Math.hypot(dx, dz);
    }

    /** What the survey found. */
    static final class Survey {
        final Map<HexCoord, GrownRoadPlan.Land> land = new HashMap<>();
        final Map<HexCoord, Double> height = new HashMap<>();
        final Map<HexCoord, List<int[]>> roadColumns = new HashMap<>();
        final Set<HexCoord> street = new HashSet<>();
        final Map<Long, int[]> columns = new HashMap<>(); // key -> {ground, wet(0/1), blocked(0/1)}
        /** Columns of the roads already built (outside plazas): new roads may join them. */
        final Set<Long> roadSet = new HashSet<>();
        HexLandView view;

        int[] column(int x, int z) { return columns.get(TownBuilder.key(x, z)); }

        /** A road may run from s to t: gentle enough, and dry and free all the way. */
        boolean edgeOk(HexLattice lattice, HexCoord s, HexCoord t, PlotRect plaza, Mode mode) {
            double[] a = start(lattice, this, s, t, plaza, mode), b = {lattice.centerX(t), 0, lattice.centerZ(t)};
            Double ht = height.get(t);
            double len = Math.hypot(b[0] - a[0], b[2] - a[2]);
            if (ht == null || len < 1) return false;
            if (!Double.isNaN(a[1]) && Math.abs(a[1] - ht) / len > MAX_GRADE) return false;
            int steps = (int) Math.ceil(len / 2);
            for (int i = 0; i <= steps; i++) {
                double f = i / (double) steps;
                int x = (int) Math.floor(a[0] + (b[0] - a[0]) * f), z = (int) Math.floor(a[2] + (b[2] - a[2]) * f);
                if (mode == Mode.PORTAL && plaza.contains(x, z)) continue;
                int[] c = nearest(x, z);
                if (c == null) return false;
                if (c[1] == 1 || c[2] == 1) return false;
            }
            return true;
        }

        private int[] nearest(int x, int z) {
            int bx = Math.floorDiv(x, SAMPLE) * SAMPLE, bz = Math.floorDiv(z, SAMPLE) * SAMPLE;
            int[] c = column(bx, bz);
            if (c != null) return c;
            for (int dx = 0; dx <= SAMPLE; dx += SAMPLE) for (int dz = 0; dz <= SAMPLE; dz += SAMPLE) { c = column(bx + dx, bz + dz); if (c != null) return c; }
            return null;
        }
    }

    /** Where a road from s towards t starts: the nearest road column in s, a point just inside the plaza, or s's middle. y is the ground there, or NaN. */
    static double[] start(HexLattice lattice, Survey survey, HexCoord s, HexCoord t, PlotRect plaza, Mode mode) {
        double tx = lattice.centerX(t), tz = lattice.centerZ(t);
        var roads = survey.roadColumns.get(s);
        if (roads != null && !roads.isEmpty()) {
            int[] best = null; double d = Double.MAX_VALUE;
            for (int[] c : roads) { double e = Math.hypot(c[0] + .5 - tx, c[1] + .5 - tz); if (e < d) { d = e; best = c; } }
            return new double[] {best[0] + .5, best[2], best[1] + .5};
        }
        if (mode == Mode.PORTAL && lattice.cellsTouching(plaza).contains(s)) {
            double x = Math.max(plaza.x() + 2.5, Math.min(plaza.endX() - 2.5, tx)), z = Math.max(plaza.z() + 2.5, Math.min(plaza.endZ() - 2.5, tz));
            return new double[] {x, Double.NaN, z};
        }
        Double h = survey.height.get(s);
        return new double[] {lattice.centerX(s), h == null ? Double.NaN : h, lattice.centerZ(s)};
    }

    /** Reads the land of every hexagon: ground heights on a 4 block grid, water, and anything a road must keep off. */
    private static Survey survey(EterniaModPlugin plugin, World world, HousingInfrastructure.WorldPlan worldPlan, HexLattice lattice, List<HexCoord> disk, PlotRect plaza, Mode mode) {
        var s = new Survey();
        TownBuilder.on(world, () -> s.view = HexLand.view(plugin, world).orElseThrow(() -> new IllegalStateException("This world has no hexagon plots"))).join();
        var view = s.view;
        s.street.addAll(view.streetCells());
        var portals = worldPlan.portals();
        // Road columns outside every plaza, by hexagon: where new roads join the old ones.
        for (var rect : worldPlan.roads()) for (int x = rect.x(); x < rect.endX(); x++) for (int z = rect.z(); z < rect.endZ(); z++) {
            int fx = x, fz = z;
            if (portals.stream().anyMatch(p -> p.contains(fx, fz))) continue;
            s.roadSet.add(TownBuilder.key(x, z));
            s.roadColumns.computeIfAbsent(lattice.cellAt(x, z), k -> new ArrayList<>()).add(new int[] {x, z, 0});
        }
        s.roadColumns.keySet().retainAll(new HashSet<>(disk));
        // Batches of hexagons, each loaded and read in one world tick.
        List<HexCoord> order = new ArrayList<>(disk);
        order.sort(HexCoord.READING_ORDER);
        for (int i = 0; i < order.size(); i += 6) {
            var batch = order.subList(i, Math.min(order.size(), i + 6));
            var box = lattice.bounds(batch);
            TownBuilder.loadArea(world, box.x() - 2, box.z() - 2, box.endX() + 2, box.endZ() + 2);
            TownBuilder.on(world, () -> {
                for (HexCoord c : batch) readCell(plugin, world, view, lattice, c, s, plaza, mode);
            }).join();
        }
        return s;
    }

    private static void readCell(EterniaModPlugin plugin, World world, HexLandView view, HexLattice lattice, HexCoord c, Survey s, PlotRect plaza, Mode mode) {
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
                boolean keepOff = !s.roadSet.contains(TownBuilder.key(x, z)) && plugin.getInfrastructure().protectedColumn(world.getName(), x, z) || view.plotAtColumn(x, z) != null
                    || ManagedHubServices.intersectsManaged(world.getName(), new PlotRect(x, z, 1, 1));
                s.columns.put(TownBuilder.key(x, z), new int[] {g, water || g == TownTerrainPlanner.UNKNOWN ? 1 : 0, keepOff ? 1 : 0});
                if (g == TownTerrainPlanner.UNKNOWN) { blocked = true; continue; }
                count++;
                if (water) wet++;
                if (keepOff && !plaza.contains(x, z)) blocked = true;
                min = Math.min(min, g); max = Math.max(max, g); sum += g;
            }
        }
        // Road columns themselves are where new roads meet the old: use the road height, not a block.
        var roads = s.roadColumns.get(c);
        if (roads != null) for (int[] r : roads) {
            int g = TownBuilder.natural(world, r[0], r[1]);
            r[2] = g == TownTerrainPlanner.UNKNOWN ? (count == 0 ? 0 : (int) (sum / Math.max(1, count))) : g;
        }
        if (count == 0) return;
        double relief = max - min, mean = sum / (double) count;
        s.height.put(c, mean);
        boolean street = s.street.contains(c), owned = view.plotAt(c) != null || view.reservedCells().contains(c) || view.blockedCells().contains(c);
        boolean dry = wet * 5 < count;
        boolean claimable = !street && !owned && dry && relief <= MAX_PLOT_RELIEF;
        boolean roadOk = !street && !owned && wet == 0 && !blocked && relief <= MAX_ROAD_RELIEF;
        s.land.put(c, new GrownRoadPlan.Land(roadOk, claimable, relief));
    }

    static boolean hasFluid(World world, int x, int y, int z) {
        if (y < 0 || y >= NativeSnapshotStore.MAX_Y) return false;
        var section = ChunkSectionBlockUtil.sectionRefAt(world, x, y, z);
        if (section == null) return false;
        var fluids = world.getChunkStore().getStore().getComponent(section, FluidSection.getComponentType());
        return fluids != null && fluids.getFluidId(x, y, z) != Fluid.EMPTY_ID;
    }

    /** The points a road runs through: its start, then each hexagon's middle, with a midpoint where the ground bends. */
    private static List<double[]> nodes(HexLattice lattice, Survey s, GrownRoadPlan.Chain chain, PlotRect plaza, Mode mode) {
        List<double[]> out = new ArrayList<>();
        var cells = chain.cells();
        double[] first = chain.fromSource() ? start(lattice, s, cells.get(0), cells.get(1), plaza, mode) : new double[] {lattice.centerX(cells.get(0)), s.height.getOrDefault(cells.get(0), Double.NaN), lattice.centerZ(cells.get(0))};
        out.add(first);
        for (int i = 1; i < cells.size(); i++) {
            double[] prev = out.getLast();
            HexCoord c = cells.get(i);
            double[] next = {lattice.centerX(c), s.height.getOrDefault(c, Double.NaN), lattice.centerZ(c)};
            out.add(new double[] {(prev[0] + next[0]) / 2, Double.NaN, (prev[2] + next[2]) / 2});
            out.add(next);
        }
        return out;
    }

    // ---- preview drawing ----------------------------------------------------------------------------------------------
    /** Draws the planned roads over the land for a minute: green lines from hexagon to hexagon. World thread. */
    public static void draw(PlayerRef player, Preview preview) {
        var color = new Vector3f(.45f, .85f, .5f);
        for (var road : preview.roads()) {
            var n = road.nodes();
            for (int i = 1; i < n.size(); i++) {
                double y0 = Double.isNaN(n.get(i - 1)[1]) ? (preview.anchor() == null ? 64 : preview.anchor()[1]) : n.get(i - 1)[1];
                double y1 = Double.isNaN(n.get(i)[1]) ? y0 : n.get(i)[1];
                line(player, n.get(i - 1)[0], y0 + 2.5, n.get(i - 1)[2], n.get(i)[0], y1 + 2.5, n.get(i)[2], color);
            }
        }
        var p = preview.plaza();
        double y = preview.anchor() == null ? (preview.roads().isEmpty() || Double.isNaN(preview.roads().getFirst().nodes().getFirst()[1]) ? 64 : preview.roads().getFirst().nodes().getFirst()[1]) + 2 : preview.anchor()[1] + 2;
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
    /** Builds a previewed network: the platform and portal first in portal mode, then each road outwards from the middle. */
    public static void build(EterniaModPlugin plugin, World world, Preview preview, PlayerRef player) {
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
            var lattice = plugin.getInfrastructure().world(world.getName()).orElseThrow().lattice();
            n.center = new int[] {(int) lattice.centerX(preview.center()), (int) lattice.centerZ(preview.center())};
            n.anchor = preview.anchor();
            try {
                SetupAccess.require(preview.actor());
                update(plugin, world.getName(), n);
                if (preview.mode() == Mode.PORTAL) platform(plugin, world, preview, n, player);
                int built = 0, skipped = 0;
                Set<HexCoord> ready = new HashSet<>();
                for (var road : preview.roads()) {
                    var chain = road.chain();
                    if (!chain.fromSource() && !ready.contains(chain.start())) { skipped++; continue; }
                    try {
                        UUID id = null;
                        for (int attempt = 1; id == null; attempt++) {
                            try { id = buildRoad(world, preview.actor(), road); }
                            catch (Exception e) {
                                // Chunks still being saved or loaded: wait and try again, as town placement does.
                                String why = TownBuilder.cause(e).toLowerCase(Locale.ROOT);
                                if (attempt >= 40 || !(why.contains("in flight") || why.contains("retry") || why.contains("loading"))) throw e;
                                Thread.sleep(250);
                            }
                        }
                        n.roads.add(id.toString());
                        update(plugin, world.getName(), n);
                        ready.addAll(chain.cells());
                        built++;
                        if (built % 5 == 0) TownBuilder.tell(player, "Built " + built + " of " + preview.roads().size() + " roads...");
                    } catch (Exception e) {
                        skipped++;
                        plugin.getLogger().atInfo().log("Town road from %s to %s skipped: %s", chain.start(), chain.end(), TownBuilder.cause(e));
                    }
                }
                n.state = "built";
                update(plugin, world.getName(), n);
                TownBuilder.tell(player, "Town roads done: " + built + " roads built" + (skipped > 0 ? ", " + skipped + " left out where the land or a building was in the way." : "."));
            } catch (Exception e) {
                n.state = "stopped";
                try { update(plugin, world.getName(), n); } catch (IOException ignored) {}
                TownBuilder.tell(player, "Town roads stopped: " + TownBuilder.cause(e) + ". /e admin town roads undo takes away what was built.");
                plugin.getLogger().atWarning().withCause(e).log("Town roads failed");
            } finally { busy = false; }
        });
    }

    /** Loads the road's land, fills in ground heights, and builds it through the Road Designer. Background thread. */
    private static UUID buildRoad(World world, UUID actor, Road road) throws Exception {
        var nodes = road.nodes();
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] p : nodes) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); }
        TownBuilder.loadArea(world, (int) minX - 12, (int) minZ - 12, (int) maxX + 12, (int) maxZ + 12);
        UUID[] id = new UUID[1];
        TownBuilder.on(world, () -> {
            List<double[]> ground = new ArrayList<>();
            for (double[] p : nodes) {
                int g = surface(world, (int) Math.floor(p[0]), (int) Math.floor(p[2]));
                if (g == TownTerrainPlanner.UNKNOWN) throw new IllegalStateException("No ground at " + (int) p[0] + ", " + (int) p[2]);
                ground.add(new double[] {p[0], g, p[2]});
            }
            id[0] = TownRoadAuthor.author(world, actor, ground, WIDTH);
        }).join();
        return id[0];
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
    /** Takes away the network nearest to (x, z): its roads, newest first, then its portal, plaza and platform. */
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
                TownBuilder.tell(player, "Taking away " + n.roads.size() + " town roads...");
                for (var id : n.roads.reversed()) {
                    try { TownBuilder.on(world, () -> TownRoadAuthor.remove(world, actor, UUID.fromString(id))).join(); }
                    catch (Exception e) {
                        // The road's own land may need loading first.
                        TownBuilder.loadArea(world, n.center[0] - 400, n.center[1] - 400, n.center[0] + 400, n.center[1] + 400);
                        TownBuilder.on(world, () -> TownRoadAuthor.remove(world, actor, UUID.fromString(id))).join();
                    }
                }
                n.roads.clear();
                update(plugin, world.getName(), n);
                if (n.portal != null) {
                    TownBuilder.loadArea(world, n.anchor[0] - 40, n.anchor[2] - 40, n.anchor[0] + 40, n.anchor[2] + 40);
                    TownBuilder.on(world, () -> ManagedHubServices.removeById(plugin, world, actor, UUID.fromString(n.portal))).join();
                    n.portal = null;
                    update(plugin, world.getName(), n);
                }
                if (n.plazaArea != null) {
                    TownBuilder.on(world, () -> {
                        var infra = plugin.getInfrastructure();
                        var snapshot = infra.snapshot();
                        var before = snapshot.worlds().get(world.getName());
                        if (before == null || !before.areas().containsKey(n.plazaArea)) return;
                        var areas = new TreeMap<>(before.areas());
                        areas.remove(n.plazaArea);
                        infra.saveWorld(snapshot.revision(), world.getName(), before.withAreas(areas));
                    }).join();
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
                TownBuilder.tell(player, "Town roads taken away" + (n.anchor != null && "PORTAL".equals(n.mode) ? ", with the platform and portal." : "."));
            } catch (Exception e) {
                TownBuilder.tell(player, "Undo stopped: " + TownBuilder.cause(e));
                plugin.getLogger().atWarning().withCause(e).log("Town roads undo failed");
            } finally { busy = false; }
        });
    }
}
