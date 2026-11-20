package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuildProfileTest {
    private record Fixture(EterniaServices services, UUID leader, UUID officerless, UUID joiner, UUID other) {}
    private static final GuildBanner RED = new GuildBanner("crimson", "chief", "gold", "swords", "white");

    private Fixture fixture() {
        var services = new EterniaServices(new InMemoryStore());
        UUID leader = UUID.randomUUID(), member = UUID.randomUUID(), joiner = UUID.randomUUID(), other = UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(leader, "Aria");
        services.accounts().recordAuthenticatedLogin(member, "Bram");
        services.accounts().recordAuthenticatedLogin(joiner, "Cass");
        services.accounts().recordAuthenticatedLogin(other, "Dorn");
        return new Fixture(services, leader, member, joiner, other);
    }

    @Test void startingAGuildKeepsItsBannerTagAndRule() {
        var f = fixture(); var guilds = f.services.guilds();
        var made = guilds.create(f.leader, "Ember Vanguard", " emb ", "Bring snacks.", RED, GuildService.JoinRule.OPEN, "start-1");
        assertEquals("EMB", made.tag()); assertEquals("Bring snacks.", made.motto()); assertEquals(RED, made.banner());
        assertEquals(made, guilds.create(f.leader, "Ember Vanguard", "EMB", "Bring snacks.", RED, GuildService.JoinRule.OPEN, "start-1"), "a retry starts no second guild");
        assertEquals(1, guilds.directory().size());
        assertEquals(1, guilds.directory().getFirst().members());
        assertThrows(DomainException.class, () -> guilds.create(f.other, "Other", "emb", "", RED, GuildService.JoinRule.OPEN, "start-2"), "tags are unique");
        assertTrue(guilds.membership(f.other).isEmpty(), "a refused guild leaves nothing behind");
        assertEquals(1, guilds.directory().size());
        assertThrows(DomainException.class, () -> guilds.create(f.other, "Other", "TOOLONG", "", RED, GuildService.JoinRule.OPEN, "start-3"));
        assertThrows(DomainException.class, () -> guilds.create(f.other, "Two\nlines", "", "", RED, GuildService.JoinRule.OPEN, "start-4"));
    }

    @Test void oldGuildsGetABannerAndTakeInvitationsOnly() {
        var f = fixture(); var guilds = f.services.guilds();
        var old = guilds.create(f.leader, "Old Guild", "old-guild");
        var profile = guilds.profile(old.id()).orElseThrow();
        assertEquals(GuildService.JoinRule.INVITE, profile.join());
        assertEquals(GuildBanner.forGuild(old.id()), profile.banner());
        assertEquals("", profile.tag());
        assertThrows(DomainException.class, () -> guilds.join(f.joiner, old.id()));
        assertThrows(DomainException.class, () -> guilds.requestJoin(f.joiner, old.id()));
    }

    @Test void openGuildsLetAnyoneJoinOnce() {
        var f = fixture(); var guilds = f.services.guilds();
        var g = guilds.create(f.leader, "Willowmere", "WIL", "", RED, GuildService.JoinRule.OPEN, "willow");
        assertEquals(g.id(), guilds.join(f.joiner, g.id()).guildId());
        assertThrows(DomainException.class, () -> guilds.join(f.joiner, g.id()), "already a member");
        assertEquals(2, guilds.members(g.id()).size());
    }

    @Test void requestsWaitForSomeoneWhoCanInvite() {
        var f = fixture(); var guilds = f.services.guilds();
        var g = guilds.create(f.leader, "Starfall", "STR", "", RED, GuildService.JoinRule.ASK, "star");
        guilds.acceptInvite(f.officerless, guilds.invite(f.leader, f.officerless));
        guilds.requestJoin(f.joiner, g.id()); guilds.requestJoin(f.joiner, g.id());
        assertEquals(Set.of(g.id()), guilds.pendingRequests(f.joiner));
        assertEquals(1, guilds.requests(f.leader).size(), "asking twice keeps one request");
        assertTrue(guilds.requests(f.officerless).isEmpty(), "plain members do not see requests");
        assertThrows(DomainException.class, () -> guilds.answerRequest(f.officerless, f.joiner, true));
        assertTrue(guilds.answerRequest(f.leader, f.joiner, true));
        assertEquals(g.id(), guilds.membership(f.joiner).orElseThrow().guildId());
        assertTrue(guilds.pendingRequests(f.joiner).isEmpty());
        assertThrows(DomainException.class, () -> guilds.answerRequest(f.leader, f.joiner, true), "a request is answered once");

        guilds.requestJoin(f.other, g.id());
        assertTrue(guilds.answerRequest(f.leader, f.other, false));
        assertTrue(guilds.membership(f.other).isEmpty());
    }

    @Test void joiningAnotherGuildClosesOtherRequests() {
        var f = fixture(); var guilds = f.services.guilds();
        var asks = guilds.create(f.leader, "Asks", "ASK", "", RED, GuildService.JoinRule.ASK, "asks");
        var open = guilds.create(f.other, "Open", "OPN", "", RED, GuildService.JoinRule.OPEN, "open");
        guilds.requestJoin(f.joiner, asks.id());
        guilds.join(f.joiner, open.id());
        assertTrue(guilds.requests(f.leader).isEmpty());
    }

    @Test void onlyTheLeaderChangesSettings() {
        var f = fixture(); var guilds = f.services.guilds();
        var g = guilds.create(f.leader, "Moonlit", "MNC", "", RED, GuildService.JoinRule.INVITE, "moon");
        guilds.acceptInvite(f.officerless, guilds.invite(f.leader, f.officerless));
        var blue = RED.withField("navy").withEmblem("moon");
        assertThrows(DomainException.class, () -> guilds.updateProfile(f.officerless, "MNC", "", blue, GuildService.JoinRule.OPEN));
        var changed = guilds.updateProfile(f.leader, "moon", "Night owls.", blue, GuildService.JoinRule.OPEN);
        assertEquals("MOON", changed.tag()); assertEquals(blue, guilds.profile(g.id()).orElseThrow().banner());
        assertEquals(GuildService.JoinRule.OPEN, changed.join());
    }

    @Test void bannersRejectUnknownPartsAndSurviveStorage() {
        assertThrows(DomainException.class, () -> new GuildBanner("crimson", "plain", "gold", "unicorn", "white"));
        assertThrows(DomainException.class, () -> new GuildBanner("plaid", "plain", "gold", "star", "white"));
        assertEquals(Optional.of(RED), GuildBanner.decode(RED.encode()));
        assertTrue(GuildBanner.decode("crimson;plain").isEmpty());
        var random = new Random(4);
        for (int i = 0; i < 200; i++) assertNotNull(GuildBanner.random(random));
        assertTrue(GuildBanner.COLOURS.size() >= 24 && GuildBanner.PATTERNS.size() >= 25 && GuildBanner.EMBLEMS.size() >= 50);
    }

    @Test void everyBannerPartHasItsPicture() {
        Path art = Path.of("src/main/resources/Common/UI/Custom/EterniaMod/Guild/Banner");
        for (String base : List.of("Field", "Shade", "Trim")) assertTrue(Files.exists(art.resolve(base + "@2x.png")), base);
        for (var p : GuildBanner.PATTERNS) if (!p.id().equals("plain")) assertTrue(Files.exists(art.resolve("Pattern_" + p.id() + "@2x.png")), p.id());
        for (var e : GuildBanner.EMBLEMS) assertTrue(Files.exists(art.resolve("Emblem_" + e.id() + "@2x.png")), e.id());
    }
}
