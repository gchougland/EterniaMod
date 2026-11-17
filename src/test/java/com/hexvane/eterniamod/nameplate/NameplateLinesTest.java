package com.hexvane.eterniamod.nameplate;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class NameplateLinesTest {
    @Test void titleNameAndGuildStackTopToBottom() {
        var lines = NameplateLines.of("Hexvane", "Alpha Tester", "", "#ffcf4a", "Guild of Testers");
        assertEquals(List.of(
            new NameplateLines.Line(NameplateLines.TITLE_STYLE, "Alpha Tester", "#ffcf4a"),
            new NameplateLines.Line(NameplateLines.NAME_STYLE, "Hexvane", "#ffcf4a"),
            new NameplateLines.Line(NameplateLines.GUILD_STYLE, "<Guild of Testers>", NameplateLines.GUILD_COLOUR)), lines);
    }

    @Test void aPlayerWithNothingShowsJustTheirNameInWhite() {
        assertEquals(List.of(new NameplateLines.Line(NameplateLines.NAME_STYLE, "Hexvane", NameplateLines.PLAIN)),
            NameplateLines.of("Hexvane", "", "", null, null));
    }

    @Test void bothTitlesShareTheTitleLine() {
        assertEquals("Alpha Tester · the Dragonslayer", NameplateLines.of("Hexvane", "Alpha Tester", "the Dragonslayer", "#ffcf4a", null).getFirst().text());
        assertEquals("the Dragonslayer", NameplateLines.of("Hexvane", "", "the Dragonslayer", "#ff7a45", " ").getFirst().text());
        assertEquals(2, NameplateLines.of("Hexvane", "", "the Dragonslayer", "#ff7a45", " ").size(), "a blank guild adds no line");
    }

    @Test void controlCharactersCannotBreakALine() {
        assertEquals("<Bad Guild>", NameplateLines.of("Hexvane", "", "", null, "Bad\nGuild").getLast().text());
    }

    @Test void coloursBecomeBytes() {
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xcf, 0x4a}, NameplateLines.rgb("#ffcf4a"));
    }

    @Test void everyStyleShipsStillAndStacked() throws Exception {
        int previous = Integer.MIN_VALUE;
        for (String style : List.of(NameplateLines.TITLE_STYLE, NameplateLines.NAME_STYLE, NameplateLines.GUILD_STYLE)) {
            var json = JsonParser.parseString(Files.readString(Path.of("src/main/resources/Server/Entity/UI/" + style + ".json"))).getAsJsonObject();
            assertEquals("CombatText", json.get("Type").getAsString(), style);
            assertEquals(0, json.getAsJsonArray("AnimationEvents").size(), style + " stays still");
            assertEquals(10, json.get("Duration").getAsInt(), style + " lasts the longest the game allows");
            int y = json.getAsJsonObject("HitboxOffset").get("Y").getAsInt();
            assertTrue(y > previous, style + " sits below the line before it");
            previous = y;
        }
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/Server/Models/Eternia/Eternia_Nameplate_Anchor.json")));
    }
}
