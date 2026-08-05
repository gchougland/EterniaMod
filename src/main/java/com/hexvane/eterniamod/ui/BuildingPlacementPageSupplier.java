package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.placement.BuildingPlacementOpenHelper;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** OpenCustomUI page supplier for {@link BuildingPlacementPage}. */
public final class BuildingPlacementPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {
    public static final BuilderCodec<BuildingPlacementPageSupplier> CODEC =
        BuilderCodec.builder(BuildingPlacementPageSupplier.class, BuildingPlacementPageSupplier::new).build();

    @Override
    @Nullable
    public CustomUIPage tryCreate(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull ComponentAccessor<EntityStore> componentAccessor,
        @Nonnull PlayerRef playerRef,
        @Nonnull InteractionContext context
    ) {
        return BuildingPlacementOpenHelper.tryOpen(ref, componentAccessor, playerRef, context);
    }
}
