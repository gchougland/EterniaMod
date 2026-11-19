package com.hexvane.eterniamod.runtime;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caps how many entities a player receives per tick ({@link EntityArrival}). Runs in the engine's group that decides
 * what each player can see, after its own trimming (small far entities, hidden players), and only ever holds back
 * entities the player does not have yet: nothing they can already see disappears. Held back entities arrive over the
 * next ticks, nearest first. This holds however the player arrived (join, teleport, a view distance change from the
 * client, which resets the entity range to full) and however many entities the area holds.
 */
final class EntityArrivalBudget extends EntityTickingSystem<EntityStore> {
    /** Players in the first seconds after joining a world or teleporting, kept by {@link PlayerLifecycle}. */
    static final Set<UUID> SETTLING = ConcurrentHashMap.newKeySet();

    private final Query<EntityStore> query = Query.and(EntityTrackerSystems.EntityViewer.getComponentType(), TransformComponent.getComponentType(), PlayerRef.getComponentType());
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class),
        new SystemDependency<>(Order.AFTER, EntityTrackerSystems.LODCull.class),
        new SystemDependency<>(Order.AFTER, EntityTrackerSystems.HideFromPlayer.class));

    @Nullable @Override public SystemGroup<EntityStore> getGroup() { return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP; }
    @Nonnull @Override public Set<Dependency<EntityStore>> getDependencies() { return dependencies; }
    @Nonnull @Override public Query<EntityStore> getQuery() { return query; }
    @Override public boolean isParallel(int archetypeChunkSize, int taskCount) { return EntityTickingSystem.maybeUseParallel(archetypeChunkSize, taskCount); }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
        var viewer = chunk.getComponent(index, EntityTrackerSystems.EntityViewer.getComponentType());
        var player = chunk.getComponent(index, PlayerRef.getComponentType());
        var transform = chunk.getComponent(index, TransformComponent.getComponentType());
        if (viewer == null || player == null || transform == null) return;
        int budget = SETTLING.contains(player.getUuid()) ? EntityArrival.SETTLING_BUDGET_PER_TICK : EntityArrival.BUDGET_PER_TICK;
        var self = chunk.getReferenceTo(index);
        var at = transform.getPosition();
        var candidates = new ArrayList<EntityArrival.Candidate<Ref<EntityStore>>>();
        Predicate<Ref<EntityStore>> isNew = ref -> !viewer.sent.containsKey(ref);
        for (var ref : viewer.visible) {
            if (ref == null || !ref.isValid() || ref.equals(self) || !isNew.test(ref)) continue;
            // The command buffer reads past the thread check, as the engine's own visibility filters do.
            var where = commands.getComponent(ref, TransformComponent.getComponentType());
            double distance = where == null ? 0 : where.getPosition().distanceSquared(at);
            candidates.add(new EntityArrival.Candidate<>(ref, distance, commands.getComponent(ref, PlayerRef.getComponentType()) != null));
        }
        if (candidates.size() <= budget) return;
        viewer.visible.removeAll(EntityArrival.holdBack(candidates, budget));
    }
}
