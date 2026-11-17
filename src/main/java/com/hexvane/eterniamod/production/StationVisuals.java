package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions;
import com.hexvane.eterniamod.placement.PropPackHighlight;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.production.ProductionRuntime.Station;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * What a station looks like while it works: a bucket riding the shaft once per load, a cart rolling the rail, a heap
 * of goods on the stockpile that grows as it fills, sparkles for a golden moment. All of it is temporary copies that
 * are never saved and that placement snapshots skip, shown only while a player is near. World thread only, except
 * {@link #playersNear}.
 */
final class StationVisuals {
    static final double VIEW = 48;
    /** A sparkle that ends by itself. Never use the vanilla Dust_Sparkles systems for a moment: they run for ever. */
    static final String SPARKLE = "Eternia_Station_Sparkle";
    static final String STOCKPILE = "Eternia_Station_Stockpile", VEIN = "Eternia_Station_Vein", BOARD = "Eternia_Station_Board";
    private final ProductionRuntime runtime;
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();
    private final Map<String, Marks> marks = new ConcurrentHashMap<>();
    private final Map<String, Integer> refreshes = new ConcurrentHashMap<>();

    /** Where the used blocks and the rail sit in a station's prefab once it is turned, in blocks from the anchor. */
    record Marks(Vector3i stockpile, Vector3i vein, Vector3i board, Vector3d railFrom, Vector3d railTo) {}

    private static final class Shown {
        Station station;
        Ref<EntityStore> bucket, cart, marker;
        final List<Ref<EntityStore>> heap = new ArrayList<>();
        /** The rope from the headframe down to the bucket, one piece per block, lowest first. */
        final List<Ref<EntityStore>> rope = new ArrayList<>();
        int heapCount = -1, heapTier;
        double clock, sparkle;
        volatile boolean near;
        // What the slow refresh last saw, for the fast one to glide between: how far into the load the station was,
        // how long a load takes, and when that was read.
        double cycle = 1, cycleAt;
        long syncNanos, movedNanos;
        boolean working, full, ready;
        int tier = 1;
    }

    /** Most rope pieces a shaft needs, and how often the moving parts are moved. */
    private static final int ROPE_PIECES = 9;
    static final long MOTION_MILLIS = 33;

    StationVisuals(ProductionRuntime runtime) { this.runtime = runtime; }

    // ---- where things are ----

    void place(Station s, HubPlotProp prop) {
        s.x = prop.getAnchorX(); s.y = prop.getAnchorY(); s.z = prop.getAnchorZ();
        s.yaw = prop.resolveRotationYaw();
        s.located = true;
    }

    void locate(World world, Station s) {
        HubPlotProp prop = prop(world, s);
        if (prop != null) place(s, prop);
    }

    private HubPlotProp prop(World world, Station s) {
        HubPlotRecord plot = s.row.plot == null ? null : EterniaWorldRegistries.getOrCreateHubPlotManager(world, runtime.plugin).getPlot(s.row.plot);
        if (plot == null) return null;
        for (HubPlotProp prop : plot.getProps()) if (s.row.id.equals(prop.getInstanceId())) return prop;
        return null;
    }

    Marks marks(Station s) {
        int tier = Math.max(1, s.row.tierShown);
        boolean shallow = StationPlacement.shallow(runtime.profession(s), s.y);
        return marks.computeIfAbsent(s.row.progress.profession + ":" + tier + ":" + shallow + ":" + s.yaw.name(), key -> {
            IPrefabBuffer buffer = PrefabResolveUtil.resolvePrefabBuffer(runtime.profession(s).prefabFile(tier, shallow));
            Vector3i[] found = new Vector3i[3];
            int[] rail = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, 0};
            if (buffer != null) buffer.forEach(IPrefabBuffer.iterateAllColumns(), (x, y, z, blockId, holder, support, rotation, filler, t, fluidId, fluidLevel) -> {
                if (blockId == 0 || filler != 0) return;
                BlockType type = BlockType.getAssetMap().getAsset(blockId);
                if (type == null) return;
                switch (type.getId()) {
                    case STOCKPILE -> found[0] = new Vector3i(x, y, z);
                    case VEIN -> found[1] = new Vector3i(x, y, z);
                    case BOARD -> found[2] = new Vector3i(x, y, z);
                    case "Rail" -> { rail[0] = Math.min(rail[0], x); rail[1] = Math.min(rail[1], z); rail[2] = Math.max(rail[2], x); rail[3] = Math.max(rail[3], z); rail[4] = y; }
                    default -> {}
                }
            }, null, null, new PrefabBufferCall(new FastRandom(), PrefabRotation.fromRotation(s.yaw)));
            Vector3d from = rail[0] == Integer.MAX_VALUE ? null : new Vector3d(rail[0] + 0.5, rail[4] + 0.5, rail[1] + 0.5);
            Vector3d to = rail[0] == Integer.MAX_VALUE ? null : new Vector3d(rail[2] + 0.5, rail[4] + 0.5, rail[3] + 0.5);
            return new Marks(found[0] == null ? new Vector3i(0, 0, 0) : found[0], found[1], found[2], from, to);
        });
    }

    /** The middle of the stockpile's top, where goods pile up and burst from. */
    Vector3d heapAt(Station s) {
        Vector3i m = marks(s).stockpile();
        return new Vector3d(s.x + m.x + 0.5, s.y + m.y + 1.0, s.z + m.z + 0.5);
    }

    Vector3d pitAt(Station s) { return new Vector3d(s.x + 0.5, s.y + 0.2, s.z + 0.5); }

    /** Any thread: is a player close enough to see this station? Kept from the last refresh. */
    boolean playersNear(Station s) {
        Shown v = shown.get(s.row.id);
        return v != null && v.near;
    }

    // ---- the refresh, a few times a second per housing world ----

    void refresh(World world, double seconds) {
        int count = refreshes.merge(world.getName(), 1, Integer::sum);
        if (count % 40 == 1) runtime.reconcile(world);
        var players = new ArrayList<Vector3d>();
        for (PlayerRef player : world.getPlayerRefs()) players.add(new Vector3d(player.getTransform().getPosition()));
        Store<EntityStore> store = world.getEntityStore().getStore();
        for (Station s : runtime.stations()) {
            if (!s.row.placed || !s.located || !world.getName().equals(s.row.world) || s.busy) continue;
            boolean near = false;
            for (Vector3d p : players) if (p.distanceSquared(s.x + 0.5, s.y, s.z + 0.5) < VIEW * VIEW) { near = true; break; }
            Shown v = shown.computeIfAbsent(s.row.id, id -> new Shown());
            v.station = s;
            v.near = near;
            if (!near) { remove(store, v); s.pulse = false; continue; }
            try { animate(world, store, s, v, seconds); }
            catch (RuntimeException failure) { runtime.plugin.getLogger().atWarning().withCause(failure).log("Station visuals failed for %s", s.row.id); }
        }
        ProductionHud.refresh(runtime, world);
        if (count % 4 == 0) ProductionStationPage.tickOpen();
    }

    private void animate(World world, Store<EntityStore> store, Station s, Shown v, double seconds) {
        Profession p = runtime.profession(s);
        var layout = p.layout();
        double cycle, phase, fill;
        int tier;
        boolean full;
        synchronized (s) {
            var mods = runtime.mods(s);
            cycle = ProductionMath.cycleSeconds(p, mods);
            phase = Math.clamp(s.row.progress.cycleSeconds / cycle, 0, 1);
            double cap = ProductionMath.cap(runtime.rules, p, s.row.progress, mods);
            fill = cap <= 0 ? 0 : Math.clamp(s.row.progress.stockpile / cap, 0, 1);
            full = s.row.progress.full;
            tier = ProductionMath.tier(runtime.rules, s.row.progress.level);
        }
        boolean working = s.running && !full;
        // The bucket, its rope, the cart and the hovering good are moved by motion(), many times a second, so they
        // glide. This slower pass only tells it where the station is in its load.
        v.cycle = Math.max(0.1, cycle);
        v.cycleAt = phase * v.cycle;
        v.syncNanos = System.nanoTime();
        v.working = working;
        v.full = full;
        v.tier = tier;
        v.ready = true;

        // The heap on the stockpile: more goods the fuller it is, always the newest good of the tier.
        int want = full ? 5 : (int) Math.floor(fill * 4.999);
        if (want != v.heapCount || tier != v.heapTier) {
            for (Ref<EntityStore> ref : v.heap) if (ref != null && ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
            v.heap.clear();
            Vector3d base = heapAt(s);
            String item = p.good(tier).item();
            for (int i = 0; i < want; i++) {
                double angle = i * 2.4, r = i == 0 ? 0 : 0.22;
                v.heap.add(item(store, item, new Vector3d(base).add(Math.cos(angle) * r, 0.05 + 0.12 * (i / 2), Math.sin(angle) * r), 1.6f, (float) angle));
            }
            v.heapCount = want;
            v.heapTier = tier;
        }

        if (!full && v.marker != null) {
            if (v.marker.isValid()) store.removeEntity(v.marker, RemoveReason.REMOVE);
            v.marker = null;
        }
        motion(store, s, v);

        if (s.pulse) {
            // A load came up: a sound, no dust. Dust is kept for when the player swings the pick.
            s.pulse = false;
            sound(store, "SFX_Pickaxe_T2_Impact_Nice", pitAt(s));
        }
        if (s.veinFlash) {
            s.veinFlash = false;
            ParticleUtil.spawnParticleEffect(StationVisuals.SPARKLE, pitAt(s).add(0, 1, 0), store);
            sound(store, "SFX_Discovery_Z1_Short", pitAt(s));
        }
        if (s.golden > 0) {
            v.sparkle -= seconds;
            if (v.sparkle <= 0) {
                v.sparkle = s.golden < 10 ? 1.0 : 2.0;
                ParticleUtil.spawnParticleEffect(StationVisuals.SPARKLE, heapAt(s).add(0, 0.6, 0), store);
            }
        }
    }

    // ---- the moving parts, many times a second ----

    /** Moves every station's bucket, rope, cart and hovering good a little, so they glide instead of jumping. */
    void motion(World world) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        for (Shown v : shown.values()) {
            Station s = v.station;
            if (s == null || !v.near || !v.ready || s.busy || !s.row.placed || !s.located || !world.getName().equals(s.row.world)) continue;
            try { motion(store, s, v); }
            catch (RuntimeException failure) { runtime.plugin.getLogger().atWarning().withCause(failure).log("Station motion failed for %s", s.row.id); }
        }
    }

    private void motion(Store<EntityStore> store, Station s, Shown v) {
        Profession p = runtime.profession(s);
        var layout = p.layout();
        long now = System.nanoTime();
        double step = v.movedNanos == 0 ? 0 : Math.min(0.5, (now - v.movedNanos) / 1e9);
        v.movedNanos = now;
        if (v.working) v.clock += step;
        // Where the load is now: what the slow pass read, plus the time since.
        double into = v.working ? v.cycleAt + (now - v.syncNanos) / 1e9 : v.cycleAt;
        double phase = Math.clamp(into / v.cycle, 0, 1);

        // The bucket goes down the shaft for the first half of a load and comes back up full for the second, easing
        // in and out at both ends.
        double travel = v.working || v.full ? (v.full ? 1 : phase < 0.5 ? 1 - phase * 2 : (phase - 0.5) * 2) : 0.85;
        travel = travel * travel * (3 - 2 * travel);
        double low = StationPlacement.shallow(p, s.y) ? 0.2 : layout.liftLow();
        double bucketY = s.y + low + (layout.liftHigh() - low) * travel;
        Vector3d bucketAt = new Vector3d(s.x + 0.5, bucketY, s.z + 0.5);
        v.bucket = move(store, v.bucket, bucketAt, () -> block(store, layout.liftBlock(), bucketAt, 0.8f, (float) Math.PI));

        // The rope: one piece per block from the bucket's handle up to the log of the headframe. The pieces ride with
        // the bucket, and the top one slides into the log, so the bucket always hangs from something.
        String line = v.tier >= 5 ? "Deco_Iron_Chains_Vertical" : "Deco_Rope";
        double top = s.y + layout.height() - 4, handle = bucketY + 0.7;
        while (v.rope.size() < ROPE_PIECES) v.rope.add(null);
        for (int i = 0; i < ROPE_PIECES; i++) {
            double y = handle + i;
            Ref<EntityStore> piece = v.rope.get(i);
            if (y >= top) {
                if (piece != null && piece.isValid()) store.removeEntity(piece, RemoveReason.REMOVE);
                v.rope.set(i, null);
                continue;
            }
            Vector3d at = new Vector3d(s.x + 0.5, y, s.z + 0.5);
            v.rope.set(i, move(store, piece, at, () -> block(store, line, at, 1f, 0f)));
        }

        // The cart rolls the rail back and forth while the station works, and waits by the stockpile when it is full.
        // It faces along the rail whichever way the station was turned.
        Marks m = marks(s);
        if (m.railFrom() != null) {
            double along = v.full ? 1 : 0.5 - 0.5 * Math.cos(v.clock * Math.PI / 5);
            Vector3d cartAt = new Vector3d(s.x, s.y, s.z).add(new Vector3d(m.railFrom()).lerp(m.railTo(), along));
            float yaw = (float) Math.atan2(m.railTo().x - m.railFrom().x, m.railTo().z - m.railFrom().z);
            v.cart = move(store, v.cart, cartAt, () -> block(store, layout.cartBlock(), cartAt, 1f, yaw));
        }

        // A full stockpile calls for attention: one good hovers and bobs above it.
        if (v.full) {
            Vector3d above = heapAt(s).add(0, 1.15 + 0.18 * Math.sin(now / 3.3e8), 0);
            v.marker = move(store, v.marker, above, () -> item(store, p.good(v.tier).item(), above, 2.2f, 0));
        }
    }

    // ---- effects for one moment ----

    void placedEffect(World world, Station s) {
        if (!s.located) return;
        Store<EntityStore> store = world.getEntityStore().getStore();
        ParticleUtil.spawnParticleEffect("Teleport", pitAt(s).add(0, 1.5, 0), store);
        sound(store, "SFX_Discovery_Z1_Medium", pitAt(s));
    }

    /** Fireworks over the headframe, for a talent kept. They end by themselves. */
    void celebrate(World world, Station s) {
        if (!s.located || !world.getName().equals(s.row.world)) return;
        Store<EntityStore> store = world.getEntityStore().getStore();
        ParticleUtil.spawnParticleEffect("Firework_GS", pitAt(s).add(0, 5, 0), store);
        ParticleUtil.spawnParticleEffect(SPARKLE, pitAt(s).add(0, 2.5, 0), store);
        sound(store, "SFX_Discovery_Z1_Medium", pitAt(s));
    }

    static void sound(Store<EntityStore> store, String event, Vector3d at) {
        int index = SoundEvent.getAssetMap().getIndex(event);
        if (index != SoundEvent.EMPTY_ID && index != Integer.MIN_VALUE) SoundUtil.playSoundEvent3d(index, SoundCategory.SFX, at, store);
    }

    // ---- changing tier ----

    /**
     * Rebuilds the station as its tier's prefab when that is not what stands there: packs the old look and places the
     * new one in the same spot, through the same safe steps as any decoration. If something stands in the way the old
     * look stays and the player is told what to move.
     */
    boolean showTier(World world, Station s, UUID actor) {
        int want;
        synchronized (s) { want = ProductionMath.tier(runtime.rules, s.row.progress.level); }
        if (want == s.row.tierShown || !s.located) return false;
        var plugin = runtime.plugin;
        HubPlotRecord plot = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).getPlot(s.row.plot);
        HubPlotProp prop = prop(world, s);
        IPrefabBuffer buffer = PrefabResolveUtil.resolvePrefabBuffer(runtime.profession(s).prefabFile(want, StationPlacement.shallow(runtime.profession(s), s.y)));
        if (plot == null || prop == null || buffer == null) return false;
        UUID who = actor != null ? actor : plot.getOwnerUuid();
        if (who == null) return false;
        s.busy = true;
        hide(world, s);
        try {
            var custody = plugin.getServices().provenance().find(s.row.id).orElseThrow().owner();
            UUID packed = NativePlacementTransactions.pickup(plugin, world, plot, null, s.row.id);
            NativePlacementTransactions.complete(plugin, packed);
            var placed = NativePlacementTransactions.place(plugin, world, plot, who, prop.getPropId(),
                new Vector3i(prop.getAnchorX(), prop.getAnchorY(), prop.getAnchorZ()), prop.resolveRotationYaw(), buffer, false, custody, true, true);
            NativePlacementTransactions.complete(plugin, placed.operation());
            if (!placed.instanceId().equals(s.row.id)) plugin.getLogger().atWarning().log("Station %s came back as %s after a tier change", s.row.id, placed.instanceId());
            synchronized (s) { s.row.tierShown = want; s.dirty = true; }
            runtime.save(s);
            Store<EntityStore> store = world.getEntityStore().getStore();
            ParticleUtil.spawnParticleEffect("Teleport", pitAt(s).add(0, 1.5, 0), store);
            ParticleUtil.spawnParticleEffect("Block_Break_Dust", pitAt(s), store);
            return true;
        } catch (Exception refused) {
            plugin.getLogger().atInfo().log("Station %s keeps its old look: %s", s.row.id, refused.getMessage());
            ProductionFeedback.lookKept(runtime, s, refused.getMessage());
            return false;
        } finally {
            s.busy = false;
        }
    }

    // ---- copies ----

    void hide(World world, Station s) {
        Shown v = shown.remove(s.row.id);
        if (v != null) remove(world.getEntityStore().getStore(), v);
    }

    private static void remove(Store<EntityStore> store, Shown v) {
        for (Ref<EntityStore> ref : v.heap) if (ref != null && ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
        v.heap.clear();
        v.heapCount = -1;
        for (Ref<EntityStore> ref : v.rope) if (ref != null && ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
        v.rope.clear();
        for (Ref<EntityStore> ref : java.util.Arrays.asList(v.bucket, v.cart, v.marker)) if (ref != null && ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
        v.bucket = v.cart = v.marker = null;
        v.ready = false;
        v.movedNanos = 0;
    }

    private interface Maker { Ref<EntityStore> make(); }

    private static Ref<EntityStore> move(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d to, Maker maker) {
        if (ref == null || !ref.isValid()) return maker.make();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform != null) transform.teleportPosition(to);
        return ref;
    }

    private static Ref<EntityStore> block(Store<EntityStore> store, String blockId, Vector3d at, float scale, float yaw) {
        Holder<EntityStore> copy = EntityStore.REGISTRY.newHolder();
        copy.putComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(at), new Rotation3f(0f, yaw, 0f)));
        copy.putComponent(BlockEntity.getComponentType(), new BlockEntity(blockId));
        copy.putComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        return spawn(store, copy);
    }

    private static Ref<EntityStore> item(Store<EntityStore> store, String itemId, Vector3d at, float scale, float yaw) {
        Holder<EntityStore> copy = EntityStore.REGISTRY.newHolder();
        copy.putComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(at), new Rotation3f(0f, yaw, 0f)));
        copy.putComponent(ItemComponent.getComponentType(), new ItemComponent(new ItemStack(itemId)));
        copy.putComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        copy.ensureComponent(PreventItemMerging.getComponentType());
        return spawn(store, copy);
    }

    /** Never saved, skipped by placement snapshots, and cannot be touched or picked up. */
    private static Ref<EntityStore> spawn(Store<EntityStore> store, Holder<EntityStore> holder) {
        holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.putComponent(UUIDComponent.getComponentType(), UUIDComponent.randomUUID());
        holder.putComponent(PropPackHighlight.Ghost.getComponentType(), new PropPackHighlight.Ghost());
        holder.putComponent(PropComponent.getComponentType(), PropComponent.get());
        holder.putComponent(Intangible.getComponentType(), Intangible.INSTANCE);
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        return store.addEntity(holder, AddReason.SPAWN);
    }

    void close() { shown.clear(); }
}
