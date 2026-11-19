package com.hexvane.eterniamod.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.wildland.WildAccess;
import com.hexvane.eterniamod.wildland.WildLandIndex;
import com.hexvane.eterniamod.wildland.WildLandSettings;
import com.hexvane.eterniamod.wildland.WildPerm;
import com.hexvane.eterniamod.wildland.WildRules.Reason;
import java.io.StringReader;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Land in adventure worlds: Coin prices, the gap between claims, limits, giving land up, guild land and friends. */
class WildLandServiceTest {
    private static final String WORLD = "wilds";
    private static final WildLandSettings SETTINGS = WildLandSettings.DEFAULTS;
    private final EterniaServices services = new EterniaServices(new InMemoryStore(), Clock.systemUTC(), UUID::randomUUID);

    private UUID player(String name, long coins) {
        UUID id = UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(id, name);
        if (coins > 0) services.economy().credit(Owner.player(id), coins, "test:" + id);
        return id;
    }

    private WildLandService.Outcome add(Owner owner, UUID actor, HexCoord cell) {
        return services.wildLand().add(WORLD, owner, actor, cell, SETTINGS, c -> false);
    }

    private long coins(UUID id) { return services.economy().balance(Owner.player(id)).available(); }

    @Test void eachHexagonCostsMoreThanTheLast() {
        UUID mira = player("Mira", 10_000);
        Owner me = Owner.player(mira);
        HexCoord cell = HexCoord.ORIGIN;
        assertEquals(0, add(me, mira, cell).coins(), "the first hexagon is free");
        assertEquals(250, add(me, mira, cell.neighbor(0)).coins(), "the second");
        assertEquals(350, add(me, mira, cell.neighbor(1)).coins(), "then 100 more each time");
        assertEquals(450, add(me, mira, cell.neighbor(2)).coins());
        assertEquals(10_000 - 1050, coins(mira));
        assertEquals(4, services.wildLand().claimOf(me, WORLD).orElseThrow().cells().size());
    }

    @Test void newLandHasToTouchAndNeverReachesPastTheMost() {
        UUID mira = player("Mira", 1_000_000);
        Owner me = Owner.player(mira);
        add(me, mira, HexCoord.ORIGIN);
        assertEquals(Reason.NOT_TOUCHING, add(me, mira, new HexCoord(5, 5)).reason());
        while (services.wildLand().claimOf(me, WORLD).orElseThrow().cells().size() < SETTINGS.player().maxCells()) {
            var mine = services.wildLand().claimOf(me, WORLD).orElseThrow().cells();
            HexCoord grow = mine.cells().stream().flatMap(c -> c.neighbors().stream()).filter(c -> !mine.contains(c)).findFirst().orElseThrow();
            assertTrue(add(me, mira, grow).ok());
        }
        var claim = services.wildLand().claimOf(me, WORLD).orElseThrow();
        HexCoord next = claim.cells().cells().stream().flatMap(c -> c.neighbors().stream()).filter(c -> !claim.cells().contains(c)).findFirst().orElseThrow();
        var refused = add(me, mira, next);
        assertEquals(Reason.MAX_CELLS, refused.reason());
        assertEquals(SETTINGS.player().maxCells(), refused.count());
    }

    @Test void nobodyClaimsRightBesideSomeoneElse() {
        UUID mira = player("Mira", 5000), tom = player("Tom", 5000);
        add(Owner.player(mira), mira, HexCoord.ORIGIN);
        var touching = add(Owner.player(tom), tom, HexCoord.ORIGIN.neighbor(0));
        assertEquals(Reason.TOO_CLOSE, touching.reason());
        assertEquals("Mira", touching.name(), "the refusal names whose land is in the way");
        assertEquals(Reason.TAKEN, add(Owner.player(tom), tom, HexCoord.ORIGIN).reason());
        assertTrue(add(Owner.player(tom), tom, new HexCoord(2, 0)).ok(), "one free ring between is enough");
        assertEquals(5000, coins(tom), "the first hexagon is free and refusals take nothing");
    }

    @Test void notEnoughCoinsTakesNothing() {
        UUID poor = player("Poor", 100);
        assertTrue(add(Owner.player(poor), poor, HexCoord.ORIGIN).ok(), "anyone can claim a first hexagon");
        var refused = add(Owner.player(poor), poor, HexCoord.ORIGIN.neighbor(0));
        assertEquals(Reason.COINS, refused.reason());
        assertEquals(250, refused.count());
        assertEquals(100, coins(poor));
        assertEquals(1, services.wildLand().claimOf(Owner.player(poor), WORLD).orElseThrow().cells().size());
    }

    @Test void givingLandUpRefundsHalfAndNeverSplitsIt() {
        UUID mira = player("Mira", 5000);
        Owner me = Owner.player(mira);
        HexCoord a = HexCoord.ORIGIN, b = a.neighbor(0), c = b.neighbor(0);
        add(me, mira, a); add(me, mira, b); add(me, mira, c);
        UUID claim = services.wildLand().claimOf(me, WORLD).orElseThrow().id();
        assertEquals(Reason.SPLITS, services.wildLand().release(claim, mira, b, SETTINGS).reason());
        long before = coins(mira);
        var gaveUp = services.wildLand().release(claim, mira, c, SETTINGS);
        assertTrue(gaveUp.ok());
        assertEquals(175, gaveUp.coins(), "half of the 350 it cost");
        assertEquals(before + 175, coins(mira));
        var all = services.wildLand().abandon(claim, mira, SETTINGS);
        assertEquals(125, all.coins(), "half of the 250 second hexagon; the free one gives nothing");
        assertTrue(services.wildLand().claimOf(me, WORLD).isEmpty());
        UUID tom = player("Tom", 500);
        assertTrue(add(Owner.player(tom), tom, a).ok(), "free for anyone after");
    }

    @Test void onlyTheOwnerChangesTheirLand() {
        UUID mira = player("Mira", 5000), tom = player("Tom", 5000);
        add(Owner.player(mira), mira, HexCoord.ORIGIN);
        UUID claim = services.wildLand().claimOf(Owner.player(mira), WORLD).orElseThrow().id();
        assertThrows(DomainException.class, () -> add(Owner.player(mira), tom, HexCoord.ORIGIN.neighbor(0)));
        assertThrows(DomainException.class, () -> services.wildLand().release(claim, tom, HexCoord.ORIGIN, SETTINGS));
        assertThrows(DomainException.class, () -> services.wildLand().trust(claim, tom, tom.toString(), WildPerm.ALL));
    }

    @Test void guildLandIsPaidFromGuildCoinsByThoseWhoMayManageIt() {
        UUID leader = player("Leader", 0), member = player("Member", 0);
        var guild = services.guilds().create(leader, "Builders", "create-1");
        services.guilds().acceptInvite(member, services.guilds().invite(leader, member));
        Owner estate = Owner.guild(guild.id());
        assertTrue(add(estate, leader, HexCoord.ORIGIN).ok(), "a guild's first hexagon is free too");
        assertEquals(Reason.GUILD_COINS, add(estate, leader, HexCoord.ORIGIN.neighbor(0)).reason());
        // Members fill the guild's purse from their own Coins.
        services.economy().credit(Owner.player(member), 5000, "member-start");
        assertEquals(5000, services.wildLand().giveGuildCoins(member, 5000, "gift-1"));
        assertEquals(5000, services.wildLand().giveGuildCoins(member, 5000, "gift-1"), "a retried gift is not paid twice");
        assertEquals(0, coins(member));
        var refused = assertThrows(DomainException.class, () -> add(estate, member, HexCoord.ORIGIN.neighbor(0)));
        assertEquals(DomainException.Code.FORBIDDEN, refused.code(), "a plain member may not spend the guild's Coins");
        var second = add(estate, leader, HexCoord.ORIGIN.neighbor(0));
        assertTrue(second.ok());
        assertEquals(500, second.coins(), "guild land has its own prices");
        assertEquals(4500, services.economy().balance(estate).available());
        // A member's own land may sit right beside their guild's.
        services.economy().credit(Owner.player(member), 1000, "member-coins");
        assertTrue(add(Owner.player(member), member, HexCoord.ORIGIN.neighbor(3)).ok());
    }

    @Test void friendsAndGuildRolesDecideWhoMayBuild() {
        UUID mira = player("Mira", 5000), tom = player("Tom", 0), ann = player("Ann", 0);
        add(Owner.player(mira), mira, HexCoord.ORIGIN);
        UUID claim = services.wildLand().claimOf(Owner.player(mira), WORLD).orElseThrow().id();
        services.wildLand().trust(claim, mira, tom.toString(), Set.of(WildPerm.CONTAINERS));
        services.wildLand().trust(claim, mira, WildLandService.EVERYONE, Set.of(WildPerm.USE));
        WildLandSettings.set(SETTINGS);
        WildLandIndex.reload(services);
        var land = WildLandIndex.byId(claim);
        assertTrue(WildAccess.allowed(land, mira, Optional.empty(), WildPerm.BUILD));
        assertTrue(WildAccess.allowed(land, tom, Optional.empty(), WildPerm.CONTAINERS));
        assertFalse(WildAccess.allowed(land, tom, Optional.empty(), WildPerm.BUILD));
        assertTrue(WildAccess.allowed(land, ann, Optional.empty(), WildPerm.USE), "everyone may use doors here");
        assertFalse(WildAccess.allowed(land, ann, Optional.empty(), WildPerm.CONTAINERS));
        services.wildLand().trust(claim, mira, tom.toString(), Set.of());
        WildLandIndex.reload(services);
        assertFalse(WildAccess.allowed(WildLandIndex.byId(claim), tom, Optional.empty(), WildPerm.CONTAINERS), "removed friends lose access");

        UUID leader = player("Leader", 0), member = player("Member2", 0);
        var guild = services.guilds().create(leader, "Diggers", "create-2");
        services.guilds().acceptInvite(member, services.guilds().invite(leader, member));
        services.economy().credit(Owner.guild(guild.id()), 5000, "guild-test-2");
        add(Owner.guild(guild.id()), leader, new HexCoord(10, 10));
        WildLandIndex.reload(services);
        var estate = WildLandIndex.at(WORLD, new HexCoord(10, 10));
        var standing = services.guilds().standing(member);
        assertTrue(WildAccess.allowed(estate, member, standing, WildPerm.BUILD), "members build on guild land by default");
        assertFalse(WildAccess.manages(estate, member, standing));
        assertTrue(WildAccess.manages(estate, leader, services.guilds().standing(leader)));
        assertFalse(WildAccess.allowed(estate, mira, services.guilds().standing(mira), WildPerm.BUILD));
    }

    @Test void olderGuildsGetLandPermissionsOnce() {
        UUID leader = player("Leader", 0);
        services.guilds().create(leader, "Old", "create-3");
        // Strip the new permissions, as a guild made before land existed would be.
        services.store().transaction(tx -> {
            for (var r : tx.scan("guild_role")) {
                var caps = new java.util.TreeSet<>(List.of(r.value("caps").split("\n")));
                caps.removeIf(c -> c.startsWith("wild."));
                tx.save("guild_role", r.key(), r.revision(), new java.util.TreeMap<>(Map.of("guild", r.value("guild"), "id", r.value("id"), "name", r.value("name"), "rank", r.value("rank"), "caps", String.join("\n", caps))));
            }
            return null;
        });
        assertTrue(services.guilds().grantWildLand() > 0);
        assertEquals(0, services.guilds().grantWildLand(), "runs only once");
        assertTrue(services.guilds().standing(leader).orElseThrow().capabilities().contains(WildLandService.MANAGE));
    }

    @Test void pricesAndSettingsReadFromTheFile() throws Exception {
        var shipped = WildLandSettings.load(getClass().getClassLoader(), null);
        assertEquals(WildLandSettings.DEFAULTS, shipped, "the shipped file matches the defaults");
        var custom = WildLandSettings.parse(new StringReader("""
            {"Worlds":["Frontier"],"BufferRings":2,"RefundShare":0,
             "Player":{"MaxCells":3,"FirstCoins":999,"Prices":[100,400],"StepCoins":50},"Lattice":{"CellWidth":30}}"""));
        assertEquals(0, custom.player().price(0), "the first hexagon is free whatever the file says");
        assertEquals(400, custom.player().price(2));
        assertEquals(450, custom.player().price(3), "past the list: the last price plus the step");
        assertEquals(2, custom.bufferRings());
        assertEquals(0, custom.refund(1000));
        assertTrue(custom.coversWorld("frontier"));
        assertFalse(custom.coversWorld("other"));
        assertEquals(32, custom.cellWidth(), "a width that is not a multiple of 4 falls back");
        assertEquals(SETTINGS.guild(), custom.guild(), "missing sections keep the defaults");
    }
}
