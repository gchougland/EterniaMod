package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FriendServiceTest {
    private record F(EterniaServices s, FriendService friends, UUID aria, UUID bram, UUID cass) {}
    private F fixture() {
        var s = new EterniaServices(new InMemoryStore());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(a, "Aria"); s.accounts().recordAuthenticatedLogin(b, "Bram"); s.accounts().recordAuthenticatedLogin(c, "Cass");
        return new F(s, s.friends(), a, b, c);
    }

    @Test void requestsAreAcceptedOnce() {
        var f = fixture();
        assertFalse(f.friends.request(f.aria, f.bram));
        assertFalse(f.friends.request(f.aria, f.bram), "asking twice keeps one request");
        assertEquals(1, f.friends.incoming(f.bram).size());
        assertEquals(1, f.friends.outgoing(f.aria).size());
        f.friends.accept(f.bram, f.aria);
        assertTrue(f.friends.areFriends(f.aria, f.bram));
        assertTrue(f.friends.incoming(f.bram).isEmpty());
        assertThrows(DomainException.class, () -> f.friends.accept(f.bram, f.aria));
        assertThrows(DomainException.class, () -> f.friends.request(f.aria, f.bram), "already friends");
        assertThrows(DomainException.class, () -> f.friends.request(f.aria, f.aria));
        assertEquals(f.bram, f.friends.friends(f.aria).getFirst().id());
    }

    @Test void askingBackMakesFriendsAtOnce() {
        var f = fixture();
        f.friends.request(f.aria, f.bram);
        assertTrue(f.friends.request(f.bram, f.aria));
        assertTrue(f.friends.areFriends(f.aria, f.bram));
        assertTrue(f.friends.outgoing(f.aria).isEmpty());
    }

    @Test void declineCancelAndRemove() {
        var f = fixture();
        f.friends.request(f.aria, f.bram); f.friends.decline(f.bram, f.aria);
        assertTrue(f.friends.incoming(f.bram).isEmpty());
        f.friends.request(f.aria, f.cass); f.friends.cancel(f.aria, f.cass);
        assertTrue(f.friends.incoming(f.cass).isEmpty());
        f.friends.request(f.aria, f.bram); f.friends.accept(f.bram, f.aria);
        f.friends.share(f.aria, f.bram, EnumSet.of(FriendService.Share.VISIT));
        f.friends.remove(f.bram, f.aria);
        assertFalse(f.friends.areFriends(f.aria, f.bram));
        assertFalse(f.friends.allows(f.aria, f.bram, "housing.door.use"), "removing a friend takes back plot access");
    }

    @Test void plotSharesFollowTheSwitches() {
        var f = fixture();
        f.friends.request(f.aria, f.bram); f.friends.accept(f.bram, f.aria);
        assertFalse(f.friends.allows(f.aria, f.bram, "housing.door.use"), "nothing is shared until you turn it on");
        f.friends.share(f.aria, f.bram, EnumSet.of(FriendService.Share.VISIT));
        assertTrue(f.friends.allows(f.aria, f.bram, "housing.door.use"));
        assertTrue(f.friends.allows(f.aria, f.bram, "housing.visit"));
        assertFalse(f.friends.allows(f.aria, f.bram, "housing.container.open"));
        assertFalse(f.friends.allows(f.bram, f.aria, "housing.door.use"), "sharing goes one way");
        assertFalse(f.friends.allows(f.aria, f.bram, "housing.prop.place"), "friends never decorate");
        f.friends.share(f.aria, f.bram, EnumSet.allOf(FriendService.Share.class));
        assertTrue(f.friends.allows(f.aria, f.bram, "housing.container.open"));
        assertThrows(DomainException.class, () -> f.friends.share(f.aria, f.cass, EnumSet.of(FriendService.Share.VISIT)), "only friends");
    }

    @Test void blockingStopsRequestsMailAndGuildInvites() {
        var f = fixture();
        f.friends.request(f.aria, f.bram); f.friends.accept(f.bram, f.aria);
        f.friends.block(f.bram, f.aria);
        assertFalse(f.friends.areFriends(f.aria, f.bram));
        assertEquals(List.of(f.aria), f.friends.blocked(f.bram));
        assertThrows(DomainException.class, () -> f.friends.request(f.aria, f.bram));
        assertThrows(DomainException.class, () -> f.s.mail().checkSend(f.aria, f.bram, "Hi", "Hello", 0));
        f.s.guilds().create(f.aria, "Aria's Guild", "aria-guild");
        assertThrows(DomainException.class, () -> f.s.guilds().invite(f.aria, f.bram));
        f.friends.unblock(f.bram, f.aria);
        assertTrue(f.friends.blocked(f.bram).isEmpty());
        assertDoesNotThrow(() -> f.s.guilds().invite(f.aria, f.bram));
        assertFalse(f.friends.request(f.aria, f.bram));
    }

    @Test void alertsAreOnUntilTurnedOff() {
        var f = fixture();
        assertTrue(f.friends.alerts(f.aria));
        f.friends.setAlerts(f.aria, false);
        assertFalse(f.friends.alerts(f.aria));
    }
}
