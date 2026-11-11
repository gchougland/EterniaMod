package com.hexvane.eterniamod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatEmotesTest {
    @Test
    void splitsTextAndEmotesInOrder() {
        var pieces = ChatEmotes.pieces("On my way :smile: see you:heart:");
        assertEquals(List.of("On my way", "smile", "see you", "heart"),
            pieces.stream().map(p -> p.isEmote() ? p.emote() : p.text()).toList());
        assertFalse(pieces.get(0).isEmote());
        assertTrue(pieces.get(1).isEmote());
    }

    @Test
    void unknownNamesAndLoneColonsStayText() {
        assertEquals(List.of(new ChatEmotes.Piece("meet at 10:30 :dragon: ok", null)), ChatEmotes.pieces("meet at 10:30 :dragon: ok"));
    }

    @Test
    void longTextIsCutBetweenWords() {
        var pieces = ChatEmotes.pieces("Guild hall is open, bring planks and anything else you can carry");
        assertTrue(pieces.size() > 1);
        for (var piece : pieces) assertTrue(piece.text().length() <= ChatEmotes.CHUNK, piece.text());
        assertEquals("Guild hall is open, bring planks and anything else you can carry",
            String.join(" ", pieces.stream().map(ChatEmotes.Piece::text).toList()));
    }

    @Test
    void addingAnEmoteSpacesItFromTheDraft() {
        assertEquals(":star: ", ChatEmotes.add("", "star"));
        assertEquals("hello :star: ", ChatEmotes.add("hello", "star"));
        assertEquals("hello :star: ", ChatEmotes.add("hello ", "star"));
        assertEquals("hello", ChatEmotes.add("hello", "dragon"));
    }

    @Test
    void onlyYoungLinesPopUpNewestLast() {
        long now = 100_000;
        var old = new ChatLine(ChatChannel.SERVER, "A", null, "old", null, now - ChatDock.POPUP_MILLIS - 1);
        var first = new ChatLine(ChatChannel.SERVER, "A", null, "first", null, now - 2_000);
        var second = new ChatLine(ChatChannel.SERVER, "A", null, "second", null, now - 1_000);
        assertEquals(List.of(first, second), ChatDock.recent(List.of(old, first, second), now));
    }
}
