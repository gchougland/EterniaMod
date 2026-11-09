package com.hexvane.eterniamod.bootstrap;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaPlacedInstance;
import com.hexvane.eterniamod.hub.PropBreakBlockSystem;
import com.hexvane.eterniamod.interaction.EterniaOpenPropPlacementInteraction;
import com.hexvane.eterniamod.interaction.EterniaPackagePropInteraction;
import com.hexvane.eterniamod.placement.PropPackagingWandTickSystem;
import com.hexvane.eterniamod.placement.PropPlacementPlayerRemoveSystem;
import com.hexvane.eterniamod.ui.PropPlacementPageSupplier;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import javax.annotation.Nonnull;

public final class PropPlacementBootstrap {
    private PropPlacementBootstrap() {}

    public static void register(@Nonnull EterniaModPlugin plugin) {
        plugin
            .getCodecRegistry(Interaction.CODEC)
            .register(
                "EterniaOpenPropPlacement",
                EterniaOpenPropPlacementInteraction.class,
                EterniaOpenPropPlacementInteraction.CODEC
            );
        plugin
            .getCodecRegistry(Interaction.CODEC)
            .register("EterniaPackageProp", EterniaPackagePropInteraction.class, EterniaPackagePropInteraction.CODEC);
        plugin
            .getCodecRegistry(Interaction.CODEC)
            .register("EterniaPropCarry", com.hexvane.eterniamod.interaction.EterniaPropCarryInteraction.class, com.hexvane.eterniamod.interaction.EterniaPropCarryInteraction.CODEC);
        OpenCustomUIInteraction.registerCustomPageSupplier(
            plugin,
            PropPlacementPageSupplier.class,
            EterniaModConstants.PAGE_PROP_PLACEMENT,
            new PropPlacementPageSupplier()
        );
        EterniaPlacedInstance.register(plugin.getEntityStoreRegistry());
        com.hexvane.eterniamod.placement.PropPackHighlight.Ghost.register(plugin.getEntityStoreRegistry());
        plugin.getEntityStoreRegistry().registerSystem(new com.hexvane.eterniamod.placement.PropPackHighlight.Cleanup());
        plugin.getEntityStoreRegistry().registerSystem(new PropPlacementPlayerRemoveSystem());
        plugin.getEntityStoreRegistry().registerSystem(new com.hexvane.eterniamod.placement.PropCarrySystems.Follow());
        plugin.getEntityStoreRegistry().registerSystem(new com.hexvane.eterniamod.placement.PropCarrySystems.RemoveDroppedBox());
        plugin.getEntityStoreRegistry().registerSystem(new com.hexvane.eterniamod.placement.PropCarrySystems.EndOnDeath());
        plugin.getEntityStoreRegistry().registerSystem(new PropPackagingWandTickSystem(plugin));
        plugin.getEntityStoreRegistry().registerSystem(new PropBreakBlockSystem(plugin));
    }
}
