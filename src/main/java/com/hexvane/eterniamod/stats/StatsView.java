package com.hexvane.eterniamod.stats;

import java.util.*;
import java.util.function.Function;

/**
 * Everything the stats page sets, as plain values. The page turns these into UI commands; tests and the page
 * pictures use the very same list, so a picture shows what a player is sent.
 */
public final class StatsView {
    private StatsView() {}
    public static final String DOCUMENT = "EterniaMod/StatsPage.ui";
    static final String LANG = "eternia_stats.";
    static final int FOES = 8, BOSSES = 6, FIRSTS = 4;

    /** A translated line from eternia_stats.lang with its parameters, in order. */
    public record Text(String key, Map<String, String> params) {
        public Text { params = Collections.unmodifiableMap(new LinkedHashMap<>(params)); }
        static Text of(String key, String... pairs) {
            var map = new LinkedHashMap<String, String>();
            for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
            return new Text(LANG + key, map);
        }
    }
    /** Text that is already in its final words (a name, a number). */
    public record Raw(String text) {}
    /** A picture path relative to Common/UI/Custom. */
    public record Icon(String path) {}
    /** One Set: {@code value} is a {@link Text}, {@link Raw}, {@link Icon} or Boolean. */
    public record Set(String selector, Object value) {}

    public enum State { LOADING, READY, UNAVAILABLE }

    /** What the page shows. {@code stats} is null while loading or when reading failed. */
    public record Model(State state, boolean self, String name, StatsService.PlayerStats stats, List<StatsService.First> serverFirsts) {
        public Model { serverFirsts = serverFirsts == null ? List.of() : List.copyOf(serverFirsts); }
    }

    public static List<Set> commands(Model model, BossCatalog catalog, Function<String, String> worldName) {
        var out = new ArrayList<Set>();
        String name = model.name() == null || model.name().isBlank() ? StatsText.unknownPlayer() : model.name();
        text(out, "#Kicker", Text.of("page.kicker"));
        // Your own page: "Your Journey" over your name. Someone else's: their name over "Fellow adventurer".
        if (model.self()) { text(out, "#PlayerTitle", Text.of("page.yourTitle")); out.add(new Set("#Subtitle.TextSpans", new Raw(name))); }
        else { out.add(new Set("#PlayerTitle.TextSpans", new Raw(name))); text(out, "#Subtitle", Text.of("page.otherKicker")); }
        text(out, "#Intro", model.self() ? Text.of("page.yourIntro") : Text.of("page.otherIntro", "player", name));
        text(out, "#PlaytimeLabel", Text.of("page.playtime"));
        text(out, "#FirstsTitle", Text.of("page.firsts"));
        text(out, "#HighlightsTitle", Text.of("page.highlights"));
        text(out, "#FoesTitle", Text.of("page.foes"));
        text(out, "#BossesTitle", Text.of("page.bosses"));
        var stats = model.stats();
        boolean ready = model.state() == State.READY && stats != null;
        out.add(new Set("#PlaytimeValue.TextSpans", !ready ? new Raw("") : stats.playSeconds() < 60 ? Text.of("page.justArrived") : new Raw(StatsText.duration(stats.playSeconds()))));

        // Highlights
        long chests = ready ? stats.get(StatsFields.CHESTS) : 0, loot = ready ? stats.get(StatsFields.LOOT_CHESTS) : 0;
        tile(out, "#TileChests", "StatsChest", "tile.chests", ready, chests,
            loot > 0 ? Text.of("tile.chestsNote", "count", StatsText.number(loot)) : Text.of("tile.chestsNoteNone"));
        tile(out, "#TileBlocks", "StatsTravel", "tile.blocks", ready, ready ? stats.get(StatsFields.BLOCKS) : 0, Text.of("tile.blocksNote"));
        var worlds = ready ? stats.worlds() : List.<StatsService.WorldStats>of();
        var furthest = worlds.stream().filter(w -> w.get(StatsFields.BLOCKS) > 0).max(Comparator.comparingLong(w -> w.get(StatsFields.BLOCKS)));
        tile(out, "#TileWorlds", "StatsWorld", "tile.worlds", ready, worlds.size(),
            furthest.map(w -> Text.of("tile.worldsNote", "world", worldName.apply(w.world()))).orElse(Text.of("tile.worldsNoteNone")));
        var topFoe = ready ? top(stats.kills()) : Optional.<Map.Entry<String, Long>>empty();
        tile(out, "#TileKills", "StatsCreature", "tile.kills", ready, ready ? stats.get(StatsFields.KILLS) : 0,
            topFoe.map(e -> Text.of("tile.killsNote", "creature", StatsText.friendly(e.getKey()))).orElse(Text.of("tile.killsNoteNone")));
        long distinctBosses = ready ? stats.bosses().stream().map(StatsService.BossRecord::boss).distinct().count() : 0;
        tile(out, "#TileBosses", "StatsBoss", "tile.bosses", ready, ready ? stats.get(StatsFields.BOSSES) : 0,
            distinctBosses == 0 ? Text.of("tile.bossesNoteNone") : distinctBosses == 1 ? Text.of("tile.bossesNoteOne") : Text.of("tile.bossesNote", "count", StatsText.number(distinctBosses)));
        var topCause = ready ? top(stats.deaths()) : Optional.<Map.Entry<String, Long>>empty();
        tile(out, "#TileDeaths", "StatsFallen", "tile.deaths", ready, ready ? stats.get(StatsFields.DEATHS) : 0,
            topCause.map(e -> Text.of("tile.deathsNote", "cause", StatsText.causePhrase(e.getKey()))).orElse(Text.of("tile.deathsNoteNone")));

        // Favourite foes
        var foes = ready ? stats.kills().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(FOES).toList() : List.<Map.Entry<String, Long>>of();
        for (int i = 0; i < FOES; i++) {
            String row = "#Foe" + i; boolean visible = i < foes.size();
            out.add(new Set(row + ".Visible", visible));
            if (!visible) continue;
            out.add(new Set(row + " #FoeRank.TextSpans", new Raw(Integer.toString(i + 1))));
            out.add(new Set(row + " #FoeName.TextSpans", new Raw(StatsText.friendly(foes.get(i).getKey()))));
            out.add(new Set(row + " #FoeCount.TextSpans", new Raw(StatsText.number(foes.get(i).getValue()))));
        }
        out.add(new Set("#FoesEmpty.Visible", ready && foes.isEmpty()));
        if (ready && foes.isEmpty()) text(out, "#FoesEmpty", Text.of("page.foesEmpty"));

        // Boss victories
        var bosses = ready ? stats.bosses() : List.<StatsService.BossRecord>of();
        for (int i = 0; i < BOSSES; i++) {
            String row = "#Boss" + i; boolean visible = i < bosses.size();
            out.add(new Set(row + ".Visible", visible));
            if (!visible) continue;
            var boss = bosses.get(i);
            out.add(new Set(row + " #BossName.TextSpans", new Raw(bossName(catalog, boss.boss()))));
            out.add(new Set(row + " #BossDate.TextSpans", boss.first() == null ? new Raw("") : Text.of("page.bossFirst", "date", StatsText.date(boss.first()))));
            out.add(new Set(row + " #BossLevel.TextSpans", new Raw(StatsText.difficulty(boss.difficulty()))));
            out.add(new Set(row + " #BossCount.TextSpans", Text.of("page.bossCount", "count", StatsText.number(boss.victories()))));
        }
        out.add(new Set("#BossesEmpty.Visible", ready && bosses.isEmpty()));
        if (ready && bosses.isEmpty()) text(out, "#BossesEmpty", Text.of("page.bossesEmpty"));

        // Server firsts this player holds
        var held = ready ? model.serverFirsts().stream().filter(f -> f.player().equals(stats.id())).toList() : List.<StatsService.First>of();
        var firsts = held.stream().limit(FIRSTS).toList();
        for (int i = 0; i < FIRSTS; i++) {
            String row = "#First" + i; boolean visible = i < firsts.size();
            out.add(new Set(row + ".Visible", visible));
            if (!visible) continue;
            var first = firsts.get(i); String boss = bossName(catalog, first.boss());
            out.add(new Set(row + " #FirstText.TextSpans", BossCatalog.NORMAL.equals(first.difficulty())
                ? Text.of("page.firstLine", "boss", boss) : Text.of("page.firstLineDifficulty", "boss", boss, "difficulty", StatsText.difficulty(first.difficulty()))));
            out.add(new Set(row + " #FirstDate.TextSpans", new Raw(StatsText.date(first.at()))));
        }
        out.add(new Set("#FirstsEmpty.Visible", ready && (firsts.isEmpty() || held.size() > FIRSTS)));
        if (ready && firsts.isEmpty()) text(out, "#FirstsEmpty", Text.of(model.self() ? "page.firstsEmpty" : "page.firstsEmptyOther"));
        else if (held.size() > FIRSTS) text(out, "#FirstsEmpty", Text.of("page.firstsMore", "count", StatsText.number(held.size() - FIRSTS)));

        text(out, "#Notice", switch (model.state()) {
            case LOADING -> Text.of("page.loading");
            case UNAVAILABLE -> Text.of("page.unavailable");
            case READY -> stats != null && stats.isEmpty() && model.self() ? Text.of("page.newPlayer") : null;
        });
        return List.copyOf(out);
    }

    private static void text(List<Set> out, String selector, Text text) { out.add(new Set(selector + ".TextSpans", text == null ? new Raw("") : text)); }

    private static void tile(List<Set> out, String tile, String icon, String label, boolean ready, long value, Text note) {
        out.add(new Set(tile + " #TileIcon.Background", new Icon("EterniaMod/Icons/" + icon + ".png")));
        out.add(new Set(tile + " #TileLabel.TextSpans", Text.of(label)));
        out.add(new Set(tile + " #TileValue.TextSpans", new Raw(ready ? StatsText.number(value) : "")));
        out.add(new Set(tile + " #TileNote.TextSpans", ready ? note : new Raw("")));
    }

    private static Optional<Map.Entry<String, Long>> top(Map<String, Long> counts) {
        return counts.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())));
    }

    static String bossName(BossCatalog catalog, String id) { return catalog.boss(id).map(BossCatalog.Boss::name).orElse(StatsText.friendly(id)); }
}
