package com.hexvane.eterniamod.production;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * A station is one object: nothing inside its space can be broken (its ore blocks would hand out real ore) and nothing
 * can be built into it (a tier change would lose it).
 */
final class StationGuard {
    private StationGuard() {}

    static final class Break extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        Break() { super(BreakBlockEvent.class); }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands, @Nonnull BreakBlockEvent event) {
            ProductionRuntime runtime = ProductionRuntime.get();
            if (runtime == null || event.isCancelled()) return;
            var p = event.getTargetBlock();
            if (runtime.at(store.getExternalData().getWorld(), p.x, p.y, p.z) != null) event.setCancelled(true);
        }

        @Override public Query<EntityStore> getQuery() { return Player.getComponentType(); }
    }

    static final class Place extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        Place() { super(PlaceBlockEvent.class); }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands, @Nonnull PlaceBlockEvent event) {
            ProductionRuntime runtime = ProductionRuntime.get();
            if (runtime == null || event.isCancelled()) return;
            var p = event.getTargetBlock();
            if (runtime.at(store.getExternalData().getWorld(), p.x, p.y, p.z) != null) event.setCancelled(true);
        }

        @Override public Query<EntityStore> getQuery() { return Player.getComponentType(); }
    }
}
