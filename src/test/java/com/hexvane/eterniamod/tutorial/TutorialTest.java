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
        for (String key : List.of("npc.greeter.returning1", "npc.greeter.returning2", "npc.greeter.waiting", "npc.rules.opening2", "hud.title", "hud.step", "hud.meetGuide", "hud.meetNpc", "hud.returnGuide", "hud.distance", "hud.here", "hud.why.return", "step.done", "step.doneLast", "dialogue.continue", "dialogue.next"))
            assertTrue(lang.containsKey(key), key);
        for (String npc : npcs) assertTrue(lang.containsKey("hud.why." + npc), "why line for " + npc);
        for (var section : EterniaServicesPage.Section.values()) {
            String npc = TutorialRuntime.npcKey(section);
            if (npc != null) assertTrue(npcs.contains(npc), section.name());
        }
        for (Object value : lang.values()) assertFalse(value.toString().contains(" - "), "no dashes in player text: " + value);
    }

    @Test void theWalkthroughGoesOneTownspersonAtATime() {
        var people = List.of("rules", "housing", "store");
        var step = TutorialStep.next(tutorial.state(player), people);
        assertEquals(new TutorialStep(TutorialStep.Kind.MEET_GUIDE, TutorialService.GUIDE, 1, 5), step);
        tutorial.meet(player, TutorialService.GUIDE);
        assertEquals(new TutorialStep(TutorialStep.Kind.MEET_NPC, "rules", 2, 5), TutorialStep.next(tutorial.state(player), people));
        tutorial.meet(player, "store");
        assertEquals(new TutorialStep(TutorialStep.Kind.MEET_NPC, "rules", 3, 5), TutorialStep.next(tutorial.state(player), people), "someone met early still counts");
        tutorial.meet(player, "rules");
        assertEquals("housing", TutorialStep.next(tutorial.state(player), people).npc());
        tutorial.meet(player, "housing");
        assertEquals(new TutorialStep(TutorialStep.Kind.RETURN_GUIDE, TutorialService.GUIDE, 5, 5), TutorialStep.next(tutorial.state(player), people));
    }

    @Test void theArrowPointsTheWayRelativeToWhereThePlayerLooks() {
        // Yaw 0 faces north (negative Z); the arrow's frame 0 is straight ahead and frames turn clockwise.
        assertEquals(0, TutorialStep.arrowFrame(0, 0, 0, 0, -10), "ahead");
        assertEquals(6, TutorialStep.arrowFrame(0, 0, 0, 10, 0), "east is to the right when facing north");
        assertEquals(18, TutorialStep.arrowFrame(0, 0, 0, -10, 0), "west is to the left");
        assertEquals(12, TutorialStep.arrowFrame(0, 0, 0, 0, 10), "behind");
        assertEquals(6, TutorialStep.arrowFrame(0, 0, Math.PI / 2, 0, -10), "facing west, north is to the right");
        assertEquals(0, TutorialStep.arrowFrame(5, 5, 0, 5, 5), "standing on it");
        assertEquals(7, TutorialStep.shownDistance(0, 0, 7.2, 0));
        assertEquals(45, TutorialStep.shownDistance(0, 0, 0, 46));
        assertEquals("EterniaMod/Tutorial/Arrow06.png", TutorialHud.arrowTexture(6));
        for (int frame = 0; frame < TutorialStep.FRAMES; frame++)
            assertTrue(Files.isRegularFile(Path.of("src/main/resources/Common/UI/Custom/" + TutorialHud.arrowTexture(frame))), "arrow " + frame);
    }

    @Test void theRulesListLoads() {
        assertTrue(RealmRules.load().size() >= 5);
    }
}
