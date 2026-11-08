package com.hexvane.eterniamod.gatehouse;

import static com.hexvane.eterniamod.gatehouse.GatehouseFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.socialui.SeasonHall;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatehouseBoardTest {
    @Test void noSeasonsGivesAnEmptyBoardWithAFriendlyGreeting() {
        var board = empty();
        assertTrue(board.passes().isEmpty());
        assertNull(board.bounties());
        assertNull(board.premium());
        assertTrue(board.greeting().contains("When a new season opens"));
        assertEquals("", GatehouseDesk.passCount(board));
    }

    @Test void aPassThatIsNotActiveAsksThePlayerToChooseOne() {
        var board = oneNotActive();
        assertEquals(1, board.passes().size());
        assertTrue(board.active().isEmpty());
        assertNull(board.bounties(), "no bounties without an active pass");
        assertTrue(board.greeting().contains("Castle Aeterna"));
        assertEquals("1 pass, yours to keep. Choose one to earn XP.", GatehouseDesk.passCount(board));
        assertEquals("Foundations", board.premium().passName());
        assertFalse(board.premium().owned());
    }

    @Test void severalPassesKeepTheirOrderAndOnlyOneIsActive() {
        var board = severalActive();
        assertEquals(List.of("Foundations", "Tidewater", "Emberfall"), board.passes().stream().map(SeasonHall.Banner::name).toList());
        assertEquals(1, board.passes().stream().filter(SeasonHall.Banner::active).count());
        assertEquals("Tidewater", board.active().orElseThrow().name());
        // Foundations: level 6, free level 1 already claimed, free level 5 ready, premium not owned.
        assertEquals(1, board.passes().get(0).ready());
        // Tidewater: level 2 with premium, free and premium level 1 rewards ready.
        assertEquals(2, board.passes().get(1).ready());
        assertEquals(3, board.readyToClaim());
        assertTrue(board.greeting().contains("3 rewards waiting"));
        assertEquals("Tidewater", board.premium().passName());
        assertTrue(board.premium().owned());
    }

    @Test void bountiesSplitByResetWithOpenOnesFirst() {
        var bounties = severalActive().bounties();
        assertEquals("eternia:tidewater", bounties.passId());
        assertEquals(List.of("A miner's morning", "On patrol"), bounties.daily().stream().map(GatehouseBoard.Bounty::name).toList());
        assertFalse(bounties.daily().get(0).done());
        assertTrue(bounties.daily().get(1).done());
        assertEquals(1, bounties.weekly().size());
        assertEquals(2, bounties.open());
        assertEquals(3, bounties.size());
        // Saturday 19:20 UTC: dailies turn over at midnight, weeklies at the start of Monday.
        assertEquals("New in 5 hours", bounties.dailyReset());
        assertEquals("New in 29 hours", bounties.weeklyReset());
    }

    @Test void resetTimesReadNaturally() {
        Instant now = Instant.parse("2026-11-07T23:59:30Z");
        assertEquals("1 minute", GatehouseBoard.until(now, Instant.parse("2026-11-08T00:00:00Z")));
        assertEquals("40 minutes", GatehouseBoard.until(now, now.plusSeconds(40 * 60)));
        assertEquals("1 hour", GatehouseBoard.until(now, now.plusSeconds(60 * 60)));
        assertEquals("47 hours", GatehouseBoard.until(now, now.plusSeconds(47 * 3600)));
        assertEquals("3 days", GatehouseBoard.until(now, now.plusSeconds(60 * 3600)));
    }

    @Test void playerTextHasNoDashesUsedAsPauses() {
        for (var board : List.of(empty(), oneNotActive(), severalActive(), overfull())) {
            assertFalse(board.greeting().contains(" - ") || board.greeting().contains("—") || board.greeting().contains("–"), board.greeting());
            assertFalse(GatehouseDesk.passCount(board).contains("—"));
        }
    }
}
