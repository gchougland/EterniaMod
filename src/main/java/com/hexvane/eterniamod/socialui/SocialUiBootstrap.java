package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SocialUiBootstrap {
    private static volatile Registration active;
    private SocialUiBootstrap() {}
    public static boolean isOpen(EterniaServices services){Registration value=active;return value!=null&&!value.closed&&value.services==services;}
    public static <T> java.util.concurrent.CompletableFuture<T> supply(EterniaServices services,java.util.function.Supplier<T> work){
        Registration value=active;if(value==null||value.closed||value.services!=services)return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Service menus are closed"));
        return java.util.concurrent.CompletableFuture.supplyAsync(work,value.executor);
    }

    public static Registration register(EterniaModPlugin plugin, EterniaServices services, SocialUiActions actions) {
        Registration registration = new Registration(services, actions);
        active = registration;
        OpenCustomUIInteraction.registerCustomPageSupplier(plugin, ServicesPageSupplier.class, "EterniaServices", new ServicesPageSupplier());
        NPCPlugin npc = NPCPlugin.get();
        if (npc != null) npc.registerCoreComponentType("OpenEterniaServices", BuilderActionOpenEterniaServices::new);
        else plugin.getLogger().atWarning().log("NPCPlugin unavailable; Eternia services still accessible through commands, but hub NPCs cannot open their menus.");
        return registration;
    }

    public static void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player,
        EterniaServices services, SocialUiActions actions, EterniaServicesPage.Section section) {
        Registration registration = active;
        if (registration == null || registration.closed || registration.services != services) return;
        registration.open(ref, store, player, section);
    }

    static void openNpc(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServicesPage.Section section) {
        Registration registration = active;
        if (registration != null && !registration.closed) registration.open(ref, store, player, section);
    }

    public static final class Registration implements AutoCloseable {
        final EterniaServices services;
        final SocialUiActions actions;
        final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "Eternia-services-menu"); thread.setDaemon(true); return thread;
        });
        volatile boolean closed;
        Registration(EterniaServices services, SocialUiActions actions) { this.services = services; this.actions = actions; }
        public void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServicesPage.Section section) {
            if (closed || !ref.isValid()) return;
            if(section==EterniaServicesPage.Section.WORLDS){actions.perform(SocialUiActions.Action.WORLD_SELECT,ref,store,player);return;}
            if(section==EterniaServicesPage.Section.STORE){var result=actions.perform(SocialUiActions.Action.STORE_OPEN,ref,store,player);if(!result.opened())player.sendMessage(com.hypixel.hytale.server.core.Message.raw(result.message()));return;}
            Player component = store.getComponent(ref, Player.getComponentType());
            if (component != null) {
                var page = new EterniaServicesPage(player, this, section);
                if (section != EterniaServicesPage.Section.GREETER) page.withReturnFrom(component.getPageManager().getCustomPage());
                component.getPageManager().openCustomPage(ref, store, page);
            }
        }
        void openBoard(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
            if(closed||!ref.isValid())return;Player component=store.getComponent(ref,Player.getComponentType());
            if(component!=null)component.getPageManager().openCustomPage(ref,store,new GuildBoardPage(player,this));
        }
        @Override public void close() { closed = true; if (active == this) active = null; executor.shutdownNow(); }
    }

    public static final class ServicesPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {
        public static final BuilderCodec<ServicesPageSupplier> CODEC = BuilderCodec.builder(ServicesPageSupplier.class, ServicesPageSupplier::new).build();
        @Override @Nullable public CustomUIPage tryCreate(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull PlayerRef player, @Nonnull InteractionContext context) {
            Registration registration = active;
            return registration == null || registration.closed ? null : new EterniaServicesPage(player, registration, EterniaServicesPage.Section.GREETER);
        }
    }
}
