package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Prevents breaking a linked management block while its plot still exists. */
public final class ManagementBreakBlockSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
    private final EterniaModPlugin plugin;

    public ManagementBreakBlockSystem(@Nonnull EterniaModPlugin plugin) {
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
        if (!EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID.equals(event.getBlockType().getId())) {
            return;
        }
        Vector3i pos = event.getTargetBlock();
        World world = store.getExternalData().getWorld();
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(pos.x, pos.z));
        if (chunk == null) {
            return;
        }
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(pos.x, pos.y, pos.z);
        if (blockRef == null) {
            return;
        }
        Store<ChunkStore> cs = blockRef.getStore();
        EterniaManagementBlock mb = cs.getComponent(blockRef, EterniaManagementBlock.getComponentType());
        if (mb == null || mb.getPlotId().isBlank()) {
            return;
        }
        UUID plotId;
        try {
            plotId = UUID.fromString(mb.getPlotId().trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        if (manager.getPlot(plotId) != null) {
            event.setCancelled(true);
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
