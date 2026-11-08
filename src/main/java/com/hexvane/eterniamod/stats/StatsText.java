package com.hexvane.eterniamod.stats;

import com.hexvane.eterniamod.ui.UiPresentation;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Friendly words and numbers for stats. Pure functions, so tests and the admin summary share them with the page. */
public final class StatsText {
    private StatsText() {}
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final Map<String, String> CAUSES = Map.of(
        "physical", "In battle", "projectile", "Arrows and missiles", "fall", "Falls", "drowning", "Drowning",
        "environment", "Fire and hazards", "out_of_world", "Into the void", "suffocation", "Suffocation", "command", "Server magic");

    /** 12,345; from a million on, 1.2 million. */
    public static String number(long value) {
        if (value < 0) value = 0;
        if (value < 1_000_000) return String.format(Locale.ENGLISH, "%,d", value);
        double millions = value / 1_000_000.0;
        if (millions < 1000) return trim(String.format(Locale.ENGLISH, "%.1f", Math.floor(millions * 10) / 10)) + " million";
        return trim(String.format(Locale.ENGLISH, "%.1f", Math.floor(millions / 100) / 10)) + " billion";
    }

    private static String trim(String decimal) { return decimal.endsWith(".0") ? decimal.substring(0, decimal.length() - 2) : decimal; }

    /** 45m, 3h 20m, 2d 4h. */
    public static String duration(long seconds) {
        if (seconds < 60) return "0m";
        long minutes = seconds / 60, hours = minutes / 60, days = hours / 24;
        if (days > 0) return String.format(Locale.ENGLISH, "%,dd %dh", days, hours % 24);
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        return minutes + "m";
    }

    /** Goblin_Scrapper reads as Goblin Scrapper. */
    public static String friendly(String id) { return UiPresentation.friendlyId(id); }

    public static String cause(String id) {
        if (id == null) return "Unknown";
        return CAUSES.getOrDefault(id.toLowerCase(Locale.ROOT), friendly(id));
    }

    private static final Map<String, String> CAUSE_PHRASES = Map.of(
        "physical", "in battle", "projectile", "to arrows and missiles", "fall", "from falls", "drowning", "by drowning",
        "environment", "to fire and hazards", "out_of_world", "into the void", "suffocation", "by suffocation", "command", "by server magic");

    /** Reads after "Mostly": "Mostly from falls". */
    public static String causePhrase(String id) {
        if (id == null) return "in mysterious ways";
        return CAUSE_PHRASES.getOrDefault(id.toLowerCase(Locale.ROOT), "to " + friendly(id).toLowerCase(Locale.ROOT));
    }

    public static String slainBy(String id) { return "player".equals(id) ? "Other players" : friendly(id); }

    public static String difficulty(String id) {
        if (id == null || id.isBlank() || BossCatalog.NORMAL.equals(id)) return "Normal";
        return Character.toUpperCase(id.charAt(0)) + id.substring(1).replace('_', ' ');
    }

    public static String date(Instant at) { return at == null ? "" : DATE.format(at); }

    public static String unknownPlayer() { return "An adventurer"; }

    /** "1 chest" or "2 chests". */
    public static String plural(long count, String one, String many) { return number(count) + " " + (count == 1 ? one : many); }
}
