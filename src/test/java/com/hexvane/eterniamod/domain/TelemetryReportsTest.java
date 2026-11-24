package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryReportsTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a"), B = UUID.fromString("00000000-0000-0000-0000-00000000000b"),
        C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    static TelemetryService.PlayerDays p(UUID id, String first, String... days) {
        var set = new TreeSet<LocalDate>(); for (String d : days) set.add(LocalDate.parse(d));
        return new TelemetryService.PlayerDays(id, LocalDate.parse(first), set);
    }
    static List<String[]> rows(String csv) { return csv.lines().map(l -> l.split(",", -1)).toList(); }

    private TelemetryReports.Input input() {
        // Mon 2026-11-02 .. Sun 2026-11-15 spans two weeks.
        var players = List.of(p(A, "2026-11-02", "2026-11-02", "2026-11-03", "2026-11-10"),
            p(B, "2026-11-03", "2026-11-03"), p(C, "2026-10-20", "2026-11-09"));
        var days = List.of(new TelemetryService.Day(LocalDate.parse("2026-11-02"), List.of(new TelemetryService.Sample(0, 1), new TelemetryService.Sample(5, 3))),
            new TelemetryService.Day(LocalDate.parse("2026-11-03"), List.of(new TelemetryService.Sample(1435, 2))));
        var info = Map.of(A, new TelemetryReports.PlayerInfo("=Aria", Instant.parse("2026-11-10T08:00:00Z"), 7200),
            B, new TelemetryReports.PlayerInfo("Bram, the Bold", Instant.parse("2026-11-03T09:00:00Z"), 1800));
        var orders = List.of(new TelemetryReports.Order(A, "eternia:prop/lamp", "Lamp", 100, Instant.parse("2026-11-02T23:59:00Z")),
            new TelemetryReports.Order(B, "eternia:prop/lamp", "Lamp", 100, Instant.parse("2026-11-03T00:01:00Z")),
            new TelemetryReports.Order(A, "eternia:prop/lamp", "Lamp", 100, Instant.parse("2026-11-20T10:00:00Z")));
        var tebex = List.of(new TelemetryReports.TebexLine("tbx-1", "crowns-500", A, 1, Instant.parse("2026-11-02T12:00:00Z"), "DELIVERED"),
            new TelemetryReports.TebexLine("tbx-0", "crowns-100", B, 1, null, "DELIVERED"));
        return new TelemetryReports.Input(players, days, info, orders, tebex);
    }

    @Test void dailyCountsPlayersSamplesAndSalesByUtcDay() {
        var r = rows(new TelemetryReports(input(), LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-03")).report("daily"));
        assertEquals("unique_players", r.get(0)[2]);
        var mon = r.get(1); var tue = r.get(2);
        assertArrayEquals(new String[] {"2026-11-02", "2026-11-02", "1", "1", "0", "3", "2.00", "2", "1", "100", "1", "1", "1"}, mon);
        assertEquals("2", tue[2]); assertEquals("1", tue[3]); assertEquals("1", tue[4], "Aria came back on Tuesday");
        assertEquals("1", tue[8], "an order one minute after midnight is the next day");
        assertEquals("0", tue[11], "a Tebex purchase without a date is not placed on a day");
    }

    @Test void emptyDaysStillGetARow() {
        var r = rows(new TelemetryReports(input(), LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-05")).report("daily"));
        assertEquals(3, r.size());
        assertEquals("0", r.get(1)[2]); assertEquals("", r.get(1)[6], "no samples means no average"); assertEquals("0", r.get(1)[7]);
    }

    @Test void weeksStartOnMondayAndTrackRetention() {
        var r = rows(new TelemetryReports(input(), LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-15")).report("weekly"));
        assertEquals(3, r.size());
        var w1 = r.get(1);
        assertEquals("2026-11-02", w1[0], "a range starting midweek starts at that week's Monday");
        assertEquals("2026-11-08", w1[1]);
        assertEquals("2", w1[2]); assertEquals("2", w1[3]);
        assertEquals("1", w1[13], "Aria, new in week one, came back in week two");
        assertEquals("0", w1[14]);
        assertEquals("50.0", w1[15]);
        var w2 = r.get(2);
        assertEquals("2", w2[2]); assertEquals("0", w2[3]); assertEquals("", w2[15], "no new players, no retention figure");
    }

    @Test void retentionForPeriodsNotOverYetIsBlank() {
        var r = rows(new TelemetryReports(input(), LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08"), LocalDate.parse("2026-11-12")).report("weekly"));
        assertEquals("", r.get(1)[13], "the next week is still under way");
        assertEquals("", r.get(1)[15]);
        assertEquals("", r.get(1)[16]);
        r = rows(new TelemetryReports(input(), LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08"), LocalDate.parse("2026-11-15")).report("weekly"));
        assertEquals("50.0", r.get(1)[15], "once the next week has ended it counts");
        assertEquals("", r.get(1)[16]);
    }

    @Test void monthsAreCalendarMonths() {
        var r = rows(new TelemetryReports(input(), LocalDate.parse("2026-10-15"), LocalDate.parse("2026-11-30")).report("monthly"));
        assertEquals("2026-10-01", r.get(1)[0]); assertEquals("2026-10-31", r.get(1)[1]);
        assertEquals("1", r.get(1)[3], "Cass is October's new player");
        assertEquals("1", r.get(1)[13], "and played again in November");
        assertEquals("3", r.get(2)[2]);
    }

    @Test void playersItemsAndTebexFilesForJoining() {
        var reports = new TelemetryReports(input(), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"));
        String players = reports.report("players");
        assertTrue(players.contains("'=Aria"), "names that look like formulas are made plain text");
        assertTrue(players.contains("\"Bram, the Bold\""), "names with commas are quoted");
        var a = rows(players).stream().filter(x -> x[0].equals(A.toString())).findFirst().orElseThrow();
        assertEquals("2.00", a[6]); assertEquals("2", a[7]); assertEquals("200", a[8]); assertEquals("1", a[9]); assertEquals("tbx-1", a[10]);
        assertTrue(rows(players).stream().filter(x -> x[0].equals(B.toString())).allMatch(x -> x[x.length - 2].equals("0") && x[x.length - 1].isEmpty()), "an undated purchase is not counted in a date range");
        var items = rows(reports.report("shop_items"));
        assertArrayEquals(new String[] {"eternia:prop/lamp", "Lamp", "2026-11-02", "3", "2", "300", "2", "3"}, items.get(1));
        var tebex = rows(reports.report("tebex_purchases"));
        assertEquals("", tebex.get(1)[0], "purchases from before dates were recorded come first, without a date");
        assertEquals("tbx-1", tebex.get(2)[1]);
    }

    @Test void rangesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryReports(input(), LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-01")));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryReports(input(), LocalDate.parse("2025-01-01"), LocalDate.parse("2026-11-01")));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryReports(input(), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")).report("secrets"));
    }

    @Test void recordingMarksDaysOnceAndKeepsSamples() {
        var now = new AtomicReference<>(Instant.parse("2026-11-02T23:58:00Z"));
        Clock clock = new Clock() { public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId z) { return this; } public Instant instant() { return now.get(); } };
        var t = new TelemetryService(new InMemoryStore(), clock, UUID::randomUUID);
        t.markActive(A); t.markActive(A);
        t.sample(List.of(A, B));
        now.set(Instant.parse("2026-11-03T00:03:00Z"));
        t.sample(List.of(A));
        var a = t.players().stream().filter(x -> x.id().equals(A)).findFirst().orElseThrow();
        assertEquals(2, a.days().size(), "play across midnight counts on both days");
        assertEquals(LocalDate.parse("2026-11-02"), a.firstSeen());
        var days = t.days(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-04"));
        assertEquals(2, days.size());
        assertEquals(new TelemetryService.Sample(1438, 2), days.get(0).samples().getFirst());
        assertEquals(1, days.get(1).peak());
    }
}
