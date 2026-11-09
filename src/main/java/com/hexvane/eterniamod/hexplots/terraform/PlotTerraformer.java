package com.hexvane.eterniamod.hexplots.terraform;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.builtin.buildertools.tooloperations.SculptOperation;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.joml.Vector3i;

/**
 * Shapes the ground of hexagons that are about to be claimed or added to a plot (docs/implementation/plot-terraform.md).
 *
 * <p>Runs before the claim commits, so the plot's saved terrain baseline already holds the shaped ground: packing up
 * puts back the shaped land, and nothing the shaping did ever counts as a player's edit. Only natural blocks are ever
 * changed (ground, plants, trees, snow): a column holding anything else, or water, is left exactly as it is, and so is
 * every road, plaza, other plot and the owner's older hexagons. Every column is checked again in the tick it is written.
 *
 * <p>Jobs run one at a time on a background thread. Reading the land and writing it happen on the world thread in
 * slices of at most {@link TerraformSettings#maxColumnsPerTick()} columns, one slice per tick, so the world never stalls.
 * A failed or refused shaping never blocks the claim: the claim goes ahead on the land as it is.
 */
public final class PlotTerraformer {
    private static final ExecutorService JOBS = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "Eternia plot terraform");
        t.setDaemon(true);
        return t;
    });
    /** How far above a column's ground natural growth (plants, trees) is read and cleared. */
    static final int GROWTH = 20;
    /** How far below the highest block the natural ground is looked for. */
    static final int GROUND_SEARCH = 64;

    private final EterniaModPlugin plugin;

    public PlotTerraformer(EterniaModPlugin plugin) { this.plugin = plugin; }

    /**
     * What to shape: {@code cells} for an owner. {@code growing} is the plot being grown (its owner group is read from the
     * world), or null for a fresh claim, whose owner group {@code group} gives (asked on the background thread, as it
     * may read the guild roster).
     */
    public record Request(String world, HexLattice lattice, Set<HexCoord> cells, Supplier<String> group, UUID growing) {
        public Request { cells = Set.copyOf(cells); }
    }

    /** What happened. {@code level} is the shaped ground height (top block y), or UNKNOWN when nothing was shaped. */
    public record Outcome(TerraformSettings.Mode mode, int level, TerraformLevel.Source source, int changed, int skipped, String note) {
        static Outcome none(TerraformSettings.Mode mode, String note) { return new Outcome(mode, TerraformLevel.UNKNOWN, TerraformLevel.Source.NONE, 0, 0, note); }
        public boolean shaped() { return level != TerraformLevel.UNKNOWN; }
    }

    /**
     * Shapes {@code request} with the current settings, then runs {@code commit} on the world thread. The shaping and the
     * commit run as one job, so two claims never shape the same land at once. {@code commit} runs even when shaping is
     * off or fails; the returned future completes with its result.
     */
    public <T> CompletableFuture<T> shapeThen(World world, Request request, Supplier<Boolean> stillWanted, java.util.function.Function<Outcome, T> commit) {
        return shapeThen(world, request, TerraformSettings.current(), stillWanted, commit);
    }

    public <T> CompletableFuture<T> shapeThen(World world, Request request, TerraformSettings settings, Supplier<Boolean> stillWanted, java.util.function.Function<Outcome, T> commit) {
        return CompletableFuture.supplyAsync(() -> {
            Outcome outcome;
            try { outcome = shape(world, request, settings, stillWanted); }
            catch (RuntimeException failure) {
                plugin.getLogger().atWarning().withCause(failure).log("Plot terraform for %s did not finish; the claim goes ahead on the land as it is", request.cells());
                outcome = Outcome.none(settings.mode(), "failed: " + failure.getMessage());
            }
            Outcome done = outcome;
            return CompletableFuture.supplyAsync(() -> commit.apply(done), world).join();
        }, JOBS);
    }

    /** Background thread. Reads, plans and writes; never commits anything. */
    Outcome shape(World world, Request request, TerraformSettings settings, Supplier<Boolean> stillWanted) {
        long started = System.nanoTime();
        try { return shapeTimed(world, request, settings, stillWanted); }
        finally { plugin.getLogger().atInfo().log("Housing terraform took %d ms", (System.nanoTime() - started) / 1_000_000); }
    }

    private Outcome shapeTimed(World world, Request request, TerraformSettings settings, Supplier<Boolean> stillWanted) {
        if (settings.mode() == TerraformSettings.Mode.OFF) return Outcome.none(settings.mode(), "off");
        if (request.cells().isEmpty()) return Outcome.none(settings.mode(), "no hexagons");
        boolean smooth = settings.mode() == TerraformSettings.Mode.SMOOTH;
        int margin = (smooth ? settings.skirtWidth() : 0) + TerraformField.KERNEL_REACH + 2;
        PlotRect box = request.lattice().bounds(request.cells());
        var grid = new TerraformField.Grid(box.x() - margin, box.z() - margin, box.width() + 2 * margin, box.depth() + 2 * margin);
        load(world, grid);
        if (!on(world, stillWanted)) return Outcome.none(settings.mode(), "no longer wanted");

        // Survey, a slice of columns per tick.
        var survey = new Survey(grid);
        String freshGroup = request.growing() == null ? request.group().get() : null;
        var context = on(world, () -> Context.read(plugin, world, request, freshGroup));
        for (var slice : slices(grid, settings.maxColumnsPerTick())) on(world, () -> { for (int i : slice) survey.read(world, plugin, context, request, i); return null; });
        // The road this land connects to: road or plaza columns inside the street hexagons its frontage touches.
        List<int[]> roadColumns = new ArrayList<>();
        for (HexCoord street : context.streets) on(world, () -> { roadColumns.addAll(roadColumns(world, plugin, request, street)); return null; });

        // Plan off the world thread.
        int level = level(request, context, survey, roadColumns);
        if (level == TerraformLevel.UNKNOWN) return Outcome.none(settings.mode(), "no ground found");
        TerraformLevel.Source source = context.decision.source();
        boolean[] road = new boolean[grid.size()];
        for (int i = 0; i < grid.size(); i++) road[i] = survey.kind[i] == Survey.ROAD;
        grid.fixNear(road, settings.roadClearance());
        int[] target = smooth
            ? TerraformField.smooth(grid, level, settings.skirtWidth(), settings.blendWidth(), settings.smoothPasses(), SculptOperation.NORMAL_HEIGHTMAP)
            : TerraformField.plateau(grid, level);
        String surface = survey.commonSurface();
        int fill = blockIndex(smooth ? settings.fillBlock() : settings.plateauBlock(), "Rock_Stone");

        // Write, a slice per tick; every column is checked again just before it changes.
        int[] counts = new int[2];
        List<Integer> changing = new ArrayList<>();
        // Columns that change height, and claimed columns with plants or rubble to clear even where the height stays.
        for (int i = 0; i < grid.size(); i++) if (target[i] != TerraformField.UNKNOWN && (target[i] != grid.natural[i] || grid.role[i] == TerraformField.OWN && survey.growth[i])) changing.add(i);
        if (!on(world, stillWanted)) return Outcome.none(settings.mode(), "no longer wanted");
        for (var slice : slices(changing, settings.maxColumnsPerTick())) on(world, () -> {
            var sel = new BlockSelection();
            sel.setPosition(0, 0, 0);
            sel.setAnchor(0, 0, 0);
            for (int i : slice) {
                if (survey.write(world, plugin, context, request, i, target[i], surface, fill, sel)) counts[0]++; else counts[1]++;
            }
            sel.placeNoReturn(world, new Vector3i(0, 0, 0), world.getEntityStore().getStore());
            return null;
        });
        plugin.getLogger().atInfo().log("Plot terraform (%s) shaped %d columns to y=%d from %s for %s; %d left as they were",
            settings.mode().displayName(), counts[0], level, source, request.cells(), counts[1]);
        return new Outcome(settings.mode(), level, source, counts[0], counts[1], "");
    }

    /** The level by the group, road and ground rules. Off the world thread. */
    private static int level(Request request, Context context, Survey survey, List<int[]> roadColumns) {
        var g = survey.grid;
        List<Integer> ground = new ArrayList<>();
        Map<UUID, List<Integer>> plotGround = new HashMap<>();
        int[] roads = TerraformLevel.nearestRoad(roadColumns, request.lattice(), request.cells(), ROAD_BAND);
        for (int i = 0; i < g.size(); i++) {
            if (g.role[i] == TerraformField.OWN) ground.add(g.natural[i]);
            UUID plot = survey.plot[i];
            if (plot != null && g.natural[i] != TerraformLevel.UNKNOWN) plotGround.computeIfAbsent(plot, k -> new ArrayList<>()).add(g.natural[i]);
        }
        context.decision = TerraformLevel.decide(context.group, request.cells(), request.growing(), context.plots,
            p -> TerraformLevel.median(ints(plotGround.get(p.plotId()))), roads, ints(ground));
        return context.decision.level();
    }

    /** Road columns further than this past the nearest one are not part of the road the land faces. */
    static final int ROAD_BAND = 4;

    /** {x, z, surface y} of every road or plaza column in {@code street}. World thread. */
    static List<int[]> roadColumns(World world, EterniaModPlugin plugin, Request request, HexCoord street) {
        List<int[]> out = new ArrayList<>();
        for (var span : request.lattice().spans(street)) for (int x = span.fromX(); x < span.toX(); x++) {
            if (!plugin.getInfrastructure().protectedColumn(request.world(), x, span.z())) continue;
            int y = Column.solidTop(world, x, span.z());
            if (y != TerraformLevel.UNKNOWN) out.add(new int[] {x, span.z(), y});
        }
        return out;
    }

    private static int[] ints(List<Integer> values) { return values == null ? new int[0] : values.stream().mapToInt(Integer::intValue).toArray(); }

    /** World facts read once per job on the world thread. */
    static final class Context {
        final HexLandView view;
        final List<TerraformLevel.GroupPlot> plots = new ArrayList<>();
        final String group;
        /** Street hexagons the new land touches. */
        final List<HexCoord> streets = new ArrayList<>();
        TerraformLevel.Decision decision;

        Context(HexLandView view, String group) { this.view = view; this.group = group; }

        static Context read(EterniaModPlugin plugin, World world, Request request, String freshGroup) {
            var view = HexLand.view(plugin, world).orElseThrow(() -> new IllegalStateException("This world has no hexagon plots"));
            String group = freshGroup;
            if (request.growing() != null) {
                var growing = view.plots().get(request.growing());
                if (growing == null) throw new IllegalStateException("The plot being grown is not in this world");
                group = TerraformLevel.group(growing);
            }
            var context = new Context(view, group);
            var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
            for (var info : view.plots().values()) {
                var record = manager.getPlot(info.plotId());
                Integer level = record == null ? null : record.getTerrainLevel();
                context.plots.add(new TerraformLevel.GroupPlot(info.plotId(), TerraformLevel.group(info), info.cells().asSet(), level, info.ownerKind() == HexLandView.OwnerKind.GUILD));
            }
            for (HexCoord cell : request.cells()) if (view.plotAt(cell) != null) throw new IllegalStateException("Hexagon " + cell + " already belongs to a plot");
            for (HexCoord cell : request.cells()) for (HexCoord n : cell.neighbors())
                if (!request.cells().contains(n) && view.isStreet(n) && !context.streets.contains(n)) context.streets.add(n);
            return context;
        }
    }

    /** Per column facts: what it is, its ground, the growth above it and the blocks to reuse on top. World thread only. */
    static final class Survey {
        static final byte LAND = 0, ROAD = 1, PLOT = 2, LOCKED = 3;
        final TerraformField.Grid grid;
        final byte[] kind;
        final int[] height, top, surfaceId, subId;
        final UUID[] plot;
        final boolean[] growth;

        Survey(TerraformField.Grid grid) {
            this.grid = grid;
            int n = grid.size();
            kind = new byte[n]; height = new int[n]; top = new int[n]; surfaceId = new int[n]; subId = new int[n]; plot = new UUID[n]; growth = new boolean[n];
        }

        void read(World world, EterniaModPlugin plugin, Context context, Request request, int i) {
            int x = grid.x(i), z = grid.z(i);
            var column = Column.read(world, x, z);
            height[i] = column.ground; top[i] = column.top; surfaceId[i] = column.surface; subId[i] = column.sub; growth[i] = column.growth;
            if (plugin.getInfrastructure().protectedColumn(request.world(), x, z)) {
                kind[i] = ROAD;
                height[i] = Column.solidTop(world, x, z);
                grid.set(x, z, TerraformField.FIXED, height[i]);
                return;
            }
            var owner = context.view.plotAtColumn(x, z);
            if (owner != null) {
                kind[i] = PLOT; plot[i] = owner.plotId();
                grid.set(x, z, TerraformField.FIXED, column.ground);
                return;
            }
            boolean mine = request.cells().contains(request.lattice().cellAt(x, z));
            if (!column.editable) { kind[i] = LOCKED; grid.set(x, z, TerraformField.FIXED, column.ground); return; }
            kind[i] = LAND;
            grid.set(x, z, mine ? TerraformField.OWN : TerraformField.FREE, column.ground);
        }

        /** Most common natural top block (grass, sand, snow) of the hexagons being shaped; grass when there is none. */
        String commonSurface() {
            Map<Integer, Integer> counts = new HashMap<>();
            for (int i = 0; i < grid.size(); i++) if (grid.role[i] == TerraformField.OWN && Column.isTopsoil(surfaceId[i])) counts.merge(surfaceId[i], 1, Integer::sum);
            return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> Column.id(e.getKey())).orElse("Soil_Grass");
        }

        /** Writes column {@code i} to height {@code t} if it is still exactly as surveyed. Returns false when left alone. */
        boolean write(World world, EterniaModPlugin plugin, Context context, Request request, int i, int t, String surface, int fill, BlockSelection sel) {
            int x = grid.x(i), z = grid.z(i);
            if (plugin.getInfrastructure().protectedColumn(request.world(), x, z) || context.view.plotAtColumn(x, z) != null) return false;
            var now = Column.read(world, x, z);
            if (!now.editable || now.ground != grid.natural[i]) return false;
            int n = now.ground;
            boolean mine = grid.role[i] == TerraformField.OWN;
            // The ground block on top stays what it was (grass, gravel, sand, stone); raised columns get it too.
            int topBlock = Column.isGround(now.surface) ? now.surface : blockIndex(mine ? surface : "Soil_Grass", "Soil_Grass");
            int subBlock = Column.isGround(now.sub) && !Column.id(now.sub).startsWith("Soil_Grass") ? now.sub : Column.subsoilFor(topBlock);
            // Natural growth above the new ground goes; it would float or be buried otherwise.
            for (int y = t + 1; y <= Math.max(now.top, n) + GROWTH && y < NativeSnapshotStore.MAX_Y; y++) {
                int id = ChunkSectionBlockUtil.blockId(world, x, y, z);
                if (id == 0) continue;
                // Grass blades stay on ground that keeps its height.
                if (y == t + 1 && t == n && TerraformBlocks.isKeptGrass(Column.id(id))) continue;
                sel.addEmptyAtWorldPos(x, y, z);
            }
            if (t > n) for (int y = n + 1; y < t; y++) sel.addBlockAtWorldPos(x, y, z, y >= t - 2 ? subBlock : fill, 0, FillerBlockUtil.NO_FILLER, 0);
            else if (t - 1 > ChunkUtil.MIN_Y) sel.addBlockAtWorldPos(x, t - 1, z, subBlock, 0, FillerBlockUtil.NO_FILLER, 0);
            sel.addBlockAtWorldPos(x, t, z, topBlock, 0, FillerBlockUtil.NO_FILLER, 0);
            return true;
        }
    }

    /** One column as it is now. */
    record Column(int ground, int top, int surface, int sub, boolean editable, boolean growth) {
        static Column read(World world, int x, int z) {
            int top = Math.min(ChunkSectionBlockUtil.columnHeight(world, x, z), NativeSnapshotStore.MAX_Y - 1);
            if (top < ChunkUtil.MIN_Y) return new Column(TerraformLevel.UNKNOWN, top, 0, 0, false, false);
            int ground = TerraformLevel.UNKNOWN;
            for (int y = top; y > Math.max(ChunkUtil.MIN_Y, top - GROUND_SEARCH); y--) {
                if (isGround(ChunkSectionBlockUtil.blockId(world, x, y, z))) { ground = y; break; }
            }
            if (ground == TerraformLevel.UNKNOWN) return new Column(ground, top, 0, 0, false, false);
            boolean editable = !hasFluid(world, x, ground + 1, z) && !hasFluid(world, x, ground, z), growth = false;
            for (int y = ground + 1; editable && y <= Math.min(top + GROWTH, NativeSnapshotStore.MAX_Y - 1); y++) {
                int id = ChunkSectionBlockUtil.blockId(world, x, y, z);
                if (id == 0) continue;
                String name = id(id);
                if (!TerraformBlocks.isGrowth(name)) editable = false;
                else if (!(y == ground + 1 && TerraformBlocks.isKeptGrass(name))) growth = true;
            }
            return new Column(ground, top, ChunkSectionBlockUtil.blockId(world, x, ground, z), ChunkSectionBlockUtil.blockId(world, x, ground - 1, z), editable, growth);
        }

        /** Highest full cube block (road paving under lamps and fences). */
        static int solidTop(World world, int x, int z) {
            int top = Math.min(ChunkSectionBlockUtil.columnHeight(world, x, z), NativeSnapshotStore.MAX_Y - 1);
            for (int y = top; y > Math.max(ChunkUtil.MIN_Y, top - 16); y--) {
                var type = ChunkSectionBlockUtil.blockType(world, x, y, z);
                if (type != null && type.isCubeDrawType()) return y;
            }
            return TerraformLevel.UNKNOWN;
        }

        static boolean hasFluid(World world, int x, int y, int z) {
            if (y < ChunkUtil.MIN_Y || y >= NativeSnapshotStore.MAX_Y) return false;
            var section = ChunkSectionBlockUtil.sectionRefAt(world, x, y, z);
            if (section == null) return true;
            var fluids = world.getChunkStore().getStore().getComponent(section, FluidSection.getComponentType());
            return fluids != null && fluids.getFluidId(x, y, z) != Fluid.EMPTY_ID;
        }

        static String id(int index) {
            var type = BlockType.getAssetMap().getAsset(index);
            return type == null ? "" : type.getId();
        }

        /** Any full solid block that is not a plant, tree or rubble: grass, dirt, gravel, sand, stone, ore. */
        static boolean isGround(int index) {
            if (index == 0) return false;
            var type = BlockType.getAssetMap().getAsset(index);
            return type != null && type.isCubeDrawType() && type.getMaterial().name().equals("Solid") && !TerraformBlocks.isGrowth(type.getId());
        }
        static boolean isNatural(int index) { return TerraformBlocks.isNatural(id(index)); }
        static boolean isTopsoil(int index) { return index != 0 && TerraformBlocks.isTopsoil(id(index)); }
        static int subsoilFor(int topIndex) { return blockIndex(TerraformBlocks.subsoilFor(id(topIndex)), "Soil_Dirt"); }
    }

    static int blockIndex(String id, String fallback) {
        int index = BlockType.getAssetMap().getIndex(id);
        if (index < 0) index = BlockType.getAssetMap().getIndex(fallback);
        if (index < 0) throw new IllegalStateException("Unknown block " + id);
        return index;
    }

    /** Groups of at most {@code size} column indices, in row order. */
    static List<List<Integer>> slices(TerraformField.Grid grid, int size) {
        List<Integer> all = new ArrayList<>(grid.size());
        for (int i = 0; i < grid.size(); i++) all.add(i);
        return slices(all, size);
    }

    static List<List<Integer>> slices(Collection<Integer> indices, int size) {
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> current = new ArrayList<>(size);
        for (int i : indices) { current.add(i); if (current.size() == size) { out.add(current); current = new ArrayList<>(size); } }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    /** Loads every chunk and section under the grid. Background thread. */
    static void load(World world, TerraformField.Grid g) {
        int cx0 = Math.floorDiv(g.minX, ChunkUtil.SIZE), cx1 = Math.floorDiv(g.minX + g.width - 1, ChunkUtil.SIZE);
        int cz0 = Math.floorDiv(g.minZ, ChunkUtil.SIZE), cz1 = Math.floorDiv(g.minZ + g.depth - 1, ChunkUtil.SIZE);
        var loads = new ArrayList<CompletableFuture<?>>();
        for (int x = cx0; x <= cx1; x++) for (int z = cz0; z <= cz1; z++) loads.add(world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(x, z), GetChunkFlags.SET_TICKING));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
        loads.clear();
        int sy1 = Math.floorDiv(NativeSnapshotStore.MAX_Y - 1, ChunkUtil.SIZE);
        for (int x = cx0; x <= cx1; x++) for (int z = cz0; z <= cz1; z++) for (int y = 0; y <= sy1; y++) loads.add(world.getChunkStore().getChunkSectionReferenceAsync(x, y, z, GetChunkFlags.SET_TICKING));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
    }

    /** Runs {@code work} on the world thread and waits for it. Background thread only. */
    static <T> T on(World world, Supplier<T> work) {
        if (world.isInThread()) throw new IllegalStateException("Plot terraform must not wait on the world thread");
        return CompletableFuture.supplyAsync(work, world).join();
    }
}
