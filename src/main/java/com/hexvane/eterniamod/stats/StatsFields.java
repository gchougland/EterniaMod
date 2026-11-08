package com.hexvane.eterniamod.stats;

import java.util.List;
import java.util.Locale;

/** Field names of the saved stat records. Counters are whole numbers; firsts are ISO instants. */
public final class StatsFields {
    private StatsFields() {}
    public static final String CHESTS = "chests";
    public static final String LOOT_CHESTS = "lootChests";
    public static final String BLOCKS = "blocks";
    public static final String KILLS = "kills";
    public static final String DEATHS = "deaths";
    public static final String BOSSES = "bosses";
    /** Not a stored counter: read from the account record, where logout already adds each session. */
    public static final String PLAYTIME = "playtime";
    /** Counters that leaderboards may rank. */
    public static final List<String> RANKED = List.of(CHESTS, LOOT_CHESTS, BLOCKS, KILLS, DEATHS, BOSSES, PLAYTIME);
    public static final List<String> COUNTERS = List.of(CHESTS, LOOT_CHESTS, BLOCKS, KILLS, DEATHS, BOSSES);
    /** {@code kill.<npc role>}: creatures defeated by kind. */
    public static final String KILL_PREFIX = "kill.";
    /** {@code death.<damage cause>}: how a player fell. */
    public static final String DEATH_PREFIX = "death.";
    /** {@code slainBy.<npc role>} or {@code slainBy.player}: who landed the final blow. */
    public static final String SLAIN_BY_PREFIX = "slainBy.";
    /** {@code boss.<boss id>.<difficulty>}: boss victories. */
    public static final String BOSS_PREFIX = "boss.";
    /** {@code first.<boss id>.<difficulty>}: the player's first victory over that boss. */
    public static final String FIRST_PREFIX = "first.";

    /** Keeps a game id usable inside a field name: letters, digits, underscores and dashes, at most 120 characters. */
    public static String part(String id) {
        if (id == null || id.isBlank()) return "unknown";
        String clean = id.strip().replaceAll("[^A-Za-z0-9_\\-]", "_");
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    public static String kill(String role) { return KILL_PREFIX + part(role); }
    public static String death(String cause) { return DEATH_PREFIX + part(cause); }
    public static String slainBy(String killer) { return SLAIN_BY_PREFIX + part(killer); }
    public static String boss(String boss, String difficulty) { return BOSS_PREFIX + bossKey(boss, difficulty); }
    public static String first(String boss, String difficulty) { return FIRST_PREFIX + bossKey(boss, difficulty); }
    public static String bossKey(String boss, String difficulty) {
        return part(boss).toLowerCase(Locale.ROOT) + "." + part(difficulty).toLowerCase(Locale.ROOT);
    }
    public static boolean ranked(String stat) { return RANKED.contains(stat); }
}
