package com.hexvane.eterniamod.domain;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;

/**
 * Turns recorded activity and purchases into CSV files for the business side. Pure: no database, no clock. Days are
 * UTC dates; weeks start on Monday; months are calendar months. Retention follows each period's new players into the
 * next two periods.
 */
public final class TelemetryReports {
    public static final List<String> NAMES = List.of("daily", "weekly", "monthly", "players", "shop_items", "tebex_purchases");
    public static final int MAX_DAYS = 400;

    public record PlayerInfo(String name, Instant lastSeen, long playSeconds) {}
    public record Order(UUID player, String itemId, String itemName, long crowns, Instant at) {}
    public record TebexLine(String transaction, String packageId, UUID player, long quantity, Instant at, String state) {}
    public record Input(List<TelemetryService.PlayerDays> players, List<TelemetryService.Day> days, Map<UUID, PlayerInfo> info,
                        List<Order> orders, List<TebexLine> tebex) {}

    private final Input in;
    private final LocalDate from, to, today;

    /** For finished history: every period is treated as over. */
    public TelemetryReports(Input input, LocalDate from, LocalDate to) { this(input, from, to, LocalDate.MAX); }

    /** {@code today} is the current UTC date; retention for a period that has not ended yet is left blank, not 0. */
    public TelemetryReports(Input input, LocalDate from, LocalDate to, LocalDate today) {
        if (to.isBefore(from)) throw new IllegalArgumentException("The end date is before the start date.");
        if (from.plusDays(MAX_DAYS).isBefore(to)) throw new IllegalArgumentException("Pick at most " + MAX_DAYS + " days.");
        this.in = input; this.from = from; this.to = to; this.today = today;
    }

    public String report(String name) {
        return switch (name) {
            case "daily" -> periods(d -> d, d -> d.plusDays(1), false);
            case "weekly" -> periods(d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), d -> d.plusWeeks(1), true);
            case "monthly" -> periods(d -> d.withDayOfMonth(1), d -> d.plusMonths(1), true);
            case "players" -> players();
            case "shop_items" -> shopItems();
            case "tebex_purchases" -> tebexPurchases();
            default -> throw new IllegalArgumentException("Unknown report.");
        };
    }

    // ------------------------------------------------------------------------------------------------ periods

    private String periods(Function<LocalDate, LocalDate> start, Function<LocalDate, LocalDate> next, boolean retention) {
        var csv = new Csv();
        var header = new ArrayList<>(List.of("period_start", "period_end", "unique_players", "new_players", "returning_players",
            "peak_concurrent", "average_concurrent", "samples", "crown_store_orders", "crowns_spent", "crown_store_buyers",
            "tebex_purchases", "tebex_buyers"));
        if (retention) header.addAll(List.of("new_players_back_next_period", "new_players_back_period_after", "retention_next_pct", "retention_after_pct"));
        csv.row(header);
        var samplesByDay = new HashMap<LocalDate, List<TelemetryService.Sample>>();
        for (var d : in.days()) samplesByDay.put(d.date(), d.samples());
        for (LocalDate p = start.apply(from); !p.isAfter(to); p = next.apply(p)) {
            LocalDate s = p, e = next.apply(p).minusDays(1);
            var active = new HashSet<UUID>(); var fresh = new HashSet<UUID>();
            for (var pl : in.players()) {
                if (any(pl.days(), s, e)) active.add(pl.id());
                if (pl.firstSeen() != null && within(pl.firstSeen(), s, e)) fresh.add(pl.id());
            }
            int peak = 0; long sum = 0, count = 0;
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1))
                for (var sample : samplesByDay.getOrDefault(d, List.of())) { peak = Math.max(peak, sample.online()); sum += sample.online(); count++; }
            var orders = in.orders().stream().filter(o -> within(day(o.at()), s, e)).toList();
            var tebex = in.tebex().stream().filter(t -> t.at() != null && within(day(t.at()), s, e)).toList();
            var row = new ArrayList<Object>(List.of(s, e, active.size(), fresh.size(), active.size() - intersect(active, fresh),
                peak, count == 0 ? "" : String.format(Locale.ROOT, "%.2f", (double) sum / count), count,
                orders.size(), orders.stream().mapToLong(Order::crowns).sum(), orders.stream().map(Order::player).distinct().count(),
                tebex.size(), tebex.stream().map(TebexLine::player).filter(Objects::nonNull).distinct().count()));
            if (retention) {
                LocalDate n1 = next.apply(p), n2 = next.apply(n1);
                LocalDate e1 = next.apply(n1).minusDays(1), e2 = next.apply(n2).minusDays(1);
                long back1 = backIn(fresh, n1, e1), back2 = backIn(fresh, n2, e2);
                // A period still under way would read as nobody came back; leave it blank until it is over.
                boolean done1 = !e1.isAfter(today), done2 = !e2.isAfter(today);
                row.addAll(List.of(done1 ? back1 : "", done2 ? back2 : "", done1 ? pct(back1, fresh.size()) : "", done2 ? pct(back2, fresh.size()) : ""));
            }
            csv.row(row);
        }
        return csv.text();
    }

    private long backIn(Set<UUID> cohort, LocalDate s, LocalDate e) {
        return in.players().stream().filter(p -> cohort.contains(p.id()) && any(p.days(), s, e)).count();
    }

    // ------------------------------------------------------------------------------------------------ players, items, tebex

    private String players() {
        var csv = new Csv();
        csv.row(List.of("player_id", "name", "first_seen", "last_seen", "active_days_in_range", "active_days_total", "playtime_hours_total",
            "crown_store_orders", "crowns_spent", "tebex_purchases", "tebex_transactions"));
        var orders = byPlayer(in.orders().stream().filter(o -> within(day(o.at()), from, to)).toList(), Order::player);
        // Only dated purchases count in a range, as in the daily files; undated older ones are listed in the Tebex file.
        var tebex = byPlayer(in.tebex().stream().filter(t -> t.player() != null && t.at() != null && within(day(t.at()), from, to)).toList(), TebexLine::player);
        var ids = new TreeSet<UUID>();
        for (var p : in.players()) if (any(p.days(), from, to)) ids.add(p.id());
        ids.addAll(orders.keySet()); ids.addAll(tebex.keySet());
        var daysById = new HashMap<UUID, TelemetryService.PlayerDays>();
        for (var p : in.players()) daysById.put(p.id(), p);
        for (UUID id : ids) {
            var p = daysById.get(id); var info = in.info().get(id);
            var mine = orders.getOrDefault(id, List.of()); var lines = tebex.getOrDefault(id, List.of());
            csv.row(List.of(id, info == null ? "" : info.name(), p == null || p.firstSeen() == null ? "" : p.firstSeen(),
                info == null || info.lastSeen() == null ? "" : day(info.lastSeen()),
                p == null ? 0 : p.days().stream().filter(d -> within(d, from, to)).count(), p == null ? 0 : p.days().size(),
                info == null ? "" : String.format(Locale.ROOT, "%.2f", info.playSeconds() / 3600.0),
                mine.size(), mine.stream().mapToLong(Order::crowns).sum(), lines.size(),
                String.join(" ", lines.stream().map(TebexLine::transaction).distinct().toList())));
        }
        return csv.text();
    }

    private String shopItems() {
        var csv = new Csv();
        csv.row(List.of("item_id", "name", "first_sale", "orders", "unique_buyers", "crowns", "orders_first_7_days", "orders_first_30_days"));
        var all = new TreeMap<String, List<Order>>();
        for (var o : in.orders()) all.computeIfAbsent(o.itemId(), k -> new ArrayList<>()).add(o);
        for (var entry : all.entrySet()) {
            var orders = entry.getValue();
            LocalDate first = orders.stream().map(o -> day(o.at())).min(Comparator.naturalOrder()).orElse(null);
            var inRange = orders.stream().filter(o -> within(day(o.at()), from, to)).toList();
            if (inRange.isEmpty()) continue;
            long week = orders.stream().filter(o -> !day(o.at()).isAfter(first.plusDays(6))).count();
            long month = orders.stream().filter(o -> !day(o.at()).isAfter(first.plusDays(29))).count();
            csv.row(List.of(entry.getKey(), orders.getLast().itemName(), first, inRange.size(), inRange.stream().map(Order::player).distinct().count(),
                inRange.stream().mapToLong(Order::crowns).sum(), week, month));
        }
        return csv.text();
    }

    private String tebexPurchases() {
        var csv = new Csv();
        csv.row(List.of("date", "transaction_id", "package_id", "player_id", "player_name", "quantity", "state"));
        var lines = in.tebex().stream().filter(t -> t.at() == null || within(day(t.at()), from, to))
            .sorted(Comparator.comparing((TebexLine t) -> t.at() == null ? Instant.EPOCH : t.at())).toList();
        for (var t : lines) {
            var info = t.player() == null ? null : in.info().get(t.player());
            csv.row(List.of(t.at() == null ? "" : day(t.at()), t.transaction(), t.packageId(), t.player() == null ? "" : t.player(),
                info == null ? "" : info.name(), t.quantity(), t.state()));
        }
        return csv.text();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static LocalDate day(Instant at) { return LocalDate.ofInstant(at, ZoneOffset.UTC); }
    private static boolean within(LocalDate d, LocalDate s, LocalDate e) { return !d.isBefore(s) && !d.isAfter(e); }
    private static boolean any(SortedSet<LocalDate> days, LocalDate s, LocalDate e) { return !days.subSet(s, e.plusDays(1)).isEmpty(); }
    private static int intersect(Set<UUID> a, Set<UUID> b) { int n = 0; for (UUID x : a) if (b.contains(x)) n++; return n; }
    private static String pct(long part, long whole) { return whole == 0 ? "" : String.format(Locale.ROOT, "%.1f", 100.0 * part / whole); }
    private static <T> Map<UUID, List<T>> byPlayer(List<T> items, Function<T, UUID> key) {
        var out = new HashMap<UUID, List<T>>();
        for (T t : items) out.computeIfAbsent(key.apply(t), k -> new ArrayList<>()).add(t);
        return out;
    }

    /** CSV with quoting, and cells that a spreadsheet would read as a formula made plain text. */
    static final class Csv {
        private final StringBuilder out = new StringBuilder();
        void row(List<?> cells) {
            for (int i = 0; i < cells.size(); i++) { if (i > 0) out.append(','); out.append(cell(cells.get(i))); }
            out.append("\r\n");
        }
        String text() { return out.toString(); }
        static String cell(Object value) {
            String s = value == null ? "" : value.toString();
            if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(value instanceof Number)) s = "'" + s;
            if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) s = "\"" + s.replace("\"", "\"\"") + "\"";
            return s;
        }
    }
}
