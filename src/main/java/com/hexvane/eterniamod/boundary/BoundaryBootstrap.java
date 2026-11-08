package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.GuildService;
import com.hexvane.eterniamod.hexmap.HexMapOverlayService;
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
        // Border colours depend on the viewer's guild (a domain read, cached off the world thread).
        HexViewers.install(player -> plugin.getServices().guilds().membership(player).map(GuildService.Membership::guildId).orElse(null));
        // Hex plots painted on each player's world map, in the same palette; closed with the glow.
        HexMapOverlayService map = new HexMapOverlayService(plugin);
        map.start();
        system.closeAlso(map);
        plugin.getEntityStoreRegistry().registerSystem(new RefSystem<EntityStore>() {
            @Nonnull @Override public Query<EntityStore> getQuery() { return Player.getComponentType(); }
            @Override public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {}
            @Override public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
                PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                if (player != null) { system.forgetPlayer(player.getUuid()); map.forgetPlayer(player.getUuid()); HexViewers.forget(player.getUuid()); }
            }
        });
        plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> system.invalidate(event.getWorld()));
        return system;
    }
}
