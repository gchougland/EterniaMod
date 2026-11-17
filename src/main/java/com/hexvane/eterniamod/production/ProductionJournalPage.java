package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.production.ProductionRuntime.Station;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiAnchors;
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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Production menu, opened from Home in the Journal: every station the player owns with its numbers kept live, the
 * plot's Estate, and today's orders. It works from anywhere. Upgrades can be bought from here through the station's
 * own screen; collecting still means walking to the crate.
 */
public final class ProductionJournalPage extends EterniaInteractiveCustomUIPage<ProductionJournalPage.Data> {
    /** Width of a station row's stockpile bar. */
    private static final int BAR = 440;
    private static final String[] PATH_NAMES = {"Bounty", "Fortune", "Vigor"};
    private static final String[] PATH_ITEMS = {"Furniture_Village_Crate", "Ore_Gold", "Tool_Pickaxe_Copper"};
    private static final String[] ESTATE_TEXT = {
        "Every station makes 5% more and holds 10% more with each rank.",
        "Rare finds and golden moments come more often with each rank.",
        "Hand work is 10% stronger, with 30 more energy a day, each rank."};
    private static final Map<UUID, ProductionJournalPage> OPEN = new ConcurrentHashMap<>();

    private final ProductionRuntime runtime;
    /** The stations shown, in the order of their rows. Fixed while the page is open so buttons keep their meaning. */
    private List<Station> stations = List.of();

    private final com.hypixel.hytale.server.core.universe.world.World world;

    private ProductionJournalPage(ProductionRuntime runtime, PlayerRef player, com.hypixel.hytale.server.core.universe.world.World world) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.runtime = runtime;
        this.world = world;
    }

    /** Opens the menu for {@code player}. Returns false while production is not running. World thread. */
    public static boolean open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        ProductionRuntime runtime = ProductionRuntime.get();
        Player component = store.getComponent(ref, Player.getComponentType());
        if (runtime == null || component == null) return false;
        var page = new ProductionJournalPage(runtime, player, store.getExternalData().getWorld());
        OPEN.put(player.getUuid(), page);
        component.getPageManager().openCustomPage(ref, store, page);
        return true;
    }

    /** Any thread, about once a second: open menus follow their stations' numbers, each on its own world's thread. */
    static void tickOpen() {
        for (var entry : OPEN.entrySet()) {
            ProductionJournalPage page = entry.getValue();
            if (page.isDismissed()) { OPEN.remove(entry.getKey(), page); continue; }
            try {
                page.world.execute(() -> {
                    if (page.isDismissed()) return;
                    var update = new UICommandBuilder();
                    page.numbers(update);
                    page.sendUpdate(update, null, false);
                });
            } catch (RuntimeException closing) {
                OPEN.remove(entry.getKey(), page);
            }
        }
    }

    private List<Station> mine() {
        String owner = Owner.player(playerRef.getUuid()).key();
        var out = new ArrayList<Station>();
        for (Station s : runtime.stations()) if (s.row.placed && owner.equals(s.row.owner)) out.add(s);
        out.sort(Comparator.comparing((Station s) -> runtime.profession(s).station()).thenComparing(s -> s.row.id));
        return out;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder c, UIEventBuilder e, Store<EntityStore> store) {
        c.append("EterniaMod/ProductionJournal.ui");
        stations = mine();
        for (int i = 0; i < stations.size(); i++) {
            c.append("#PjStations", "EterniaMod/ProductionJournalStation.ui");
            String row = "#PjStations[" + i + "]";
            e.addEventBinding(CustomUIEventBindingType.Activating, row + " #SjOpen", new EventData().append("Action", "Open:" + i), false);
            e.addEventBinding(CustomUIEventBindingType.Activating, row + " #SjGo", new EventData().append("Action", "Go:" + i), false);
        }
        if (stations.isEmpty()) {
            plain(c, "#PjStations", 0, "Deco_Bucket", "No station is working yet",
                "Every player gets a Mine Shaft. Add your free production hexagon on the Claim Map, stand on it, then place the Mine Shaft from Decorations in Home.");
        }
        // Estate ranks and orders are rows too; their texts are filled by numbers().
        for (int i = 0; i < 3; i++) c.append("#PjEstate", "EterniaMod/ProductionRow.ui");
        PlotProduction estate = estate();
        int orders = estate == null ? 0 : orderCount(estate);
        for (int i = 0; i < Math.max(1, orders); i++) c.append("#PjOrders", "EterniaMod/ProductionRow.ui");
        shownOrders = Math.max(1, orders);
        numbers(c);
        bindHome(e);
        // The same tabs as the Journal across the top, with Production lit.
        var sections = com.hexvane.eterniamod.socialui.EterniaServicesPage.journalSections();
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            String tab = com.hexvane.eterniamod.ui.JournalChrome.navTab(c, i, com.hexvane.eterniamod.socialui.EterniaServicesPage.journalTabLabel(section),
                com.hexvane.eterniamod.socialui.EterniaServicesPage.journalTabIcon(section), false, false);
            e.addEventBinding(CustomUIEventBindingType.Activating, tab, new EventData().append("Action", "nav:" + section.name()), false);
        }
        e.addEventBinding(CustomUIEventBindingType.Activating, com.hexvane.eterniamod.ui.JournalChrome.navTab(c, sections.size(), "Records", "board", false, false),
            new EventData().append("Action", "records"), false);
        com.hexvane.eterniamod.ui.JournalChrome.navTab(c, sections.size() + 1, "Production", "storage", true, false);
        showWallet(c, ref, store);
        e.addEventBinding(CustomUIEventBindingType.Activating, "#Close", new EventData().append("Action", "Close"), false);
    }

    private int shownOrders;

    private static int orderCount(PlotProduction estate) { synchronized (estate) { return estate.orders.size(); } }

    /** The Estate of the plot the player's stations stand on, or null when they have none placed. */
    private PlotProduction estate() {
        if (stations.isEmpty()) return null;
        runtime.dealOrders(stations.getFirst());
        return runtime.plot(stations.getFirst().row.plot).plot();
    }

    private static void plain(UICommandBuilder c, String list, int index, String item, String title, String text) {
        if (index == 0 && list.equals("#PjStations")) c.append(list, "EterniaMod/ProductionRow.ui");
        String row = list + "[" + index + "]";
        c.set(row + " #PRowTitle.TextSpans", Message.raw(title));
        c.set(row + " #PRowText.TextSpans", Message.raw(text));
        c.set(row + " #PRowItem.Visible", !item.isEmpty());
        c.set(row + " #PRowMark.Visible", item.isEmpty());
        if (!item.isEmpty()) c.set(row + " #PRowItem.ItemId", item);
        c.set(row + " #PRowAction.Visible", false);
    }

    /** Everything that changes while the menu is open. */
    private void numbers(UICommandBuilder c) {
        var r = runtime.rules;
        String headItem = "Tool_Pickaxe_Copper";
        int finds = 0, findsTotal = 0, full = 0;
        for (int i = 0; i < stations.size(); i++) {
            Station station = stations.get(i);
            Profession p = runtime.profession(station);
            String row = "#PjStations[" + i + "]";
            synchronized (station) {
                StationProgress s = station.row.progress;
                var mods = runtime.mods(station);
                int tier = ProductionMath.tier(r, s.level);
                double perMinute = ProductionMath.perMinute(r, p, s, mods), cap = ProductionMath.cap(r, p, s, mods);
                double fill = cap <= 0 ? 0 : Math.clamp(s.stockpile / cap, 0, 1);
                if (i == 0) headItem = p.good(tier).item();
                finds += s.finds.size();
                findsTotal += p.finds().size();
                if (s.full) full++;
                c.set(row + " #SjItem.ItemId", p.good(tier).item());
                c.set(row + " #SjName.TextSpans", Message.raw(p.station()));
                c.set(row + " #SjRate.TextSpans", Message.raw(ProductionMath.format(perMinute) + " " + p.unit() + " each minute"));
                c.set(row + " #SjSub.TextSpans", Message.raw("Level " + s.level + "  ·  Mining " + p.good(tier).name()
                    + (s.renown > 0 ? "  ·  " + s.renown + " Renown" : "")));
                c.setObject(row + " #SjFill.Anchor", UiAnchors.size((int) Math.round(BAR * fill), 18));
                c.set(row + " #SjStock.TextSpans", Message.raw(ProductionMath.format(s.stockpile) + " of " + ProductionMath.format(cap) + " in the stockpile"
                    + (s.full ? "" : station.running ? "  ·  Full in " + ProductionMath.duration(ProductionMath.secondsToFull(r, p, s, mods)) : "")));
                int owed = ProductionMath.talentPicksOwed(r, s);
                int afford = ProductionMath.affordableLevels(r, s.level, s.purse);
                String status = station.golden > 0 ? "Golden moment! Go and touch it before it fades."
                    : s.full ? "Full. Walk over and collect it."
                    : owed > 0 ? "A talent card is waiting. Press Upgrades to choose."
                    : s.frenzySeconds > 0 ? "Frenzy: seven times the output."
                    : s.veinLeft > 0 ? "Vein struck: " + s.veinLeft + " rich loads to come."
                    : afford > 0 ? "You can afford " + afford + (afford == 1 ? " level." : " levels.")
                    : !station.running ? "Resting." : "Working.";
                c.set(row + " #SjStatus.TextSpans", Message.raw(status).color(station.golden > 0 || s.full || owed > 0 ? "#d8b66b" : "#85c5b3"));
            }
        }
        c.set("#PjHeadItem.ItemId", headItem);
        c.set("#PjKicker.TextSpans", Message.raw(stations.isEmpty() ? "No stations yet" : stations.size() == 1 ? "1 station at work" : stations.size() + " stations at work"));
        c.set("#PjCount.TextSpans", Message.raw(full > 0 ? full + (full == 1 ? " station is full" : " stations are full") : ""));
        c.set("#PjHint.TextSpans", Message.raw(stations.isEmpty() ? "" : "Stations work while you are online anywhere in Eternia. Collecting means a walk to the crate."));

        PlotProduction estate = stations.isEmpty() ? null : runtime.plot(stations.getFirst().row.plot).plot();
        int seals = 0, streak = 0, energy = r.dailyEnergy();
        int[] ranks = new int[3];
        var orders = new ArrayList<String[]>();
        if (estate != null) synchronized (estate) {
            seals = estate.seals;
            streak = estate.streak;
            energy = estate.energyLeft(r, ProductionRuntime.today());
            ranks = estate.estate.clone();
            Station first = stations.getFirst();
            Profession p = runtime.profession(first);
            int tier;
            synchronized (first) { tier = ProductionMath.tier(r, first.row.progress.level); }
            for (var order : estate.orders) orders.add(new String[] {
                switch (order.kind) { case "collect" -> p.good(tier).item(); case "golden" -> "Ingredient_Bar_Gold"; case "levels" -> p.upgradeItem(tier); default -> "Tool_Pickaxe_Copper"; },
                (order.paid ? "Done: " : "") + PlotProduction.orderText(order, p.unit()),
                ProductionMath.format(Math.min(order.done, order.goal)) + " of " + ProductionMath.format(order.goal)
                    + (order.paid ? "   Paid " : "   Pays ") + order.seals + (order.seals == 1 ? " Seal" : " Seals")});
        }
        c.set("#PjSealItem.ItemId", "Ingredient_Bar_Gold");
        c.set("#PjSeals.TextSpans", Message.raw(Integer.toString(seals)));
        c.set("#PjSealsWord.TextSpans", Message.raw(seals == 1 ? "Seal" : "Seals"));
        c.set("#PjSealsNote.TextSpans", Message.raw("Seals come from milestones, orders and Renown. Spend them at any station's signboard, on the Estate tab."));
        stat(c, 0, "Ingredient_Bar_Gold", streak > 0 ? "Day " + streak : "No streak", "Daily streak +" + Math.round(r.streakStep() * Math.min(streak, r.streakDays()) * 100) + "%");
        stat(c, 1, "Tool_Pickaxe_Copper", Integer.toString(energy), "Hand work energy left today");
        stat(c, 2, "Deco_Lantern", findsTotal == 0 ? "None yet" : finds + " of " + findsTotal, "Rare finds");
        for (int i = 0; i < 3; i++) plain(c, "#PjEstate", i, PATH_ITEMS[i], PATH_NAMES[i] + "   rank " + ranks[i] + " of " + PlotProduction.ESTATE_RANKS, ESTATE_TEXT[i]);
        c.set("#PjOrdersNote.TextSpans", Message.raw(estate == null ? "Orders start once a station is placed." : "Small jobs for today. Each one pays Seals for your Estate."));
        for (int i = 0; i < shownOrders; i++) {
            if (i < orders.size()) plain(c, "#PjOrders", i, orders.get(i)[0], orders.get(i)[1], orders.get(i)[2]);
            else plain(c, "#PjOrders", i, "", estate == null ? "Nothing yet" : "No orders today", estate == null ? "Place a station to get your first orders." : "New orders come tomorrow.");
        }
    }

    private static void stat(UICommandBuilder c, int slot, String item, String value, String label) {
        c.set("#PjStatItem" + slot + ".ItemId", item);
        c.set("#PjStatValue" + slot + ".TextSpans", Message.raw(value));
        c.set("#PjStatLabel" + slot + ".TextSpans", Message.raw(label));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.action == null || isDismissed()) return;
        var world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            String action = data.action;
            if (action.equals("Close")) { close(); return; }
            if (action.startsWith("nav:")) {
                try { com.hexvane.eterniamod.socialui.SocialUiBootstrap.openJournal(ref, store, playerRef, com.hexvane.eterniamod.socialui.EterniaServicesPage.Section.valueOf(action.substring(4))); }
                catch (IllegalArgumentException unknown) { /* not a Journal section */ }
                return;
            }
            if (action.equals("records")) { com.hexvane.eterniamod.stats.StatsPage.open(ref, store, playerRef, playerRef.getUuid(), playerRef.getUsername()); return; }
            int colon = action.indexOf(':');
            if (colon < 0) return;
            int index;
            try { index = Integer.parseInt(action.substring(colon + 1)); } catch (NumberFormatException bad) { return; }
            if (index < 0 || index >= stations.size()) return;
            Station station = stations.get(index);
            if (action.startsWith("Open:")) {
                ProductionStationPage.open(runtime, ref, store, playerRef, station, true);
            } else if (action.startsWith("Go:")) {
                close();
                EterniaModPlugin.get().getTravel().visitPlot(playerRef, Owner.player(playerRef.getUuid()));
            }
        });
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        OPEN.remove(playerRef.getUuid(), this);
        super.onDismiss(ref, store);
    }

    public static final class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add().build();
    }
}
