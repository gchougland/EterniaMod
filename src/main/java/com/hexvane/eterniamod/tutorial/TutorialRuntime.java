package com.hexvane.eterniamod.tutorial;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.TutorialService;
import com.hexvane.eterniamod.socialui.EterniaServicesPage;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs the Hub tutorial in game: remembers each online player's progress, shows the walkthrough panel only while
 * they stand in the Hub world, and turns talking to a Hub NPC into a meeting (dialogue first, then their desk).
 */
public final class TutorialRuntime {
    private static final long CHECK_MILLIS = 1000;
    private static final Map<UUID, TutorialService.State> STATES = new ConcurrentHashMap<>();
    private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> CHECKED = new ConcurrentHashMap<>();

    private TutorialRuntime() {}

    public static void register(EterniaModPlugin plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new Panel());
        plugin.getEntityStoreRegistry().registerSystem(new Forget());
    }

    /** The NPC key behind a services section a Hub NPC opens, or null for sections no NPC opens. */
    @Nullable
    public static String npcKey(EterniaServicesPage.Section section) {
        return switch (section) {
            case GREETER -> TutorialService.GUIDE;
            case HOUSING -> "housing";
            case GUILD -> "guild";
            case SHOP -> "shop";
            case STORE -> "store";
            case SEASON -> "gatehouse";
            case COMING_SOON -> "minigame";
            case RULES -> "rules";
            default -> null;
        };
    }

    /**
     * A player talked to a Hub NPC. Records the meeting off the world thread, then on it shows the NPC's opening
     * lines on a first meeting (or the guide's farewell) before {@code afterDialogue}, else runs {@code open}.
     */
    public static void meet(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, EterniaServicesPage.Section section,
                            BiConsumer<Ref<EntityStore>, Store<EntityStore>> open, BiConsumer<Ref<EntityStore>, Store<EntityStore>> afterDialogue) {
        String npc = npcKey(section);
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (npc == null || plugin == null) { open.accept(ref, store); return; }
        var services = plugin.getServices();
        UUID id = player.getUuid();
        World world = store.getExternalData().getWorld();
        var required = townspeople(world);
        SocialUiBootstrap.supply(services, () -> services.tutorial().meet(id, npc, required)).whenComplete((meeting, failure) -> {
            try {
                world.execute(() -> {
                    if (!ref.isValid()) return;
                    if (failure != null) {
                        plugin.getLogger().atWarning().withCause(failure).log("Tutorial meeting was not recorded");
                        open.accept(ref, store);
                        return;
                    }
                    STATES.put(id, meeting.state());
                    refresh(ref, store, player);
                    if (meeting.finishedNow()) player.sendMessage(Message.translation(HubDialogue.text("finished")).param("player", player.getUsername()));
                    else if (meeting.first() && meeting.state().active() && TutorialService.TOWNSPEOPLE.contains(npc))
                        player.sendMessage(Message.translation(HubDialogue.text("step.done")).param("name", HubDialogue.name(npc)));
                    var pages = HubDialogue.pages(npc, meeting);
                    NpcDialoguePage.open(ref, store, player, npc, pages, pages.isEmpty() ? open : afterDialogue);
                });
            } catch (RuntimeException closing) { /* The world is stopping. */ }
        });
    }

    /** Applies a changed state from a command and refreshes the panel. World thread. */
    static void changed(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, TutorialService.State state) {
        STATES.put(player.getUuid(), state);
        refresh(ref, store, player);
    }

    /** Runs {@code work} off the world thread and hands the result back on it. */
    static <T> void async(Store<EntityStore> store, Supplier<T> work, java.util.function.BiConsumer<T, Throwable> done) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin == null) { done.accept(null, new IllegalStateException("Eternia is not running")); return; }
        World world = store.getExternalData().getWorld();
        SocialUiBootstrap.supply(plugin.getServices(), work).whenComplete((value, failure) -> {
            try { world.execute(() -> done.accept(value, failure)); } catch (RuntimeException closing) { /* The world is stopping. */ }
        });
    }

    /** The Hub world, or any world where the Hub's guide stands (a town built inside another world). */
    static boolean inHub(World world) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin == null) return false;
        if (plugin.getInfrastructure().world(world.getName()).map(plan -> plan.role().equals("hub")).orElse(false)) return true;
        return com.hexvane.eterniamod.setup.ManagedHubServices.live(world, com.hexvane.eterniamod.setup.HubNpcIdentity.GREETER.role()).isPresent();
    }

    /** The townspeople who stand in this world, in walkthrough order; every one when none are placed yet. */
    static java.util.List<String> townspeople(World world) {
        var present = new java.util.ArrayList<String>();
        for (var identity : com.hexvane.eterniamod.setup.HubNpcIdentity.values()) {
            String npc = HubDialogue.npcKey(identity);
            if (TutorialService.TOWNSPEOPLE.contains(npc) && com.hexvane.eterniamod.setup.ManagedHubServices.live(world, identity.role()).isPresent()) present.add(npc);
        }
        if (present.isEmpty()) return TutorialService.TOWNSPEOPLE;
        return TutorialService.TOWNSPEOPLE.stream().filter(present::contains).toList();
    }

    /** Shows, updates or removes the panel for where the player is and what they have done. */
    static void refresh(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        TutorialService.State state = STATES.get(player.getUuid());
        boolean show = state != null && state.active() && inHub(store.getExternalData().getWorld());
        var huds = component.getHudManager();
        if (!show) {
            if (huds.getCustomHud(TutorialHud.ID) != null) huds.removeCustomHud(player, TutorialHud.ID);
            return;
        }
        TutorialHud hud;
        if (huds.getCustomHud(TutorialHud.ID) instanceof TutorialHud existing) hud = existing;
        else { hud = new TutorialHud(player); huds.addCustomHud(player, hud); }
        hud.refresh(state, townspeople(store.getExternalData().getWorld()));
    }

    /** Once a second per player: load progress the first time, then keep the panel matching the world they are in. */
    static final class Panel extends EntityTickingSystem<EntityStore> {
        @Nonnull @Override
        public Query<EntityStore> getQuery() { return Query.and(Player.getComponentType(), PlayerRef.getComponentType()); }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            if (player == null) return;
            UUID id = player.getUuid();
            long now = System.currentTimeMillis();
            Long last = CHECKED.get(id);
            if (last != null && now - last < CHECK_MILLIS) return;
            CHECKED.put(id, now);
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            if (!STATES.containsKey(id)) {
                if (!LOADING.add(id)) return;
                EterniaModPlugin plugin = EterniaModPlugin.get();
                if (plugin == null) { LOADING.remove(id); return; }
                var services = plugin.getServices();
                async(store, () -> services.tutorial().state(id), (state, failure) -> {
                    LOADING.remove(id);
                    if (failure != null || state == null) return;
                    STATES.putIfAbsent(id, state);
                    if (ref.isValid()) refresh(ref, store, player);
                });
                return;
            }
            store.getExternalData().getWorld().execute(() -> { if (ref.isValid()) refresh(ref, store, player); });
        }
    }

    /** Forgets a player's cached progress when they leave, so the next login reads it fresh. */
    static final class Forget extends RefSystem<EntityStore> {
        @Nonnull @Override
        public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {}

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) return;
            // Moving between worlds also removes the player entity; the cached state is still right, so keep it.
            CHECKED.remove(player.getUuid());
        }
    }
}
