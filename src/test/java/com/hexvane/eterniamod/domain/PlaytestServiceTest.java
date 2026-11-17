package com.hexvane.eterniamod.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.persistence.LocalFileStore;
import com.hexvane.eterniamod.runtime.PlaytestSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaytestServiceTest {
    private final InMemoryStore store = new InMemoryStore();
    private final EterniaServices services = new EterniaServices(store);
    private final UUID tester = UUID.randomUUID(), early = UUID.randomUUID();

    @Test void theStartingAllowanceIsGivenOnceEvenIfTheAmountChanges() {
        services.welcomePlayer(tester, "Tester");
        long coinsBefore = services.economy().balance(Owner.player(tester)).available();
        assertTrue(services.playtest().grantStartingAllowance(tester, 5000, 2000));
        assertFalse(services.playtest().grantStartingAllowance(tester, 5000, 2000), "only once");
        assertFalse(services.playtest().grantStartingAllowance(tester, 9000, 9000), "raising the amount later gives nothing more");
        assertEquals(5000, services.premium().balance(tester).available());
        assertEquals(coinsBefore + 2000, services.economy().balance(Owner.player(tester)).available());
        assertFalse(services.playtest().grantStartingAllowance(UUID.randomUUID(), 0, 0), "nothing to give when both are zero");
    }

    @Test void theBetaWipeKeepsOnlyTheAlphaTesters() {
        services.welcomePlayer(tester, "Tester");
        services.playtest().recordAlphaTester(tester, "Tester");
        services.welcomePlayer(early, "Early");   // joined before testers were recorded: no alpha_tester row yet
        services.playtest().grantStartingAllowance(tester, 5000, 2000);
        services.memories().record(tester, List.of(new MemoryService.Entry("Kweebec", "t", "l", 1)));

        var preview = PlaytestService.preview(store);
        assertEquals(2, preview.alphaTesters(), "the preview counts the early player too");
        assertTrue(preview.total() > 0);
        assertFalse(preview.records().containsKey(PlaytestService.ALPHA_TESTERS));
        assertTrue(services.accounts().find(tester).isPresent(), "a preview changes nothing");

        var report = PlaytestService.wipeForBeta(store, Instant.parse("2026-12-01T00:00:00Z"));
        assertEquals(2, report.alphaTesters());
        assertEquals(List.of(PlaytestService.ALPHA_TESTERS), store.transaction(tx -> tx.namespaces()), "nothing else is left");

        var fresh = new EterniaServices(store);
        assertTrue(fresh.playtest().isAlphaTester(tester));
        assertTrue(fresh.playtest().isAlphaTester(early), "players who joined before testers were recorded keep the title too");
        assertTrue(fresh.accounts().find(tester).isEmpty(), "accounts are gone");
        assertEquals(0, fresh.premium().balance(tester).available(), "Crowns are gone");
        assertTrue(fresh.memories().recorded(tester).isEmpty(), "memories are gone");
        assertTrue(fresh.welcomePlayer(tester, "Tester") && fresh.playtest().grantStartingAllowance(tester, 100, 0), "the beta allowance can be given again");
    }

    @Test void theWipeWorksOnTheFileStore(@TempDir Path folder) throws Exception {
        try (var file = new LocalFileStore(folder.resolve("store.bin"))) {
            var s = new EterniaServices(file);
            s.welcomePlayer(tester, "Tester");
            PlaytestService.wipeForBeta(file, Instant.now());
            assertEquals(List.of(PlaytestService.ALPHA_TESTERS), file.transaction(tx -> tx.namespaces()));
        }
    }

    @Test void settingsAreReadAndCheckedAndOffByDefault() {
        assertEquals(PlaytestSettings.OFF, PlaytestSettings.from(Map.of()));
        var alpha = PlaytestSettings.from(Map.of("ETERNIA_ALPHA", "true", "ETERNIA_STARTING_CROWNS", "5000", "ETERNIA_STARTING_COINS", " 2500 "));
        assertTrue(alpha.alpha());
        assertEquals(5000, alpha.startingCrowns());
        assertEquals(2500, alpha.startingCoins());
        assertThrows(IllegalArgumentException.class, () -> PlaytestSettings.from(Map.of("ETERNIA_ALPHA", "yes")));
        assertThrows(IllegalArgumentException.class, () -> PlaytestSettings.from(Map.of("ETERNIA_STARTING_CROWNS", "-1")));
        assertThrows(IllegalArgumentException.class, () -> PlaytestSettings.from(Map.of("ETERNIA_STARTING_COINS", "lots")));
    }

    @Test void theAlphaTesterTitleShips() throws Exception {
        String json = Files.readString(Path.of("src/main/resources/Server/EterniaMod/Collections/alpha_tester.json"));
        assertTrue(json.contains("\"id\":\"" + PlaytestService.ALPHA_TITLE + "\""));
        assertTrue(Files.readAllLines(Path.of("src/main/resources/Server/EterniaMod/Collections/catalog.index")).contains("alpha_tester.json"));
        assertTrue(Files.readString(Path.of("src/main/resources/Server/Languages/en-US/eternia_titles.lang")).contains("alpha_tester.name = Alpha Tester"));
    }
}
