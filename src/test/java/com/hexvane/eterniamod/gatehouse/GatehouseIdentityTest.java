package com.hexvane.eterniamod.gatehouse;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.hexvane.eterniamod.setup.HubNpcIdentity;
import com.hexvane.eterniamod.socialui.EterniaServicesPage;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Varek's role, model, name and prompt, and that talking to him opens his own desk. */
class GatehouseIdentityTest {
    private static final Path RES = Path.of("src/main/resources");
    private static JsonObject read(String path) throws Exception { return JsonParser.parseString(Files.readString(RES.resolve(path))).getAsJsonObject(); }
    private static Map<String, String> lang(String file) throws Exception {
        var map = new HashMap<String, String>();
        for (var line : Files.readAllLines(RES.resolve("Server/Languages/en-US/" + file))) { int eq = line.indexOf('='); if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim()); }
        return map;
    }

    @Test void gatehouseIdentityIsWiredToItsRoleModelAndPrompt() throws Exception {
        var gate = HubNpcIdentity.GATEHOUSE;
        assertEquals(Optional.of(gate), HubNpcIdentity.forRole("Eternia_Gatehouse"));
        assertEquals("Varek Dawnmantle [Gate Warden]", gate.nameplate());
        var role = read("Server/NPC/Roles/Eternia/Eternia_Gatehouse.json");
        assertEquals("Eternia_Gatehouse_Warden", role.get("Appearance").getAsString());
        assertTrue(role.get("Invulnerable").getAsBoolean());
        var steps = role.getAsJsonObject("InteractionInstruction").getAsJsonArray("Instructions");
        var prompt = steps.get(1).getAsJsonObject().getAsJsonArray("Actions").get(0).getAsJsonObject();
        assertTrue(prompt.get("ShowPrompt").getAsBoolean());
        assertEquals(gate.hint(), prompt.get("Hint").getAsString());
        var open = steps.get(2).getAsJsonObject().getAsJsonArray("Actions").get(0).getAsJsonObject();
        assertEquals("OpenEterniaServices", open.get("Type").getAsString());
        var section = EterniaServicesPage.Section.valueOf(open.get("Section").getAsString());
        assertEquals(EterniaServicesPage.Desk.GATEHOUSE, EterniaServicesPage.Desk.forNpc(section), "talking to Varek opens his desk, not the Journal");

        var model = read("Server/Models/Eternia/Eternia_Gatehouse_Warden.json");
        assertTrue(model.get("Parent").getAsString().startsWith("Outlander"));
        assertTrue(model.getAsJsonObject("RandomAttachmentSets").isEmpty(), "a named character always looks the same");

        var text = lang("eternia_gatehouse.lang");
        assertTrue(text.get("interact").contains("{key}"), "the native prompt shows the player's own key");
        assertEquals(gate.nameplate(), text.get("npc"));
        assertTrue(text.containsKey("welcome.title") && text.containsKey("welcome.subtitle"));
        assertEquals(role.get("NameTranslationKey").getAsString(), "eternia_gatehouse.npc");
    }

    @Test void otherDesksAreUnchanged() {
        assertEquals(EterniaServicesPage.Desk.JOURNAL, EterniaServicesPage.Desk.forShortcut(EterniaServicesPage.Section.SEASON), "the Seasons shortcut still opens the Journal");
        assertEquals(EterniaServicesPage.Desk.JOURNAL, EterniaServicesPage.Desk.forNpc(EterniaServicesPage.Section.QUESTS));
        assertEquals(EterniaServicesPage.Desk.GAMES, EterniaServicesPage.Desk.forNpc(EterniaServicesPage.Section.COMING_SOON));
    }
}
