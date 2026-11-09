package com.hexvane.eterniamod.placement;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * The carried decoration's hologram, built from temporary copies (one per block, plus each entity part) instead of the
 * client's paste preview. The paste preview is lit by sky light only, so it turns black at night indoors; these copies
 * are ordinary entities and take the lamplight around them. Moving the hologram moves the copies; turning it or
 * changing decoration rebuilds them. A green glow means it fits, red means it does not. World thread only.
 */
final class PropCarryGhost {
    static final String FITS = "Eternia_Place_Fits";
    static final String BLOCKED = "Eternia_Place_Blocked";
    private static final int MAX_GHOSTS = 256;

    private static final class Shown {
        final World world;
        final String prefab;
        final int steps;
        Vector3i origin;
        boolean fits;
        final List<Ref<EntityStore>> ghosts;

        Shown(World world, String prefab, int steps, Vector3i origin, boolean fits, List<Ref<EntityStore>> ghosts) {
            this.world = world;
            this.prefab = prefab;
            this.steps = steps;
            this.origin = origin;
            this.fits = fits;
            this.ghosts = ghosts;
        }
    }

    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();

    private PropCarryGhost() {}

    /** Shows the hologram of {@code prefab} pasted at {@code origin} turned to {@code yaw}, glowing by {@code fits}. */
    static void show(
        @Nonnull World world,
        @Nonnull UUID viewer,
        @Nonnull String prefab,
        @Nonnull IPrefabBuffer buffer,
        int steps,
        @Nonnull Rotation yaw,
        @Nonnull Vector3i origin,
        boolean fits
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Shown shown = SHOWN.get(viewer);
        if (shown != null && shown.world == world && shown.prefab.equals(prefab) && shown.steps == steps && shown.ghosts.stream().allMatch(Ref::isValid)) {
            if (!shown.origin.equals(origin)) {
                double dx = origin.x - shown.origin.x, dy = origin.y - shown.origin.y, dz = origin.z - shown.origin.z;
                for (Ref<EntityStore> ref : shown.ghosts) {
                    TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                    if (transform != null) transform.teleportPosition(new Vector3d(transform.getPosition()).add(dx, dy, dz));
                }
                shown.origin = origin;
            }
            if (shown.fits != fits) {
                EntityEffect glow = glow(fits);
                for (Ref<EntityStore> ref : shown.ghosts) {
                    EffectControllerComponent controller = store.getComponent(ref, EffectControllerComponent.getComponentType());
                    if (controller == null) continue;
                    controller.clearEffects(ref, store);
                    if (glow != null) controller.addEffect(ref, glow, store);
                }
                shown.fits = fits;
            }
            return;
        }
        clear(world, viewer);
        SHOWN.put(viewer, new Shown(world, prefab, steps, origin, fits, spawnAll(store, buffer, yaw, origin, glow(fits))));
    }

    @Nullable
    private static EntityEffect glow(boolean fits) {
        return EntityEffect.getAssetMap().getAsset(fits ? FITS : BLOCKED);
    }

    private static List<Ref<EntityStore>> spawnAll(Store<EntityStore> store, IPrefabBuffer buffer, Rotation yaw, Vector3i origin, @Nullable EntityEffect glow) {
        var holders = new ArrayList<Holder<EntityStore>>();
        PrefabRotation rotation = PrefabRotation.fromRotation(yaw);
        buffer.forEach(
            IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, support, blockRotation, filler, t, fluidId, fluidLevel) -> {
                if (filler != 0 || blockId == 0 || holders.size() >= MAX_GHOSTS) return;
                BlockType type = BlockType.getAssetMap().getAsset(blockId);
                if (type == null || type.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Empty) return;
                RotationTuple turn = RotationTuple.get(blockRotation);
                // How the game makes a falling block from a placed one: the middle of its cell, its yaw plus half a turn.
                Holder<EntityStore> copy = EntityStore.REGISTRY.newHolder();
                copy.putComponent(TransformComponent.getComponentType(), new TransformComponent(
                    new Vector3d(origin.x + x + 0.5, origin.y + y + 0.5, origin.z + z + 0.5),
                    new Rotation3f((float) turn.pitch().getRadians(), (float) (turn.yaw().getRadians() + Math.PI), (float) turn.roll().getRadians())));
                copy.putComponent(BlockEntity.getComponentType(), new BlockEntity(type.getId()));
                copy.putComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(1f));
                holders.add(copy);
            },
            (x, z, entities, t) -> {
                if (entities == null) return;
                for (Holder<EntityStore> source : entities) {
                    if (holders.size() >= MAX_GHOSTS) return;
                    Holder<EntityStore> copy = source.clone();
                    TransformComponent transform = copy.getComponent(TransformComponent.getComponentType());
                    if (transform == null) continue;
                    // Exactly as the placement pastes a prefab's entity parts.
                    Vector3d at = new Vector3d(transform.getPosition());
                    rotation.rotate(at);
                    transform.getPosition().set(at.add(origin.x, origin.y, origin.z));
                    if (copy.getComponent(EntityScaleComponent.getComponentType()) == null) {
                        copy.putComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(1f));
                    }
                    holders.add(copy);
                }
            },
            null,
            new PrefabBufferCall(new FastRandom(), rotation)
        );
        var refs = new ArrayList<Ref<EntityStore>>();
        for (Holder<EntityStore> holder : holders) {
            Ref<EntityStore> ref = spawn(store, holder, glow);
            if (ref != null) refs.add(ref);
        }
        return refs;
    }

    @Nullable
    private static Ref<EntityStore> spawn(Store<EntityStore> store, Holder<EntityStore> holder, @Nullable EntityEffect glow) {
        holder.putComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.putComponent(UUIDComponent.getComponentType(), UUIDComponent.randomUUID());
        // Placement snapshots skip it, and a copy left over after a crash is removed when its area loads.
        holder.putComponent(PropPackHighlight.Ghost.getComponentType(), new PropPackHighlight.Ghost());
        holder.putComponent(PropComponent.getComponentType(), PropComponent.get());
        holder.putComponent(Intangible.getComponentType(), Intangible.INSTANCE);
        holder.ensureComponent(EffectControllerComponent.getComponentType());
        Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
        EffectControllerComponent controller = ref == null ? null : store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (controller != null && glow != null) controller.addEffect(ref, glow, store);
        return ref;
    }

    /** Removes the viewer's hologram, in whichever world it stands. Call from {@code world}'s thread. */
    static void clear(@Nonnull World world, @Nonnull UUID viewer) {
        Shown shown = SHOWN.remove(viewer);
        if (shown == null) return;
        if (shown.world != world) {
            shown.world.execute(() -> remove(shown));
            return;
        }
        remove(shown);
    }

    private static void remove(Shown shown) {
        Store<EntityStore> store = shown.world.getEntityStore().getStore();
        for (Ref<EntityStore> ref : shown.ghosts) if (ref != null && ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
    }
}
