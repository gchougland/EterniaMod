package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.CollectionService;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hexvane.eterniamod.ui.ContentImages;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiAnchors;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The admin's "give content" screen, {@code /e admin give}: a player's name on the left (typed, or picked from who is
 * online), everything that can be given on the right in four lists, one Give button per thing. It records the same
 * audited grant as {@code /e admin grant}, without ids, kinds or receipts to type.
 */
final class GrantPage extends EterniaInteractiveCustomUIPage<GrantPage.Data> {
    private static final String[] CATEGORIES = {"Stations", "Decorations", "Houses", "Collection"};
    private static final int ONLINE_SLOTS = 6;

    /** One thing that can be given. */
    private record Entry(String contentId, String name, OwnershipService.Kind kind) {}

    private String category = "Stations";
    private String player;
    private String status = "";

    private GrantPage(PlayerRef admin) {
        super(admin, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.player = admin.getUsername();
    }

    static void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef admin) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component != null) component.getPageManager().openCustomPage(ref, store, new GrantPage(admin));
    }

    private List<Entry> entries() {
        var plugin = EterniaModPlugin.get();
        var out = new ArrayList<Entry>();
        switch (category) {
            case "Houses" -> plugin.getBuildingCatalog().asMap().keySet().forEach(id -> out.add(entry("eternia:house/" + id, OwnershipService.Kind.UNLOCK)));
            case "Collection" -> {
                for (var def : plugin.getServices().collection().definitions())
                    out.add(new Entry(def.id(), def.name(), def.kind() == CollectionService.Kind.PET ? OwnershipService.Kind.QUANTITY : OwnershipService.Kind.UNLOCK));
            }
            default -> {
                boolean stations = category.equals("Stations");
                for (var def : plugin.getPropCatalog().asMap().values()) {
                    if ("generator".equals(def.getCategory()) != stations) continue;
                    String name = def.getDisplayName() == null || def.getDisplayName().isBlank() ? UiPresentation.contentName("eternia:prop/" + def.getId()) : def.getDisplayName();
                    out.add(new Entry("eternia:prop/" + def.getId(), name, OwnershipService.Kind.QUANTITY));
                }
            }
        }
        out.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static Entry entry(String contentId, OwnershipService.Kind kind) { return new Entry(contentId, UiPresentation.contentName(contentId), kind); }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder c, UIEventBuilder e, Store<EntityStore> store) {
        c.append("EterniaMod/GrantPage.ui");
        c.set("#GrantPlayer.Value", player);
        c.set("#GrantStatus.TextSpans", Message.raw(status));
        var online = new ArrayList<String>();
        Universe universe = Universe.get();
        if (universe != null) for (PlayerRef other : universe.getPlayers()) if (other.getWorldUuid() != null) online.add(other.getUsername());
        online.sort(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < ONLINE_SLOTS; i++) {
            boolean on = i < online.size();
            c.set("#Online" + i + ".Visible", on);
            if (!on) continue;
            c.set("#Online" + i + ".Text", online.get(i));
            e.addEventBinding(CustomUIEventBindingType.Activating, "#Online" + i, new EventData().append("Action", "Pick:" + online.get(i)), false);
        }
        List<Entry> entries = entries();
        c.set("#GrantTitle.TextSpans", Message.raw(category));
        c.set("#GrantCount.TextSpans", Message.raw(entries.size() + (entries.size() == 1 ? " thing" : " things")));
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            String selector = "#GrantRows[" + i + "]";
            c.append("#GrantRows", "EterniaMod/ServiceRow.ui");
            ContentImages.row(c, selector, entry.contentId());
            c.set(selector + " #RowText.Text", entry.name() + (entry.kind() == OwnershipService.Kind.QUANTITY ? "\nGives one each time" : "\nUnlocks it for good"));
            c.set(selector + " #RowAction.Text", "Give");
            c.setObject(selector + " #RowAction.Anchor", UiAnchors.serviceAction(120));
            c.setObject(selector + ".Anchor", UiAnchors.heightWithBottom(76, 8));
            e.addEventBinding(CustomUIEventBindingType.Activating, selector + " #RowAction",
                new EventData().append("Action", "Give:" + i).append("@Player", "#GrantPlayer.Value"), false);
        }
        for (String name : CATEGORIES)
            e.addEventBinding(CustomUIEventBindingType.Activating, "#Cat" + name, new EventData().append("Action", "Cat:" + name).append("@Player", "#GrantPlayer.Value"), false);
        e.addEventBinding(CustomUIEventBindingType.Activating, "#Close", new EventData().append("Action", "Close"), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.action == null || isDismissed()) return;
        var world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            if (data.player != null && !data.player.isBlank()) player = data.player.trim();
            String action = data.action;
            if (action.equals("Close")) { close(); return; }
            if (action.startsWith("Pick:")) { player = action.substring(5); status = ""; rebuild(); return; }
            if (action.startsWith("Cat:")) { category = action.substring(4); status = ""; rebuild(); return; }
            if (!action.startsWith("Give:")) return;
            List<Entry> entries = entries();
            int index = Integer.parseInt(action.substring(5));
            if (index < 0 || index >= entries.size()) return;
            give(world, ref, entries.get(index), player);
        });
    }

    /** Looks the name up and records the grant away from the world thread, then says what happened. */
    private void give(com.hypixel.hytale.server.core.universe.world.World world, Ref<EntityStore> ref, Entry entry, String name) {
        var plugin = EterniaModPlugin.get();
        var services = plugin.getServices();
        String receipt = "admin:give:" + UUID.randomUUID();
        SocialUiBootstrap.supply(services, () -> {
            var account = services.accounts().findByName(name).orElseThrow(() -> new IllegalArgumentException("No player called " + name + " has joined Eternia."));
            services.ownership().grant(new OwnershipService.GrantRequest(receipt, Owner.player(account.id()), entry.contentId(), entry.kind(), 1, null));
            plugin.getLogger().atInfo().log("Administrator %s gave %s to %s (%s)", playerRef.getUsername(), entry.contentId(), account.displayName(), receipt);
            return account.displayName();
        }).whenComplete((given, failure) -> world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            Throwable cause = failure;
            while (cause != null && cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException)) cause = cause.getCause();
            status = cause == null ? "Gave " + entry.name() + " to " + given + "."
                : cause instanceof IllegalArgumentException || cause instanceof DomainException ? cause.getMessage() : "That could not be given. Try again in a moment.";
            if (cause != null && !(cause instanceof IllegalArgumentException) && !(cause instanceof DomainException)) plugin.getLogger().atWarning().withCause(cause).log("Give failed: %s", receipt);
            rebuild();
        }));
    }

    public static final class Data {
        public String action;
        public String player;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("@Player", Codec.STRING), (d, v) -> d.player = v, d -> d.player).add().build();
    }
}
