package com.hexvane.eterniamod.prefab;

import com.hexvane.eterniamod.hub.EterniaPlacedInstance;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.Axis;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.FromPrefab;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.event.PrefabPlaceEntityEvent;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.prefab.selection.standard.RotateBlockMode;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Pastes and removes world entities from prefab files for Eternia props and buildings. */
public final class PrefabEntityOps {
    private static final long PREFAB_BUFFER_ITERATION_SEED = 0L;

    private PrefabEntityOps() {}

    public static void pasteEntities(
        @Nonnull Path prefabPath,
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull ComponentAccessor<EntityStore> componentAccessor,
        @Nonnull EterniaPlacedInstance.Kind kind,
        @Nonnull UUID instanceId,
        @Nonnull String catalogId
    ) {
        BlockSelection selection = PrefabStore.get().getPrefab(prefabPath);
        BlockSelection rotated = rotateSelection(selection, yaw);
        if (rotated.getEntityCount() > 0) {
            pasteFromBlockSelection(rotated, world, origin, componentAccessor, kind, instanceId, catalogId);
            return;
        }
        pasteFromPrefabBuffer(PrefabBufferUtil.getCached(prefabPath), world, origin, yaw, componentAccessor, kind, instanceId, catalogId);
    }

    private static void pasteFromBlockSelection(
        @Nonnull BlockSelection rotated,
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull ComponentAccessor<EntityStore> componentAccessor,
        @Nonnull EterniaPlacedInstance.Kind kind,
        @Nonnull UUID instanceId,
        @Nonnull String catalogId
    ) {
        int prefabId = PrefabUtil.getNextPrefabId();
        Store<EntityStore> store = world.getEntityStore().getStore();

        rotated.forEachEntity(
            source -> {
                Holder<EntityStore> entityToAdd = source.clone();
                TransformComponent transformComp = entityToAdd.getComponent(TransformComponent.getComponentType());
                if (transformComp == null) {
                    return;
                }
                transformComp.getPosition().add(origin.x, origin.y, origin.z);
                spawnLinkedEntity(world, store, componentAccessor, entityToAdd, prefabId, kind, instanceId, catalogId);
            }
        );
    }

    private static void pasteFromPrefabBuffer(
        @Nonnull IPrefabBuffer buffer,
        @Nonnull World world,
        @Nonnull Vector3i origin,
        @Nonnull Rotation yaw,
        @Nonnull ComponentAccessor<EntityStore> componentAccessor,
        @Nonnull EterniaPlacedInstance.Kind kind,
        @Nonnull UUID instanceId,
        @Nonnull String catalogId
    ) {
        PrefabRotation rotation = PrefabRotation.fromRotation(yaw);
        PrefabBufferCall call = new PrefabBufferCall(new Random(PREFAB_BUFFER_ITERATION_SEED), rotation);
        int prefabId = PrefabUtil.getNextPrefabId();
        Store<EntityStore> store = world.getEntityStore().getStore();

        buffer.forEach(
            IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, supportValue, blockRotation, filler, t, fluidId, fluidLevel) -> {},
            (x, z, entityWrappers, t) -> {
                if (entityWrappers == null || entityWrappers.length == 0) {
                    return;
                }
                for (Holder<EntityStore> source : entityWrappers) {
                    Holder<EntityStore> entityToAdd = source.clone();
                    TransformComponent transformComp = entityToAdd.getComponent(TransformComponent.getComponentType());
                    if (transformComp == null) {
                        continue;
                    }
                    Vector3d entityPosition = new Vector3d(transformComp.getPosition());
                    rotation.rotate(entityPosition);
                    Vector3d entityWorldPosition = entityPosition.add(origin.x, origin.y, origin.z);
                    transformComp.getPosition().set(entityWorldPosition);
                    spawnLinkedEntity(world, store, componentAccessor, entityToAdd, prefabId, kind, instanceId, catalogId);
                }
            },
            null,
            call
        );
    }

    private static void spawnLinkedEntity(
        @Nonnull World world,
        @Nonnull Store<EntityStore> store,
        @Nonnull ComponentAccessor<EntityStore> componentAccessor,
        @Nonnull Holder<EntityStore> entityToAdd,
        int prefabId,
        @Nonnull EterniaPlacedInstance.Kind kind,
        @Nonnull UUID instanceId,
        @Nonnull String catalogId
    ) {
        PrefabPlaceEntityEvent prefabPlaceEntityEvent = new PrefabPlaceEntityEvent(prefabId, entityToAdd);
        componentAccessor.invoke(prefabPlaceEntityEvent);
        if (prefabPlaceEntityEvent.isCancelled()) {
            return;
        }

        entityToAdd.addComponent(FromPrefab.getComponentType(), FromPrefab.INSTANCE);
        entityToAdd.addComponent(
            EterniaPlacedInstance.getComponentType(),
            new EterniaPlacedInstance(kind, instanceId, catalogId)
        );

        world.execute(
            () -> {
                Ref<EntityStore> entityRef = new Ref<>(store);
                store.addEntity(entityToAdd, entityRef, AddReason.LOAD);
            }
        );
    }

    public static void removeLinkedEntities(@Nonnull World world, @Nonnull UUID instanceId) {
        world.execute(
            () -> {
                Store<EntityStore> store = world.getEntityStore().getStore();
                store.forEachEntityParallel(
                    EterniaPlacedInstance.getComponentType(),
                    (index, archetypeChunk, commandBuffer) -> {
                        EterniaPlacedInstance placed =
                            archetypeChunk.getComponent(index, EterniaPlacedInstance.getComponentType());
                        if (placed != null && instanceId.equals(placed.getInstanceId())) {
                            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
                        }
                    }
                );
            }
        );
    }

    @Nonnull
    private static BlockSelection rotateSelection(@Nonnull BlockSelection selection, @Nonnull Rotation yaw) {
        int steps = (yaw.getDegrees() / 90) % 4;
        if (steps == 0) {
            return selection.cloneSelection();
        }
        return selection.cloneSelection().rotate(Axis.Y, 90 * steps, RotateBlockMode.ALL);
    }
}
