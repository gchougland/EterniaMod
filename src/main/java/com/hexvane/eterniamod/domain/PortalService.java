package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/**
 * Personal portals: every placed Teleporter pad is a portal with an owner, a name, a private flag and a list of players
 * the owner allows. Each player keeps their own list of portals they have visited; travel picks from that list. A
 * private portal can only be used and added by its owner and the players they allow. Going private hides it from
 * everyone else's list until it is public again or they are allowed; their list keeps it, so nothing has to be revisited.
 */
public final class PortalService extends DomainSupport {
    /** A placed pad. {@code owner} is null for an old pad nobody could be traced to. */
    public record Portal(String id, String world, int x, int y, int z, UUID owner, String ownerName, String name,
                         boolean isPrivate, Set<UUID> allowed, Instant placed) {
        public Portal { allowed = Set.copyOf(allowed); }
        public boolean ownedBy(UUID player) { return owner != null && owner.equals(player); }
        /** Owner, an allowed player, or anyone when the portal is public. */
        public boolean usableBy(UUID player) { return !isPrivate || ownedBy(player) || allowed.contains(player); }
    }
    /** What happened when a player stood at a portal. */
    public enum Visit { OWN, ADDED, KNOWN, PRIVATE }

    public static final int NAME_MAX = 24;
    public static final int KNOWN_MAX = 200;
    public static final int ALLOWED_MAX = 50;
    static final String PORTAL = "portal", KNOWN = "portal_known";

    PortalService(TransactionalStore s, Clock c, Supplier<UUID> i) { super(s, c, i); }

    public static String id(String world, int x, int y, int z) { return world + "@" + x + "," + y + "," + z; }

    // ------------------------------------------------------------------------------------------------ pads

    /** Records a newly placed pad, replacing anything left at that spot. The owner learns it at once. */
    public Portal place(UUID owner, String ownerName, String world, int x, int y, int z) {
        Objects.requireNonNull(owner); text(world, "world", 200);
        String id = id(world, x, y, z);
        return store.transaction(tx -> {
            String name = defaultName(tx, owner, ownerName);
            var old = tx.find(PORTAL, id);
            var saved = tx.save(PORTAL, id, old.map(TransactionalStore.Row::revision).orElse(0L), fields("world", world, "x", x, "y", y, "z", z,
                "owner", owner, "ownerName", ownerName == null ? "" : ownerName, "name", name, "private", false, "allowed", "", "placed", clock.instant(), "state", "ACTIVE"));
            var made = portal(saved);
            learn(tx, owner, made);
            return made;
        });
    }

    /**
     * A pad placed before portals were tracked. Adopted by whoever placed it when the game still knows; otherwise it has
     * no owner and stays public. Returns the existing record when there is one.
     */
    public Portal adopt(String world, int x, int y, int z, UUID placedBy, String placerName) {
        String id = id(world, x, y, z);
        var existing = find(id);
        if (existing.isPresent()) return existing.get();
        if (placedBy != null) return place(placedBy, placerName, world, x, y, z);
        return store.transaction(tx -> portal(tx.save(PORTAL, id, tx.find(PORTAL, id).map(TransactionalStore.Row::revision).orElse(0L), fields("world", world, "x", x, "y", y, "z", z,
            "owner", "", "ownerName", "", "name", "Old portal", "private", false, "allowed", "", "placed", clock.instant(), "state", "ACTIVE"))));
    }

    /** The pad is gone. Lists that held it drop it when they are next read. */
    public void remove(String id) {
        store.transaction(tx -> { tx.find(PORTAL, id).filter(PortalService::active).ifPresent(r -> save(tx, r, "state", "REMOVED")); return null; });
    }

    public Optional<Portal> find(String id) {
        return store.transaction(tx -> tx.find(PORTAL, id).filter(PortalService::active).map(PortalService::portal));
    }

    /** Every pad in one world, for the map index. */
    public List<Portal> inWorld(String world) {
        return store.transaction(tx -> tx.scan(PORTAL).stream().filter(PortalService::active).filter(r -> r.value("world").equals(world)).map(PortalService::portal).toList());
    }

    /** Every pad, for loading the map index at start. */
    public List<Portal> all() {
        return store.transaction(tx -> tx.scan(PORTAL).stream().filter(PortalService::active).map(PortalService::portal).toList());
    }

    // ------------------------------------------------------------------------------------------------ lists

    /** A player stands at a portal. It joins their list unless it is private to them. */
    public Visit visit(UUID player, String id) {
        return store.transaction(tx -> {
            var p = portal(tx.find(PORTAL, id).filter(PortalService::active).orElseThrow(() -> new DomainException(NOT_FOUND, "This portal is not set up yet.")));
            if (p.ownedBy(player)) { learn(tx, player, p); return Visit.OWN; }
            if (!p.usableBy(player)) return Visit.PRIVATE;
            var list = knownIds(tx, player);
            if (list.contains(entry(p))) return Visit.KNOWN;
            require(list.size() < KNOWN_MAX, CAPACITY, "Your portal list is full. Forget a portal first.");
            learn(tx, player, p);
            return Visit.ADDED;
        });
    }

    /** The player's portals they may use now, their own first, then by name. Removed pads drop out for good. */
    public List<Portal> known(UUID player) {
        return store.transaction(tx -> {
            var ids = knownIds(tx, player);
            var rows = tx.findAll(PORTAL, ids.stream().map(PortalService::idOf).distinct().toList());
            var out = new ArrayList<Portal>(); var keep = new ArrayList<String>();
            for (String e : ids) {
                var row = rows.get(idOf(e));
                if (row == null || !active(row)) continue;
                var p = portal(row);
                // A pad placed again on the same spot is a new portal: the old entry does not carry over.
                if (!entry(p).equals(e)) continue;
                keep.add(e);
                if (p.usableBy(player)) out.add(p);
            }
            if (keep.size() != ids.size()) saveKnown(tx, player, keep);
            out.sort(Comparator.comparing((Portal p) -> !p.ownedBy(player)).thenComparing(p -> p.name().toLowerCase(Locale.ROOT)).thenComparing(Portal::id));
            return List.copyOf(out);
        });
    }

    /** Takes a portal off the player's list. Their own portals stay. */
    public void forget(UUID player, String id) {
        store.transaction(tx -> {
            tx.find(PORTAL, id).map(PortalService::portal).ifPresent(p -> require(!p.ownedBy(player), INVALID_INPUT, "Your own portals stay on your list."));
            var ids = knownIds(tx, player);
            if (ids.removeIf(e -> idOf(e).equals(id))) saveKnown(tx, player, ids);
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ owner settings

    public Portal rename(UUID actor, boolean staff, String id, String name) {
        String clean = cleanName(name);
        return edit(actor, staff, id, r -> fields("name", clean));
    }

    public Portal setPrivate(UUID actor, boolean staff, String id, boolean on) {
        return edit(actor, staff, id, r -> fields("private", on));
    }

    /** Lets one player use a private portal. They still need to visit it once to add it to their list. */
    public Portal allow(UUID actor, boolean staff, String id, UUID other) {
        return edit(actor, staff, id, r -> {
            var allowed = new LinkedHashSet<>(parse(r.value("allowed")));
            require(!other.toString().equals(r.value("owner")), INVALID_INPUT, "The owner can always use this portal.");
            require(allowed.contains(other) || allowed.size() < ALLOWED_MAX, CAPACITY, "This portal already allows " + ALLOWED_MAX + " players.");
            allowed.add(other);
            return fields("allowed", join(allowed));
        });
    }

    public Portal disallow(UUID actor, boolean staff, String id, UUID other) {
        return edit(actor, staff, id, r -> {
            var allowed = new LinkedHashSet<>(parse(r.value("allowed"))); allowed.remove(other);
            return fields("allowed", join(allowed));
        });
    }

    /** Owner or staff. An ownerless old pad can be changed by staff only. */
    public static boolean mayManage(Portal p, UUID actor, boolean staff) { return staff || p.ownedBy(actor); }

    private Portal edit(UUID actor, boolean staff, String id, java.util.function.Function<TransactionalStore.Row, Map<String, String>> change) {
        return store.transaction(tx -> {
            var row = tx.find(PORTAL, id).filter(PortalService::active).orElseThrow(() -> new DomainException(NOT_FOUND, "This portal is gone."));
            require(mayManage(portal(row), actor, staff), FORBIDDEN, "Only the owner can change this portal.");
            var merged = new TreeMap<>(row.fields()); merged.putAll(change.apply(row));
            return portal(tx.save(PORTAL, id, row.revision(), merged));
        });
    }

    /** Trims and checks a portal name: 1 to 24 letters, digits, spaces and simple punctuation. */
    public static String cleanName(String name) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        require(!clean.isEmpty(), INVALID_INPUT, "Give the portal a name.");
        require(clean.length() <= NAME_MAX, INVALID_INPUT, "Portal names can be up to " + NAME_MAX + " characters.");
        require(clean.matches("[\\p{L}\\p{N} '.,!?&()#:+]+"), INVALID_INPUT, "Use letters, numbers and spaces in the name.");
        return clean;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private String defaultName(TransactionalStore.Transaction tx, UUID owner, String ownerName) {
        String base = (ownerName == null || ownerName.isBlank() ? "My" : ownerName + "'s") + " portal";
        if (base.length() > NAME_MAX - 3) base = "Portal";
        long mine = tx.scan(PORTAL).stream().filter(PortalService::active).filter(r -> r.value("owner").equals(owner.toString())).count();
        return mine == 0 ? base : base + " " + (mine + 1);
    }

    private void learn(TransactionalStore.Transaction tx, UUID player, Portal p) {
        var ids = knownIds(tx, player);
        if (ids.contains(entry(p))) return;
        ids.removeIf(e -> idOf(e).equals(p.id()));
        ids.add(entry(p));
        saveKnown(tx, player, ids);
    }

    /** A list entry: the portal and when it was placed, so a pad placed again on the same spot starts unknown. */
    private static String entry(Portal p) { return p.id() + "|" + p.placed(); }
    private static String idOf(String entry) { int bar = entry.lastIndexOf('|'); return bar < 0 ? entry : entry.substring(0, bar); }

    private static List<String> knownIds(TransactionalStore.Transaction tx, UUID player) {
        return tx.find(KNOWN, player.toString()).map(r -> r.value("ids")).filter(s -> !s.isEmpty())
            .map(s -> new ArrayList<>(Arrays.asList(s.split("\n")))).orElseGet(ArrayList::new);
    }

    private static void saveKnown(TransactionalStore.Transaction tx, UUID player, List<String> ids) {
        var old = tx.find(KNOWN, player.toString());
        tx.save(KNOWN, player.toString(), old.map(TransactionalStore.Row::revision).orElse(0L), fields("ids", String.join("\n", ids)));
    }

    private static boolean active(TransactionalStore.Row r) { return r.value("state").equals("ACTIVE"); }

    private static Portal portal(TransactionalStore.Row r) {
        String owner = r.value("owner");
        return new Portal(r.key(), r.value("world"), (int) r.number("x"), (int) r.number("y"), (int) r.number("z"),
            owner.isEmpty() ? null : UUID.fromString(owner), r.value("ownerName"), r.value("name"), r.value("private").equals("true"),
            parse(r.value("allowed")), Instant.parse(r.value("placed")));
    }

    private static Set<UUID> parse(String text) {
        var out = new LinkedHashSet<UUID>();
        if (text != null) for (String s : text.split(",")) if (!s.isBlank()) out.add(UUID.fromString(s.trim()));
        return out;
    }

    private static String join(Collection<UUID> ids) { return String.join(",", ids.stream().map(UUID::toString).toList()); }
}
