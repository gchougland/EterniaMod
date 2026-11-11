package com.hexvane.eterniamod.chat;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * How chat lines look: {@code [Party] Name: message}. The tag carries the channel colour; the name carries it too
 * on every channel except Server, so private lines stand out in a busy chat box.
 */
public final class ChatFormat {
    /** Muted Citadel text for feedback lines (switch confirmations, hints). */
    public static final String NOTE_COLOR = "#b5c3b8";
    /** Citadel warning colour for "nobody heard that". */
    public static final String WARN_COLOR = "#e69085";

    private ChatFormat() {}

    public static Message tag(ChatChannel channel) {
        return Message.translation(channel.langKey("tag")).color(channel.color());
    }

    /** The channel's name, coloured, for use inside feedback sentences. */
    public static Message channelName(ChatChannel channel) {
        return Message.translation(channel.langKey("name")).color(channel.color()).bold(true);
    }

    /** One chat line. {@code name} comes from {@link #speaker}, the only place a rank or title prefix is added. */
    public static Message line(ChatChannel channel, Message name, String text) {
        if (channel != ChatChannel.SERVER) name = name.color(channel.color());
        return Message.join(tag(channel), Message.raw(" "), name, Message.raw(": "), Message.raw(text));
    }

    /**
     * The sender's name as everyone sees it in chat: their collection display name today.
     * <p>RANK PREFIX HOOK: when ranks arrive, build the prefix here, for example
     * {@code Message.join(rankTag(player), Message.raw(" "), name)}. Every channel, including Server and Guild,
     * formats through this method, so one change covers all of chat.
     */
    public static Message speaker(PlayerRef player) { return Message.raw(speakerName(player)); }

    /** The same name as plain text, for the chat dock. */
    public static String speakerName(PlayerRef player) {
        var plugin = EterniaModPlugin.get();
        var collections = plugin == null ? null : plugin.getCollectionRuntime();
        return inline(collections == null ? player.getUsername() : collections.displayName(player));
    }

    /**
     * The mark drawn before the sender's name in the dock, as the art name after {@code Chat/Rank_}, or null for none.
     * Nobody has a rank yet: this is where one is chosen when ranks arrive.
     */
    public static String rank(PlayerRef player) { return null; }

    /** A feedback line for the player. */
    public static Message note(Message body) { return body.color(NOTE_COLOR); }
    public static Message warn(Message body) { return body.color(WARN_COLOR); }

    static String inline(String text) { return text == null ? "" : text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " "); }
}
