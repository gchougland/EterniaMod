package com.hexvane.eterniamod.mail;

import com.hexvane.eterniamod.chat.ChatEmotes;
import java.util.ArrayList;
import java.util.List;

/** Turns a letter's text into lines of words and emotes, keeping its line breaks. Pure, so it is tested alone. */
public final class MailText {
    private MailText() {}

    /** One line of a letter: its words and emotes in order. An empty list is a blank line. */
    public static List<List<ChatEmotes.Piece>> lines(String body) {
        var out = new ArrayList<List<ChatEmotes.Piece>>();
        if (body == null || body.isEmpty()) return out;
        for (String line : body.split("\\R", -1)) out.add(ChatEmotes.pieces(line));
        while (!out.isEmpty() && out.getLast().isEmpty()) out.removeLast();
        return out;
    }
}
