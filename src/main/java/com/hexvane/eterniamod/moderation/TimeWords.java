package com.hexvane.eterniamod.moderation;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Spans of time in plain English: the two largest parts, with seconds rounded up to the next minute. */
public final class TimeWords {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private TimeWords() {}

    public static String of(Duration span) {
        if (span.isNegative() || span.isZero()) return "less than a minute";
        long minutes = span.toMinutes() + (span.toSecondsPart() > 0 || span.toNanosPart() > 0 ? 1 : 0);
        if (span.compareTo(Duration.ofMinutes(1)) < 0) return "less than a minute";
        long years = minutes / (365L * 1440);
        long days = minutes % (365L * 1440) / 1440;
        long hours = minutes % 1440 / 60;
        long mins = minutes % 60;
        List<String> parts = new ArrayList<>();
        add(parts, years, "year");
        add(parts, days, "day");
        add(parts, hours, "hour");
        add(parts, mins, "minute");
        return String.join(" ", parts.subList(0, Math.min(2, parts.size())));
    }

    public static String between(Instant now, Instant end) { return of(Duration.between(now, end)); }

    public static String date(Instant when) { return DATE.format(when); }

    private static void add(List<String> parts, long amount, String unit) {
        if (amount > 0) parts.add(amount + " " + unit + (amount == 1 ? "" : "s"));
    }
}
