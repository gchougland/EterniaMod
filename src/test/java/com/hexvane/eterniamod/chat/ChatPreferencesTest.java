package com.hexvane.eterniamod.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.persistence.LocalFileStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatPreferencesTest {
    final UUID player = UUID.randomUUID();

    @Test void newPlayersStartOnTheDefault() {
        var preferences = new ChatPreferences(new InMemoryStore());
        assertEquals(ChatChannel.SERVER, preferences.current(player));
        preferences.setDefaultChannel(ChatChannel.WORLD);
        assertEquals(ChatChannel.WORLD, preferences.current(player));
    }

    @Test void theChoiceSurvivesLeavingAndARestart(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("store.json");
        try (var store = new LocalFileStore(file)) {
            var services = new EterniaServices(store);
            assertEquals(ChatChannel.SERVER, services.chatPreferences().select(player, ChatChannel.PARTY));
            services.chatPreferences().forget(player); // logged out
            assertEquals(ChatChannel.PARTY, services.chatPreferences().current(player));
            services.chatPreferences().select(player, ChatChannel.NEARBY);
        }
        try (var store = new LocalFileStore(file)) {
            assertEquals(ChatChannel.NEARBY, new EterniaServices(store).chatPreferences().current(player));
        }
    }

    @Test void choicesArePerPlayer() {
        var preferences = new ChatPreferences(new InMemoryStore());
        UUID other = UUID.randomUUID();
        preferences.select(player, ChatChannel.GUILD);
        assertEquals(ChatChannel.GUILD, preferences.current(player));
        assertEquals(ChatChannel.SERVER, preferences.current(other));
    }

    @Test void anUnknownSavedValueFallsBackToTheDefault() {
        var store = new InMemoryStore();
        store.transaction(tx -> tx.save(ChatPreferences.NAMESPACE, player.toString(), 0, Map.of("channel", "shout")));
        assertEquals(ChatChannel.SERVER, new ChatPreferences(store).current(player));
    }

    @Test void channelNamesAndShortFormsParse() {
        assertEquals(ChatChannel.PARTY, ChatChannel.parse("p").orElseThrow());
        assertEquals(ChatChannel.PARTY, ChatChannel.parse(" Party ").orElseThrow());
        assertEquals(ChatChannel.SERVER, ChatChannel.parse("global").orElseThrow());
        assertEquals(ChatChannel.NEARBY, ChatChannel.parse("local").orElseThrow());
        assertEquals(ChatChannel.GUILD, ChatChannel.parse("g").orElseThrow());
        assertTrue(ChatChannel.parse("shout").isEmpty());
        assertTrue(ChatChannel.parse(null).isEmpty());
    }

    @Test void aliasesNeverCollide() {
        var seen = new java.util.HashSet<String>();
        for (ChatChannel channel : ChatChannel.values()) {
            assertTrue(seen.add(channel.id()), channel.id());
            for (String alias : channel.aliases()) assertTrue(seen.add(alias), alias);
        }
    }
}
