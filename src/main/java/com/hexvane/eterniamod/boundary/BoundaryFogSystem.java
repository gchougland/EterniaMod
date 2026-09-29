package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/** Default-on enchanted boundary motes, entirely cosmetic and explicitly addressed to each nearby viewer. */
public final class BoundaryFogSystem extends EntityTickingSystem<EntityStore> implements AutoCloseable {
    public static final String PARTICLE_SYSTEM = "Eternia_Plot_Border_Motes";
    public static final float REFRESH_SECONDS = 1.2f;
    public static final float MAX_EFFECT_SECONDS = 3.7f;
    public static final double EMITTER_HEIGHT = .07;
    public static final double SPRITE_ENVELOPE_RADIUS = .44;
    private final EterniaModPlugin plugin;
    private final BoundaryGroundSampler sampler;
    private final Map<UUID, BoundaryEmissionSchedule> schedules = new ConcurrentHashMap<>();
    private final Map<String, Index> indexes = new ConcurrentHashMap<>();
    private volatile boolean closed;

    BoundaryFogSystem(EterniaModPlugin plugin, BoundarySurfacePolicy policy) {
        this.plugin = plugin;
        this.sampler = new BoundaryGroundSampler(policy);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() { return Player.getComponentType(); }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
        @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (closed) return;
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        PlayerRef viewer = store.getComponent(ref, PlayerRef.getComponentType());
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (viewer == null || transform == null || !Float.isFinite(dt) || dt <= 0) return;
        if (store.getComponent(ref, DeathComponent.getComponentType()) != null) { forgetPlayer(viewer.getUuid()); return; }
        var schedule = schedules.computeIfAbsent(viewer.getUuid(), ignored -> new BoundaryEmissionSchedule());
        if (!schedule.advance(dt)) return;
        World world = store.getExternalData().getWorld();
        var position = transform.getPosition();
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) return;
        List<BoundaryGeometry.Rectangle> rectangles = nearbyRectangles(world, position.x, position.z);
        for (BoundaryGeometry.Sample sample : BoundaryGeometry.nearby(rectangles, position.x, position.z)) {
            if (!schedule.ready(world.getName(), sample.x(), sample.z())) continue;
            double ground = sampler.sample(world, sample);
            if (!Double.isFinite(ground) || Math.abs(position.y - ground) > 3) continue;
            double strength = BoundaryGeometry.intensity(sample.distance());
            if (strength <= 0) continue;
            // Steady emission of small rising motes; overlapping lifetimes prevent a synchronized pulse.
            String particle = strength > .66 ? PARTICLE_SYSTEM : strength > .25 ? PARTICLE_SYSTEM + "_Soft" : PARTICLE_SYSTEM + "_Faint";
            ParticleUtil.spawnParticleEffect(particle, sample.x(), ground + EMITTER_HEIGHT, sample.z(),
                sample.horizontal()?0:(float)(Math.PI/2), 0, 0, 1, null, null, List.of(ref), commandBuffer, MAX_EFFECT_SECONDS);
            schedule.emitted(world.getName(), sample.x(), sample.z());
        }
    }

    public void forgetPlayer(UUID player) { schedules.remove(player); }
    public void invalidate(World world) { indexes.remove(world.getName()); }
    @Override public void close() { closed = true; schedules.clear(); indexes.clear(); }

    private List<BoundaryGeometry.Rectangle> nearbyRectangles(World world, double x, double z) {
        long revision = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).getRevision();
        Index index = indexes.get(world.getName());
        if (index == null || revision != index.revision) {
            index = buildIndex(world, revision);
            indexes.put(world.getName(), index);
        }
        Set<BoundaryGeometry.Rectangle> found = new LinkedHashSet<>();
        int centerX = (int) Math.floor(x / 32);
        int centerZ = (int) Math.floor(z / 32);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            found.addAll(index.buckets.getOrDefault(key(centerX + dx, centerZ + dz), List.of()));
        }
        return List.copyOf(found);
    }

    private Index buildIndex(World world, long revision) {
        Map<Long, List<BoundaryGeometry.Rectangle>> buckets = new HashMap<>();
        for (HubPlotRecord plot : EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).listPlots()) {
            HubPlotFootprint fp = plot.getFootprint();
            if (fp == null || fp.getMaxX() == Integer.MAX_VALUE || fp.getMaxZ() == Integer.MAX_VALUE) continue;
            long width = (long) fp.getMaxX() - fp.getMinX() + 1;
            long depth = (long) fp.getMaxZ() - fp.getMinZ() + 1;
            if (width <= 0 || depth <= 0 || width > 512 || depth > 512) continue;
            var rectangle = new BoundaryGeometry.Rectangle(fp.getMinX(), fp.getMinZ(), fp.getMaxX() + 1, fp.getMaxZ() + 1, fp.resolveVisualCenterY());
            int minX = Math.floorDiv(rectangle.minX(), 32), maxX = Math.floorDiv(rectangle.maxX(), 32);
            int minZ = Math.floorDiv(rectangle.minZ(), 32), maxZ = Math.floorDiv(rectangle.maxZ(), 32);
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
                if (cx != minX && cx != maxX && cz != minZ && cz != maxZ) continue;
                buckets.computeIfAbsent(key(cx, cz), ignored -> new ArrayList<>()).add(rectangle);
            }
        }
        return new Index(revision, buckets);
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private record Index(long revision, Map<Long, List<BoundaryGeometry.Rectangle>> buckets) {}
}
