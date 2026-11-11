package com.hexvane.eterniamod.chat;

import java.util.ArrayList;
import java.util.List;

/** The emotes players can put in chat, typed as {@code :name:}. Pure, so it is tested without a server. */
public final class ChatEmotes {
    /** In picker order. Each has a picture at {@code Chat/Emote_<name>.png}. */
    public static final List<String> NAMES = List.of(
        "smile", "laugh", "wink", "wow", "sad", "heart", "star", "flame", "leaf", "gem", "crown", "sword");
    /** Longest run of plain text in one label, so a long message can wrap between labels. */
    static final int CHUNK = 28;

    private ChatEmotes() {}

    /** A piece of a message: plain text, or an emote name. */
    public record Piece(String text, String emote) {
        public boolean isEmote() { return emote != null; }
    }

    /** Splits a message into text and emotes, in order. Unknown {@code :names:} stay as text. */
    public static List<Piece> pieces(String message) {
        var result = new ArrayList<Piece>();
        var text = new StringBuilder();
        int i = 0;
        while (i < message.length()) {
            if (message.charAt(i) == ':') {
                int end = message.indexOf(':', i + 1);
                if (end > i + 1 && NAMES.contains(message.substring(i + 1, end))) {
                    flush(result, text);
                    result.add(new Piece(null, message.substring(i + 1, end)));
                    i = end + 1;
                    continue;
                }
            }
            text.append(message.charAt(i++));
        }
        flush(result, text);
        return result;
    }

    private static void flush(List<Piece> result, StringBuilder text) {
        String pending = text.toString().strip();
        text.setLength(0);
        if (pending.isEmpty()) return;
        var chunk = new StringBuilder();
        for (String word : pending.split("\\s+")) {
            if (chunk.length() > 0 && chunk.length() + 1 + word.length() > CHUNK) {
                result.add(new Piece(chunk.toString(), null));
                chunk.setLength(0);
            }
            if (chunk.length() > 0) chunk.append(' ');
            chunk.append(word);
        }
        if (chunk.length() > 0) result.add(new Piece(chunk.toString(), null));
    }

    /** The draft with an emote added at the end, spaced from what was there. */
    public static String add(String draft, String emote) {
        String base = draft == null ? "" : draft;
        if (!NAMES.contains(emote)) return base;
        String spaced = base.isEmpty() || base.endsWith(" ") ? base : base + " ";
        String next = spaced + ":" + emote + ": ";
        return next.length() > ChatText.MAX_LENGTH ? base : next;
    }
}
