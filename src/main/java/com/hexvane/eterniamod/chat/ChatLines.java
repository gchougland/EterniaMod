package com.hexvane.eterniamod.chat;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Draws chat lines into the dock's log and the popup HUD: channel mark, rank mark, name, then text and emotes. */
final class ChatLines {
    static final String ART = "EterniaMod/Chat/";
    private static final String SERVER_NAME_COLOR = "#f0e8d5";

    private ChatLines() {}

    /** Appends {@code line} as child {@code index} of {@code container}. {@code hud} picks the popup look. */
    static void append(UICommandBuilder c, String container, int index, ChatLine line, boolean hud) {
        String row = container + "[" + index + "]";
        if (line.isSystem()) {
            c.append(container, hud ? "EterniaMod/ChatDockHudNote.ui" : "EterniaMod/ChatDockNote.ui");
            c.set(row + " #NoteText.TextSpans", line.system());
            return;
        }
        c.append(container, hud ? "EterniaMod/ChatDockHudLine.ui" : "EterniaMod/ChatDockLine.ui");
        int n = 0;
        pic(c, row, n++, "Channel_" + line.channel().id());
        if (line.rank() != null) pic(c, row, n++, "Rank_" + line.rank());
        String color = line.channel() == ChatChannel.SERVER ? SERVER_NAME_COLOR : line.channel().color();
        word(c, row, n++, Message.raw(line.speaker()).bold(true).color(color));
        for (var piece : ChatEmotes.pieces(line.text())) {
            if (piece.isEmote()) pic(c, row, n++, "Emote_" + piece.emote());
            else word(c, row, n++, Message.raw(piece.text()));
        }
    }

    private static void pic(UICommandBuilder c, String row, int n, String art) {
        c.append(row, "EterniaMod/ChatDockPic.ui");
        c.setObject(row + "[" + n + "].Background", new PatchStyle(Value.of(ART + art + ".png")));
    }

    private static void word(UICommandBuilder c, String row, int n, Message text) {
        c.append(row, "EterniaMod/ChatDockWord.ui");
        c.set(row + "[" + n + "].TextSpans", text);
    }
}
