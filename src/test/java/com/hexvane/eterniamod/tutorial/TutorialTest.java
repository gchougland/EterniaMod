package com.hexvane.eterniamod.tutorial;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.TutorialService;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.setup.HubNpcIdentity;
import com.hexvane.eterniamod.socialui.EterniaServicesPage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;

class TutorialTest {
    private final EterniaServices services = new EterniaServices(new InMemoryStore(), Clock.systemUTC(), UUID::randomUUID);
    private final TutorialService tutorial = services.tutorial();
    private final UUID player = UUID.randomUUID();

    @Test void meetingEveryoneThenTheGuideFinishesTheTutorial() {
        var first = tutorial.meet(player, TutorialService.GUIDE);
        assertTrue(first.first());
        assertFalse(first.finishedNow());
        assertEquals(List.of("eternia_tutorial.npc.greeter.opening"), HubDialogue.pages(TutorialService.GUIDE, first));
        for (String npc : TutorialService.TOWNSPEOPLE) {
            var meeting = tutorial.meet(player, npc);
            assertTrue(meeting.first(), npc);
            assertFalse(tutorial.meet(player, npc).first(), "a second visit is not a first meeting");
        }
        assertTrue(tutorial.state(player).metEveryone());
        assertFalse(tutorial.state(player).finished());
        var farewell = tutorial.meet(player, TutorialService.GUIDE);
        assertTrue(farewell.finishedNow());
        assertEquals(2, HubDialogue.pages(TutorialService.GUIDE, farewell).size());
        assertTrue(tutorial.state(player).finished());
        assertFalse(tutorial.state(player).active());
        assertTrue(HubDialogue.pages(TutorialService.GUIDE, tutorial.meet(player, TutorialService.GUIDE)).isEmpty(), "said only once");
    }

    @Test void theGuideOnlyFinishesItAfterEveryTownsperson() {
        tutorial.meet(player, TutorialService.GUIDE);
        tutorial.meet(player, "housing");
        var again = tutorial.meet(player, TutorialService.GUIDE);
        assertFalse(again.finishedNow());
        assertTrue(HubDialogue.pages(TutorialService.GUIDE, again).isEmpty());
        assertEquals(1, tutorial.state(player).townspeopleMet());
    }

    @Test void hideSkipAndRestart() {
        tutorial.meet(player, TutorialService.GUIDE);
        assertTrue(tutorial.state(player).active());
        assertFalse(tutorial.hide(player, true).active());
        assertTrue(tutorial.hide(player, false).active());
        assertFalse(tutorial.skip(player).active());
        var fresh = tutorial.restart(player);
        assertTrue(fresh.active());
        assertTrue(fresh.met().isEmpty());
        assertTrue(tutorial.meet(player, TutorialService.GUIDE).first(), "restarting greets the player as new again");
    }

    @Test void theRulesHaveTwoOpeningPagesAndUnknownNpcsAreIgnored() {
        assertEquals(2, HubDialogue.pages("rules", tutorial.meet(player, "rules")).size());
        assertFalse(tutorial.meet(player, "nobody").first());
    }

    @Test void everyHubNpcHasItsLinesAndASection() throws Exception {
        var lang = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/Server/Languages/en-US/eternia_tutorial.lang"))) { lang.load(reader); }
        var npcs = new HashSet<String>();
        for (var identity : HubNpcIdentity.values()) {
            String npc = HubDialogue.npcKey(identity);
            assertNotNull(npc, identity.name());
            npcs.add(npc);
            for (String line : List.of("opening", "recurring")) assertTrue(lang.containsKey("npc." + npc + "." + line), npc + " " + line);
        }
        assertEquals(new HashSet<>(TutorialService.TOWNSPEOPLE), new HashSet<>(npcs) {{ remove(TutorialService.GUIDE); }});
        for (String key : List.of("npc.greeter.returning1", "npc.greeter.returning2", "npc.greeter.waiting", "npc.rules.opening2", "hud.title", "hud.meetGuide", "hud.meetTown", "hud.returnGuide", "dialogue.continue", "dialogue.next"))
            assertTrue(lang.containsKey(key), key);
        for (var section : EterniaServicesPage.Section.values()) {
            String npc = TutorialRuntime.npcKey(section);
            if (npc != null) assertTrue(npcs.contains(npc), section.name());
        }
        for (Object value : lang.values()) assertFalse(value.toString().contains(" - "), "no dashes in player text: " + value);
    }

    @Test void theRulesListLoads() {
        assertTrue(RealmRules.load().size() >= 5);
    }
}
