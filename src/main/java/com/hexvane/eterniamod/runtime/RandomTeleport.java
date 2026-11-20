package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.domain.TravelService;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hexvane.eterniamod.worldborder.WorldBorders;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;

/**
 * Random teleport in an adventure world: a random spot on dry land at the surface, inside the world border. The trip is
 * paid (or the free one used) only once a spot is found, and given back if the player cannot be moved there.
 * Each try loads one chunk without ticking it and looks at several columns in it.
 */
public final class RandomTeleport {
    /** Chunks tried before giving up. */
    static final int CHUNKS = 10;
    /** Columns looked at in each chunk. */
    static final int COLUMNS = 10;
    /** Keep this far inside the border. */
    static final int MARGIN = 24;
    /** The search area when an adventure world has no border set. */
    static final int DEFAULT_RADIUS = 1500;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Eternia-random-teleport"); t.setDaemon(true); return t; });
    private static final Set<UUID> SEARCHING = ConcurrentHashMap.newKeySet();
    private RandomTeleport() {}

    public static boolean available(EterniaModPlugin plugin, String worldId) {
        var plan = plugin.getInfrastructure().world(worldId).orElse(null);
        return plan != null && plan.role().equals("adventure") && Universe.get().getWorld(worldId) != null;
    }

    /** The area searched: the world border, or a square around the world's arrival when no border is set. */
    static WorldBorders.Border area(EterniaModPlugin plugin, String worldId) {
        var set = plugin.getWorldBorders() == null ? null : plugin.getWorldBorders().get(worldId).orElse(null);
        if (set != null) return set;
        var arrival = plugin.getInfrastructure().world(worldId).map(p -> p.arrival()).orElse(null);
        return new WorldBorders.Border(arrival == null ? 0 : (int) arrival.x(), arrival == null ? 0 : (int) arrival.z(), DEFAULT_RADIUS);
    }

    /** Called from a portal's travel menu, on the player's world thread. */
    public static void go(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, String worldId) {
        if (!plugin.getTravel().canUseTravel(ref, store, player.getUuid())) { player.sendMessage(Message.raw("Stand at a public portal to use random teleport.")); return; }
        if (!available(plugin, worldId)) { player.sendMessage(Message.raw("Random teleport only works in adventure worlds.")); return; }
        UUID id = player.getUuid();
        if (!SEARCHING.add(id)) { player.sendMessage(Message.raw("Still looking for a spot for you.")); return; }
        World world = Universe.get().getWorld(worldId);
        var area = area(plugin, worldId);
        String name = WorldDisplayNames.name(worldId);
        // Refuse before searching when the player cannot pay, so nobody makes the server explore for nothing.
        CompletableFuture.supplyAsync(() -> {
            long price = plugin.getServices().travel().randomPrice(id);
            if (price > 0 && plugin.getServices().economy().balance(Owner.player(id)).available() < price)
                throw new DomainException(DomainException.Code.INSUFFICIENT_BALANCE, "A random teleport costs " + price + " Coins.");
            return price;
        }, WORK).thenCompose(price -> {
            player.sendMessage(Message.raw("Looking for a quiet spot in " + name + "…"));
            return search(plugin, world, area, new Random(), 0);
        }).thenCompose(spot -> {
            if (spot == null) throw new CompletionException(new IllegalStateException("No dry land turned up this time. Try again."));
            String attempt = UUID.randomUUID().toString();
            return CompletableFuture.supplyAsync(() -> plugin.getServices().travel().chargeRandom(id, attempt), WORK)
                .thenCompose(paid -> plugin.getTravel().place(player, world, spot).orTimeout(30, TimeUnit.SECONDS)
                    .handle((ok, error) -> {
                        if (error != null || !Boolean.TRUE.equals(ok)) {
                            WORK.execute(() -> { try { plugin.getServices().travel().refundRandom(id, attempt); } catch (RuntimeException ignored) {} });
                            throw new CompletionException(new IllegalStateException("The trip did not work, so nothing was taken. Try again."));
                        }
                        return paid;
                    }));
        }).orTimeout(90, TimeUnit.SECONDS).whenComplete((paid, error) -> {
            SEARCHING.remove(id);
            if (error != null) {
                Throwable cause = error; while (cause.getCause() != null && (cause instanceof CompletionException || cause instanceof ExecutionException)) cause = cause.getCause();
                boolean ours = cause instanceof DomainException || cause instanceof IllegalStateException;
                player.sendMessage(Message.raw(ours ? cause.getMessage() : "Random teleport is not available right now. Try again in a moment."));
                return;
            }
            player.sendMessage(Message.raw(paid == 0
                ? "Welcome to a new corner of " + name + ". That was your free random trip; the next ones cost " + TravelService.RANDOM_PRICE + " Coins."
                : "Welcome to a new corner of " + name + ". " + paid + " Coins paid."));
        });
    }

    /** One try: a random chunk inside the area, loaded without ticking, then the best of several columns in it. */
    private static CompletableFuture<Vector3d> search(EterniaModPlugin plugin, World world, WorldBorders.Border area, Random random, int tries) {
        if (tries >= CHUNKS) return CompletableFuture.completedFuture(null);
        int reach = Math.max(1, area.radius() - MARGIN);
        int x = area.centerX() + random.nextInt(-reach, reach + 1), z = area.centerZ() + random.nextInt(-reach, reach + 1);
        int cx = Math.floorDiv(x, 32), cz = Math.floorDiv(z, 32);
        return world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(cx, cz), GetChunkFlags.NONE)
            .thenCompose(chunk -> onWorld(world, () -> columns(world, area, cx, cz, random)))
            .thenCompose(candidates -> tryColumns(plugin, world, candidates, 0))
            .thenCompose(spot -> spot != null ? CompletableFuture.completedFuture(spot) : search(plugin, world, area, random, tries + 1));
    }

    /** Random columns of the chunk that lie inside the area, each with its top block. */
    private static List<int[]> columns(World world, WorldBorders.Border area, int cx, int cz, Random random) {
        var out = new ArrayList<int[]>();
        for (int i = 0; i < COLUMNS; i++) {
            int x = cx * 32 + random.nextInt(32), z = cz * 32 + random.nextInt(32);
            if (area.distanceInside(x + 0.5, z + 0.5) < MARGIN) continue;
            int top = ChunkSectionBlockUtil.columnHeight(world, x, z);
            if (top >= ChunkUtil.MIN_Y + 2 && top <= 300) out.add(new int[] {x, top, z});
        }
        return out;
    }

    private static CompletableFuture<Vector3d> tryColumns(EterniaModPlugin plugin, World world, List<int[]> candidates, int i) {
        if (i >= candidates.size()) return CompletableFuture.completedFuture(null);
        int[] c = candidates.get(i);
        return TravelLanding.load(world, new Vector3d(c[0], c[1] + 1, c[2]), 0)
            .thenCompose(v -> onWorld(world, () -> land(world, c[0], c[1], c[2])))
            .thenCompose(spot -> spot != null ? CompletableFuture.completedFuture(spot) : tryColumns(plugin, world, candidates, i + 1));
    }

    /**
     * Dry, solid ground at the top of the column (not leaves, ice or a fluid), with the two cells above free of solid
     * blocks and fluids. Tall grass and flowers at feet height are fine to stand in.
     */
    static Vector3d land(World world, int x, int top, int z) {
        var floor = ChunkSectionBlockUtil.blockType(world, x, top, z);
        if (floor == null || floor.getMaterial() == null || !floor.getMaterial().name().equals("Solid")) return null;
        String id = floor.getId() == null ? "" : floor.getId().toLowerCase(Locale.ROOT);
        if (id.contains("leaves") || id.contains("ice") || id.contains("lava") || id.contains("water") || id.contains("cactus")) return null;
        if (wet(world, x, top, z)) return null;
        for (int dy = 1; dy <= 2; dy++) {
            var cell = ChunkSectionBlockUtil.blockType(world, x, top + dy, z);
            if (cell != null && cell.getMaterial() != null && cell.getMaterial().name().equals("Solid")) return null;
            if (wet(world, x, top + dy, z)) return null;
        }
        return new Vector3d(x + 0.5, top + 1, z + 0.5);
    }

    private static boolean wet(World world, int x, int y, int z) {
        var section = ChunkSectionBlockUtil.sectionRefAt(world, x, y, z);
        if (section == null) return true;
        var fluids = section.getStore().getComponent(section, FluidSection.getComponentType());
        return fluids != null && fluids.getFluidId(x, y, z) != 0;
    }

    private static <T> CompletableFuture<T> onWorld(World world, java.util.function.Supplier<T> work) {
        var out = new CompletableFuture<T>();
        world.execute(() -> { try { out.complete(work.get()); } catch (RuntimeException e) { out.completeExceptionally(e); } });
        return out;
    }
}
