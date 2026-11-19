package com.hexvane.eterniamod.domain;

import static com.hexvane.eterniamod.domain.DomainException.Code.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.persistence.TransactionalStore;
import com.hexvane.eterniamod.wildland.WildLandSettings;
import com.hexvane.eterniamod.wildland.WildPerm;
import com.hexvane.eterniamod.wildland.WildRules;
import com.hexvane.eterniamod.wildland.WildRules.Reason;
import java.time.Clock;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Land in adventure worlds: plain block claims, no houses or roads. Each player and each guild may hold one connected
 * patch of hexagons per world, bought with Coins at prices that rise with every hexagon ({@link WildLandSettings}).
 * Owners let friends build, open chests or use doors; guild land follows guild role permissions.
 *
 * <p>Rows: {@code wild_claim} (one per patch: world, owner, lattice, cells, what each hexagon cost), {@code wild_trust}
 * (claim and player, or {@code *} for everyone, with what they may do) and {@code wild_world} (one per world, rewritten
 * by every change so two claims in the same world never both pass the gap check).
 */
public final class WildLandService extends DomainSupport {
    static final String CLAIMS = "wild_claim", TRUST = "wild_trust", WORLD = "wild_world";
    /** Guild role capability to claim, grow, give up and share guild land, paid from the guild's Coins. */
    public static final String MANAGE = "wild.claim";
    /** The trust entry that applies to everyone. */
    public static final String EVERYONE = "*";

    private final EconomyService economy;

    /** One patch of land. {@code paid} is what each hexagon cost, for refunds. */
    public record Claim(UUID id, String world, Owner owner, PlotCells cells, Map<HexCoord, Long> paid, long revision) {
        public Claim { paid = Map.copyOf(paid); }
    }

    /** A change's result: refused with a reason (and the number or name it mentions), or done with the Coins it moved. */
    public record Outcome(boolean ok, Reason reason, long count, String name, long coins, UUID claimId) {
        static Outcome refused(Reason reason) { return new Outcome(false, reason, 0, null, 0, null); }
        static Outcome refused(Reason reason, long count) { return new Outcome(false, reason, count, null, 0, null); }
        static Outcome done(long coins, UUID claimId) { return new Outcome(true, null, 0, null, coins, claimId); }
        public String message() { return ok ? "" : reason.text(count, name); }
    }

    WildLandService(TransactionalStore store, Clock clock, Supplier<UUID> ids, EconomyService economy) {
        super(store, clock, ids);
        this.economy = economy;
    }

    // ---- Reading -------------------------------------------------------------------------------------------------

    /** Every live claim in every world. */
    public List<Claim> all() { return store.transaction(tx -> claimsIn(tx, null)); }

    /** Every live claim in {@code world}. */
    public List<Claim> claims(String world) { return store.transaction(tx -> claimsIn(tx, world)); }

    public Optional<Claim> find(UUID claim) { return store.transaction(tx -> tx.find(CLAIMS, claim.toString()).filter(WildLandService::live).map(WildLandService::claim)); }

    /** The owner's land in {@code world}, if any. */
    public Optional<Claim> claimOf(Owner owner, String world) {
        return claims(world).stream().filter(c -> c.owner().equals(owner)).findFirst();
    }

    /** Who is trusted on a claim and with what: player id (or {@link #EVERYONE}) to permissions. */
    public Map<String, Set<WildPerm>> trusts(UUID claim) { return store.transaction(tx -> trustsIn(tx, claim)); }

    /** Every trust entry of every claim: claim id to its entries. */
    public Map<UUID, Map<String, Set<WildPerm>>> allTrusts() {
        return store.transaction(tx -> {
            Map<UUID, Map<String, Set<WildPerm>>> out = new HashMap<>();
            for (var r : tx.scan(TRUST)) {
                var perms = WildPerm.parse(r.value("perms"));
                if (perms.isEmpty()) continue;
                out.computeIfAbsent(UUID.fromString(r.value("claim")), k -> new HashMap<>()).put(r.value("who"), Set.copyOf(perms));
            }
            return out;
        });
    }

    // ---- Changing land -------------------------------------------------------------------------------------------

    /**
     * Adds {@code cell} to the owner's land in {@code world}, starting it when they have none. {@code actor} must be the
     * player themself, or a member with {@link #MANAGE} for guild land (the guild's Coins pay). {@code blocked} is land
     * kept free for everyone (portals, past the border).
     */
    public Outcome add(String world, Owner owner, UUID actor, HexCoord cell, WildLandSettings settings, Predicate<HexCoord> blocked) {
        return store.transaction(tx -> {
            requireManager(tx, owner, actor);
            touchWorld(tx, world);
            HexLattice lattice = settings.lattice();
            List<Claim> here = claimsIn(tx, world).stream().filter(c -> c.cells().latticeId().equals(lattice.id())).toList();
            Map<HexCoord, String> land = landOf(here);
            Claim mine = here.stream().filter(c -> c.owner().equals(owner)).findFirst().orElse(null);
            Set<HexCoord> cells = mine == null ? Set.of() : mine.cells().asSet();
            var tier = settings.tier(owner.kind() == Owner.Kind.GUILD);
            Predicate<String> friendly = friends(tx, owner);
            Reason refused = WildRules.add(land, cells, cell, owner.key(), settings.bufferRings(), friendly, tier.maxCells(), blocked);
            if (refused == Reason.MAX_CELLS) return Outcome.refused(refused, tier.maxCells());
            if (refused == Reason.TOO_CLOSE) {
                String other = WildRules.blockedBy(land, cell, settings.bufferRings(), o -> o.equals(owner.key()) || friendly.test(o));
                return new Outcome(false, refused, 0, other == null ? null : nameOf(tx, Owner.parse(other)), 0, null);
            }
            if (refused != null) return Outcome.refused(refused);
            long price = tier.price(cells.size());
            if (price > 0 && economy.balanceIn(tx, owner).available() < price)
                return Outcome.refused(owner.kind() == Owner.Kind.GUILD ? Reason.GUILD_COINS : Reason.COINS, price);
            UUID id = mine == null ? ids.get() : mine.id();
            if (price > 0) economy.spendIn(tx, owner, price, "wild-land:" + id + ":" + cell + ":" + clock.millis() + ":" + ids.get());
            Map<HexCoord, Long> paid = new HashMap<>(mine == null ? Map.of() : mine.paid());
            paid.put(cell, price);
            PlotCells next = mine == null ? PlotCells.of(lattice, List.of(cell)) : mine.cells().with(cell);
            if (mine == null) {
                tx.save(CLAIMS, id.toString(), 0, fields("world", world, "owner", owner.key(), "lattice", lattice.id(), "cells", next.text(),
                    "paid", paidText(paid), "state", "LIVE", "since", clock.millis()));
            } else {
                save(tx, row(tx, CLAIMS, id.toString()), "cells", next.text(), "paid", paidText(paid));
            }
            return Outcome.done(price, id);
        });
    }

    /** Gives up one hexagon, refunding part of what it cost. Giving up the last one ends the claim. */
    public Outcome release(UUID claimId, UUID actor, HexCoord cell, WildLandSettings settings) {
        return store.transaction(tx -> {
            var row = tx.find(CLAIMS, claimId.toString()).filter(WildLandService::live).orElse(null);
            if (row == null) return Outcome.refused(Reason.CHANGED);
            Claim claim = claim(row);
            requireManager(tx, claim.owner(), actor);
            Reason refused = WildRules.release(claim.cells().asSet(), cell);
            if (refused != null) return Outcome.refused(refused);
            touchWorld(tx, claim.world());
            long back = settings.refund(claim.paid().getOrDefault(cell, 0L));
            if (back > 0) economy.creditIn(tx, claim.owner(), back, "wild-land-refund:" + claimId + ":" + cell + ":" + row.revision());
            Map<HexCoord, Long> paid = new HashMap<>(claim.paid());
            paid.remove(cell);
            if (claim.cells().size() == 1) save(tx, row, "state", "ENDED", "paid", "");
            else save(tx, row, "cells", claim.cells().without(cell).text(), "paid", paidText(paid));
            return Outcome.done(back, claimId);
        });
    }

    /** Gives up the whole claim, refunding part of what every hexagon cost. */
    public Outcome abandon(UUID claimId, UUID actor, WildLandSettings settings) {
        return store.transaction(tx -> {
            var row = tx.find(CLAIMS, claimId.toString()).filter(WildLandService::live).orElse(null);
            if (row == null) return Outcome.refused(Reason.CHANGED);
            Claim claim = claim(row);
            requireManager(tx, claim.owner(), actor);
            touchWorld(tx, claim.world());
            long back = 0;
            for (long paid : claim.paid().values()) back = Math.addExact(back, settings.refund(paid));
            if (back > 0) economy.creditIn(tx, claim.owner(), back, "wild-land-abandon:" + claimId + ":" + row.revision());
            save(tx, row, "state", "ENDED", "paid", "");
            return Outcome.done(back, claimId);
        });
    }

    /** Staff: ends a claim without refunds. */
    public boolean remove(UUID claimId) {
        return store.transaction(tx -> {
            var row = tx.find(CLAIMS, claimId.toString()).filter(WildLandService::live).orElse(null);
            if (row == null) return false;
            touchWorld(tx, row.value("world"));
            save(tx, row, "state", "ENDED", "paid", "");
            return true;
        });
    }

    // ---- Guild Coins ----------------------------------------------------------------------------------------------

    /**
     * A member gives {@code amount} of their own Coins to their guild, for guild land. Needs the deposit permission every
     * built in role has. {@code requestId} makes a retry safe. Returns the guild's Coins after.
     */
    public long giveGuildCoins(UUID member, long amount, String requestId) {
        positive(amount);
        text(requestId, "request", 160);
        return store.transaction(tx -> {
            var membership = GuildService.currentMember(tx, member);
            UUID guild = UUID.fromString(membership.value("guild"));
            GuildService.requireCapability(tx, member, guild, "treasury.deposit");
            Owner purse = Owner.guild(guild);
            // A retry with the same request finds its receipt first and pays nothing again.
            try { economy.transferIn(tx, Owner.player(member), purse, amount, "guild-coins:" + member + ":" + requestId); }
            catch (DomainException short_) { if (short_.code() == INSUFFICIENT_BALANCE) throw new DomainException(INSUFFICIENT_BALANCE, "You need more Coins."); throw short_; }
            return economy.balanceIn(tx, purse).available();
        });
    }

    // ---- Friends -------------------------------------------------------------------------------------------------

    /**
     * Sets what {@code who} (a player id, or {@link #EVERYONE}) may do on the claim. An empty set removes them. Only
     * someone who may manage the land may change it.
     */
    public void trust(UUID claimId, UUID actor, String who, Set<WildPerm> perms) {
        store.transaction(tx -> {
            var claim = claim(tx.find(CLAIMS, claimId.toString()).filter(WildLandService::live).orElseThrow(() -> new DomainException(NOT_FOUND, "That land is gone.")));
            requireManager(tx, claim.owner(), actor);
            if (!who.equals(EVERYONE)) {
                UUID player = UUID.fromString(who);
                require(!(claim.owner().kind() == Owner.Kind.PLAYER && claim.owner().id().equals(player)), INVALID_INPUT, "It is already your land.");
            }
            String key = claimId + "|" + who;
            var prior = tx.find(TRUST, key);
            tx.save(TRUST, key, prior.map(TransactionalStore.Row::revision).orElse(0L), fields("claim", claimId, "who", who, "perms", WildPerm.text(perms)));
            return null;
        });
    }

    // ---- Helpers -------------------------------------------------------------------------------------------------

    private static boolean live(TransactionalStore.Row r) { return r.value("state").equals("LIVE"); }

    static List<Claim> claimsIn(TransactionalStore.Transaction tx, String world) {
        List<Claim> out = new ArrayList<>();
        for (var r : tx.scan(CLAIMS)) {
            if (!live(r) || world != null && !r.value("world").equals(world)) continue;
            try { out.add(claim(r)); } catch (RuntimeException unreadable) { /* a broken row never blocks the rest */ }
        }
        out.sort(Comparator.comparing(c -> c.id().toString()));
        return out;
    }

    private static Claim claim(TransactionalStore.Row r) {
        PlotCells cells = PlotCells.parse(r.value("lattice"), r.value("cells"));
        return new Claim(UUID.fromString(r.key()), r.value("world"), Owner.parse(r.value("owner")), cells, paidOf(r.value("paid")), r.revision());
    }

    private static Map<String, Set<WildPerm>> trustsIn(TransactionalStore.Transaction tx, UUID claim) {
        Map<String, Set<WildPerm>> out = new TreeMap<>();
        for (var r : tx.scan(TRUST)) {
            if (!r.value("claim").equals(claim.toString())) continue;
            var perms = WildPerm.parse(r.value("perms"));
            if (!perms.isEmpty()) out.put(r.value("who"), Set.copyOf(perms));
        }
        return out;
    }

    static Map<HexCoord, String> landOf(Collection<Claim> claims) {
        Map<HexCoord, String> land = new HashMap<>();
        for (Claim c : claims) for (HexCoord cell : c.cells().cells()) land.put(cell, c.owner().key());
        return land;
    }

    /** Who may sit right beside {@code owner}'s land: a player's own guild, and a guild's own members. */
    private static Predicate<String> friends(TransactionalStore.Transaction tx, Owner owner) {
        if (owner.kind() == Owner.Kind.PLAYER) {
            String guild = tx.find("guild_member", owner.id().toString()).filter(r -> r.value("active").equals("true")).map(r -> Owner.guild(UUID.fromString(r.value("guild"))).key()).orElse(null);
            return other -> other.equals(guild);
        }
        return other -> {
            Owner o = Owner.parse(other);
            if (o.kind() != Owner.Kind.PLAYER) return false;
            return tx.find("guild_member", o.id().toString()).filter(r -> r.value("active").equals("true") && r.value("guild").equals(owner.id().toString())).isPresent();
        };
    }

    private static void requireManager(TransactionalStore.Transaction tx, Owner owner, UUID actor) {
        require(actor != null, FORBIDDEN, Reason.NOT_YOURS.text(0, null));
        if (owner.kind() == Owner.Kind.GUILD) {
            var membership = tx.find("guild_member", actor.toString()).filter(r -> r.value("active").equals("true") && r.value("guild").equals(owner.id().toString()));
            require(membership.isPresent(), FORBIDDEN, Reason.NOT_YOURS.text(0, null));
            try { GuildService.requireCapability(tx, actor, owner.id(), MANAGE); }
            catch (DomainException missing) { throw new DomainException(FORBIDDEN, Reason.FORBIDDEN.text(0, null)); }
            return;
        }
        require(owner.kind() == Owner.Kind.PLAYER && owner.id().equals(actor), FORBIDDEN, Reason.NOT_YOURS.text(0, null));
    }

    /** Rewrites the world's row so a claim made at the same moment in the same world fails and is retried. */
    private void touchWorld(TransactionalStore.Transaction tx, String world) {
        var r = tx.find(WORLD, world);
        tx.save(WORLD, world, r.map(TransactionalStore.Row::revision).orElse(0L), fields("changed", clock.millis()));
    }

    private static String nameOf(TransactionalStore.Transaction tx, Owner owner) {
        if (owner.kind() == Owner.Kind.GUILD) return tx.find("guild", owner.id().toString()).map(r -> r.value("name")).orElse("a guild");
        return tx.find("account", owner.id().toString()).map(r -> r.value("name")).orElse("someone");
    }

    static String paidText(Map<HexCoord, Long> paid) {
        StringBuilder out = new StringBuilder();
        paid.entrySet().stream().sorted(Map.Entry.comparingByKey(HexCoord.READING_ORDER))
            .forEach(e -> out.append(out.isEmpty() ? "" : ";").append(e.getKey()).append('=').append(e.getValue()));
        return out.toString();
    }

    static Map<HexCoord, Long> paidOf(String text) {
        Map<HexCoord, Long> out = new HashMap<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(HexCoord.parse(part.substring(0, eq)), Long.parseLong(part.substring(eq + 1)));
        }
        return out;
    }
}
