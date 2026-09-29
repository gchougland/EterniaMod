package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.placement.BuildingPickupCommit;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BuildingPickupPage extends EterniaInteractiveCustomUIPage<BuildingPickupPage.PageData> {
    static final String MSG_UI = "eterniamod_ui.eterniamod.ui.buildingpickup";
    static final String MSG_COMMON = "eterniamod_common.eterniamod.common";

    @Nonnull
    private final UUID plotId;

    public BuildingPickupPage(@Nonnull PlayerRef playerRef, @Nonnull UUID plotId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.plotId = plotId;
    }

    @Override
    public void build(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull UICommandBuilder commandBuilder,
        @Nonnull UIEventBuilder eventBuilder,
        @Nonnull Store<EntityStore> store
    ) {
        commandBuilder.append("EterniaMod/BuildingPickupPage.ui");bindHome(eventBuilder);
        commandBuilder.set("#PickupTitle.TextSpans", Message.translation(MSG_UI + ".title"));
        commandBuilder.set("#PickupBody.TextSpans", Message.translation(MSG_UI + ".body"));
        commandBuilder.set("#ConfirmButton.TextSpans", Message.translation(MSG_UI + ".confirm"));
        commandBuilder.set("#CancelButton.TextSpans", Message.translation(MSG_UI + ".cancel"));
        bind(eventBuilder, "#ConfirmButton", "Confirm");
        bind(eventBuilder, "#CancelButton", "Cancel");
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        if (data.action == null) {
            return;
        }
        if ("Cancel".equalsIgnoreCase(data.action)) {
            returnOrClose(ref,store);
            return;
        }
        if (!"Confirm".equalsIgnoreCase(data.action)) {
            return;
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin == null) {
            sendError(store, ref, Message.translation(MSG_COMMON + ".pickupFailed"));
            close();
            return;
        }
        store.getExternalData().getWorld().execute(
            () -> {
                if (!ref.isValid()) {
                    return;
                }
                BuildingPickupCommit.Result result = BuildingPickupCommit.pickup(ref, store, plotId, plugin);
                switch (result) {
                    case SUCCESS -> {
                        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                        if (pr != null) {
                            pr.sendMessage(Message.translation(MSG_COMMON + ".buildingPickedUp"));
                        }
                        close();
                    }
                    case INVENTORY_FULL -> sendError(store, ref, Message.translation(MSG_COMMON + ".inventoryFull"));
                    case FAILED -> sendError(store, ref, Message.translation(MSG_COMMON + ".pickupFailed"));
                }
            }
        );
    }

    private void sendError(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Message message) {
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (pr != null) {
            pr.sendMessage(message);
        }
    }

    private static void bind(@Nonnull UIEventBuilder eventBuilder, @Nonnull String selector, @Nonnull String action) {
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            selector,
            new EventData().append("Action", action),
            false
        );
    }

    public static final class PageData {
        public static final BuilderCodec<PageData> CODEC =
            BuilderCodec.builder(PageData.class, PageData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, a) -> d.action = a, d -> d.action)
                .add()
                .build();

        @Nullable
        public String action;
    }
}
