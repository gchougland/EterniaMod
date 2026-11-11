package com.hexvane.eterniamod.chat;

import com.hypixel.hytale.server.core.Message;

/**
 * One line in the chat dock. A chat line has a channel, a speaker and typed text; a system line (feedback, hints,
 * anything the server would have printed in the game's chat) carries a ready message instead.
 */
public record ChatLine(ChatChannel channel, String speaker, String rank, String text, Message system, long at) {
    public static ChatLine chat(ChatChannel channel, String speaker, String rank, String text) {
        return new ChatLine(channel, speaker, rank, text, null, System.currentTimeMillis());
    }

    public static ChatLine system(Message message) {
        return new ChatLine(null, null, null, null, message, System.currentTimeMillis());
    }

    public boolean isSystem() { return system != null; }
}
