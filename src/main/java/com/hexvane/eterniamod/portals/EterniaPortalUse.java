package com.hexvane.eterniamod.portals;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Using a Teleporter pad, or walking onto it, opens the Eternia portal menu. Players who have just arrived on the pad,
 * are still loading in, are riding, or are mid teleport are left alone, as vanilla's own teleporter does.
 */
public final class EterniaPortalUse extends SimpleBlockInteraction {
    public static final BuilderCodec<EterniaPortalUse> CODEC = BuilderCodec.builder(EterniaPortalUse.class, EterniaPortalUse::new, SimpleBlockInteraction.CODEC).build();

    @Nonnull @Override public WaitForDataFrom getWaitForDataFrom() { return WaitForDataFrom.Server; }

    @Override
    protected void interactWithBlock(@Nonnull World world, @Nonnull CommandBuffer<EntityStore> commands, @Nonnull InteractionType type, @Nonnull InteractionContext context,
                                     @Nullable ItemStack itemInHand, @Nonnull Vector3i target, @Nonnull CooldownHandler cooldown) {
        var ref = context.getEntity();
        var player = commands.getComponent(ref, PlayerRef.getComponentType());
        var component = commands.getComponent(ref, Player.getComponentType());
        boolean walkedIn = type == InteractionType.CollisionEnter;
        if (player == null || component == null || component.isWaitingForClientReady() || component.getMountEntityId() != 0) { context.getState().state = InteractionState.Failed; return; }
        var archetype = commands.getArchetype(ref);
        if (archetype.contains(Teleport.getComponentType()) || archetype.contains(PendingTeleport.getComponentType())) { context.getState().state = InteractionState.Failed; return; }
        var pad = new Vector3i(target);
        // The menu opens after this tick, outside the interaction system.
        world.execute(() -> { if (ref.isValid()) PortalBootstrap.open(world, ref, player, pad, walkedIn); });
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nullable ItemStack itemInHand, @Nonnull World world, @Nonnull Vector3i target) {
        if (context.getServerState() == null) return;
        context.getState().state = context.getServerState().state;
    }
}
