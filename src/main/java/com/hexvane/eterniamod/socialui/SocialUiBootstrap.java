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
import java.util.UUID;
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
        registration.journalKey = new JournalKey();
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

    /** Opens the mailbox page: letters, the open letter and the writing desk. */
    public static void openMailbox(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        Registration registration = active;
        if (registration == null || registration.closed || !ref.isValid()) return;
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component != null) component.getPageManager().openCustomPage(ref, store, new MailboxPage(player, registration));
    }

    /** Opens the Friends screen. */
    public static void openFriends(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        Registration registration = active;
        if (registration != null && !registration.closed) registration.openFriends(ref, store, player, false);
    }

    /** Opens the guild screen: your guild if you have one, otherwise every guild to browse. */
    public static void openGuild(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        Registration registration = active;
        if (registration != null && !registration.closed) registration.openGuild(ref, store, player);
    }

    /** Opens one desk where the player stands: the mailbox's own page, or the season desk from a Season Board. */
    public static void openDesk(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServices services,
        EterniaServicesPage.Desk desk, EterniaServicesPage.Section section, boolean deskHome) {
        Registration registration = active;
        if (registration == null || registration.closed || registration.services != services) return;
        registration.show(ref, store, player, desk, section, deskHome, false);
    }

    static void openNpc(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServicesPage.Section section) {
        Registration registration = active;
        if (registration != null && !registration.closed) registration.openNpc(ref, store, player, section);
    }

    /** The game mode key: opens the Journal, or closes it when it is already open. */
    static void toggleJournal(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component != null && component.getPageManager().getCustomPage() instanceof EterniaServicesPage page && page.isJournal()) { page.closeJournal(); return; }
        openJournal(ref, store, player);
    }

    /** The Journal: personal sections that work anywhere, opened by the game mode key. */
    public static void openJournal(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        Registration registration = active;
        if (registration != null && !registration.closed) registration.show(ref, store, player, EterniaServicesPage.Desk.JOURNAL, EterniaServicesPage.Section.COLLECTION, false, false);
    }

    /** The Journal open at {@code section}. */
    public static void openJournal(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServicesPage.Section section) {
        Registration registration = active;
        if (registration == null || registration.closed) return;
        registration.show(ref, store, player, EterniaServicesPage.Desk.JOURNAL, section, false, false);
    }

    public static final class Registration implements AutoCloseable {
        final EterniaServices services;
        final SocialUiActions actions;
        final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "Eternia-services-menu"); thread.setDaemon(true); return thread;
        });
        volatile boolean closed;
        JournalKey journalKey;
        Registration(EterniaServices services, SocialUiActions actions) { this.services = services; this.actions = actions; }
        public void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServicesPage.Section section) {
            if (closed || !ref.isValid()) return;
            if(section==EterniaServicesPage.Section.WORLDS){actions.perform(SocialUiActions.Action.WORLD_SELECT,ref,store,player);return;}
            if(section==EterniaServicesPage.Section.GUILD){openGuild(ref,store,player);return;}
            if(section==EterniaServicesPage.Section.STORE){var result=actions.perform(SocialUiActions.Action.STORE_OPEN,ref,store,player);if(!result.opened())player.sendMessage(com.hypixel.hytale.server.core.Message.raw(result.message()));return;}
            show(ref, store, player, EterniaServicesPage.Desk.forShortcut(section), section, false, section != EterniaServicesPage.Section.GREETER);
        }
        /** A Hub NPC opens only its own desk; the Crown Store keeps its own page. */
        void openNpc(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServicesPage.Section section) {
            if (closed || !ref.isValid()) return;
            UUID id = player.getUuid();
            executor.execute(() -> { try { services.accounts().markIntroduced(id); } catch (RuntimeException ignored) {} });
            var desk = EterniaServicesPage.Desk.forNpc(section);
            java.util.function.BiConsumer<Ref<EntityStore>, Store<EntityStore>> visit = (r, st) -> {
                if (section == EterniaServicesPage.Section.STORE || section == EterniaServicesPage.Section.GUILD) open(r, st, player, section);
                else show(r, st, player, desk, section, true, false);
            };
            // After the Master of Arms first speaks, the rules themselves come next.
            java.util.function.BiConsumer<Ref<EntityStore>, Store<EntityStore>> afterDialogue = section == EterniaServicesPage.Section.RULES
                ? (r, st) -> show(r, st, player, desk, section, false, false) : visit;
            com.hexvane.eterniamod.tutorial.TutorialRuntime.meet(ref, store, player, section, visit, afterDialogue);
        }
        void show(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServicesPage.Desk desk, EterniaServicesPage.Section section, boolean deskHome, boolean returnable) {
            if (closed || !ref.isValid()) return;
            if (section == EterniaServicesPage.Section.GUILD) { openGuild(ref, store, player); return; }
            if (section == EterniaServicesPage.Section.FRIENDS) { openFriends(ref, store, player, false); return; }
            if (section == EterniaServicesPage.Section.STORE) { actions.perform(SocialUiActions.Action.STORE_OPEN, ref, store, player); return; }
            Player component = store.getComponent(ref, Player.getComponentType());
            if (component == null) return;
            var page = new EterniaServicesPage(player, this, desk, section, deskHome);
            if (returnable) page.withReturnFrom(component.getPageManager().getCustomPage());
            component.getPageManager().openCustomPage(ref, store, page);
        }
        void openFriends(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,boolean requests){
            if(closed||!ref.isValid())return;Player component=store.getComponent(ref,Player.getComponentType());
            if(component==null)return;var page=new FriendsPage(player,this);if(requests)page.onRequests();
            component.getPageManager().openCustomPage(ref,store,page);
        }
        void openGuild(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
            if(closed||!ref.isValid())return;Player component=store.getComponent(ref,Player.getComponentType());
            if(component!=null)component.getPageManager().openCustomPage(ref,store,new GuildPage(player,this));
        }
        /** The guild's role editor, which still lives on the Journal's guild desk. */
        void openGuildRoles(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
            if(closed||!ref.isValid())return;Player component=store.getComponent(ref,Player.getComponentType());
            if(component==null)return;
            var page=new EterniaServicesPage(player,this,EterniaServicesPage.Desk.JOURNAL,EterniaServicesPage.Section.GUILD,false).startInGuildRoles();
            component.getPageManager().openCustomPage(ref,store,page);
        }
        void openBoard(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
            if(closed||!ref.isValid())return;Player component=store.getComponent(ref,Player.getComponentType());
            if(component!=null)component.getPageManager().openCustomPage(ref,store,new GuildBoardPage(player,this));
        }
        @Override public void close() { closed = true; if (active == this) active = null; if (journalKey != null) journalKey.close(); executor.shutdownNow(); }
    }

    public static final class ServicesPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {
        public static final BuilderCodec<ServicesPageSupplier> CODEC = BuilderCodec.builder(ServicesPageSupplier.class, ServicesPageSupplier::new).build();
        @Override @Nullable public CustomUIPage tryCreate(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull PlayerRef player, @Nonnull InteractionContext context) {
            Registration registration = active;
            return registration == null || registration.closed ? null : new EterniaServicesPage(player, registration, EterniaServicesPage.Desk.JOURNAL, EterniaServicesPage.Section.COLLECTION, false);
        }
    }
}
