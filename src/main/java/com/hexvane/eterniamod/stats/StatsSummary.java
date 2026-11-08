package com.hexvane.eterniamod.stats;

import java.util.*;

/** Plain text summaries for administrators. */
public final class StatsSummary {
    private StatsSummary() {}
    private static final Map<String, String> LABELS = Map.of(
        StatsFields.CHESTS, "Chests opened", StatsFields.LOOT_CHESTS, "Loot chests opened", StatsFields.BLOCKS, "Blocks travelled",
        StatsFields.KILLS, "Creatures defeated", StatsFields.DEATHS, "Times fallen", StatsFields.BOSSES, "Bosses defeated", StatsFields.PLAYTIME, "Time in Eternia");

    public static String label(String stat) { return LABELS.getOrDefault(stat, stat); }

    public static String value(String stat, long value) { return StatsFields.PLAYTIME.equals(stat) ? StatsText.duration(value) : StatsText.number(value); }

    public static List<String> player(StatsService.PlayerStats stats, List<StatsService.First> firsts, BossCatalog catalog) {
        var lines = new ArrayList<String>();
        lines.add("Stats for " + (stats.name().isBlank() ? stats.id().toString() : stats.name()) + " (" + stats.id() + ")");
        for (String stat : StatsFields.RANKED) lines.add("  " + label(stat) + ": " + value(stat, stats.get(stat)));
        if (!stats.kills().isEmpty()) lines.add("  Top creatures: " + top(stats.kills(), 5, StatsText::friendly));
        if (!stats.deaths().isEmpty()) lines.add("  Fallen: " + top(stats.deaths(), 5, StatsText::cause));
        if (!stats.slainBy().isEmpty()) lines.add("  Slain by: " + top(stats.slainBy(), 5, StatsText::slainBy));
        for (var boss : stats.bosses())
            lines.add("  Boss " + StatsView.bossName(catalog, boss.boss()) + " (" + StatsText.difficulty(boss.difficulty()) + "): " + StatsText.number(boss.victories())
                + (boss.first() == null ? "" : ", first " + boss.first()));
        for (var first : firsts) lines.add("  Server first: " + StatsView.bossName(catalog, first.boss()) + " (" + StatsText.difficulty(first.difficulty()) + ") at " + first.at());
        if (!stats.worlds().isEmpty()) {
            var worlds = new StringJoiner(", ");
            for (var world : stats.worlds()) worlds.add(world.world() + " " + StatsText.number(world.get(StatsFields.BLOCKS)) + " blocks");
            lines.add("  Worlds: " + worlds);
        }
        return lines;
    }

    public static List<String> server(List<StatsService.First> firsts, Map<String, List<StatsService.Ranked>> leaders, BossCatalog catalog) {
        var lines = new ArrayList<String>();
        lines.add("Server firsts: " + (firsts.isEmpty() ? "none yet" : firsts.size()));
        for (var first : firsts)
            lines.add("  " + StatsView.bossName(catalog, first.boss()) + " (" + StatsText.difficulty(first.difficulty()) + "): "
                + (first.name().isBlank() ? first.player().toString() : first.name()) + " at " + first.at() + " in " + first.world());
        lines.add("Leaders:");
        leaders.forEach((stat, ranked) -> {
            var joined = new StringJoiner(", ");
            for (var entry : ranked) joined.add(entry.rank() + ". " + (entry.name().isBlank() ? entry.player().toString() : entry.name()) + " " + value(stat, entry.value()));
            lines.add("  " + label(stat) + ": " + (ranked.isEmpty() ? "nobody yet" : joined.toString()));
        });
        return lines;
    }

    private static String top(Map<String, Long> counts, int limit, java.util.function.Function<String, String> names) {
        var joined = new StringJoiner(", ");
        counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(limit)
            .forEach(e -> joined.add(names.apply(e.getKey()) + " " + StatsText.number(e.getValue())));
        return joined.toString();
    }
}
