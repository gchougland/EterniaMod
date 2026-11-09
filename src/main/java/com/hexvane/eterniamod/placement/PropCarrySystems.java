package com.hexvane.eterniamod.placement;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.interaction.components.CarriedBlock;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Keeps the carried prop hologram under the crosshair and makes sure the moving box never stays in the world. */
public final class PropCarrySystems {
    private PropCarrySystems() {}

    /** Every carrying player: schedule a follow step (throttled) on the world thread. */
    public static final class Follow extends EntityTickingSystem<EntityStore> {
        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType(), PlayerRef.getComponentType(), CarriedBlock.getComponentType());
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            CarriedBlock carried = chunk.getComponent(index, CarriedBlock.getComponentType());
            if (carried == null || !PropCarry.BOX.equals(carried.getBlockTypeKey())) return;
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            if (player == null) return;
            PropCarry.requestFollow(chunk.getReferenceTo(index), store, player);
        }
    }

    /** The game drops a carried block when its carrier dies or leaves; a dropped moving box is removed at once. */
    public static final class RemoveDroppedBox extends RefSystem<EntityStore> {
        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(CarriedBlock.getComponentType(), BlockEntity.getComponentType());
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            BlockEntity block = store.getComponent(ref, BlockEntity.getComponentType());
            if (block != null && PropCarry.BOX.equals(block.getBlockTypeKey())) {
                commands.removeEntity(ref, RemoveReason.REMOVE);
            }
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {}
    }

    /** Dying while carrying ends the placement: hologram, outline and hints go away. */
    public static final class EndOnDeath extends DeathSystems.OnDeathSystem {
        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || PropPlacementSessions.get(player.getUuid()) == null) return;
            store.getExternalData().getWorld().execute(() -> PropCarry.end(ref, store, player));
        }
    }
}
