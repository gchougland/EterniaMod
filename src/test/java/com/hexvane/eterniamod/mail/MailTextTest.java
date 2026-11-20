package com.hexvane.eterniamod.mail;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailTextTest {
    @Test void keepsLineBreaksAndBlankLines() {
        var lines = MailText.lines("Hey :smile:\n\nSee you soon\n");
        assertEquals(3, lines.size());
        assertEquals("smile", lines.get(0).get(1).emote());
        assertTrue(lines.get(1).isEmpty());
        assertEquals("See you soon", lines.get(2).getFirst().text());
    }
    @Test void unknownNamesStayText() {
        var line = MailText.lines("a :nope: b").getFirst();
        assertTrue(line.stream().noneMatch(p -> p.isEmote()));
    }
    @Test void emptyBodyHasNoLines() { assertEquals(List.of(), MailText.lines("")); }
}
