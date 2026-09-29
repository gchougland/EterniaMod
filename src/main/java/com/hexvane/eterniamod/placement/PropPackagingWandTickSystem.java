package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/** Draws prop highlight boxes while holding the packaging wand. Packet writes only. */
public final class PropPackagingWandTickSystem extends EntityTickingSystem<EntityStore> {
    private static final int TICK_INTERVAL = 10;

    private final EterniaModPlugin plugin;
    private final Map<UUID, Integer> tickCounters = new ConcurrentHashMap<>();

    public PropPackagingWandTickSystem(@Nonnull EterniaModPlugin plugin) {
        this.plugin = plugin;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void tick(
        float dt,
        int index,
        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        // Debug shapes share one client surface. A held wand must never clear a placement page's grid.
        var player=store.getComponent(ref,Player.getComponentType());
        if(player!=null&&player.getPageManager().getCustomPage()!=null){tickCounters.remove(playerUuid);return;}
        ItemStack held = InventoryComponent.getItemInHand(store, ref);
        if (ItemStack.isEmpty(held) || !EterniaModConstants.PACKAGING_WAND_ID.equals(held.getItemId())) {
            if (tickCounters.remove(playerUuid) != null) {
                PropPackagingOverlay.clearFor(playerRef);
            }
            return;
        }
        int count = tickCounters.merge(playerUuid, 1, Integer::sum);
        if (count % TICK_INTERVAL != 0) {
            return;
        }
        PropPackagingOverlay.sendForOwnedPlot(playerRef, ref, store, plugin);
    }
}
