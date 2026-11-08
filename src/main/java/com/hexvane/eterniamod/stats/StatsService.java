package com.hexvane.eterniamod.stats;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;

/**
 * Saved player stats and the server firsts. Hytale free: game adapters only fill a {@link StatsTally}; this service
 * writes drained tallies in batches and answers reads for pages, leaderboards, contests and the website.
 *
 * <ul>
 *   <li>{@code stats_player} / player UUID: totals across all worlds.</li>
 *   <li>{@code stats_world} / {@code <player UUID>|<world>}: the same counters for one world.</li>
 *   <li>{@code stats_first} / {@code <boss>.<difficulty>}: the first victory on the server, written once.</li>
 * </ul>
 * Playtime is not copied: it is the account's {@code playSeconds} (added at each logout) plus the live session.
 */
public final class StatsService {
    public static final String PLAYER = "stats_player", WORLD = "stats_world", FIRST = "stats_first";
    private static final String ACCOUNT = "account";
    /** Slots written per transaction, so one flush never holds the store for long. */
    static final int SLOTS_PER_TRANSACTION = 64;

    public record WorldStats(String world, Map<String, Long> counters) {
        public WorldStats { counters = Map.copyOf(counters); }
        public long get(String field) { return counters.getOrDefault(field, 0L); }
    }
    public record BossRecord(String boss, String difficulty, long victories, Instant first) {}
    public record PlayerStats(UUID id, String name, long playSeconds, Map<String, Long> counters, Map<String, Long> kills,
                              Map<String, Long> deaths, Map<String, Long> slainBy, List<BossRecord> bosses, List<WorldStats> worlds) {
        public PlayerStats {
            counters = Map.copyOf(counters); kills = Map.copyOf(kills); deaths = Map.copyOf(deaths); slainBy = Map.copyOf(slainBy);
            bosses = List.copyOf(bosses); worlds = List.copyOf(worlds);
        }
        public long get(String field) { return StatsFields.PLAYTIME.equals(field) ? playSeconds : counters.getOrDefault(field, 0L); }
        /** Nothing done yet besides being online. */
        public boolean isEmpty() { return counters.values().stream().allMatch(v -> v == 0); }
    }
    public record Ranked(int rank, UUID player, String name, long value) {}
    public record First(String boss, String difficulty, UUID player, String name, String world, Instant at) {}

    private final TransactionalStore store;
    private final Clock clock;
    private volatile java.util.function.Predicate<UUID> online = id -> true;

    public StatsService(TransactionalStore store, Clock clock) {
        this.store = Objects.requireNonNull(store); this.clock = Objects.requireNonNull(clock);
    }

    /** Only an online player's open session adds to playtime; a session left open by a crash does not keep growing. */
    public void onlineCheck(java.util.function.Predicate<UUID> check) { online = Objects.requireNonNull(check); }

    /** Writes drained changes, a bounded number of slots per transaction. Returns the server firsts this write claimed. */
    public List<First> apply(Map<StatsTally.Slot, StatsTally.Delta> changes) {
        if (changes.isEmpty()) return List.of();
        var slots = new ArrayList<>(changes.entrySet());
        var claimed = new ArrayList<First>();
        for (int from = 0; from < slots.size(); from += SLOTS_PER_TRANSACTION) {
            var part = slots.subList(from, Math.min(slots.size(), from + SLOTS_PER_TRANSACTION));
            try { claimed.addAll(store.transaction(tx -> write(tx, part))); }
            catch (RuntimeException failure) {
                var unsaved = new LinkedHashMap<StatsTally.Slot, StatsTally.Delta>();
                slots.subList(from, slots.size()).forEach(e -> unsaved.put(e.getKey(), e.getValue()));
                throw new FlushFailure(unsaved, claimed, failure);
            }
        }
        return List.copyOf(claimed);
    }

    /** Some or all changes could not be saved. Earlier transactions of the same flush are saved and must not be retried. */
    public static final class FlushFailure extends RuntimeException {
        private final transient Map<StatsTally.Slot, StatsTally.Delta> unsaved;
        private final transient List<First> claimed;
        FlushFailure(Map<StatsTally.Slot, StatsTally.Delta> unsaved, List<First> claimed, RuntimeException cause) {
            super("Player stats could not be saved", cause); this.unsaved = Map.copyOf(unsaved); this.claimed = List.copyOf(claimed);
        }
        public Map<StatsTally.Slot, StatsTally.Delta> unsaved() { return unsaved; }
        public List<First> claimed() { return claimed; }
    }

    private List<First> write(TransactionalStore.Transaction tx, List<Map.Entry<StatsTally.Slot, StatsTally.Delta>> part) {
        String now = clock.instant().toString();
        var totals = new LinkedHashMap<UUID, StatsTally.Delta>();
        var visited = new HashMap<UUID, Set<String>>();
        var kills = new ArrayList<StatsTally.BossKill>();
        for (var entry : part) {
            var slot = entry.getKey(); var delta = entry.getValue();
            merge(tx, WORLD, worldKey(slot.player(), slot.world()), delta, Map.of("player", slot.player().toString(), "world", slot.world()), now);
            var total = totals.computeIfAbsent(slot.player(), p -> new StatsTally.Delta());
            delta.counts().forEach(total::add);
            visited.computeIfAbsent(slot.player(), p -> new TreeSet<>()).add(slot.world());
            kills.addAll(delta.bosses());
        }
        totals.forEach((player, delta) -> {
            var known = new TreeSet<>(worlds(tx.find(PLAYER, player.toString()).map(r -> r.value("worlds")).orElse("")));
            known.addAll(visited.get(player));
            merge(tx, PLAYER, player.toString(), delta, Map.of("worlds", String.join("|", known)), now);
        });
        // Each player's own firsts, then the server's: earliest victory wins, and a saved first is never replaced.
        kills.sort(Comparator.comparing(StatsTally.BossKill::at).thenComparing(k -> k.player().toString()));
        var playerFirsts = new LinkedHashMap<UUID, Map<String, String>>();
        for (var kill : kills) {
            String field = StatsFields.first(kill.boss(), kill.difficulty());
            playerFirsts.computeIfAbsent(kill.player(), p -> new TreeMap<>()).merge(field, kill.at().toString(), (a, b) -> Instant.parse(a).isBefore(Instant.parse(b)) ? a : b);
        }
        playerFirsts.forEach((player, firsts) -> {
            var row = tx.find(PLAYER, player.toString()).orElseThrow();
            var fields = new TreeMap<>(row.fields()); boolean changed = false;
            for (var first : firsts.entrySet()) {
                String saved = fields.get(first.getKey());
                if (saved == null || saved.isEmpty() || Instant.parse(first.getValue()).isBefore(Instant.parse(saved))) { fields.put(first.getKey(), first.getValue()); changed = true; }
            }
            if (changed) tx.save(PLAYER, player.toString(), row.revision(), fields);
        });
        var claimed = new ArrayList<First>();
        for (var kill : kills) {
            String key = StatsFields.bossKey(kill.boss(), kill.difficulty());
            if (tx.find(FIRST, key).isPresent()) continue;
            String name = tx.find(ACCOUNT, kill.player().toString()).map(r -> r.value("name")).orElse("");
            tx.save(FIRST, key, 0, Map.of("boss", StatsFields.part(kill.boss()).toLowerCase(Locale.ROOT), "difficulty", StatsFields.part(kill.difficulty()).toLowerCase(Locale.ROOT),
                "player", kill.player().toString(), "name", name, "world", kill.world(), "at", kill.at().toString()));
            claimed.add(new First(kill.boss(), kill.difficulty(), kill.player(), name, kill.world(), kill.at()));
        }
        return claimed;
    }

    private static void merge(TransactionalStore.Transaction tx, String namespace, String key, StatsTally.Delta delta, Map<String, String> identity, String now) {
        var row = tx.find(namespace, key);
        var fields = new TreeMap<String, String>(row.map(TransactionalStore.Row::fields).orElse(Map.of()));
        fields.putAll(identity);
        delta.counts().forEach((field, amount) -> fields.put(field, Long.toString(add(number(fields.get(field)), amount))));
        fields.put("updatedAt", now);
        tx.save(namespace, key, row.map(TransactionalStore.Row::revision).orElse(0L), fields);
    }

    /** A player's stats, empty counters for a player who has an account but no activity yet. */
    public Optional<PlayerStats> player(UUID player) {
        return store.transaction(tx -> {
            var account = tx.find(ACCOUNT, player.toString());
            var totals = tx.find(PLAYER, player.toString());
            if (account.isEmpty() && totals.isEmpty()) return Optional.empty();
            var fields = totals.map(TransactionalStore.Row::fields).orElse(Map.of());
            var keys = worlds(fields.getOrDefault("worlds", "")).stream().map(w -> worldKey(player, w)).toList();
            var worlds = tx.findAll(WORLD, keys).values().stream()
                .map(r -> new WorldStats(r.value("world"), counters(r.fields()))).sorted(Comparator.comparing(WorldStats::world)).toList();
            return Optional.of(new PlayerStats(player, account.map(r -> r.value("name")).orElse(""), playSeconds(account.orElse(null)),
                counters(fields), prefixed(fields, StatsFields.KILL_PREFIX), prefixed(fields, StatsFields.DEATH_PREFIX),
                prefixed(fields, StatsFields.SLAIN_BY_PREFIX), bosses(fields), worlds));
        });
    }

    /** The best {@code limit} players for one of {@link StatsFields#RANKED}. Ties keep name order; zero is never ranked. */
    public List<Ranked> top(String stat, int limit) {
        if (!StatsFields.ranked(stat)) throw new IllegalArgumentException("Unknown stat " + stat);
        int size = Math.max(1, Math.min(limit, 100));
        return store.transaction(tx -> {
            var names = new HashMap<String, String>();
            var values = new HashMap<String, Long>();
            for (var account : tx.scan(ACCOUNT)) {
                names.put(account.key(), account.value("name"));
                if (StatsFields.PLAYTIME.equals(stat)) values.put(account.key(), playSeconds(account));
            }
            if (!StatsFields.PLAYTIME.equals(stat)) for (var row : tx.scan(PLAYER)) values.put(row.key(), number(row.fields().get(stat)));
            var sorted = values.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                    .thenComparing(e -> names.getOrDefault(e.getKey(), "").toLowerCase(Locale.ROOT)))
                .limit(size).toList();
            var ranked = new ArrayList<Ranked>();
            for (int i = 0; i < sorted.size(); i++) {
                var e = sorted.get(i);
                int rank = i > 0 && ranked.get(i - 1).value() == e.getValue() ? ranked.get(i - 1).rank() : i + 1;
                ranked.add(new Ranked(rank, UUID.fromString(e.getKey()), names.getOrDefault(e.getKey(), ""), e.getValue()));
            }
            return List.copyOf(ranked);
        });
    }

    /** Saved kills of one creature role by a player (title goals, contests). Changes still pending in memory are not included. */
    public long kills(UUID player, String role) {
        return store.transaction(tx -> tx.find(PLAYER, player.toString()).map(r -> number(r.fields().get(StatsFields.kill(role)))).orElse(0L));
    }

    /** Every server first, oldest first. */
    public List<First> firsts() {
        return store.transaction(tx -> tx.scan(FIRST).stream().map(r -> new First(r.value("boss"), r.value("difficulty"), UUID.fromString(r.value("player")),
            r.value("name"), r.value("world"), Instant.parse(r.value("at")))).sorted(Comparator.comparing(First::at)).toList());
    }

    private long playSeconds(TransactionalStore.Row account) {
        if (account == null) return 0;
        long total = number(account.value("playSeconds"));
        String started = account.value("sessionStarted");
        if (!started.isEmpty() && online.test(UUID.fromString(account.key()))) {
            try { total = add(total, Math.max(0, Duration.between(Instant.parse(started), clock.instant()).getSeconds())); }
            catch (DateTimeException ignored) { /* An unreadable session start adds nothing. */ }
        }
        return total;
    }

    private static List<String> worlds(String saved) { return saved.isEmpty() ? List.of() : List.of(saved.split("\\|")); }

    static String worldKey(UUID player, String world) { return player + "|" + world; }

    private static Map<String, Long> counters(Map<String, String> fields) {
        var map = new TreeMap<String, Long>();
        for (String field : StatsFields.COUNTERS) map.put(field, number(fields.get(field)));
        return map;
    }
    private static Map<String, Long> prefixed(Map<String, String> fields, String prefix) {
        var map = new TreeMap<String, Long>();
        fields.forEach((k, v) -> { if (k.startsWith(prefix)) { long n = number(v); if (n > 0) map.put(k.substring(prefix.length()), n); } });
        return map;
    }
    private static List<BossRecord> bosses(Map<String, String> fields) {
        var list = new ArrayList<BossRecord>();
        fields.forEach((k, v) -> {
            if (!k.startsWith(StatsFields.BOSS_PREFIX)) return;
            String key = k.substring(StatsFields.BOSS_PREFIX.length()); int dot = key.lastIndexOf('.');
            if (dot <= 0) return;
            String first = fields.get(StatsFields.FIRST_PREFIX + key);
            Instant at = null;
            try { if (first != null && !first.isEmpty()) at = Instant.parse(first); } catch (DateTimeException ignored) {}
            list.add(new BossRecord(key.substring(0, dot), key.substring(dot + 1), number(v), at));
        });
        list.sort(Comparator.comparing(BossRecord::first, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(BossRecord::boss));
        return list;
    }
    static long number(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return 0; }
    }
    private static long add(long a, long b) { long r = a + b; return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r; }
}
