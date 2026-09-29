package com.hexvane.eterniamod.ui;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Blocks {@link #rebuild()} and {@link #sendUpdate} after {@link #onDismiss} and when another custom page is active,
 * so scheduled world tasks cannot push UI deltas to the wrong client surface.
 */
public abstract class EterniaInteractiveCustomUIPage<T> extends InteractiveCustomUIPage<T> {
    private volatile boolean dismissed;
    private final String homeEvent = java.util.UUID.randomUUID().toString();

    protected final void bindHome(UIEventBuilder events) {
        events.addEventBinding(com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating,
            "#MenuHome", new com.hypixel.hytale.server.core.ui.builder.EventData().append("EterniaHome", homeEvent), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, String rawData) {
        var data = org.bson.BsonDocument.parse(rawData);
        if (!data.containsKey("EterniaHome")) { super.handleDataEvent(ref, store, rawData); return; }
        if (!data.get("EterniaHome").equals(new org.bson.BsonString(homeEvent))) return;
        store.getExternalData().getWorld().execute(() -> {
            if (dismissed || !ref.isValid()) return;
            var player = store.getComponent(ref, Player.getComponentType());
            var plugin = com.hexvane.eterniamod.EterniaModPlugin.get();
            if (player == null || plugin == null || player.getPageManager().getCustomPage() != this) return;
            // Normal page dismissal preserves placement drafts and releases preview/camera ownership.
            com.hexvane.eterniamod.socialui.SocialUiBootstrap.open(ref, store, playerRef,
                plugin.getServices(), plugin.getMenuActions(), com.hexvane.eterniamod.socialui.EterniaServicesPage.Section.GREETER);
        });
    }

    public EterniaInteractiveCustomUIPage(
        @Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime, @Nonnull BuilderCodec<T> eventDataCodec
    ) {
        super(playerRef, lifetime, eventDataCodec);
    }

    private java.util.function.BiConsumer<Ref<EntityStore>,Store<EntityStore>> returnAction;
    public EterniaInteractiveCustomUIPage<T> withReturnFrom(CustomUIPage parent){returnAction=ChoicePage.returnAction(parent);return this;}
    protected boolean hasReturnPage(){return returnAction!=null;}
    protected void returnOrClose(Ref<EntityStore> ref,Store<EntityStore> store){if(returnAction!=null)returnAction.accept(ref,store);else close();}

    protected boolean isDismissed() {
        return dismissed;
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        dismissed = true;
        super.onDismiss(ref, store);
    }

    @Override
    protected void rebuild() {
        if (dismissed) {
            return;
        }
        Ref<EntityStore> ref = this.playerRef.getReference();
        if (ref == null) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            return;
        }
        CustomUIPage active = playerComponent.getPageManager().getCustomPage();
        if (active != this) {
            return;
        }
        super.rebuild();
    }

    @Override
    protected void sendUpdate(@Nullable UICommandBuilder commandBuilder, @Nullable UIEventBuilder eventBuilder, boolean clear) {
        if (dismissed) {
            return;
        }
        Ref<EntityStore> ref = this.playerRef.getReference();
        if (ref == null) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(
            () -> {
                if (dismissed || !ref.isValid()) {
                    return;
                }
                Player playerComponent = store.getComponent(ref, Player.getComponentType());
                if (playerComponent == null) {
                    return;
                }
                if (playerComponent.getPageManager().getCustomPage() != this) {
                    return;
                }
                playerComponent.getPageManager()
                    .updateCustomPage(
                        new CustomPage(
                            this.getClass().getName(),
                            false,
                            clear,
                            this.lifetime,
                            commandBuilder != null ? commandBuilder.getCommands() : UICommandBuilder.EMPTY_COMMAND_ARRAY,
                            eventBuilder != null ? eventBuilder.getEvents() : UIEventBuilder.EMPTY_EVENT_BINDING_ARRAY
                        )
                    );
            }
        );
    }
}
