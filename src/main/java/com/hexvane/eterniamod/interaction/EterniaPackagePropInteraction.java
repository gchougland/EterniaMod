package com.hexvane.eterniamod.interaction;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.placement.PropPackageCommit;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class EterniaPackagePropInteraction extends SimpleBlockInteraction {
    @Nonnull
    public static final BuilderCodec<EterniaPackagePropInteraction> CODEC =
        BuilderCodec.builder(
                EterniaPackagePropInteraction.class,
                EterniaPackagePropInteraction::new,
                SimpleBlockInteraction.CODEC
            )
            .documentation("Packages a prop back into an item using the packaging wand.")
            .build();

    public EterniaPackagePropInteraction() {
        super("EterniaPackageProp");
    }

    @Override
    protected void interactWithBlock(
        @Nonnull World world,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        @Nonnull InteractionType type,
        @Nonnull InteractionContext context,
        @Nullable ItemStack itemInHand,
        @Nonnull Vector3i targetBlock,
        @Nonnull CooldownHandler cooldownHandler
    ) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin == null) {
            return;
        }
        PropPackageCommit.packageProp(context.getEntity(), commandBuffer.getStore(), plugin);
    }

    @Override
    protected void simulateInteractWithBlock(
        @Nonnull InteractionType type,
        @Nonnull InteractionContext context,
        @Nullable ItemStack itemInHand,
        @Nonnull World world,
        @Nonnull Vector3i targetBlock
    ) {}
}
