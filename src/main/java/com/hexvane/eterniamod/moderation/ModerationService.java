package com.hexvane.eterniamod.moderation;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Timed bans with an escalation ladder and a full audit trail. Hytale-free: the game adapter decides who is staff,
 * resolves names and refuses connections.
 *
 * <p>Saved data: {@code moderation_ban} holds one row per player who was ever banned (key: player UUID) with the
 * current state and the offence count; {@code moderation_history} holds one row per ban, unban and expiry
 * (key: player UUID and a sequence number).
 */
public final class ModerationService {
    static final String BANS = "moderation_ban";
    static final String HISTORY = "moderation_history";
    public static final int MAX_REASON = 200;
    /** Who an automatic expiry is recorded as. */
    public static final String SYSTEM = "Eternia";

    public enum Kind { BAN, UNBAN, EXPIRE }
    private enum State { ACTIVE, LIFTED, EXPIRED }

    /** Who did something: a staff member's UUID (null for the console or the server) and the name to show. */
    public record Actor(UUID id, String name) {
        public Actor { Objects.requireNonNull(name); }
        public static Actor system() { return new Actor(null, SYSTEM); }
    }
    /** A ban as it stands. {@code until} is null for a permanent ban. */
    public record Ban(UUID player, String name, String reason, Actor by, Instant at, Instant until, int offence) {
        public boolean permanent() { return until == null; }
        public boolean endedBy(Instant now) { return until != null && !until.isAfter(now); }
    }
    public record Event(UUID player, String name, Kind kind, Instant at, Actor by, String reason, BanDuration length, int offence) {}
    public sealed interface BanResult permits Banned, AlreadyBanned {}
    /** {@code fromLadder} is true when the length came from the escalation ladder. */
    public record Banned(Ban ban, BanDuration length, boolean fromLadder) implements BanResult {}
    public record AlreadyBanned(Ban ban) implements BanResult {}

    private final TransactionalStore store;
    private final Clock clock;
    private final Supplier<BanLadder> ladder;

    public ModerationService(TransactionalStore store, Clock clock, Supplier<BanLadder> ladder) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.ladder = Objects.requireNonNull(ladder);
    }

    public BanLadder ladder() { return ladder.get(); }

    /** Bans a player. With no length the ladder decides from the number of earlier bans. Refuses while a ban is still running. */
    public BanResult ban(UUID player, String name, Actor by, BanDuration requested, String reason) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(by);
        String cleanName = clean(name, 64);
        String cleanReason = clean(reason, MAX_REASON);
        if (player.equals(by.id())) throw new IllegalArgumentException("Staff cannot ban themselves");
        return store.transaction(tx -> {
            Instant now = clock.instant();
            var existing = tx.find(BANS, player.toString());
            var row = existing.map(r -> expireIfDue(tx, r, now)).orElse(null);
            if (row != null && State.valueOf(row.value("state")) == State.ACTIVE) return new AlreadyBanned(ban(row));
            int offence = (row == null ? 0 : (int) number(row, "offences")) + 1;
            BanDuration length = requested != null ? requested : ladder.get().forOffence(offence);
            Instant until = length.permanent() ? null : now.plus(length.length());
            long events = row == null ? 0 : number(row, "events");
            var fields = new TreeMap<String, String>();
            fields.put("state", State.ACTIVE.name());
            fields.put("name", cleanName);
            fields.put("reason", cleanReason);
            fields.put("by", by.id() == null ? "" : by.id().toString());
            fields.put("byName", by.name());
            fields.put("at", now.toString());
            fields.put("until", until == null ? "" : until.toString());
            fields.put("offence", Integer.toString(offence));
            fields.put("offences", Integer.toString(offence));
            fields.put("events", Long.toString(events + 1));
            var saved = tx.save(BANS, player.toString(), row == null ? 0 : row.revision(), fields);
            record(tx, player, events + 1, Kind.BAN, cleanName, now, by, cleanReason, length, offence);
            return new Banned(ban(saved), length, requested == null);
        });
    }

    /** Lifts a running ban. Empty when the player was not banned. */
    public Optional<Ban> unban(UUID player, Actor by, String note) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(by);
        String cleanNote = clean(note, MAX_REASON);
        return store.transaction(tx -> {
            Instant now = clock.instant();
            var found = tx.find(BANS, player.toString());
            if (found.isEmpty()) return Optional.<Ban>empty();
            var row = expireIfDue(tx, found.get(), now);
            if (State.valueOf(row.value("state")) != State.ACTIVE) return Optional.<Ban>empty();
            Ban lifted = ban(row);
            long events = number(row, "events") + 1;
            tx.save(BANS, row.key(), row.revision(), changed(row, "state", State.LIFTED.name(), "events", Long.toString(events)));
            record(tx, player, events, Kind.UNBAN, lifted.name(), now, by, cleanNote, null, lifted.offence());
            return Optional.of(lifted);
        });
    }

    /** The ban stopping this player right now, if any. A ban that has run out is recorded as expired here. */
    public Optional<Ban> active(UUID player) {
        return store.transaction(tx -> tx.find(BANS, player.toString())
            .map(row -> expireIfDue(tx, row, clock.instant()))
            .filter(row -> State.valueOf(row.value("state")) == State.ACTIVE)
            .map(ModerationService::ban));
    }

    /** Every running ban, newest first. Records any that have run out. */
    public List<Ban> activeBans() {
        return store.transaction(tx -> {
            Instant now = clock.instant();
            List<Ban> bans = new ArrayList<>();
            for (var row : tx.scan(BANS)) {
                var current = expireIfDue(tx, row, now);
                if (State.valueOf(current.value("state")) == State.ACTIVE) bans.add(ban(current));
            }
            bans.sort(Comparator.comparing(Ban::at).reversed());
            return List.copyOf(bans);
        });
    }

    /** Records every ban that has run out. Returns how many ended. */
    public int expireDue() {
        return store.transaction(tx -> {
            Instant now = clock.instant();
            int ended = 0;
            for (var row : tx.scan(BANS)) {
                if (State.valueOf(row.value("state")) == State.ACTIVE && expireIfDue(tx, row, now) != row) ended++;
            }
            return ended;
        });
    }

    /** How many times this player has been banned, including a running ban. */
    public int offences(UUID player) {
        return store.transaction(tx -> tx.find(BANS, player.toString()).map(r -> (int) number(r, "offences")).orElse(0));
    }

    /** Every ban, unban and expiry for a player, oldest first. */
    public List<Event> history(UUID player) {
        String prefix = player.toString() + ":";
        return store.transaction(tx -> tx.scan(HISTORY).stream()
            .filter(row -> row.key().startsWith(prefix))
            .sorted(Comparator.comparing(TransactionalStore.Row::key))
            .map(ModerationService::event)
            .toList());
    }

    /** The name a ban was recorded under, for players found by an old name. */
    public Optional<UUID> findBannedByName(String name) {
        return store.transaction(tx -> tx.scan(BANS).stream()
            .filter(row -> row.value("name").equalsIgnoreCase(name.trim()))
            .map(row -> UUID.fromString(row.key()))
            .findFirst());
    }

    /** Names of players with a running ban, for tab completion. Does not record expiries. */
    public List<String> activeNames() {
        Instant now = clock.instant();
        return store.transaction(tx -> tx.scan(BANS).stream()
            .filter(row -> State.valueOf(row.value("state")) == State.ACTIVE && !ban(row).endedBy(now))
            .map(row -> row.value("name"))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList());
    }

    private TransactionalStore.Row expireIfDue(TransactionalStore.Transaction tx, TransactionalStore.Row row, Instant now) {
        if (State.valueOf(row.value("state")) != State.ACTIVE) return row;
        Ban ban = ban(row);
        if (!ban.endedBy(now)) return row;
        long events = number(row, "events") + 1;
        var saved = tx.save(BANS, row.key(), row.revision(), changed(row, "state", State.EXPIRED.name(), "events", Long.toString(events)));
        // Recorded at the moment it ended, not when someone noticed.
        record(tx, ban.player(), events, Kind.EXPIRE, ban.name(), ban.until(), Actor.system(), "", null, ban.offence());
        return saved;
    }

    private static void record(TransactionalStore.Transaction tx, UUID player, long sequence, Kind kind, String name, Instant at,
                               Actor by, String reason, BanDuration length, int offence) {
        var fields = new TreeMap<String, String>();
        fields.put("player", player.toString());
        fields.put("kind", kind.name());
        fields.put("name", name);
        fields.put("at", at.toString());
        fields.put("by", by.id() == null ? "" : by.id().toString());
        fields.put("byName", by.name());
        fields.put("reason", reason);
        fields.put("length", length == null ? "" : length.compact());
        fields.put("offence", Integer.toString(offence));
        tx.save(HISTORY, player + ":" + String.format("%08d", sequence), 0, fields);
    }

    private static Ban ban(TransactionalStore.Row row) {
        return new Ban(UUID.fromString(row.key()), row.value("name"), row.value("reason"), actor(row),
            Instant.parse(row.value("at")), row.value("until").isEmpty() ? null : Instant.parse(row.value("until")), (int) number(row, "offence"));
    }

    private static Event event(TransactionalStore.Row row) {
        String length = row.value("length");
        return new Event(UUID.fromString(row.value("player")), row.value("name"), Kind.valueOf(row.value("kind")), Instant.parse(row.value("at")),
            actor(row), row.value("reason"), length.isEmpty() ? null : BanDuration.parse(length).orElse(null), (int) number(row, "offence"));
    }

    private static Actor actor(TransactionalStore.Row row) {
        String id = row.value("by");
        return new Actor(id.isEmpty() ? null : UUID.fromString(id), row.value("byName"));
    }

    private static long number(TransactionalStore.Row row, String field) {
        String value = row.value(field);
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }

    private static Map<String, String> changed(TransactionalStore.Row row, String... pairs) {
        var map = new TreeMap<>(row.fields());
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private static String clean(String text, int max) {
        if (text == null) return "";
        String flat = text.replaceAll("[\\r\\n\\t]+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max).trim();
    }
}
