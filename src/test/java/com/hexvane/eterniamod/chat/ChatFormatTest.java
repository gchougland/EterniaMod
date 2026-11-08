package com.hexvane.eterniamod.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.server.core.Message;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatFormatTest {
    @Test void aLineIsTagNameAndMessage() {
        Message line = ChatFormat.line(ChatChannel.PARTY, Message.raw("Hexvane"), "on my way, [north] gate!");
        var parts = line.getChildren();
        assertEquals(5, parts.size());
        assertEquals("eternia_chat.channel.party.tag", parts.get(0).getMessageId());
        assertEquals(ChatChannel.PARTY.color(), parts.get(0).getColor());
        assertEquals(" ", parts.get(1).getRawText());
        assertEquals("Hexvane", parts.get(2).getRawText());
        assertEquals(ChatChannel.PARTY.color(), parts.get(2).getColor());
        assertEquals(": ", parts.get(3).getRawText());
        // The message is plain text exactly as typed, never parsed as markup.
        assertEquals("on my way, [north] gate!", parts.get(4).getRawText());
        assertNull(parts.get(4).getColor());
    }

    @Test void serverNamesKeepTheDefaultColour() {
        var parts = ChatFormat.line(ChatChannel.SERVER, Message.raw("Hexvane"), "hi").getChildren();
        assertEquals("eternia_chat.channel.server.tag", parts.get(0).getMessageId());
        assertNull(parts.get(2).getColor());
    }

    @Test void everyChannelHasItsOwnColour() {
        var colours = new java.util.HashSet<String>();
        for (ChatChannel channel : ChatChannel.values()) assertTrue(colours.add(channel.color()), channel.id());
    }

    @Test void atShortcutsPickAChannelOnlyWhenTheyNameOne() {
        var party = ChatShortcut.parse("@p on my way, [north] gate!").orElseThrow();
        assertEquals(ChatChannel.PARTY, party.channel());
        assertEquals("on my way, [north] gate!", party.text());
        assertEquals(ChatChannel.GUILD, ChatShortcut.parse("@Guild  raid at 8").orElseThrow().channel());
        assertEquals(ChatChannel.SERVER, ChatShortcut.parse("@all hi").orElseThrow().channel());
        assertTrue(ChatShortcut.parse("@Hexvane thanks!").isEmpty());
        assertTrue(ChatShortcut.parse("@p").isEmpty());
        assertTrue(ChatShortcut.parse("@p    ").isEmpty());
        assertTrue(ChatShortcut.parse("mail me at a@p.com").isEmpty());
        assertTrue(ChatShortcut.parse("@p.com is down").isEmpty());
    }

    @Test void namesLoseLineBreaks() {
        assertEquals("Bad Name", ChatFormat.inline("Bad\nName"));
    }

    @Test void typedMessagesAreCheckedForLengthAndLines() {
        assertTrue(ChatText.clean("   ").isEmpty());
        assertEquals("hello", ChatText.clean("  hello ").orElseThrow());
        assertTrue(ChatText.problem("a".repeat(500)).isEmpty());
        assertEquals("eternia_chat.fail.tooLong", ChatText.problem("a".repeat(501)).orElseThrow());
        assertEquals("eternia_chat.fail.oneLine", ChatText.problem("a\nb").orElseThrow());
    }

    @Test void nearbyRadiusComesFromDataAndStaysSensible() {
        var bundled = ChatSettings.load(getClass().getClassLoader());
        assertEquals(50, bundled.nearbyBlocks());
        assertEquals(ChatChannel.SERVER, bundled.defaultChannel());
        assertEquals(32, ChatSettings.parse(new StringReader("{\"NearbyRadius\": 32, \"DefaultChannel\": \"w\"}")).nearbyRadius());
        assertEquals(ChatChannel.WORLD, ChatSettings.parse(new StringReader("{\"DefaultChannel\": \"world\"}")).defaultChannel());
        // Nonsense falls back instead of silencing everyone or shouting across the map.
        assertEquals(50, ChatSettings.parse(new StringReader("{\"NearbyRadius\": 0}")).nearbyBlocks());
        assertEquals(50, ChatSettings.parse(new StringReader("{\"NearbyRadius\": 100000}")).nearbyBlocks());
        // A group channel cannot be the default: a new player has no party or guild.
        assertEquals(ChatChannel.SERVER, ChatSettings.parse(new StringReader("{\"DefaultChannel\": \"party\"}")).defaultChannel());
    }

    @Test void gateAllowsByDefaultAndBlocksWhenAPolicyFails() {
        var player = java.util.UUID.randomUUID();
        try {
            assertTrue(ChatGate.blockReason(player).isEmpty());
            ChatGate.install(sender -> java.util.Optional.of(Message.raw("You are muted.")));
            assertEquals("You are muted.", ChatGate.blockReason(player).orElseThrow().getRawText());
            ChatGate.install(sender -> { throw new IllegalStateException("store down"); });
            assertEquals("eternia_chat.fail.unavailable", ChatGate.blockReason(player).orElseThrow().getMessageId());
        } finally {
            ChatGate.install(null);
        }
    }

    @Test void everyKeyTheCodeUsesExistsAndPlayerTextHasNoDashes() throws IOException {
        Map<String, String> lang = lang();
        for (ChatChannel channel : ChatChannel.values())
            for (String part : new String[] {"tag", "name", "about", "command", "switch"})
                assertTrue(lang.containsKey(channel.langKey(part).substring("eternia_chat.".length())), channel.langKey(part));
        for (String key : new String[] {"command.desc", "command.message", "command.help", "switch.done", "switch.already",
            "status.current", "help.usage", "help.shortcut", "join.reminder", "hint.nobodyNearby", "hint.partyAlone", "hint.worldAlone",
            "hint.guildAlone", "fail.empty", "fail.tooLong", "fail.oneLine", "fail.noParty", "fail.noPartySwitch", "fail.noGuild",
            "fail.noGuildSwitch", "fail.guildRole", "fail.guildCooldown", "fail.guildBusy", "fail.unavailable"})
            assertTrue(lang.containsKey(key), key);
        lang.forEach((key, value) -> assertFalse(value.matches(".*(\\s-\\s|\\u2013|\\u2014).*"), key + " uses a dash"));
    }

    static Map<String, String> lang() throws IOException {
        try (InputStream input = ChatFormatTest.class.getClassLoader().getResourceAsStream("Server/Languages/en-US/eternia_chat.lang")) {
            assertNotNull(input);
            var result = new HashMap<String, String>();
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) result.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
            return result;
        }
    }
}
