package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/**
 * Friends: requests that the other player accepts, a shared friends list, what each friend may do on your plot, and one
 * block list. Blocking uses the mail block rows, so a blocked player can send you no friend requests, mail or guild
 * invites.
 */
public final class FriendService extends DomainSupport {
    /** What a friend may do on your plot. */
    public enum Share {
        VISIT("Visit and use doors", "Walk in, open doors, sit and sleep"),
        CHESTS("Open chests", "Take from and add to storage");
        public final String label, note;
        Share(String label, String note) { this.label = label; this.note = note; }
        static Set<Share> parse(String text) {
            var out = EnumSet.noneOf(Share.class);
            if (text != null) for (String s : text.split(",")) { try { if (!s.isBlank()) out.add(valueOf(s.trim())); } catch (IllegalArgumentException old) {} }
            return out;
        }
        static String text(Set<Share> shares) { return String.join(",", shares.stream().map(Enum::name).sorted().toList()); }
    }
    public record Friend(UUID id, Instant since, Set<Share> theyMay, Set<Share> iMay) {}
    public record Request(UUID from, UUID to, Instant at) {}

    public static final int MAX_FRIENDS = 200;
    public static final Duration REQUEST_LIFE = Duration.ofDays(7);
    static final String LINK = "friend", REQUEST = "friend_request", SHARE = "friend_share", PREF = "friend_pref", BLOCK = "mail_block";

    FriendService(TransactionalStore s, Clock c, Supplier<UUID> i) { super(s, c, i); }

    // ------------------------------------------------------------------------------------------------ requests

    /** Sends a request, or accepts at once when they already asked you. Returns true when you are now friends. */
    public boolean request(UUID from, UUID to) {
        require(!from.equals(to), INVALID_INPUT, "You cannot add yourself.");
        return store.transaction(tx -> {
            row(tx, "account", to.toString());
            require(!blockedIn(tx, to, from), FORBIDDEN, "That player is not taking friend requests.");
            require(!blockedIn(tx, from, to), CONFLICT, "Unblock them first.");
            require(!friendsIn(tx, from, to), CONFLICT, "You are already friends.");
            var theirs = pending(tx, to, from);
            if (theirs.isPresent()) { link(tx, from, to); save(tx, theirs.get(), "state", "ACCEPTED"); return true; }
            require(countIn(tx, from) < MAX_FRIENDS, CAPACITY, "Your friends list is full.");
            String k = from + ":" + to; var old = tx.find(REQUEST, k);
            if (old.isPresent() && live(old.get(), clock.instant())) return false;
            tx.save(REQUEST, k, old.map(TransactionalStore.Row::revision).orElse(0L), fields("from", from, "to", to, "at", clock.instant(), "state", "PENDING"));
            return false;
        });
    }

    public void accept(UUID me, UUID from) {
        store.transaction(tx -> {
            var r = pending(tx, from, me).orElseThrow(() -> new DomainException(NOT_FOUND, "That request is no longer open."));
            require(countIn(tx, me) < MAX_FRIENDS, CAPACITY, "Your friends list is full.");
            link(tx, me, from); save(tx, r, "state", "ACCEPTED");
            pending(tx, me, from).ifPresent(mine -> save(tx, mine, "state", "ACCEPTED"));
            return null;
        });
    }

    public void decline(UUID me, UUID from) {
        store.transaction(tx -> { pending(tx, from, me).ifPresent(r -> save(tx, r, "state", "DECLINED")); return null; });
    }

    public void cancel(UUID me, UUID to) {
        store.transaction(tx -> { pending(tx, me, to).ifPresent(r -> save(tx, r, "state", "CANCELLED")); return null; });
    }

    public List<Request> incoming(UUID me) { return requests(me, false); }
    public List<Request> outgoing(UUID me) { return requests(me, true); }

    private List<Request> requests(UUID me, boolean sent) {
        Instant now = clock.instant();
        return store.transaction(tx -> tx.scan(REQUEST).stream()
            .filter(r -> r.value(sent ? "from" : "to").equals(me.toString()) && live(r, now))
            .map(r -> new Request(UUID.fromString(r.value("from")), UUID.fromString(r.value("to")), Instant.parse(r.value("at"))))
            .sorted(Comparator.comparing(Request::at).reversed()).toList());
    }

    // ------------------------------------------------------------------------------------------------ friends

    public List<Friend> friends(UUID me) {
        return store.transaction(tx -> {
            var out = new ArrayList<Friend>();
            for (var r : tx.scan(LINK)) {
                if (!r.value("state").equals("ACTIVE")) continue;
                UUID a = UUID.fromString(r.value("a")), b = UUID.fromString(r.value("b"));
                UUID other = a.equals(me) ? b : b.equals(me) ? a : null;
                if (other == null) continue;
                out.add(new Friend(other, Instant.parse(r.value("since")), sharesIn(tx, me, other), sharesIn(tx, other, me)));
            }
            return List.copyOf(out);
        });
    }

    public boolean areFriends(UUID a, UUID b) { return store.transaction(tx -> friendsIn(tx, a, b)); }

    public void remove(UUID me, UUID other) {
        store.transaction(tx -> { unlink(tx, me, other); return null; });
    }

    /** Sets what a friend may do on your plot. */
    public void share(UUID owner, UUID friend, Set<Share> shares) {
        store.transaction(tx -> {
            require(friendsIn(tx, owner, friend), FORBIDDEN, "You are no longer friends.");
            String k = owner + ":" + friend; var old = tx.find(SHARE, k);
            tx.save(SHARE, k, old.map(TransactionalStore.Row::revision).orElse(0L), fields("shares", Share.text(shares)));
            return null;
        });
    }

    /** Whether {@code visitor} may use {@code capability} on {@code owner}'s plot because they are friends. */
    public boolean allows(UUID owner, UUID visitor, String capability) {
        Share needed = needed(capability);
        if (needed == null || owner.equals(visitor)) return false;
        return store.transaction(tx -> friendsIn(tx, owner, visitor) && sharesIn(tx, owner, visitor).contains(needed));
    }

    /** The plot share a housing capability needs; null for anything friends may never do. */
    public static Share needed(String capability) {
        return switch (capability) {
            case "housing.door.use", "housing.bed.use", "housing.bench.use", "housing.visit" -> Share.VISIT;
            case "housing.container.open" -> Share.CHESTS;
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------ blocking

    /** Blocks a player: ends the friendship and any requests, and stops their requests, mail and guild invites. */
    public void block(UUID me, UUID other) {
        require(!me.equals(other), INVALID_INPUT, "You cannot block yourself.");
        store.transaction(tx -> {
            row(tx, "account", other.toString());
            unlink(tx, me, other);
            pending(tx, me, other).ifPresent(r -> save(tx, r, "state", "CANCELLED"));
            pending(tx, other, me).ifPresent(r -> save(tx, r, "state", "DECLINED"));
            setBlock(tx, me, other, true);
            return null;
        });
    }

    public void unblock(UUID me, UUID other) { store.transaction(tx -> { setBlock(tx, me, other, false); return null; }); }

    /** Players {@code me} has blocked. */
    public List<UUID> blocked(UUID me) {
        String prefix = me + ":";
        return store.transaction(tx -> tx.scan(BLOCK).stream().filter(r -> r.key().startsWith(prefix) && r.value("blocked").equals("true"))
            .map(r -> UUID.fromString(r.key().substring(prefix.length()))).toList());
    }

    /** Whether {@code blocker} has blocked {@code other}. */
    public boolean isBlocked(UUID blocker, UUID other) { return store.transaction(tx -> blockedIn(tx, blocker, other)); }

    static boolean blockedIn(TransactionalStore.Transaction tx, UUID blocker, UUID other) {
        return tx.find(BLOCK, blocker + ":" + other).filter(r -> r.value("blocked").equals("true")).isPresent();
    }

    // ------------------------------------------------------------------------------------------------ alerts

    /** Whether to tell this player in chat when a friend comes online. On unless turned off. */
    public boolean alerts(UUID me) { return store.transaction(tx -> tx.find(PREF, me.toString()).map(r -> !r.value("alerts").equals("false")).orElse(true)); }

    public void setAlerts(UUID me, boolean on) {
        store.transaction(tx -> { var old = tx.find(PREF, me.toString()); tx.save(PREF, me.toString(), old.map(TransactionalStore.Row::revision).orElse(0L), fields("alerts", on)); return null; });
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String pair(UUID a, UUID b) { return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a; }

    static boolean friendsIn(TransactionalStore.Transaction tx, UUID a, UUID b) {
        return tx.find(LINK, pair(a, b)).filter(r -> r.value("state").equals("ACTIVE")).isPresent();
    }

    private void link(TransactionalStore.Transaction tx, UUID a, UUID b) {
        String k = pair(a, b); var old = tx.find(LINK, k);
        tx.save(LINK, k, old.map(TransactionalStore.Row::revision).orElse(0L), fields("a", a, "b", b, "since", clock.instant(), "state", "ACTIVE"));
    }

    private static void unlink(TransactionalStore.Transaction tx, UUID a, UUID b) {
        tx.find(LINK, pair(a, b)).filter(r -> r.value("state").equals("ACTIVE")).ifPresent(r -> save(tx, r, "state", "REMOVED"));
        for (String k : List.of(a + ":" + b, b + ":" + a)) tx.find(SHARE, k).ifPresent(r -> save(tx, r, "shares", ""));
    }

    private static Set<Share> sharesIn(TransactionalStore.Transaction tx, UUID owner, UUID friend) {
        return tx.find(SHARE, owner + ":" + friend).map(r -> Share.parse(r.value("shares"))).map(Set::copyOf).orElse(Set.of());
    }

    private int countIn(TransactionalStore.Transaction tx, UUID me) {
        String id = me.toString(); int n = 0;
        for (var r : tx.scan(LINK)) if (r.value("state").equals("ACTIVE") && (r.value("a").equals(id) || r.value("b").equals(id))) n++;
        return n;
    }

    private Optional<TransactionalStore.Row> pending(TransactionalStore.Transaction tx, UUID from, UUID to) {
        Instant now = clock.instant();
        return tx.find(REQUEST, from + ":" + to).filter(r -> live(r, now));
    }

    private static boolean live(TransactionalStore.Row r, Instant now) {
        return r.value("state").equals("PENDING") && Instant.parse(r.value("at")).plus(REQUEST_LIFE).isAfter(now);
    }

    private static void setBlock(TransactionalStore.Transaction tx, UUID me, UUID other, boolean on) {
        String k = me + ":" + other; var old = tx.find(BLOCK, k);
        tx.save(BLOCK, k, old.map(TransactionalStore.Row::revision).orElse(0L), fields("blocked", on));
    }
}
