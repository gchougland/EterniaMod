package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Player analytics for the business side: which days each player played (one row per player, so unique players over
 * any period can be counted), and how many were online every few minutes (one row per day). Days are UTC.
 * Rows grow with players and days, never with sessions, so reports read little under the server-wide lock.
 */
public final class TelemetryService extends DomainSupport {
    static final String PLAYER = "telemetry_player", DAY = "telemetry_day";
    public record PlayerDays(UUID id, LocalDate firstSeen, SortedSet<LocalDate> days) {}
    /** One online count: minute of the UTC day, and players online. */
    public record Sample(int minute, int online) {}
    public record Day(LocalDate date, List<Sample> samples) {
        public int peak() { return samples.stream().mapToInt(Sample::online).max().orElse(0); }
    }

    TelemetryService(TransactionalStore s, Clock c, Supplier<UUID> i) { super(s, c, i); }

    /** Marks a player as having played today. Called at every join, inside the join's own transaction. */
    public void markActive(UUID player) {
        LocalDate today = today();
        store.transaction(tx -> { markIn(tx, player, today); return null; });
    }

    /** Records one online count and marks everyone online as active today, so play across midnight still counts. */
    public void sample(Collection<UUID> online) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int minute = LocalTime.ofInstant(now, ZoneOffset.UTC).toSecondOfDay() / 60;
        store.transaction(tx -> {
            var old = tx.find(DAY, today.toString());
            String samples = old.map(r -> r.value("samples")).orElse("");
            String entry = minute + "=" + online.size();
            samples = samples.isEmpty() ? entry : samples + ";" + entry;
            tx.save(DAY, today.toString(), old.map(TransactionalStore.Row::revision).orElse(0L), fields("samples", samples));
            for (UUID id : online) markIn(tx, id, today);
            return null;
        });
    }

    /** Every player's days, read in one go. */
    public List<PlayerDays> players() {
        return store.transaction(tx -> tx.scan(PLAYER).stream().map(TelemetryService::playerDays).toList());
    }

    /** The online counts for each day in the range that has any. */
    public List<Day> days(LocalDate from, LocalDate to) {
        var keys = new ArrayList<String>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) keys.add(d.toString());
        return store.transaction(tx -> {
            var rows = tx.findAll(DAY, keys);
            var out = new ArrayList<Day>();
            for (String k : keys) { var r = rows.get(k); if (r != null) out.add(new Day(LocalDate.parse(k), parseSamples(r.value("samples")))); }
            return List.copyOf(out);
        });
    }

    /** Everything the reports need, read in one transaction. The CSV is built afterwards, outside it. */
    public TelemetryReports.Input reportInput(LocalDate from, LocalDate to) {
        return store.transaction(tx -> {
            var players = tx.scan(PLAYER).stream().map(TelemetryService::playerDays).toList();
            var keys = new ArrayList<String>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) keys.add(d.toString());
            var dayRows = tx.findAll(DAY, keys);
            var days = new ArrayList<Day>();
            for (String k : keys) { var r = dayRows.get(k); if (r != null) days.add(new Day(LocalDate.parse(k), parseSamples(r.value("samples")))); }
            var info = new HashMap<UUID, TelemetryReports.PlayerInfo>();
            for (var a : tx.scan("account")) {
                try {
                    Instant seen = a.value("lastSeen").isEmpty() ? null : Instant.parse(a.value("lastSeen"));
                    long seconds = a.value("playSeconds").isEmpty() ? 0 : Long.parseLong(a.value("playSeconds"));
                    info.put(UUID.fromString(a.key()), new TelemetryReports.PlayerInfo(a.value("name"), seen, seconds));
                } catch (RuntimeException unreadable) { /* one odd row never stops a report */ }
            }
            var orders = new ArrayList<TelemetryReports.Order>();
            for (var o : tx.scan("premium_order")) {
                if (!o.value("state").equals("DELIVERED") || o.value("at").isEmpty()) continue;
                try { orders.add(new TelemetryReports.Order(UUID.fromString(o.value("player")), o.value("item"), o.value("name"), Long.parseLong(o.value("price")), Instant.parse(o.value("at")))); }
                catch (RuntimeException unreadable) {}
            }
            var tebex = new ArrayList<TelemetryReports.TebexLine>();
            for (var c : tx.scan("commerce_purchase")) {
                if (!c.value("provider").equals("tebex")) continue;
                try {
                    var owner = Owner.parse(c.value("recipient"));
                    UUID player = owner.kind() == Owner.Kind.PLAYER ? owner.id() : null;
                    long quantity = c.value("quantity").isEmpty() ? 1 : Long.parseLong(c.value("quantity"));
                    Instant at = c.value("at").isEmpty() ? null : Instant.parse(c.value("at"));
                    tebex.add(new TelemetryReports.TebexLine(c.value("transaction"), c.value("package"), player, quantity, at, c.value("state")));
                } catch (RuntimeException unreadable) {}
            }
            return new TelemetryReports.Input(players, List.copyOf(days), info, orders, tebex);
        });
    }

    private void markIn(TransactionalStore.Transaction tx, UUID player, LocalDate day) {
        var old = tx.find(PLAYER, player.toString());
        if (old.isPresent()) {
            String days = old.get().value("days");
            String d = day.toString();
            if (days.endsWith(d) || days.contains(d + ",")) return;
            save(tx, old.get(), "days", days.isEmpty() ? d : days + "," + d);
            return;
        }
        // A player who joined before analytics existed: their first day comes from their welcome Coins.
        LocalDate first = tx.find("ledger", key("welcome-coins-v1:" + player)).map(r -> r.value("at"))
            .filter(at -> !at.isEmpty()).map(at -> LocalDate.ofInstant(Instant.parse(at), ZoneOffset.UTC)).orElse(day);
        if (first.isAfter(day)) first = day;
        tx.save(PLAYER, player.toString(), 0, fields("first", first, "days", day));
    }

    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private static PlayerDays playerDays(TransactionalStore.Row r) {
        var days = new TreeSet<LocalDate>();
        for (String d : r.value("days").split(",")) if (!d.isBlank()) days.add(LocalDate.parse(d));
        LocalDate first = r.value("first").isEmpty() ? (days.isEmpty() ? null : days.first()) : LocalDate.parse(r.value("first"));
        return new PlayerDays(UUID.fromString(r.key()), first, Collections.unmodifiableSortedSet(days));
    }

    static List<Sample> parseSamples(String text) {
        var out = new ArrayList<Sample>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try { out.add(new Sample(Integer.parseInt(part.substring(0, eq)), Integer.parseInt(part.substring(eq + 1)))); } catch (NumberFormatException ignored) {}
        }
        return List.copyOf(out);
    }
}
