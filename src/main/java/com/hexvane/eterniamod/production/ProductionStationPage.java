package com.hexvane.eterniamod.production;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The station screen: what it makes, how full it is, the one big Upgrade button, and tabs for tools, talents, the
 * album and the plot's Estate. A talent choice that is owed takes over the right side until a card is kept.
 * Visitors see the same screen with nothing to press.
 */
final class ProductionStationPage extends EterniaInteractiveCustomUIPage<ProductionStationPage.Data> {
    static final int BAR = 776;
    private static final String[] PATH_NAMES = {"Bounty", "Fortune", "Vigor"};
    /** Vanilla items that picture the three paths: a full crate, a lump of gold, a pick in hand. */
    private static final String[] PATH_ITEMS = {"Furniture_Village_Crate", "Ore_Gold", "Tool_Pickaxe_Copper"};
    private static final String[] TOOL_ROLES = {"Loads come up sooner", "More in every load", "The stockpile holds more", "Better luck with veins and rare finds"};
    private static final String[] ESTATE_TEXT = {
        "Every station makes 5% more and holds 10% more with each rank.",
        "Rare finds and golden moments come more often with each rank.",
        "Hand work is 10% stronger, with 30 more energy a day, each rank."};
    private static final Map<UUID, ProductionStationPage> OPEN = new ConcurrentHashMap<>();

    private final ProductionRuntime runtime;
    private final Station station;
    private final boolean manages;
    private String tab = "Tools";
    private boolean firstBuild = true;

    /** A row on the right: text, and a button when there is something to buy or choose. */
    private record Row(String title, String text, String button, String action, boolean enabled, String item) {
        Row(String title, String text, String button, String action, boolean enabled) { this(title, text, button, action, enabled, ""); }
        static Row plain(String title, String text) { return new Row(title, text, "", "", false); }
        /** The same row with a vanilla item pictured beside it. */
        Row icon(String item) { return new Row(title, text, button, action, enabled, item == null ? "" : item); }
    }

    private ProductionStationPage(ProductionRuntime runtime, PlayerRef player, Station station, boolean manages) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.runtime = runtime;
        this.station = station;
        this.manages = manages;
    }

    /** Opens the station for a player: the talent choice first while a card is owed, else the station's screen. */
    static void open(ProductionRuntime runtime, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, Station station, boolean manages) {
        if (manages && ProductionTalentPage.owed(runtime, station)) { ProductionTalentPage.open(runtime, ref, store, player, station); return; }
        openScreen(runtime, ref, store, player, station, manages);
    }

    /** The station's own screen, whether or not a card is owed. */
    static void openScreen(ProductionRuntime runtime, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, Station station, boolean manages) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        runtime.dealOrders(station);
        var page = new ProductionStationPage(runtime, player, station, manages);
        OPEN.put(player.getUuid(), page);
        component.getPageManager().openCustomPage(ref, store, page);
    }

    /** World thread, about once a second: open screens follow their station's numbers. */
    static void tickOpen() {
        for (var entry : OPEN.entrySet()) {
            ProductionStationPage page = entry.getValue();
            if (page.isDismissed()) { OPEN.remove(entry.getKey(), page); continue; }
            var update = new UICommandBuilder();
            page.numbers(update);
            page.sendUpdate(update, null, false);
        }
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder c, UIEventBuilder e, Store<EntityStore> store) {
        c.append("EterniaMod/ProductionStation.ui");
        numbers(c);
        List<Row> rows = rows(c);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            String selector = "#TabRows[" + i + "]";
            c.append("#TabRows", "EterniaMod/ProductionRow.ui");
            c.set(selector + " #PRowTitle.TextSpans", Message.raw(row.title()));
            c.set(selector + " #PRowText.TextSpans", Message.raw(row.text()));
            c.set(selector + " #PRowItem.Visible", !row.item().isEmpty());
            c.set(selector + " #PRowMark.Visible", row.item().isEmpty());
            if (!row.item().isEmpty()) c.set(selector + " #PRowItem.ItemId", row.item());
            c.set(selector + " #PRowAction.Visible", !row.button().isEmpty());
            if (row.button().isEmpty()) continue;
            c.set(selector + " #PRowAction.Text", row.button());
            c.set(selector + " #PRowAction.Disabled", !row.enabled() || !manages);
            e.addEventBinding(CustomUIEventBindingType.Activating, selector + " #PRowAction", new EventData().append("Action", row.action()), false);
        }
        for (String action : new String[] {"Close", "Buy1", "Buy10", "BuyMax", "TabTools", "TabTalents", "TabAlbum", "TabOrders", "TabEstate"})
            e.addEventBinding(CustomUIEventBindingType.Activating, "#" + action, new EventData().append("Action", action), false);
    }

    /** Everything that changes while the screen is open: the left and middle panels and the purse. */
    private void numbers(UICommandBuilder c) {
        var r = runtime.rules;
        Profession p = runtime.profession(station);
        synchronized (station) {
            StationProgress s = station.row.progress;
            var mods = runtime.mods(station);
            int tier = ProductionMath.tier(r, s.level);
            double perMinute = ProductionMath.perMinute(r, p, s, mods), cap = ProductionMath.cap(r, p, s, mods);
            double fill = cap <= 0 ? 0 : Math.clamp(s.stockpile / cap, 0, 1);
            String good = p.good(tier).item();
            c.set("#HeadItem.ItemId", good);
            c.set("#PurseItem.ItemId", good);
            c.set("#RateItem.ItemId", good);
            c.set("#StockItem.ItemId", "Furniture_Village_Crate");
            c.set("#UpgradeItem.ItemId", p.upgradeItem(tier));
            c.set("#NextItem.ItemId", p.good(Math.min(tier + 1, p.goods().size())).item());
            c.set("#StationName.TextSpans", Message.raw(p.station()));
            c.set("#StationSub.TextSpans", Message.raw(p.name() + "  ·  Tier " + tier + (manages ? "" : "  ·  Visiting")));
            c.set("#Purse.TextSpans", Message.raw(ProductionMath.format(s.purse) + " " + p.unit()));
            c.set("#PurseNote.TextSpans", Message.raw("Collected this season: " + ProductionMath.format(s.lifetime)));
            c.set("#Level.TextSpans", Message.raw(Integer.toString(s.level)));
            c.set("#Renown.TextSpans", Message.raw(s.renown > 0 ? s.renown + " Renown: +" + Math.round(r.renownBonus() * 100 * s.renown) + "% output"
                : ProductionMath.canRebuild(r, s) ? "Ready to rebuild for Renown. See the Estate tab." : ""));
            for (int i = 0; i < TIER_ROWS; i++) {
                boolean exists = i < p.goods().size();
                c.set("#TierRow" + i + ".Visible", exists);
                if (!exists) continue;
                boolean has = i < tier, now = i == tier - 1, next = i == tier;
                c.set("#TierItem" + i + ".ItemId", p.goods().get(i).item());
                c.set("#Tier" + i + ".TextSpans", Message.raw(p.goods().get(i).name()).color(now ? "#d8b66b" : has ? "#f0e8d5" : next ? "#b5c3b8" : "#6f7f78"));
                c.set("#TierNote" + i + ".TextSpans", Message.raw(now ? "Mining now" : has ? "Unlocked" : "Level " + r.milestones()[i - 1]).color(now ? "#d8b66b" : has ? "#8fa39a" : next ? "#b5c3b8" : "#6f7f78"));
            }
            c.set("#Quirk.TextSpans", Message.raw(p.quirk()));
            c.set("#Rate.TextSpans", Message.raw(ProductionMath.format(perMinute)));
            c.set("#RateUnit.TextSpans", Message.raw(p.unit() + " each minute"));
            String status = station.golden > 0 ? "Golden moment! Touch the station before it fades."
                : s.frenzySeconds > 0 ? "Frenzy: seven times the output for " + (int) Math.ceil(s.frenzySeconds) + " seconds."
                : s.veinLeft > 0 ? "Vein struck: " + s.veinLeft + " rich loads still to come."
                : s.full ? "The stockpile is full. Collect it to start work again."
                : !station.running ? "Resting while its owner is away." : "";
            c.set("#Status.TextSpans", Message.raw(status));
            int nextAt = ProductionMath.nextMilestone(r, s.level), from = ProductionMath.previousMilestone(r, s.level);
            if (nextAt > 0) {
                c.set("#MilestoneLeft.TextSpans", Message.raw("Next milestone: level " + nextAt));
                c.set("#MilestoneRight.TextSpans", Message.raw(p.good(tier + 1).name() + " unlocks and output doubles"));
                c.setObject("#MilestoneFill.Anchor", UiAnchors.size((int) Math.round(BAR * Math.clamp((s.level - from) / (double) (nextAt - from), 0, 1)), 16));
            } else {
                c.set("#MilestoneLeft.TextSpans", Message.raw("Every tier is unlocked"));
                c.set("#MilestoneRight.TextSpans", Message.raw(""));
                c.setObject("#MilestoneFill.Anchor", UiAnchors.size(BAR, 16));
            }
            c.set("#StockState.TextSpans", Message.raw(s.full ? "Full" : station.running ? "Full in " + ProductionMath.duration(ProductionMath.secondsToFull(r, p, s, mods)) : "Resting"));
            c.setObject("#StockFill.Anchor", UiAnchors.size((int) Math.round(BAR * fill), 24));
            c.set("#StockText.TextSpans", Message.raw(ProductionMath.format(s.stockpile) + " of " + ProductionMath.format(cap)
                + "      A fuller cart pays more: +" + Math.round(r.fullCartBonus() * fill * 100) + "% now, +" + Math.round(r.fullCartBonus() * 100) + "% when full"));
            int streak;
            var estate = runtime.plot(station.row.plot);
            synchronized (estate) { streak = estate.plot().streak; }
            stat(c, 0, good, s.bestHaul > 0 ? ProductionMath.format(s.bestHaul) : "None yet", "Best haul");
            stat(c, 1, "Deco_Lantern", s.finds.size() + " of " + p.finds().size(), "Rare finds");
            stat(c, 2, "Ingredient_Bar_Gold", streak > 0 ? "Day " + streak : "No streak",
                "Daily streak +" + Math.round(r.streakStep() * Math.min(streak, r.streakDays()) * 100) + "%");
            double one = ProductionMath.levelCost(r, s.level);
            int max = ProductionMath.affordableLevels(r, s.level, s.purse);
            var after = s.copy();
            after.level++;
            c.set("#UpgradeTitle.TextSpans", Message.raw("Level " + s.level + " to " + (s.level + 1)));
            c.set("#UpgradeGain.TextSpans", Message.raw("+" + ProductionMath.format(Math.max(1, ProductionMath.perMinute(r, p, after, mods) - perMinute)) + " " + p.unit() + " each minute"));
            c.set("#Buy1.Text", "1 level");
            c.set("#Cost1.TextSpans", Message.raw(ProductionMath.format(one) + " " + p.unit()));
            c.set("#Buy1.Disabled", !manages || max < 1);
            c.set("#Buy10.Text", "10 levels");
            c.set("#Cost10.TextSpans", Message.raw(ProductionMath.format(ProductionMath.levelsCost(r, s.level, 10)) + " " + p.unit()));
            c.set("#Buy10.Disabled", !manages || max < 10);
            c.set("#BuyMax.Text", max > 1 ? "Max: " + max + " levels" : "Max");
            c.set("#CostMax.TextSpans", Message.raw(max > 1 ? ProductionMath.format(ProductionMath.levelsCost(r, s.level, max)) + " " + p.unit() : "All you can afford"));
            c.set("#BuyMax.Disabled", !manages || max < 2);
        }
    }

    /** The right side for the current tab. Sets its title and note and returns its rows. */
    private List<Row> rows(UICommandBuilder c) {
        var r = runtime.rules;
        Profession p = runtime.profession(station);
        var rows = new ArrayList<Row>();
        String title, note;
        synchronized (station) {
            StationProgress s = station.row.progress;
            int owed = manages ? ProductionMath.talentPicksOwed(r, s) : 0;
            if (owed > 0 && firstBuild) tab = "Talents";
            firstBuild = false;
            if (owed > 0 && tab.equals("Talents")) {
                title = "Talents";
                note = owed == 1 ? "A talent card is waiting for you." : owed + " talent cards are waiting for you.";
                rows.add(new Row("Choose a talent", "A milestone was reached. Pick one of three cards.", "Choose", "Talent", true).icon("Ingredient_Bar_Gold"));
                for (String id : s.talents) { var card = p.card(id); if (card != null) rows.add(Row.plain(card.name() + "   " + pathName(card.path()), card.text()).icon(pathItem(card.path()))); }
            } else if (tab.equals("Talents")) {
                title = "Talents";
                note = "Bounty " + Professions.held(p, s, "bounty") + ", Fortune " + Professions.held(p, s, "fortune") + ", Vigor " + Professions.held(p, s, "vigor")
                    + ". Three cards of one path give its set bonus.";
                for (String id : s.talents) { var card = p.card(id); if (card != null) rows.add(Row.plain(card.name() + "   " + pathName(card.path()), card.text()).icon(pathItem(card.path()))); }
                if (s.talents.isEmpty()) rows.add(Row.plain("No talents yet", "The first card is dealt at level " + r.milestones()[0] + "."));
                else { double cost = runtime.reshuffleCost(station); rows.add(new Row("Reshuffle", "Hand every card back and choose again.", ProductionMath.format(cost), "Reshuffle", s.purse >= cost).icon("Rail_Kart")); }
            } else if (tab.equals("Album")) {
                title = "Album   " + s.finds.size() + " of " + p.finds().size();
                note = "Rare finds turn up now and then and come out when you collect. Each one adds 1% output for good.";
                for (var find : p.finds()) rows.add(s.finds.contains(find.id()) ? Row.plain(find.name(), find.text()).icon(find.item()) : Row.plain("Not found yet", "Keep the mine working."));
            } else if (tab.equals("Orders")) {
                var plot = runtime.plot(station.row.plot);
                synchronized (plot) {
                    var estate = plot.plot();
                    long open = estate.orders.stream().filter(order -> !order.paid).count();
                    title = "Today's orders";
                    note = "Small jobs for today. Each one pays Seals, which you spend on the Estate tab. New orders come every day.";
                    for (var order : estate.orders) rows.add(Row.plain((order.paid ? "Done: " : "") + PlotProduction.orderText(order, p.unit()),
                        ProductionMath.format(Math.min(order.done, order.goal)) + " of " + ProductionMath.format(order.goal) + (order.paid ? "   Paid " : "   Pays ") + order.seals + (order.seals == 1 ? " Seal" : " Seals"))
                        .icon(switch (order.kind) { case "collect" -> p.good(ProductionMath.tier(r, s.level)).item(); case "golden" -> "Ingredient_Bar_Gold"; case "levels" -> p.upgradeItem(ProductionMath.tier(r, s.level)); default -> "Tool_Pickaxe_Copper"; }));
                    if (estate.orders.isEmpty()) rows.add(Row.plain("No orders today", "New orders come tomorrow."));
                    else if (open == 0) rows.add(Row.plain("All done for today", "Come back tomorrow for new orders."));
                    rows.add(Row.plain("Hand work energy", estate.energyLeft(r, ProductionRuntime.today()) + " left today. Streak: day " + estate.streak + ".").icon("Tool_Pickaxe_Copper"));
                }
            } else if (tab.equals("Estate")) {
                var plot = runtime.plot(station.row.plot);
                synchronized (plot) {
                    var estate = plot.plot();
                    title = "Estate   " + estate.seals + (estate.seals == 1 ? " Seal to spend" : " Seals to spend");
                    note = "Lasting upgrades for every station on your plot, bought with Seals. Earn Seals from orders, milestones and Renown.";
                    for (int i = 0; i < 3; i++) {
                        int rank = estate.estate[i];
                        boolean maxed = rank >= PlotProduction.ESTATE_RANKS;
                        int cost = PlotProduction.estateCost(rank);
                        rows.add(new Row(PATH_NAMES[i] + "   rank " + rank + " of " + PlotProduction.ESTATE_RANKS, ESTATE_TEXT[i], maxed ? "Max" : cost + (cost == 1 ? " Seal" : " Seals"), "Estate:" + i, !maxed && estate.seals >= cost).icon(PATH_ITEMS[i]));
                    }
                }
                if (ProductionMath.canRebuild(r, s)) rows.add(new Row("Rebuild for Renown", "Back to level 1 for a star worth +" + Math.round(r.renownBonus() * 100)
                    + "% output for good. Tools, talents and the album stay.", "Rebuild", "Rebuild", true).icon("Ingredient_Bar_Gold"));
                else rows.add(Row.plain("Renown", "From level " + r.renownLevel() + " the station can be rebuilt from level 1 for a lasting star.").icon("Ingredient_Bar_Gold"));
            } else {
                title = "Tools";
                note = "Four tools, ten ranks each.";
                for (int i = 0; i < 4; i++) {
                    int rank = s.tools[i];
                    boolean maxed = rank >= r.toolRanks();
                    double cost = ProductionMath.toolCost(r, rank);
                    rows.add(new Row(p.tools().get(i) + "   rank " + rank + " of " + r.toolRanks(), TOOL_ROLES[i] + ".", maxed ? "Max" : ProductionMath.format(cost) + " " + p.unit(), "Tool:" + i, !maxed && s.purse >= cost).icon(p.toolItems().get(i)));
                }
            }
        }
        c.set("#TabTitle.TextSpans", Message.raw(title));
        c.set("#TabNote.TextSpans", Message.raw(note));
        return rows;
    }

    /** Rows the goods ladder has room for. */
    private static final int TIER_ROWS = 8;

    private static void stat(UICommandBuilder c, int slot, String item, String value, String label) {
        c.set("#StatItem" + slot + ".ItemId", item);
        c.set("#StatValue" + slot + ".TextSpans", Message.raw(value));
        c.set("#StatLabel" + slot + ".TextSpans", Message.raw(label));
    }

    private static String pathItem(String path) { return PATH_ITEMS[Math.max(0, Profession.PATHS.indexOf(path))]; }

    private static String pathName(String path) { return PATH_NAMES[Math.max(0, Profession.PATHS.indexOf(path))]; }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.action == null || isDismissed()) return;
        var world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            String action = data.action;
            if (action.equals("Close")) { close(); return; }
            if (action.startsWith("Tab")) { tab = action.substring(3); rebuild(); return; }
            if (!manages) return;
            if (action.startsWith("Buy")) {
                int count = action.equals("Buy1") ? 1 : action.equals("Buy10") ? 10 : 10_000;
                int[] bought = runtime.upgrade(station, count);
                if (bought[0] > 0) {
                    ProductionActions.note(playerRef, Math.min(ProductionActions.NOTES, 3 + bought[0]));
                    if (bought[1] > 0) {
                        milestone(world);
                        if (ProductionTalentPage.owed(runtime, station)) { ProductionTalentPage.open(runtime, ref, store, playerRef, station); return; }
                    }
                }
            } else if (action.startsWith("Tool:")) {
                if (runtime.tool(station, Integer.parseInt(action.substring(5)))) ProductionActions.note(playerRef, 8);
            } else if (action.equals("Talent")) {
                if (ProductionTalentPage.owed(runtime, station)) { ProductionTalentPage.open(runtime, ref, store, playerRef, station); return; }
            } else if (action.equals("Reshuffle")) {
                runtime.reshuffle(station);
            } else if (action.startsWith("Estate:")) {
                if (runtime.estate(station.row.plot, Integer.parseInt(action.substring(7)))) ProductionActions.note(playerRef, 10);
            } else if (action.equals("Rebuild")) {
                if (runtime.rebuild(station)) {
                    ProductionActions.sound(playerRef, "SFX_Discovery_Z1_Medium");
                    ProductionHud.banner(playerRef, "RENOWN", "Rebuilt from level 1 with a new star", 4);
                    runtime.visuals.showTier(world, station, playerRef.getUuid());
                }
            }
            rebuild();
        });
    }

    /** A new tier: the banner, the fanfare, and the station itself rebuilt in its richer look. */
    private void milestone(com.hypixel.hytale.server.core.universe.world.World world) {
        Profession p = runtime.profession(station);
        int tier;
        synchronized (station) { tier = ProductionMath.tier(runtime.rules, station.row.progress.level); }
        ProductionActions.sound(playerRef, "SFX_Discovery_Z1_Medium");
        ProductionHud.banner(playerRef, p.station().toUpperCase() + " REACHED TIER " + tier, p.good(tier).name() + " unlocked. Output doubled", 5);
        runtime.visuals.showTier(world, station, playerRef.getUuid());
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
