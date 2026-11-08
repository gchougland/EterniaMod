package com.hexvane.eterniamod.gatehouse;

import com.google.gson.*;
import com.hexvane.eterniamod.domain.SeasonService;
import com.hexvane.eterniamod.domain.SeasonService.*;
import com.hexvane.eterniamod.setup.HubNpcIdentity;
import com.hexvane.eterniamod.ui.CurrencyUi;
import com.hexvane.eterniamod.ui.JournalChrome;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Season data for Gatehouse tests, and the commands the desk home sends, written out for ui_render. */
final class GatehouseFixtures {
    private GatehouseFixtures() {}

    static final Instant NOW = Instant.parse("2026-11-07T19:20:00Z"); // a Saturday evening
    static final Function<String, String> NAMES = id -> switch (id) {
        case "eternia:prop/aqua_lamp" -> "Aqua Lamp";
        case "eternia:title/wayfarer" -> "Wayfarer";
        case "eternia:prop/potion_shelf" -> "Potion Shelf";
        case "eternia:prop/cacti" -> "Potted Cacti";
        case "eternia:outfit/prowl_shirt" -> "Prowl Shirt";
        case "eternia:pet/corn_buddy" -> "Corn Buddy";
        default -> id.substring(id.lastIndexOf('/') + 1);
    };

    static List<Long> levels(int count, long step) { var list = new ArrayList<Long>(); for (int i = 1; i <= count; i++) list.add(i * step); return list; }

    static Quest quest(String id, ActivityKind kind, Cadence cadence, String name, String goal, long required, long xp, long coins) {
        return new Quest("eternia:quest/" + id, kind, ObjectiveKind.COUNT, Set.of(), required, xp, coins, cadence, name, goal);
    }

    static Definition pass(String id, String name, int levels) {
        return new Definition(id, name, levels(levels, 2000), List.of(
            new Reward(Track.FREE, 1, 0, "eternia:prop/aqua_lamp", com.hexvane.eterniamod.domain.OwnershipService.Kind.QUANTITY, 1),
            new Reward(Track.FREE, 5, 0, "eternia:title/wayfarer", com.hexvane.eterniamod.domain.OwnershipService.Kind.UNLOCK, 1),
            new Reward(Track.PAID, 1, 0, "eternia:prop/cacti", com.hexvane.eterniamod.domain.OwnershipService.Kind.QUANTITY, 1),
            new Reward(Track.PAID, 10, 0, "eternia:outfit/prowl_shirt", com.hexvane.eterniamod.domain.OwnershipService.Kind.UNLOCK, 1),
            new Reward(Track.FREE, 15, 0, "eternia:prop/potion_shelf", com.hexvane.eterniamod.domain.OwnershipService.Kind.QUANTITY, 1)),
            List.of(quest("daily_mine", ActivityKind.MINE, Cadence.DAILY, "A miner's morning", "Mine 40 ore", 40, 250, 100),
                quest("daily_hunt", ActivityKind.KILL, Cadence.DAILY, "On patrol", "Defeat 12 creatures", 12, 250, 100),
                quest("weekly_harvest", ActivityKind.HARVEST, Cadence.WEEKLY, "A fruitful week", "Harvest 300 crops", 300, 1500, 500)));
    }

    static List<QuestProgress> questsOf(Definition pass, long... counts) {
        var list = new ArrayList<QuestProgress>();
        for (int i = 0; i < pass.quests().size(); i++) {
            var q = pass.quests().get(i); long count = i < counts.length ? counts[i] : 0;
            list.add(new QuestProgress(q, count, count >= q.required()));
        }
        return list;
    }

    // ---- the states the renders show ----------------------------------------------------------------------------
    static final Definition FOUNDATIONS = pass("eternia:foundations", "Foundations", 30);
    static final Definition TIDE = pass("eternia:tidewater", "Tidewater", 30);
    static final Definition EMBER = pass("eternia:emberfall", "Emberfall", 20);
    static final Definition LONG = pass("eternia:long", "The Long Road to Castle Aeterna and Beyond", 40);

    static GatehouseBoard empty() { return GatehouseBoard.of(List.of(), List.of(), id -> Set.of(), List.of(), NAMES, NOW); }

    static GatehouseBoard oneNotActive() {
        return GatehouseBoard.of(List.of(FOUNDATIONS), List.of(new Progress(FOUNDATIONS.id(), FOUNDATIONS.name(), 0, 60000, 0, false, false)),
            id -> Set.of(), List.of(), NAMES, NOW);
    }

    /** One pass whose premium track is not on sale yet (season sales, once merged). */
    static GatehouseBoard premiumUpcoming() {
        var b = oneNotActive();
        return new GatehouseBoard(b.greeting(), b.passes(), b.bounties(), b.premium().sale("Premium for Foundations goes on sale on Saturday, 14 November.", "Coming soon"));
    }

    static GatehouseBoard severalActive() {
        var claimed = Map.<String, Set<Reward>>of(FOUNDATIONS.id(), Set.of(FOUNDATIONS.rewards().getFirst()));
        return GatehouseBoard.of(List.of(FOUNDATIONS, TIDE, EMBER),
            List.of(new Progress(FOUNDATIONS.id(), FOUNDATIONS.name(), 13200, 60000, 6, false, false),
                new Progress(TIDE.id(), TIDE.name(), 4600, 60000, 2, true, true),
                new Progress(EMBER.id(), EMBER.name(), 0, 40000, 0, false, false)),
            id -> claimed.getOrDefault(id, Set.of()), questsOf(TIDE, 18, 12, 120), NAMES, NOW);
    }

    static GatehouseBoard overfull() {
        var many = new ArrayList<Quest>();
        String[][] daily = {{"m", "MINE", "Deep veins under the old quarry road", "Mine 80 ore from three different kinds of rock"}, {"k", "KILL", "Clear the King's Road", "Defeat 25 Trorks, goblins or Outlanders"},
            {"h", "HARVEST", "Supper for the whole town", "Harvest 150 crops"}, {"a", "ACQUIRE", "Gathering expedition", "Gather 60 resources"}};
        for (String[] d : daily) many.add(quest("d" + d[0], ActivityKind.valueOf(d[1]), Cadence.DAILY, d[2], d[3], 40, 12500, 1200));
        for (String[] d : daily) many.add(quest("w" + d[0], ActivityKind.valueOf(d[1]), Cadence.WEEKLY, d[2] + " (week)", d[3], 400, 50000, 5000));
        many.add(quest("once", ActivityKind.INTEGRATION, Cadence.ONCE, "A very long season goal name that keeps going", "Finish every bounty posted at the Gatehouse this season", 1, 100000, 10000));
        var big = new Definition(LONG.id(), LONG.name(), LONG.thresholds(), LONG.rewards(), many);
        var defs = new ArrayList<Definition>(); var progress = new ArrayList<Progress>();
        defs.add(big); progress.add(new Progress(big.id(), big.name(), 79999, 80000, 39, true, true));
        String[] names = {"Foundations", "Tidewater Festival of Lanterns and Long Summer Nights", "Emberfall", "Frostwake", "Greenwood"};
        for (int i = 0; i < names.length; i++) {
            var d = pass("eternia:p" + i, names[i], 30); defs.add(d);
            progress.add(new Progress(d.id(), d.name(), 60000L * i / 4, 60000, Math.min(30, 30 * i / 4), i % 2 == 0, false));
        }
        long[] counts = new long[many.size()]; for (int i = 0; i < counts.length; i++) counts[i] = i % 3 == 0 ? 400 : i * 7;
        return GatehouseBoard.of(defs, progress, id -> Set.of(), questsOf(big, counts), NAMES, NOW);
    }

    /** Exactly what EterniaServicesPage.build sends for a desk home with this board (wallet loaded). */
    static UICommandBuilder deskHome(GatehouseBoard board, String status, boolean busy) {
        var c = new UICommandBuilder();
        c.append("EterniaMod/ServicesPage.ui");
        c.append("#CurrencyWallet", "EterniaMod/Wallet.ui");
        CurrencyUi.wallet(c, 1250L, 700L);
        var identity = HubNpcIdentity.GATEHOUSE;
        JournalChrome.header(c, identity.characterName(), identity.profession(), "season");
        c.set("#JournalShortcut.Visible", true);
        c.set("#HintJournal.Visible", true);
        c.set("#HintCloseText.TextSpans", Message.raw("Goodbye"));
        c.set("#Return.Visible", false);
        c.set("#SideBar.Visible", false);
        c.append("#ServicesBody", GatehouseDesk.DOCUMENT);
        GatehouseDesk.render(c, board, (selector, route) -> {}, busy, status);
        return c;
    }

    /** The commands as ui_render takes them. */
    static JsonArray json(UICommandBuilder c) {
        var out = new JsonArray();
        for (var command : c.getCommands()) {
            var o = new JsonObject();
            o.addProperty("type", command.type.name());
            if (command.selector != null) o.addProperty("selector", command.selector);
            if (command.text != null) o.addProperty("text", command.text);
            if (command.data != null) o.add("data", JsonParser.parseString(command.data));
            out.add(o);
        }
        return out;
    }

    static void write(String name, UICommandBuilder c) throws Exception {
        var dir = Path.of("build/gatehouse-ui");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(json(c)));
    }
}
