package com.hexvane.eterniamod.collections;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

/** Pressing the use key on a pet: it leans into the hand, shuts its eyes happily and gives off a few hearts. Anyone may pet any pet. */
public final class EterniaPetPat extends SimpleInstantInteraction {
    public static final String ROOT = "Root_Eternia_Pet_Pat";
    public static final BuilderCodec<EterniaPetPat> CODEC = BuilderCodec.builder(EterniaPetPat.class, EterniaPetPat::new, SimpleInstantInteraction.CODEC).build();

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldown) {
        var commands = context.getCommandBuffer();
        var target = context.getTargetEntity();
        if (commands == null || target == null || type != InteractionType.Use) return;
        var player = context.getEntity();
        var world = commands.getStore().getExternalData().getWorld();
        world.execute(() -> CollectionBootstrap.pat(world, target, player));
    }
}
