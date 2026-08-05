package com.hexvane.eterniamod.interaction;

import com.hexvane.eterniamod.placement.BuildingPlacementDebugLog;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Opens building placement like vanilla {@link OpenCustomUIInteraction}, but replaces a stale custom page via
 * {@link PageManager#openCustomPage} instead of {@link PageManager#setPage}. {@code setPage(Page.None)} increments the
 * server acknowledgement counter without a matching client {@code CustomPage} ack, which leaves
 * {@code customPageRequiredAcknowledgments != 0} and silently drops every UI button event.
 */
public final class EterniaOpenBuildingPlacementInteraction extends SimpleInstantInteraction {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    public static final BuilderCodec<EterniaOpenBuildingPlacementInteraction> CODEC =
        BuilderCodec.builder(
                EterniaOpenBuildingPlacementInteraction.class,
                EterniaOpenBuildingPlacementInteraction::new,
                SimpleInstantInteraction.CODEC
            )
            .documentation("Opens the Eternia building placement UI.")
            .appendInherited(
                new KeyedCodec<>("Page", OpenCustomUIInteraction.PAGE_CODEC),
                (o, v) -> o.customPageSupplier = v,
                o -> o.customPageSupplier,
                (o, p) -> o.customPageSupplier = p.customPageSupplier
            )
            .addValidator(Validators.nonNull())
            .add()
            .build();

    private OpenCustomUIInteraction.CustomPageSupplier customPageSupplier;

    @Override
    protected void firstRun(
        @Nonnull InteractionType type,
        @Nonnull InteractionContext context,
        @Nonnull CooldownHandler cooldownHandler
    ) {
        Ref<EntityStore> ref = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }

        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        String playerName = playerRef != null ? playerRef.getUsername() : "?";
        if (playerRef == null || customPageSupplier == null) {
            BuildingPlacementDebugLog.openAttempt(playerName, null, false);
            LOGGER.atWarning().log(
                "Building placement blocked for %s: playerRef=%s pageSupplier=%s",
                playerName,
                playerRef != null,
                customPageSupplier != null
            );
            return;
        }

        CustomUIPage page = customPageSupplier.tryCreate(ref, commandBuffer, playerRef, context);
        if (page == null) {
            BuildingPlacementDebugLog.openAttempt(playerName, null, false);
            return;
        }

        PageManager pageManager = player.getPageManager();
        pageManager.openCustomPage(ref, commandBuffer.getStore(), page);
        BuildingPlacementDebugLog.openAttempt(playerName, null, true);
    }
}
