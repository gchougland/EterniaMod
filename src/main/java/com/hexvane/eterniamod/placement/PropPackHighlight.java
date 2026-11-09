package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore;
import com.hexvane.eterniamod.hub.EterniaPlacedInstance;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * The Packaging Wand's highlight: glowing gold lines over the decoration you aim at, drawn by a same size copy made of
 * temporary entities (one per block, plus a copy of each of its entity parts). Copies carry a {@link Ghost} marker:
 * placement snapshots skip them and any left over after a crash are removed when their area loads. World thread only.
 */
public final class PropPackHighlight {
    static final String EFFECT = "Eternia_Pack_Highlight";
    static final String VANISH = "Teleport";
    /**
     * Copies are the decoration's own size: they grow from a pivot that is not the model's middle, so any enlargement
     * reads as a doubled image. With no colour fill the copy draws the same texture as the real thing, so where the two
     * overlap nothing can be seen to flicker, and only the glowing lines show.
     */
    private static final float GROW = 1.0f;
    private static final float GROW_BLOCKS = 1.0f;
    private static final int MAX_GHOSTS = 256;

    /** Marks a highlight copy. */
    public static final class Ghost implements Component<EntityStore> {
        public static final BuilderCodec<Ghost> CODEC = BuilderCodec.builder(Ghost.class, Ghost::new).build();
        private static volatile ComponentType<EntityStore, Ghost> type;

        public static void register(@Nonnull ComponentRegistryProxy<EntityStore> registry) {
            type = registry.registerComponent(Ghost.class, "EterniaPackHighlightGhost", CODEC);
        }

        @Nonnull
        public static ComponentType<EntityStore, Ghost> getComponentType() {
            var t = type;
            if (t == null) throw new IllegalStateException("EterniaPackHighlightGhost not registered");
            return t;
        }

        @Override
        public Component<EntityStore> clone() { return new Ghost(); }
    }

    private record Shown(UUID instance, List<Ref<EntityStore>> ghosts) {}

    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();

    private PropPackHighlight() {}

    /** Highlights {@code prop} for {@code viewer}, or clears the highlight when it is null. Cheap when unchanged. */
    public static void show(@Nonnull World world, @Nonnull UUID viewer, @Nullable HubPlotProp prop, @Nonnull EterniaModPlugin plugin) {
        Shown current = SHOWN.get(viewer);
        UUID wanted = prop == null ? null : prop.getInstanceId();
        if (current != null && Objects.equals(current.instance(), wanted) && current.ghosts().stream().allMatch(Ref::isValid)) return;
        clear(world, viewer);
        if (prop == null) return;
        PropDefinition def = plugin.getPropCatalog().get(prop.getPropId());
        IPrefabBuffer buffer = def == null ? null : PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());
        if (buffer == null) return;
        var store = world.getEntityStore().getStore();
        var effect = EntityEffect.getAssetMap().getAsset(EFFECT);
        var ghosts = new ArrayList<Ref<EntityStore>>();
        var yaw = prop.resolveRotationYaw();
        int ox = prop.getAnchorX(), oy = prop.getAnchorY(), oz = prop.getAnchorZ();
        var blocks = new ArrayList<double[]>();
        var keys = new ArrayList<String>();
        // Cells come from the prefab; what stands there (block and rotation) is read from the world, so the copy
        // matches the placed block exactly, however the decoration was turned.
        var cells = new ArrayList<int[]>();
        buffer.forEach(IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, support, rotation, filler, t, fluidId, fluidLevel) -> {
                if (filler != 0 || blockId == 0 || cells.size() >= MAX_GHOSTS) return;
                cells.add(new int[] {ox + x, oy + y, oz + z});
            }, null, null, new PrefabBufferCall(new FastRandom(), PrefabRotation.fromRotation(yaw)));
        for (int[] c : cells) {
            if (com.hexvane.eterniamod.world.ChunkSectionBlockUtil.filler(world, c[0], c[1], c[2]) != 0) continue;
            BlockType type = com.hexvane.eterniamod.world.ChunkSectionBlockUtil.blockType(world, c[0], c[1], c[2]);
            if (type == null || type.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Empty) continue;
            var turn = RotationTuple.get(com.hexvane.eterniamod.world.ChunkSectionBlockUtil.rotationIndex(world, c[0], c[1], c[2]));
            keys.add(type.getId());
            // Exactly how the game makes a falling block from a placed one: the middle of its cell, its yaw plus half a turn.
            blocks.add(new double[] {c[0] + 0.5, c[1] + 0.5, c[2] + 0.5, turn.yaw().getRadians() + Math.PI, turn.pitch().getRadians(), turn.roll().getRadians()});
        }
        for (int i = 0; i < blocks.size(); i++) {
            double[] b = blocks.get(i);
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.putComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(b[0], b[1], b[2]), new Rotation3f((float) b[4], (float) b[3], (float) b[5])));
            holder.putComponent(BlockEntity.getComponentType(), new BlockEntity(keys.get(i)));
            ghosts.add(spawn(store, holder, GROW_BLOCKS, effect));
        }
        // The decoration's entity parts (potions, plates): a copy of each, the same way a little larger.
        var fp = PlotFootprintUtil.computePropFootprint(new org.joml.Vector3i(ox, oy, oz), yaw, buffer);
        var area = new NativeSnapshotStore.Bounds(fp.getMinX() - 1, fp.getMinY() - 1, fp.getMinZ() - 1, fp.getMaxX() + 2, fp.getMaxY() + 2, fp.getMaxZ() + 2);
        for (var ref : NativeSnapshotStore.entities(world, area)) {
            if (ghosts.size() >= MAX_GHOSTS) break;
            var link = store.getComponent(ref, EterniaPlacedInstance.getComponentType());
            if (link == null || !link.getInstanceId().equals(prop.getInstanceId())) continue;
            Holder<EntityStore> copy = store.copyEntity(ref);
            copy.removeComponent(EterniaPlacedInstance.getComponentType());
            var scale = copy.getComponent(EntityScaleComponent.getComponentType());
            ghosts.add(spawn(store, copy, (scale == null ? 1f : scale.getScale()) * GROW, effect));
        }
        SHOWN.put(viewer, new Shown(prop.getInstanceId(), ghosts));
    }

    private static Ref<EntityStore> spawn(Store<EntityStore> store, Holder<EntityStore> holder, float scale, @Nullable EntityEffect effect) {
        holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.putComponent(UUIDComponent.getComponentType(), UUIDComponent.randomUUID());
        holder.putComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        holder.putComponent(Ghost.getComponentType(), new Ghost());
        // Stays put like a placed decoration, and nothing can bump into it or hit it.
        holder.putComponent(com.hypixel.hytale.server.core.modules.entity.component.PropComponent.getComponentType(), com.hypixel.hytale.server.core.modules.entity.component.PropComponent.get());
        holder.putComponent(com.hypixel.hytale.server.core.modules.entity.component.Intangible.getComponentType(), com.hypixel.hytale.server.core.modules.entity.component.Intangible.INSTANCE);
        holder.ensureComponent(EffectControllerComponent.getComponentType());
        var ref = store.addEntity(holder, AddReason.SPAWN);
        var controller = ref == null ? null : store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (controller != null && effect != null) controller.addEffect(ref, effect, store);
        return ref;
    }

    /** Removes the viewer's highlight. */
    public static void clear(@Nonnull World world, @Nonnull UUID viewer) {
        Shown shown = SHOWN.remove(viewer);
        if (shown == null) return;
        var store = world.getEntityStore().getStore();
        for (var ref : shown.ghosts()) if (ref != null && ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
    }

    /** The decoration vanishes with a swirl of sparkles where it stood. */
    public static void vanish(@Nonnull Store<EntityStore> store, double x, double y, double z) {
        ParticleUtil.spawnParticleEffect(VANISH, new Vector3d(x, y, z), store);
    }

    /** Removes highlight copies saved by accident (a crash while one was showing) as soon as their area loads. */
    public static final class Cleanup extends RefSystem<EntityStore> {
        @Nonnull
        @Override
        public Query<EntityStore> getQuery() { return Ghost.getComponentType(); }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            if (reason != AddReason.SPAWN) commands.removeEntity(ref, RemoveReason.REMOVE);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {}
    }
}
