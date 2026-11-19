package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.domain.PremiumService;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * {@code /e admin catalog}: for every house, decoration and station, whether players get it free at the start and
 * whether it is in the Crown store and for how much. Free items live in {@code StarterContentService}; store listings
 * are ordinary store items ({@code eternia:store/<id>}), priced by a new revision and taken down with
 * {@link PremiumService#setListed}.
 */
final class CatalogManagerPage extends EterniaInteractiveCustomUIPage<CatalogManagerPage.Data> {
    private static final String[] CATEGORIES = {"Houses", "Decorations", "Stations"};

    /** What the store and starter list say now, read away from the world thread. */
    private record State(Set<String> free, Map<String, PremiumService.Item> items, Set<String> retired) {}

    private String category = "Houses";
    private String status = "Loading...";
    private State state;

    private CatalogManagerPage(PlayerRef admin) { super(admin, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC); }

    static void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef admin) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        var page = new CatalogManagerPage(admin);
        component.getPageManager().openCustomPage(ref, store, page);
        page.run(store.getExternalData().getWorld(), ref, () -> "");
    }

    private List<CatalogListing.Entry> entries() {
        var plugin = EterniaModPlugin.get();
        var out = new ArrayList<CatalogListing.Entry>();
        if (category.equals("Houses")) {
            for (var def : plugin.getBuildingCatalog().asMap().values()) {
                String content = "eternia:house/" + def.getId();
                out.add(new CatalogListing.Entry(def.getId(), content, name(def.getDisplayName(), content), true, "guild".equals(def.getHousingKind()), "Housing"));
            }
        } else {
            boolean stations = category.equals("Stations");
            for (var def : plugin.getPropCatalog().asMap().values()) {
                if ("generator".equals(def.getCategory()) != stations) continue;
                String content = "eternia:prop/" + def.getId();
                out.add(new CatalogListing.Entry(def.getId(), content, name(def.getDisplayName(), content), false, false, stations ? "Stations" : "Furnishings"));
            }
        }
        out.sort(Comparator.comparing(CatalogListing.Entry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static String name(String given, String content) { return given == null || given.isBlank() ? UiPresentation.contentName(content) : given; }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder c, UIEventBuilder e, Store<EntityStore> store) {
        c.append("EterniaMod/CatalogManagerPage.ui");
        c.set("#CatalogStatus.TextSpans", Message.raw(status));
        List<CatalogListing.Entry> entries = entries();
        c.set("#CatalogTitle.TextSpans", Message.raw(category));
        c.set("#CatalogCount.TextSpans", Message.raw(entries.size() + (entries.size() == 1 ? " item" : " items")));
        if (state != null) for (int i = 0; i < entries.size(); i++) {
            CatalogListing.Entry entry = entries.get(i);
            String row = "#CatalogRows[" + i + "]";
            boolean free = state.free().contains(entry.contentId());
            var item = state.items().get(entry.storeId());
            boolean listed = item != null && !state.retired().contains(entry.storeId());
            c.append("#CatalogRows", "EterniaMod/CatalogManagerRow.ui");
            ContentImages.row(c, row, entry.contentId());
            c.set(row + " #RowText.Text", CatalogListing.describe(entry, free, item, !listed));
            c.set(row + " #RowFree.Visible", !entry.guild());
            c.set(row + " #RowFree.Text", free ? "Stop giving free" : "Give free at start");
            c.set(row + " #RowPrice.Value", item == null ? "" : Long.toString(item.price()));
            c.set(row + " #RowSell.Text", listed ? "Change price" : "Sell");
            c.set(row + " #RowStop.Visible", listed);
            c.setObject(row + ".Anchor", UiAnchors.heightWithBottom(76, 8));
            e.addEventBinding(CustomUIEventBindingType.Activating, row + " #RowFree", new EventData().append("Action", "Free:" + i), false);
            e.addEventBinding(CustomUIEventBindingType.Activating, row + " #RowSell", new EventData().append("Action", "Sell:" + i).append("@Price", row + " #RowPrice.Value"), false);
            e.addEventBinding(CustomUIEventBindingType.Activating, row + " #RowStop", new EventData().append("Action", "Stop:" + i), false);
        }
        for (String name : CATEGORIES)
            e.addEventBinding(CustomUIEventBindingType.Activating, "#Cat" + name, new EventData().append("Action", "Cat:" + name), false);
        e.addEventBinding(CustomUIEventBindingType.Activating, "#Close", new EventData().append("Action", "Close"), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.action == null || isDismissed()) return;
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            String action = data.action;
            if (action.equals("Close")) { close(); return; }
            if (action.startsWith("Cat:")) { category = action.substring(4); status = ""; rebuild(); return; }
            int colon = action.indexOf(':');
            if (colon < 0 || state == null) return;
            List<CatalogListing.Entry> entries = entries();
            int index;
            try { index = Integer.parseInt(action.substring(colon + 1)); } catch (NumberFormatException bad) { return; }
            if (index < 0 || index >= entries.size()) return;
            CatalogListing.Entry entry = entries.get(index);
            var services = EterniaModPlugin.get().getServices();
            switch (action.substring(0, colon)) {
                case "Free" -> {
                    if (entry.guild()) return;
                    boolean free = !state.free().contains(entry.contentId());
                    run(world, ref, () -> {
                        services.starters().setFree(entry.contentId(), free);
                        return free ? entry.name() + " is now free for every player." : entry.name() + " is no longer free for new players.";
                    });
                }
                case "Sell" -> {
                    long price = CatalogListing.price(data.price);
                    if (price <= 0) { status = "Type a Crown price from 1 to " + CatalogListing.MAX_PRICE + ", then press Sell."; rebuild(); return; }
                    run(world, ref, () -> CatalogListing.sell(services, entry, price));
                }
                case "Stop" -> run(world, ref, () -> {
                    services.premium().setListed(entry.storeId(), false);
                    return entry.name() + " is out of the Crown store.";
                });
                default -> { }
            }
        });
    }

    /** Does the change away from the world thread, reloads what the store and starter list say, then shows the result. */
    private void run(World world, Ref<EntityStore> ref, Supplier<String> change) {
        var plugin = EterniaModPlugin.get();
        var services = plugin.getServices();
        SocialUiBootstrap.supply(services, () -> {
            String said = change.get();
            var items = new TreeMap<String, PremiumService.Item>();
            for (var item : services.premium().items()) items.put(item.id(), item);
            return new Object[] {said, new State(Set.copyOf(services.starters().free()), items, services.premium().retiredIds())};
        }).whenComplete((result, failure) -> world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            Throwable cause = failure;
            while (cause != null && cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException)) cause = cause.getCause();
            if (cause == null) {
                status = (String) result[0];
                state = (State) result[1];
                if (!status.isEmpty()) plugin.getLogger().atInfo().log("Administrator %s changed the starter and shop list: %s", playerRef.getUsername(), status);
            } else {
                status = cause instanceof IllegalArgumentException || cause instanceof DomainException ? cause.getMessage() : "That could not be saved. Try again in a moment.";
                if (!(cause instanceof IllegalArgumentException) && !(cause instanceof DomainException)) plugin.getLogger().atWarning().withCause(cause).log("Starter and shop change failed");
            }
            rebuild();
        }));
    }

    public static final class Data {
        public String action;
        public String price;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("@Price", Codec.STRING), (d, v) -> d.price = v, d -> d.price).add().build();
    }
}
