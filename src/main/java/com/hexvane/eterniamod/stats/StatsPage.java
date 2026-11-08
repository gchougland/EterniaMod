package com.hexvane.eterniamod.stats;

import com.hexvane.eterniamod.runtime.WorldDisplayNames;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.UUID;

/** A player's adventure records in the Citadel Codex frame. Opens at once and fills in when the records are read. */
public final class StatsPage extends EterniaInteractiveCustomUIPage<ChoicePage.Data> {
    private final UUID target;
    private final boolean self;
    private final String name;
    private final BossCatalog catalog;
    private volatile StatsView.Model model;

    private StatsPage(PlayerRef viewer, UUID target, String name, BossCatalog catalog) {
        super(viewer, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ChoicePage.Data.CODEC);
        this.target = target; this.self = viewer.getUuid().equals(target); this.name = name; this.catalog = catalog;
        model = new StatsView.Model(StatsView.State.LOADING, self, name, null, List.of());
    }

    /** Opens the page for {@code target}; call on the world thread. */
    public static void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef viewer, UUID target, String name) {
        var runtime = StatsRuntime.active(); var player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;
        if (runtime == null) { viewer.sendMessage(Message.translation("eternia_stats.command.notReady")); return; }
        var page = new StatsPage(viewer, target, name, runtime.bosses());
        player.getPageManager().openCustomPage(ref, store, page);
        var world = store.getExternalData().getWorld();
        runtime.read(() -> new Loaded(runtime.servicesStats().player(target).orElse(null), runtime.servicesStats().firsts()))
            .whenComplete((loaded, failure) -> {
                try {
                    world.execute(() -> {
                        if (page.isDismissed() || !ref.isValid()) return;
                        String shownName = loaded != null && loaded.stats() != null && !loaded.stats().name().isBlank() ? loaded.stats().name() : name;
                        page.model = failure != null || loaded == null || loaded.stats() == null
                            ? new StatsView.Model(StatsView.State.UNAVAILABLE, page.self, shownName, null, List.of())
                            : new StatsView.Model(StatsView.State.READY, page.self, shownName, loaded.stats(), loaded.firsts());
                        var update = new UICommandBuilder(); apply(update, StatsView.commands(page.model, page.catalog, WorldDisplayNames::name));
                        page.sendUpdate(update, null, false);
                    });
                } catch (RuntimeException closing) { /* The world is stopping. */ }
            });
    }

    private record Loaded(StatsService.PlayerStats stats, List<StatsService.First> firsts) {}

    @Override public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events, Store<EntityStore> store) {
        commands.append(StatsView.DOCUMENT);
        bindHome(events);
        apply(commands, StatsView.commands(model, catalog, WorldDisplayNames::name));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Close", new EventData().append("Action", "Close"), false);
    }

    @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, ChoicePage.Data data) {
        if (data.action == null || isDismissed()) return;
        store.getExternalData().getWorld().execute(() -> { if (!isDismissed() && ref.isValid() && "Close".equals(data.action)) close(); });
    }

    /** Turns the view's plain values into UI commands. */
    static void apply(UICommandBuilder commands, List<StatsView.Set> sets) {
        for (var set : sets) {
            switch (set.value()) {
                case StatsView.Text text -> {
                    var message = Message.translation(text.key());
                    text.params().forEach(message::param);
                    commands.set(set.selector(), message);
                }
                case StatsView.Raw raw -> commands.set(set.selector(), Message.raw(raw.text()));
                case StatsView.Icon icon -> commands.setObject(set.selector(), new PatchStyle(Value.of(icon.path())));
                case Boolean flag -> commands.set(set.selector(), flag);
                default -> throw new IllegalArgumentException("Unsupported stats page value " + set.value());
            }
        }
    }
}
