package com.hexvane.eterniamod.interaction;

import com.hexvane.eterniamod.placement.PropCarry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** The carried moving box's keys: Place, Rotate or PutAway. The work runs on the world thread after the tick. */
public final class EterniaPropCarryInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<EterniaPropCarryInteraction> CODEC =
        BuilderCodec.builder(EterniaPropCarryInteraction.class, EterniaPropCarryInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Places, rotates or puts away the Eternia decoration being carried.")
            .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action)
            .add()
            .build();

    private String action = "Place";

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commands = context.getCommandBuffer();
        if (commands == null) return;
        Ref<EntityStore> ref = context.getEntity();
        PlayerRef player = commands.getComponent(ref, PlayerRef.getComponentType());
        if (player == null) return;
        Store<EntityStore> store = commands.getStore();
        String chosen = action;
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) return;
            switch (chosen) {
                case "Rotate" -> PropCarry.rotate(ref, store, player);
                case "PutAway" -> PropCarry.putAway(ref, store, player);
                default -> PropCarry.place(ref, store, player);
            }
        });
    }
}
