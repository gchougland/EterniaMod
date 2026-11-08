package com.hexvane.eterniamod.moderation;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How long a ban lasts. A null length means permanent. Hytale-free so it can be tested and read from data. */
public record BanDuration(Duration length) {
    public static final BanDuration PERMANENT = new BanDuration(null);
    /** Longer than this is almost certainly a typo; staff should say perm instead. */
    static final Duration LONGEST = Duration.ofDays(3650);
    private static final Pattern PART = Pattern.compile("(\\d{1,6})\\s*(minutes|minute|mins|min|m|hours|hour|hrs|hr|h|days|day|d|weeks|week|wk|w|years|year|yr|y)");

    public BanDuration {
        if (length != null && (length.isNegative() || length.isZero())) throw new IllegalArgumentException("A ban must last some time");
    }
    public static BanDuration of(Duration length) { return new BanDuration(length); }
    public boolean permanent() { return length == null; }

    /** Reads friendly forms: 30m, 2h, 7d, 2w, 1y, 1d12h, "3 days", perm. Empty when the text is not a time. */
    public static Optional<BanDuration> parse(String input) {
        if (input == null) return Optional.empty();
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return Optional.empty();
        if (text.equals("perm") || text.equals("permanent") || text.equals("forever") || text.equals("never")) return Optional.of(PERMANENT);
        Matcher matcher = PART.matcher(text);
        Duration total = Duration.ZERO;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end && !text.substring(end, matcher.start()).isBlank()) return Optional.empty();
            long amount = Long.parseLong(matcher.group(1));
            total = total.plus(unit(matcher.group(2)).multipliedBy(amount));
            end = matcher.end();
        }
        if (end == 0 || end != text.length() || total.isZero() || total.compareTo(LONGEST) > 0) return Optional.empty();
        return Optional.of(new BanDuration(total));
    }

    /** True when the text was clearly meant as a time (starts with a digit, or a permanent word) even if it is not one we can read. */
    public static boolean looksLikeTime(String input) {
        if (input == null) return false;
        String text = input.trim().toLowerCase(Locale.ROOT);
        return !text.isEmpty() && (Character.isDigit(text.charAt(0)) || text.startsWith("perm"));
    }

    private static Duration unit(String unit) {
        return switch (unit.charAt(0)) {
            case 'm' -> Duration.ofMinutes(1);
            case 'h' -> Duration.ofHours(1);
            case 'd' -> Duration.ofDays(1);
            case 'w' -> Duration.ofDays(7);
            default -> Duration.ofDays(365);
        };
    }

    /** Compact form used in data and suggestions: 1d, 2h30m, perm. */
    public String compact() {
        if (permanent()) return "perm";
        long minutes = length.toMinutes();
        StringBuilder out = new StringBuilder();
        long days = minutes / 1440, hours = minutes % 1440 / 60, mins = minutes % 60;
        if (days > 0) out.append(days).append('d');
        if (hours > 0) out.append(hours).append('h');
        if (mins > 0 || out.isEmpty()) out.append(mins).append('m');
        return out.toString();
    }

    /** Plain words, for players and staff: "3 days", "2 hours 30 minutes". */
    public String words() {
        return permanent() ? "permanent" : TimeWords.of(length);
    }
}
