package com.hexvane.eterniamod.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The page's commands for an empty, a typical and an overfull player. Every selector must reach the document, every
 * text key must exist in eternia_stats.lang, and the same commands are written to build/stats-ui/*.json for ui_render.
 */
class StatsPageViewTest {
    private static final Path UI = Path.of("src/main/resources/Common/UI/Custom/EterniaMod/StatsPage.ui");
    private static final Path LANG = Path.of("src/main/resources/Server/Languages/en-US/eternia_stats.lang");
    private static final Instant DAY = Instant.parse("2026-11-03T18:20:00Z");
    private final UUID ada = new UUID(0, 1);
    private final BossCatalog catalog = BossCatalog.load(getClass().getClassLoader());

    static StatsService.PlayerStats stats(UUID id, String name, long play, Map<String, Long> counters, Map<String, Long> kills,
                                          Map<String, Long> deaths, List<StatsService.BossRecord> bosses, List<StatsService.WorldStats> worlds) {
        var all = new TreeMap<String, Long>(); for (String f : StatsFields.COUNTERS) all.put(f, counters.getOrDefault(f, 0L));
        return new StatsService.PlayerStats(id, name, play, all, kills, deaths, Map.of(), bosses, worlds);
    }

    private StatsView.Model empty() {
        return new StatsView.Model(StatsView.State.READY, true, "Ada", stats(ada, "Ada", 30, Map.of(), Map.of(), Map.of(), List.of(), List.of()), List.of());
    }

    private StatsView.Model typical() {
        var s = stats(ada, "Ada", 3 * 3600 + 25 * 60,
            Map.of(StatsFields.CHESTS, 37L, StatsFields.LOOT_CHESTS, 12L, StatsFields.BLOCKS, 18_452L, StatsFields.KILLS, 146L, StatsFields.DEATHS, 9L, StatsFields.BOSSES, 2L),
            Map.of("Goblin_Scrapper", 58L, "Trork_Warrior", 41L, "Skeleton_Soldier", 30L, "Spider", 17L),
            Map.of("Fall", 5L, "Physical", 4L),
            List.of(new StatsService.BossRecord("gobliterator", "normal", 2, DAY)),
            List.of(new StatsService.WorldStats("hub", Map.of(StatsFields.BLOCKS, 2_100L)), new StatsService.WorldStats("adventure", Map.of(StatsFields.BLOCKS, 16_352L))));
        return new StatsView.Model(StatsView.State.READY, true, "Ada", s,
            List.of(new StatsService.First("gobliterator", "normal", ada, "Ada", "adventure", DAY)));
    }

    private StatsView.Model overfull() {
        var kills = new LinkedHashMap<String, Long>();
        String[] roles = {"Goblin_Scrapper", "Trork_Warrior", "Skeleton_Soldier", "Spider", "Feran_Longtooth_Hunter", "Scarak_Defender", "Outlander_Berserker", "Void_Crawler", "Wolf_Black", "Bear_Grizzly"};
        for (int i = 0; i < roles.length; i++) kills.put(roles[i], 9_876_543L - i * 812_345L);
        var bosses = new ArrayList<StatsService.BossRecord>(); int n = 0;
        for (var boss : catalog.bosses()) for (String d : new TreeSet<>(boss.roles().values())) bosses.add(new StatsService.BossRecord(boss.id(), d, 1234 - n, DAY.plusSeconds(86400L * n++)));
        var s = stats(ada, "Xx_LongestNameHere", 412L * 86400 + 23 * 3600,
            Map.of(StatsFields.CHESTS, 1_234_567L, StatsFields.LOOT_CHESTS, 456_789L, StatsFields.BLOCKS, 98_765_432_100L, StatsFields.KILLS, 54_321_987L, StatsFields.DEATHS, 99_999L, StatsFields.BOSSES, 7_421L),
            kills, Map.of("Out_Of_World", 50_000L, "Fall", 49_999L), bosses,
            List.of(new StatsService.WorldStats("instance-goblin-lair-7f3a9c", Map.of(StatsFields.BLOCKS, 98_000_000_000L)), new StatsService.WorldStats("hub", Map.of(StatsFields.BLOCKS, 765_432_100L))));
        var firsts = new ArrayList<StatsService.First>(); int i = 0;
        for (var b : bosses) firsts.add(new StatsService.First(b.boss(), b.difficulty(), ada, "Xx_LongestNameHere", "dungeon", DAY.plusSeconds(3600L * i++)));
        return new StatsView.Model(StatsView.State.READY, false, "Xx_LongestNameHere", s, firsts);
    }

    private static String worldName(String id) { return id.equals("hub") ? "Eternia Hub" : com.hexvane.eterniamod.ui.UiPresentation.friendlyId(id); }

    @Test void everyStateReachesTheDocumentAndTheLangFile() throws IOException {
        String ui = Files.readString(UI, StandardCharsets.UTF_8); var lang = langKeys();
        var ids = new HashSet<String>(); var m = Pattern.compile("#([A-Za-z0-9]+)").matcher(ui); while (m.find()) ids.add(m.group(1));
        var states = new LinkedHashMap<String, StatsView.Model>();
        states.put("loading", new StatsView.Model(StatsView.State.LOADING, true, "Ada", null, List.of()));
        states.put("empty", empty()); states.put("typical", typical()); states.put("overfull", overfull());
        states.put("unavailable", new StatsView.Model(StatsView.State.UNAVAILABLE, false, "Bo", null, List.of()));
        Files.createDirectories(Path.of("build/stats-ui"));
        for (var state : states.entrySet()) {
            var sets = StatsView.commands(state.getValue(), catalog, StatsPageViewTest::worldName);
            for (var set : sets) {
                for (String part : set.selector().split(" ")) {
                    String id = part.substring(1).replaceAll("[.\\[].*", "");
                    assertTrue(ids.contains(id), state.getKey() + ": " + set.selector() + " has no element in StatsPage.ui");
                }
                if (set.value() instanceof StatsView.Text text) assertTrue(lang.contains(text.key().substring(StatsView.LANG.length())), "missing lang key " + text.key());
            }
            Files.writeString(Path.of("build/stats-ui/" + state.getKey() + ".json"), json(sets), StandardCharsets.UTF_8);
        }
    }

    @Test void emptyStateInvitesTheNewPlayer() {
        var sets = StatsView.commands(empty(), catalog, StatsPageViewTest::worldName);
        assertEquals(new StatsView.Text("eternia_stats.page.newPlayer", Map.of()), value(sets, "#Notice.TextSpans"));
        assertEquals(true, value(sets, "#FoesEmpty.Visible")); assertEquals(false, value(sets, "#Foe0.Visible"));
        assertEquals(new StatsView.Raw("0"), value(sets, "#TileChests #TileValue.TextSpans"));
    }

    @Test void typicalStateShowsFoesInOrderAndTheFirst() {
        var sets = StatsView.commands(typical(), catalog, StatsPageViewTest::worldName);
        assertEquals(new StatsView.Raw("Goblin Scrapper"), value(sets, "#Foe0 #FoeName.TextSpans"));
        assertEquals(new StatsView.Raw("58"), value(sets, "#Foe0 #FoeCount.TextSpans"));
        assertEquals(false, value(sets, "#Foe4.Visible"));
        assertEquals(new StatsView.Raw("The Gobliterator"), value(sets, "#Boss0 #BossName.TextSpans"));
        assertEquals(true, value(sets, "#First0.Visible"));
        assertEquals(new StatsView.Text("eternia_stats.tile.worldsNote", Map.of("world", "Adventure")), value(sets, "#TileWorlds #TileNote.TextSpans"));
        assertEquals(new StatsView.Raw("3h 25m"), value(sets, "#PlaytimeValue.TextSpans"));
    }

    private static Object value(List<StatsView.Set> sets, String selector) {
        return sets.stream().filter(s -> s.selector().equals(selector)).reduce((a, b) -> b).orElseThrow(() -> new AssertionError(selector)).value();
    }

    private static Set<String> langKeys() throws IOException {
        var keys = new HashSet<String>();
        for (String line : Files.readAllLines(LANG, StandardCharsets.UTF_8)) { int eq = line.indexOf('='); if (eq > 0) keys.add(line.substring(0, eq).strip()); }
        return keys;
    }

    /** The commands as the page sends them, in the shape ui_render reads: Append the document, then every Set. */
    static String json(List<StatsView.Set> sets) {
        var out = new StringBuilder("[\n  {\"type\":\"Append\",\"text\":\"").append(StatsView.DOCUMENT).append("\"}");
        for (var set : sets) {
            out.append(",\n  {\"type\":\"Set\",\"selector\":").append(quote(set.selector())).append(",\"data\":");
            switch (set.value()) {
                case StatsView.Text t -> {
                    out.append("{\"MessageId\":").append(quote(t.key())).append(",\"Params\":{");
                    var first = true; for (var p : t.params().entrySet()) { if (!first) out.append(','); first = false; out.append(quote(p.getKey())).append(':').append(quote(p.getValue())); }
                    out.append("}}");
                }
                case StatsView.Raw r -> out.append("{\"RawText\":").append(quote(r.text())).append('}');
                case StatsView.Icon i -> out.append("{\"TexturePath\":").append(quote(i.path())).append('}');
                case Boolean b -> out.append(b);
                default -> throw new IllegalStateException();
            }
            out.append('}');
        }
        return out.append("\n]\n").toString();
    }

    private static String quote(String s) { return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'; }
}
