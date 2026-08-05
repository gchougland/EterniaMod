package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prefab.PropPrefabOps;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Prevents breaking blocks that belong to a registered prop. */
public final class PropBreakBlockSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
    private final EterniaModPlugin plugin;

    public PropBreakBlockSystem(@Nonnull EterniaModPlugin plugin) {
        super(BreakBlockEvent.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(
        int index,
        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        @Nonnull BreakBlockEvent event
    ) {
        if (event.isCancelled()) {
            return;
        }
        Vector3i pos = event.getTargetBlock();
        World world = store.getExternalData().getWorld();
        HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = manager.findPlotContainingHorizontal(pos.x, pos.z);
        if (plot == null) {
            return;
        }
        for (HubPlotProp prop : plot.getProps()) {
            PropDefinition def = plugin.getPropCatalog().get(prop.getPropId());
            if (def == null) {
                continue;
            }
            Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
            if (prefabPath == null) {
                continue;
            }
            IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
            Vector3i origin = new Vector3i(prop.getAnchorX(), prop.getAnchorY(), prop.getAnchorZ());
            if (PropPrefabOps.blockBelongsToProp(
                world, origin, prop.resolveRotationYaw(), buffer, pos.x, pos.y, pos.z
            )) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
