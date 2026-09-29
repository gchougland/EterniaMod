package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

public final class BoundaryBootstrap {
    private BoundaryBootstrap() {}

    public static BoundaryFogSystem register(EterniaModPlugin plugin) {
        return register(plugin, (world, x, z) -> false);
    }

    /** Retain the result and close it on plugin shutdown; no static viewer or world state is retained. */
    public static BoundaryFogSystem register(EterniaModPlugin plugin, BoundarySurfacePolicy policy) {
        BoundaryFogSystem system = new BoundaryFogSystem(plugin, policy);
        plugin.getEntityStoreRegistry().registerSystem(system);
        plugin.getEntityStoreRegistry().registerSystem(new RefSystem<EntityStore>() {
            @Nonnull @Override public Query<EntityStore> getQuery() { return Player.getComponentType(); }
            @Override public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {}
            @Override public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
                PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                if (player != null) system.forgetPlayer(player.getUuid());
            }
        });
        plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> system.invalidate(event.getWorld()));
        return system;
    }
}
