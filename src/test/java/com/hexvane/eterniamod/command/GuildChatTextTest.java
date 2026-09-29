package com.hexvane.eterniamod.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuildChatTextTest {
    @Test void chatAcceptsPlainUnicodeTextAndBoundsTheNativeMessage(){
        assertEquals("Meet at the guild hall — 5 pm",GuildChatText.normalize("  Meet at the guild hall — 5 pm  "));
        assertEquals(500,GuildChatText.normalize("a".repeat(500)).length());
        assertThrows(IllegalArgumentException.class,()->GuildChatText.normalize("a".repeat(501)));
        assertThrows(IllegalArgumentException.class,()->GuildChatText.normalize("   "));
    }
    @Test void chatCannotInjectAnotherLineOrPacketControlText(){
        assertThrows(IllegalArgumentException.class,()->GuildChatText.normalize("Ready\n[Another guild] hello"));
        assertThrows(IllegalArgumentException.class,()->GuildChatText.normalize("Ready\u0000now"));
        assertThrows(IllegalArgumentException.class,()->GuildChatText.normalize("Ready\tnow"));
        assertThrows(IllegalArgumentException.class,()->GuildChatText.normalize("Ready\u2028Another line"));
        assertThrows(IllegalArgumentException.class,()->GuildChatText.normalize("Ready\u2029Another paragraph"));
    }
}
