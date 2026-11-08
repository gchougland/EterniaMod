package com.hexvane.eterniamod.gatehouse;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Switching and claiming at the desk, against the real SeasonService on an in-memory store. */
class GatehouseActionsTest {
    private static UUID player(EterniaServices s) { UUID id = UUID.randomUUID(); s.accounts().recordAuthenticatedLogin(id, "GateTester"); return id; }
    private static void earn(EterniaServices s, UUID actor, long xp) {
        s.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(), actor, SeasonService.ActivityKind.MINE, "Rock_Stone", 1, xp, UUID.randomUUID().toString(), true));
    }

    @Test void switchingKeepsProgressAndOnlyTheActivePassEarns() {
        var s = new EterniaServices(new InMemoryStore()); UUID actor = player(s);
        s.seasons().register(GatehouseFixtures.FOUNDATIONS); s.seasons().register(GatehouseFixtures.TIDE);
        assertTrue(GatehouseActions.activate(s.seasons(), actor, "eternia:foundations").startsWith("Foundations is now your active pass"));
        earn(s, actor, 4500);
        GatehouseActions.activate(s.seasons(), actor, "eternia:tidewater");
        earn(s, actor, 2100);
        var progress = new HashMap<String, SeasonService.Progress>();
        s.seasons().progress(actor).forEach(p -> progress.put(p.id(), p));
        assertEquals(4500, progress.get("eternia:foundations").xp());
        assertFalse(progress.get("eternia:foundations").active());
        assertEquals(2100, progress.get("eternia:tidewater").xp());
        assertTrue(progress.get("eternia:tidewater").active());
        assertThrows(IllegalArgumentException.class, () -> GatehouseActions.activate(s.seasons(), actor, "eternia:missing"));
    }

    @Test void claimAllTakesEveryReachedFreeRewardOnceAndSkipsPremiumWhenNotOwned() {
        var s = new EterniaServices(new InMemoryStore()); UUID actor = player(s);
        s.seasons().register(GatehouseFixtures.FOUNDATIONS);
        assertTrue(GatehouseActions.claimAll(s.seasons(), actor, "eternia:foundations").startsWith("Nothing new to claim"));
        GatehouseActions.activate(s.seasons(), actor, "eternia:foundations");
        earn(s, actor, 10000); // level 5
        assertEquals("2 rewards from Foundations added to your collection.", GatehouseActions.claimAll(s.seasons(), actor, "eternia:foundations"));
        assertTrue(GatehouseActions.claimAll(s.seasons(), actor, "eternia:foundations").startsWith("Nothing new to claim"));
        var board = GatehouseBoard.load(s.seasons(), GatehouseFixtures.NAMES, actor, Instant.now());
        assertEquals(0, board.readyToClaim());
        assertEquals("Foundations", board.bounties().passName());
        assertEquals(3, board.bounties().size());
    }

    @Test void claimAllIncludesPremiumOnceOwned() {
        var s = new EterniaServices(new InMemoryStore()); UUID actor = player(s);
        s.seasons().register(GatehouseFixtures.FOUNDATIONS);
        GatehouseActions.activate(s.seasons(), actor, "eternia:foundations");
        earn(s, actor, 2000); // level 1
        s.ownership().grant(new OwnershipService.GrantRequest("test:premium:" + actor, Owner.player(actor), SeasonService.paidEntitlementId("eternia:foundations"), OwnershipService.Kind.CAPABILITY, 1, null));
        assertEquals("2 rewards from Foundations added to your collection.", GatehouseActions.claimAll(s.seasons(), actor, "eternia:foundations"));
    }
}
