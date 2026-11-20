package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TravelServiceTest {
    @Test void firstRandomTeleportIsFreeThenCostsCoins() {
        var s = new EterniaServices(new InMemoryStore()); UUID p = UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(p, "Aria"); s.economy().credit(Owner.player(p), 250, "test-coins");
        long start = s.economy().balance(Owner.player(p)).available();
        assertEquals(0, s.travel().randomPrice(p));
        assertEquals(0, s.travel().chargeRandom(p, "a1"));
        assertEquals(0, s.travel().chargeRandom(p, "a1"), "a retry pays nothing more");
        assertEquals(TravelService.RANDOM_PRICE, s.travel().randomPrice(p));
        assertEquals(TravelService.RANDOM_PRICE, s.travel().chargeRandom(p, "a2"));
        assertEquals(start - TravelService.RANDOM_PRICE, s.economy().balance(Owner.player(p)).available());
        s.travel().refundRandom(p, "a2"); s.travel().refundRandom(p, "a2");
        assertEquals(start, s.economy().balance(Owner.player(p)).available(), "refunded once");
    }

    @Test void aFailedFreeTripStaysFree() {
        var s = new EterniaServices(new InMemoryStore()); UUID p = UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(p, "Aria");
        s.travel().chargeRandom(p, "a1"); s.travel().refundRandom(p, "a1");
        assertEquals(0, s.travel().randomPrice(p));
    }

    @Test void notEnoughCoinsMovesNothing() {
        var s = new EterniaServices(new InMemoryStore()); UUID p = UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(p, "Aria");
        long have = s.economy().balance(Owner.player(p)).available();
        if (have > 0) s.economy().transfer(Owner.player(p), Owner.player(UUID.randomUUID()), have, "drain");
        s.travel().chargeRandom(p, "free");
        assertThrows(DomainException.class, () -> s.travel().chargeRandom(p, "paid"));
        assertEquals(0, s.economy().balance(Owner.player(p)).available());
    }

    @Test void requestCooldownLastsThirtyMinutes() {
        var now = new AtomicReference<>(Instant.parse("2026-11-20T10:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId z) { return this; }
            public Instant instant() { return now.get(); }
        };
        var store = new InMemoryStore();
        var travel = new TravelService(store, clock, UUID::randomUUID, new EconomyService(store, clock, UUID::randomUUID));
        UUID p = UUID.randomUUID();
        assertEquals(Duration.ZERO, travel.requestCooldown(p));
        travel.useRequest(p);
        assertEquals(Duration.ofMinutes(30), travel.requestCooldown(p));
        assertThrows(DomainException.class, () -> travel.useRequest(p));
        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertEquals(Duration.ZERO, travel.requestCooldown(p));
        assertDoesNotThrow(() -> travel.useRequest(p));
    }
}
