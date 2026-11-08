package com.hexvane.eterniamod.stats;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class StatsServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private final UUID ada = new UUID(0, 1), bo = new UUID(0, 2), cy = new UUID(0, 3);

    /** A store that counts transactions and can fail the next one. */
    private static class CountingStore implements TransactionalStore {
        final InMemoryStore inner = new InMemoryStore(); final AtomicInteger transactions = new AtomicInteger(); boolean failNext;
        @Override public <T> T transaction(Function<Transaction, T> work) {
            transactions.incrementAndGet();
            if (failNext) { failNext = false; throw new IllegalStateException("database offline"); }
            return inner.transaction(work);
        }
    }

    private EterniaServices services(TransactionalStore store) {
        var services = new EterniaServices(store, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
        services.welcomePlayer(ada, "Ada"); services.welcomePlayer(bo, "Bo"); services.welcomePlayer(cy, "Cy");
        services.stats().onlineCheck(id -> false);
        return services;
    }

    @Test void flushWritesTotalsAndWorldsAndAccumulates() {
        var services = services(new InMemoryStore()); var tally = new StatsTally();
        tally.add(ada, "hub", StatsFields.CHESTS, 2); tally.add(ada, "wilds", StatsFields.CHESTS, 3); tally.add(ada, "wilds", StatsFields.LOOT_CHESTS, 1);
        tally.kill(ada, "wilds", "Goblin_Scrapper"); tally.kill(ada, "wilds", "Goblin_Scrapper"); tally.kill(ada, "wilds", "Feran_Hunter");
        tally.death(ada, "wilds", "Fall", null); tally.death(ada, "wilds", "Physical", "Goblin_Scrapper");
        tally.add(ada, "wilds", StatsFields.BLOCKS, 120);
        services.stats().apply(tally.drain());
        assertTrue(tally.isEmpty());
        tally.add(ada, "wilds", StatsFields.BLOCKS, 30); tally.kill(ada, "wilds", "Feran_Hunter");
        services.stats().apply(tally.drain());

        var stats = services.stats().player(ada).orElseThrow();
        assertEquals("Ada", stats.name());
        assertEquals(5, stats.get(StatsFields.CHESTS)); assertEquals(1, stats.get(StatsFields.LOOT_CHESTS));
        assertEquals(150, stats.get(StatsFields.BLOCKS)); assertEquals(4, stats.get(StatsFields.KILLS)); assertEquals(2, stats.get(StatsFields.DEATHS));
        assertEquals(Map.of("Goblin_Scrapper", 2L, "Feran_Hunter", 2L), stats.kills());
        assertEquals(Map.of("Fall", 1L, "Physical", 1L), stats.deaths());
        assertEquals(Map.of("Goblin_Scrapper", 1L), stats.slainBy());
        assertEquals(List.of("hub", "wilds"), stats.worlds().stream().map(StatsService.WorldStats::world).toList());
        assertEquals(2, stats.worlds().get(0).get(StatsFields.CHESTS));
        assertEquals(150, stats.worlds().get(1).get(StatsFields.BLOCKS));
        assertEquals(2L, services.stats().kills(ada, "Feran_Hunter"));
        assertEquals(0L, services.stats().kills(bo, "Feran_Hunter"));
    }

    @Test void newPlayerHasEmptyStatsAndUnknownPlayerHasNone() {
        var services = services(new InMemoryStore());
        var stats = services.stats().player(bo).orElseThrow();
        assertTrue(stats.isEmpty()); assertTrue(stats.worlds().isEmpty()); assertTrue(stats.bosses().isEmpty());
        assertTrue(services.stats().player(new UUID(9, 9)).isEmpty());
    }

    @Test void serverFirstIsTheEarliestAndIsNeverReplaced() {
        var services = services(new InMemoryStore()); var tally = new StatsTally();
        // Same flush: Bo's victory happened first even though Ada's was tallied first.
        tally.boss(new StatsTally.BossKill(ada, "dungeon", "gobliterator", "hard", NOW.plusSeconds(5)));
        tally.boss(new StatsTally.BossKill(bo, "dungeon", "gobliterator", "hard", NOW));
        tally.boss(new StatsTally.BossKill(ada, "dungeon", "gobliterator", "normal", NOW.plusSeconds(1)));
        var claimed = services.stats().apply(tally.drain());
        assertEquals(2, claimed.size());
        tally.boss(new StatsTally.BossKill(cy, "dungeon", "gobliterator", "hard", NOW.minusSeconds(60)));
        assertTrue(services.stats().apply(tally.drain()).isEmpty(), "a later flush never claims a saved first");

        var firsts = services.stats().firsts();
        assertEquals(2, firsts.size());
        var hard = firsts.stream().filter(f -> f.difficulty().equals("hard")).findFirst().orElseThrow();
        assertEquals(bo, hard.player()); assertEquals("Bo", hard.name()); assertEquals(NOW, hard.at());
        assertEquals(ada, firsts.stream().filter(f -> f.difficulty().equals("normal")).findFirst().orElseThrow().player());
    }

    @Test void playersKeepTheirOwnEarliestVictory() {
        var services = services(new InMemoryStore()); var tally = new StatsTally();
        tally.boss(new StatsTally.BossKill(ada, "dungeon", "feastmaster", "normal", NOW.plusSeconds(100)));
        services.stats().apply(tally.drain());
        tally.boss(new StatsTally.BossKill(ada, "dungeon", "feastmaster", "normal", NOW.plusSeconds(200)));
        services.stats().apply(tally.drain());
        var stats = services.stats().player(ada).orElseThrow();
        assertEquals(2, stats.get(StatsFields.BOSSES));
        var record = stats.bosses().getFirst();
        assertEquals("feastmaster", record.boss()); assertEquals(2, record.victories()); assertEquals(NOW.plusSeconds(100), record.first());
    }

    @Test void topRanksSharesTiesAndSkipsZero() {
        var services = services(new InMemoryStore()); var tally = new StatsTally();
        tally.add(ada, "w", StatsFields.CHESTS, 5); tally.add(bo, "w", StatsFields.CHESTS, 9); tally.add(cy, "w", StatsFields.CHESTS, 5);
        tally.add(cy, "w", StatsFields.BLOCKS, 3);
        services.stats().apply(tally.drain());
        var top = services.stats().top(StatsFields.CHESTS, 10);
        assertEquals(List.of("Bo", "Ada", "Cy"), top.stream().map(StatsService.Ranked::name).toList());
        assertEquals(List.of(1, 2, 2), top.stream().map(StatsService.Ranked::rank).toList());
        assertEquals(1, services.stats().top(StatsFields.CHESTS, 1).size());
        assertEquals(List.of("Cy"), services.stats().top(StatsFields.BLOCKS, 10).stream().map(StatsService.Ranked::name).toList());
        assertThrows(IllegalArgumentException.class, () -> services.stats().top("gold", 3));
    }

    @Test void playtimeComesFromTheAccountAndCountsOnlyAnOnlineSession() {
        var store = new InMemoryStore(); var services = services(store);
        store.transaction(tx -> { var row = tx.find("account", ada.toString()).orElseThrow(); var f = new TreeMap<>(row.fields());
            f.put("playSeconds", "3600"); f.put("sessionStarted", NOW.minusSeconds(600).toString()); return tx.save("account", ada.toString(), row.revision(), f); });
        assertEquals(3600, services.stats().player(ada).orElseThrow().playSeconds(), "an offline player's stale session adds nothing");
        services.stats().onlineCheck(id -> true);
        assertEquals(4200, services.stats().player(ada).orElseThrow().playSeconds());
        assertEquals(ada, services.stats().top(StatsFields.PLAYTIME, 1).getFirst().player());
    }

    @Test void manySlotsAreWrittenInBoundedTransactions() {
        var store = new CountingStore(); var services = services(store); var tally = new StatsTally();
        for (int i = 0; i < StatsService.SLOTS_PER_TRANSACTION + 10; i++) tally.add(ada, "world" + i, StatsFields.CHESTS, 1);
        int before = store.transactions.get();
        services.stats().apply(tally.drain());
        assertEquals(2, store.transactions.get() - before);
        assertEquals(StatsService.SLOTS_PER_TRANSACTION + 10, services.stats().player(ada).orElseThrow().get(StatsFields.CHESTS));
    }

    @Test void aFlushFailingHalfwayRetriesOnlyWhatWasNotSaved() {
        var store = new CountingStore() {
            int calls;
            @Override public <T> T transaction(Function<Transaction, T> work) { if (++calls == 2 && armed) throw new IllegalStateException("lost connection"); return super.transaction(work); }
            boolean armed;
        };
        var services = services(store); var tally = new StatsTally();
        for (int i = 0; i < StatsService.SLOTS_PER_TRANSACTION + 6; i++) tally.add(ada, "world" + i, StatsFields.CHESTS, 1);
        store.calls = 0; store.armed = true;
        var failure = assertThrows(StatsService.FlushFailure.class, () -> services.stats().apply(tally.drain()));
        assertEquals(6, failure.unsaved().size());
        store.armed = false; tally.restore(failure.unsaved());
        services.stats().apply(tally.drain());
        assertEquals(StatsService.SLOTS_PER_TRANSACTION + 6, services.stats().player(ada).orElseThrow().get(StatsFields.CHESTS), "nothing counted twice");
    }

    @Test void aFailedFlushKeepsEverythingForTheNextOne() {
        var store = new CountingStore(); var services = services(store); var tally = new StatsTally();
        tally.add(ada, "w", StatsFields.CHESTS, 4);
        var drained = tally.drain();
        store.failNext = true;
        var failure = assertThrows(StatsService.FlushFailure.class, () -> services.stats().apply(drained));
        tally.restore(failure.unsaved());
        tally.add(ada, "w", StatsFields.CHESTS, 1);
        services.stats().apply(tally.drain());
        assertEquals(5, services.stats().player(ada).orElseThrow().get(StatsFields.CHESTS));
    }
}
