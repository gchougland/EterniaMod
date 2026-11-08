package com.hexvane.eterniamod.hexmap;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.boundary.HexBorderPlanner;
import com.hexvane.eterniamod.boundary.HexViewers;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandEvents;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.packets.worldmap.MapChunk;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Paints hex plots onto each player's own world map: borders and a soft fill in the shared palette (mine, my guild,
 * other players, other guilds) and a faint stone tint on street cells. Ported from Aetherhaven's town border overlay.
 *
 * <ul>
 *   <li>Per player: tiles are painted for one viewer and sent only to that player; the shared map cache is never
 *       touched, so other players and the engine keep the plain tiles.</li>
 *   <li>Only tiles the client already has (read from the engine's map tracker), nearest first, at most
 *       {@link #MAX_TILES_PER_PLAYER_PER_TICK} per player every {@link #TICK_MS} ms, and the whole world pass stops
 *       after {@link #MAX_TICK_BUDGET_NS} (the next pass continues with the next player).</li>
 *   <li>Cached: the viewer independent layout per tile and the painted tile per player are reused until
 *       {@link HexLandEvents} touches the tile, the engine regenerates the tile, or the viewer's guild changes.</li>
 *   <li>Restored: a tile whose plots are gone, or a world that stops being a hex world, gets the plain tile back.</li>
 * </ul>
 */
public final class HexMapOverlayService implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(HexMapOverlayService.class.getName());
    public static final long TICK_MS = 750L;
    public static final int MAX_TILES_PER_PLAYER_PER_TICK = 12;
    public static final long MAX_TICK_BUDGET_NS = 6_000_000L;
    public static final int MAX_SORTED_CANDIDATES = 96;
    private static final int MAX_STREET_TILES = 4096;

    private final EterniaModPlugin plugin;
    private final Map<String, WorldState> worlds = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Runnable stopListening;
    private volatile ScheduledFuture<?> task;
    private volatile boolean closed;

    /** Live numbers for the notes and the admin log. */
    public record Stats(long tilesPainted, long tilesSent, long tilesRestored, long worstPassNanos) {}
    private long tilesPainted, tilesSent, tilesRestored, worstPassNanos;

    public HexMapOverlayService(EterniaModPlugin plugin) {
        this.plugin = plugin;
        this.stopListening = HexLandEvents.listen(change -> {
            WorldState state = worlds.get(change.world());
            if (state != null) state.stamps.apply(change, HexLand.lattice(change.world()).orElse(null));
        });
    }

    public void start() {
        if (!MapTilesOnClient.available()) return;
        task = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::schedule, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        closed = true;
        ScheduledFuture<?> running = task;
        if (running != null) running.cancel(false);
        stopListening.run();
        worlds.clear();
        players.clear();
    }

    public synchronized Stats stats() { return new Stats(tilesPainted, tilesSent, tilesRestored, worstPassNanos); }

    public void forgetPlayer(UUID player) { players.remove(player); }

    private void schedule() {
        if (closed) return;
        try {
            for (World world : Universe.get().getWorlds().values()) {
                if (!world.isAlive()) { worlds.remove(world.getName()); continue; }
                String name = world.getName();
                if (HexLand.isHexWorld(name) || worlds.containsKey(name)) world.execute(() -> tick(world));
            }
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "Hex map overlay pass failed", failure);
        }
    }

    // ---- per world --------------------------------------------------------------------------------------------

    private static final class WorldState {
        final HexMapLandStamps stamps = new HexMapLandStamps();
        final Map<UUID, Integer> slots = new HashMap<>();
        final List<UUID> slotPlots = new ArrayList<>();
        final Long2ObjectOpenHashMap<CachedGeometry> geometry = new Long2ObjectOpenHashMap<>();
        HexLandView view;
        LongOpenHashSet candidates = new LongOpenHashSet();
        int cursor;

        int slot(UUID plot) {
            Integer slot = slots.get(plot);
            if (slot == null) { slot = slotPlots.size(); slots.put(plot, slot); slotPlots.add(plot); }
            return slot;
        }

        int keyAt(int x, int z) {
            HexCoord cell = view.lattice().cellAt(x, z);
            HexLandView.PlotInfo plot = view.plotAt(cell);
            if (plot != null) return slot(plot.plotId());
            return view.isStreet(cell) ? HexMapPainter.STREET : HexMapPainter.NONE;
        }
    }

    private record CachedGeometry(long stamp, int width, int height, HexMapPainter.Geometry geometry) {}

    private void tick(World world) {
        if (closed) return;
        long start = System.nanoTime();
        String name = world.getName();
        HexLandView view = HexLand.isHexWorld(name) ? HexLand.view(plugin, world).orElse(null) : null;
        if (view == null) {
            // Not (or no longer) a hex world: give everyone their plain map back.
            WorldState gone = worlds.remove(name);
            if (gone != null) for (PlayerRef ref : world.getPlayerRefs()) restoreAll(world, ref);
            return;
        }
        WorldState state = worlds.computeIfAbsent(name, ignored -> new WorldState());
        if (state.view != view) {
            if (state.view != null && !state.view.lattice().equals(view.lattice())) state.stamps.apply(new HexLandEvents.Change(name, Set.of(), HexLandEvents.Cause.LATTICE), null);
            state.view = view;
            state.candidates = candidateTiles(view);
        }
        List<PlayerRef> refs = new ArrayList<>(world.getPlayerRefs());
        Set<UUID> here = new HashSet<>();
        for (PlayerRef ref : refs) here.add(ref.getUuid());
        players.entrySet().removeIf(e -> e.getValue().world.equals(name) && !here.contains(e.getKey()));
        int count = refs.size();
        if (count == 0) return;
        int first = Math.floorMod(state.cursor, count);
        for (int i = 0; i < count; i++) {
            if (System.nanoTime() - start > MAX_TICK_BUDGET_NS) { state.cursor = first + i; record(start); return; }
            try {
                updatePlayer(world, state, refs.get((first + i) % count));
            } catch (RuntimeException failure) {
                LOG.log(Level.FINE, "Hex map overlay skipped a player", failure);
            }
        }
        state.cursor = first + count;
        record(start);
    }

    private synchronized void record(long start) { worstPassNanos = Math.max(worstPassNanos, System.nanoTime() - start); }

    /** Map tiles under any plot, and under street cells (capped), as chunk indices. */
    static LongOpenHashSet candidateTiles(HexLandView view) {
        LongOpenHashSet out = new LongOpenHashSet();
        for (HexLandView.PlotInfo plot : view.plots().values()) addTiles(out, plot.cells().bounds(), 2);
        int streets = 0;
        for (HexCoord cell : view.streetCells()) {
            if (streets++ > MAX_STREET_TILES) break;
            addTiles(out, view.lattice().bounds(cell), 0);
        }
        return out;
    }

    private static void addTiles(LongSet out, PlotRect r, int margin) {
        int minX = Math.floorDiv(r.x() - margin, HexMapPixels.TILE_BLOCKS), maxX = Math.floorDiv(r.endX() + margin, HexMapPixels.TILE_BLOCKS);
        int minZ = Math.floorDiv(r.z() - margin, HexMapPixels.TILE_BLOCKS), maxZ = Math.floorDiv(r.endZ() + margin, HexMapPixels.TILE_BLOCKS);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) out.add(ChunkUtil.indexChunk(x, z));
    }

    // ---- per player -------------------------------------------------------------------------------------------

    private static final class PlayerState {
        final String world;
        final Long2ObjectOpenHashMap<CachedTile> tiles = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet painted = new LongOpenHashSet();

        PlayerState(String world) { this.world = world; }
    }

    /** {@code image} is null when the tile has nothing to draw (the plain tile is on the client). */
    private record CachedTile(int baseIdentity, long stamp, int viewerKey, MapImage image) {}

    private void updatePlayer(World world, WorldState state, PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || !playerRef.getPacketHandler().getChannel(NetworkChannel.WorldMap).isWritable()) return;
        UUID id = playerRef.getUuid();
        PlayerState ps = players.compute(id, (k, old) -> old == null || !old.world.equals(world.getName()) ? new PlayerState(world.getName()) : old);

        LongSet loaded = MapTilesOnClient.of(player);
        // Tiles the client dropped: forget them, the engine sends the plain tile again when it reloads one.
        ps.tiles.keySet().removeIf(index -> !loaded.contains(index));
        ps.painted.removeIf(index -> !loaded.contains(index));

        HexLandView.Viewer viewer = HexViewers.viewer(id);
        int viewerKey = Objects.hash(viewer.player(), viewer.guild());
        WorldMapManager maps = world.getWorldMapManager();
        List<MapChunk> send = new ArrayList<>();

        // Tiles that were painted but have no plot or street any more: plain tile back.
        LongArrayList stale = new LongArrayList();
        for (long index : ps.painted) if (!state.candidates.contains(index)) stale.add(index);
        for (long index : stale) {
            if (send.size() >= MAX_TILES_PER_PLAYER_PER_TICK) break;
            MapImage base = maps.getImageIfInMemory(index);
            ps.painted.remove(index);
            ps.tiles.remove(index);
            if (base != null && HexMapPixels.hasPixelData(base)) {
                send.add(new MapChunk(ChunkUtil.xOfChunkIndex(index), ChunkUtil.zOfChunkIndex(index), HexMapPixels.cloneImage(base)));
                count(0, 0, 1);
            }
        }

        var position = playerRef.getTransform().getPosition();
        long[] queue = nearest(loaded, state.candidates, ChunkUtil.chunkCoordinate((int) Math.floor(position.x)), ChunkUtil.chunkCoordinate((int) Math.floor(position.z)));
        for (long index : queue) {
            if (send.size() >= MAX_TILES_PER_PLAYER_PER_TICK) break;
            int cx = ChunkUtil.xOfChunkIndex(index), cz = ChunkUtil.zOfChunkIndex(index);
            MapImage base = maps.getImageIfInMemory(cx, cz);
            if (base == null || !HexMapPixels.hasPixelData(base)) continue;
            int baseIdentity = System.identityHashCode(base);
            long stamp = state.stamps.stamp(cx, cz);
            CachedTile cached = ps.tiles.get(index);
            if (cached != null && cached.baseIdentity == baseIdentity && cached.stamp == stamp && cached.viewerKey == viewerKey) continue;
            MapImage painted = paintTile(state, base, cx, cz, stamp, viewer);
            ps.tiles.put(index, new CachedTile(baseIdentity, stamp, viewerKey, painted));
            if (painted != null) {
                ps.painted.add(index);
                send.add(new MapChunk(cx, cz, painted));
                count(1, 1, 0);
            } else if (ps.painted.remove(index)) {
                send.add(new MapChunk(cx, cz, HexMapPixels.cloneImage(base)));
                count(0, 0, 1);
            }
        }
        if (!send.isEmpty()) playerRef.getPacketHandler().writeNoCache(new UpdateWorldMap(send.toArray(MapChunk[]::new), null, null));
    }

    private synchronized void count(long painted, long sent, long restored) {
        tilesPainted += painted;
        tilesSent += sent + restored;
        tilesRestored += restored;
    }

    /**
     * One tile painted for one viewer exactly as players get it, without a server: for the pictures in the notes
     * and tests. Returns null when nothing on the tile is drawn.
     */
    public static MapImage render(HexLandView view, MapImage base, int chunkX, int chunkZ, HexLandView.Viewer viewer) {
        WorldState state = new WorldState();
        state.view = view;
        return paintTile(state, base, chunkX, chunkZ, 0L, viewer);
    }

    private static MapImage paintTile(WorldState state, MapImage base, int cx, int cz, long stamp, HexLandView.Viewer viewer) {
        long index = ChunkUtil.indexChunk(cx, cz);
        CachedGeometry cached = state.geometry.get(index);
        if (cached == null || cached.stamp != stamp || cached.width != base.width || cached.height != base.height) {
            cached = new CachedGeometry(stamp, base.width, base.height, HexMapPainter.geometry(cx, cz, base.width, base.height, state::keyAt));
            state.geometry.put(index, cached);
        }
        if (cached.geometry.isEmpty()) return null;
        int[] pixels = HexMapPixels.unpack(base);
        if (pixels == null) return null;
        HexLandView view = state.view;
        int[] out = HexMapPainter.paint(pixels, cached.geometry, key -> {
            if (key < 0 || key >= state.slotPlots.size()) return null;
            HexLandView.PlotInfo plot = view.plots().get(state.slotPlots.get(key));
            return plot == null ? null : HexMapPainter.Style.of(HexBorderPlanner.relation(view, plot, viewer));
        });
        return HexMapPixels.pack(base.width, base.height, out);
    }

    /** Candidate tiles the client has, nearest to the player first, at most {@link #MAX_SORTED_CANDIDATES}. */
    static long[] nearest(LongSet loaded, LongSet candidates, int playerChunkX, int playerChunkZ) {
        LongArrayList both = new LongArrayList();
        LongSet small = loaded.size() < candidates.size() ? loaded : candidates, big = small == loaded ? candidates : loaded;
        for (long index : small) if (big.contains(index)) both.add(index);
        long[] all = both.toLongArray();
        Long[] boxed = new Long[all.length];
        for (int i = 0; i < all.length; i++) boxed[i] = all[i];
        java.util.Arrays.sort(boxed, java.util.Comparator.comparingLong(index -> {
            long dx = ChunkUtil.xOfChunkIndex(index) - playerChunkX, dz = ChunkUtil.zOfChunkIndex(index) - playerChunkZ;
            return dx * dx + dz * dz;
        }));
        int n = Math.min(MAX_SORTED_CANDIDATES, boxed.length);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = boxed[i];
        return out;
    }

    private void restoreAll(World world, PlayerRef playerRef) {
        PlayerState ps = players.remove(playerRef.getUuid());
        if (ps == null || ps.painted.isEmpty() || !playerRef.getPacketHandler().getChannel(NetworkChannel.WorldMap).isWritable()) return;
        WorldMapManager maps = world.getWorldMapManager();
        List<MapChunk> send = new ArrayList<>();
        for (long index : ps.painted) {
            MapImage base = maps.getImageIfInMemory(index);
            if (base != null && HexMapPixels.hasPixelData(base))
                send.add(new MapChunk(ChunkUtil.xOfChunkIndex(index), ChunkUtil.zOfChunkIndex(index), HexMapPixels.cloneImage(base)));
        }
        if (!send.isEmpty()) playerRef.getPacketHandler().writeNoCache(new UpdateWorldMap(send.toArray(MapChunk[]::new), null, null));
        count(0, 0, send.size());
    }
}
