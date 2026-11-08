package com.hexvane.eterniamod.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Aggregation in memory, travel filtering and the boss list. */
class StatsTrackingTest {
    private static final long SECOND = 1_000_000_000L;
    private final UUID ada = new UUID(0, 1), bo = new UUID(0, 2);

    @Test void tallyAddsUpPerPlayerAndWorld() {
        var tally = new StatsTally();
        tally.add(ada, "hub", StatsFields.CHESTS, 1); tally.add(ada, "hub", StatsFields.CHESTS, 2); tally.add(ada, "wilds", StatsFields.CHESTS, 1);
        tally.add(bo, "hub", StatsFields.BLOCKS, 0); // zero and below are ignored
        tally.kill(ada, "wilds", "Trork_Warrior");
        tally.death(bo, "wilds", "Drowning", null);
        var drained = tally.drain();
        assertEquals(3, drained.size());
        assertEquals(3L, drained.get(new StatsTally.Slot(ada, "hub")).counts().get(StatsFields.CHESTS));
        assertEquals(Map.of(StatsFields.CHESTS, 1L, StatsFields.KILLS, 1L, "kill.Trork_Warrior", 1L), drained.get(new StatsTally.Slot(ada, "wilds")).counts());
        assertEquals(Map.of(StatsFields.DEATHS, 1L, "death.Drowning", 1L), drained.get(new StatsTally.Slot(bo, "wilds")).counts());
        assertTrue(tally.isEmpty()); assertTrue(tally.drain().isEmpty());
    }

    @Test void restoreMergesWithNewerChanges() {
        var tally = new StatsTally();
        tally.add(ada, "hub", StatsFields.BLOCKS, 10);
        tally.boss(new StatsTally.BossKill(ada, "hub", "gobliterator", "normal", Instant.EPOCH));
        var drained = tally.drain();
        tally.add(ada, "hub", StatsFields.BLOCKS, 5);
        tally.restore(drained);
        var again = tally.drain().get(new StatsTally.Slot(ada, "hub"));
        assertEquals(15L, again.counts().get(StatsFields.BLOCKS));
        assertEquals(1, again.bosses().size());
        assertEquals(1L, again.counts().get("boss.gobliterator.normal"));
    }

    @Test void fieldPartsAreSafeForStorage() {
        assertEquals("kill.Some_Mod_Wolf", StatsFields.kill("Some Mod:Wolf"));
        assertEquals("death.unknown", StatsFields.death(null));
        assertEquals(new StatsTally.Slot(ada, "my_world"), new StatsTally.Slot(ada, "my|world"));
    }

    @Test void walkingCountsWholeBlocksAndCarriesFractions() {
        var travel = new TravelTracker();
        assertEquals(0, travel.sample(ada, "hub", 0, 64, 0, 0));
        assertEquals(4, travel.sample(ada, "hub", 4.5, 64, 0, SECOND));
        assertEquals(1, travel.sample(ada, "hub", 5.0, 64, 0, 2 * SECOND)); // half a block carried plus half a block
        assertEquals(0, travel.sample(ada, "hub", 5.01, 64, 0, 3 * SECOND)); // standing still wobble
        assertEquals(5, travel.sample(ada, "hub", 5.01, 64, 5, 4 * SECOND)); // 3D distance, including vertical moves
    }

    @Test void teleportsAndWorldChangesNeverCount() {
        var travel = new TravelTracker();
        travel.sample(ada, "hub", 0, 64, 0, 0);
        assertEquals(0, travel.sample(ada, "hub", 900, 64, 0, SECOND), "a waygate jump of 900 blocks in a second");
        assertEquals(3, travel.sample(ada, "hub", 903, 64, 0, 2 * SECOND), "walking resumes from the new spot");
        assertEquals(0, travel.sample(ada, "wilds", 905, 64, 0, 3 * SECOND), "another world starts fresh");
        travel.reset(ada);
        assertEquals(0, travel.sample(ada, "wilds", 915, 64, 0, 4 * SECOND), "after a teleport the next sample is a new baseline");
        assertEquals(2, travel.sample(ada, "wilds", 917, 64, 0, 5 * SECOND));
        assertEquals(0, travel.sample(ada, "wilds", 937, 64, 0, 5 * SECOND + 30 * SECOND), "a long pause restarts the baseline");
        travel.forget(ada);
        assertEquals(0, travel.sample(ada, "wilds", 950, 64, 0, 40 * SECOND));
    }

    @Test void fastHonestTravelStillCounts() {
        var travel = new TravelTracker();
        travel.sample(ada, "wilds", 0, 200, 0, 0);
        assertEquals(30, travel.sample(ada, "wilds", 0, 170, 0, SECOND), "a long fall");
        assertEquals(0, travel.sample(ada, "wilds", 0, 120, 0, SECOND + SECOND / 2), "100 blocks a second is no fall");
    }

    @Test void shippedBossListLoadsAndMatchesHardMode() {
        var catalog = BossCatalog.load(getClass().getClassLoader());
        assertTrue(catalog.bosses().size() >= 4);
        var hard = catalog.match("Goblin_Gobliterator_Phase3_HM").orElseThrow();
        assertEquals("gobliterator", hard.boss().id()); assertEquals("hard", hard.difficulty());
        assertEquals("normal", catalog.match("Goblin_Gobliterator_Phase3").orElseThrow().difficulty());
        assertTrue(catalog.match("Goblin_Scrapper").isEmpty());
        assertEquals(64, catalog.creditRange());
    }

    @Test void bossListRejectsMistakes() {
        assertThrows(IllegalArgumentException.class, () -> BossCatalog.parse(new StringReader(
            "{\"Bosses\":[{\"Id\":\"a\",\"Name\":\"A\",\"Roles\":{\"X\":\"normal\"}},{\"Id\":\"b\",\"Name\":\"B\",\"Roles\":{\"X\":\"hard\"}}]}")));
        assertThrows(IllegalArgumentException.class, () -> BossCatalog.parse(new StringReader("{\"Bosses\":[{\"Id\":\"Bad Id\",\"Name\":\"A\",\"Roles\":{\"X\":\"normal\"}}]}")));
    }

    @Test void friendlyWords() {
        assertEquals("0", StatsText.number(0)); assertEquals("12,345", StatsText.number(12345));
        assertEquals("1.2 million", StatsText.number(1_290_000)); assertEquals("3 million", StatsText.number(3_000_000));
        assertEquals("0m", StatsText.duration(59)); assertEquals("45m", StatsText.duration(45 * 60));
        assertEquals("3h 20m", StatsText.duration(3 * 3600 + 20 * 60)); assertEquals("2d 4h", StatsText.duration(52 * 3600));
        assertEquals("from falls", StatsText.causePhrase("Fall")); assertEquals("Hard", StatsText.difficulty("hard"));
        assertEquals("Goblin Scrapper", StatsText.friendly("Goblin_Scrapper"));
    }
}
