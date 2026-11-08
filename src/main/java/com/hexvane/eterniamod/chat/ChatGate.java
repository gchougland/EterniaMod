package com.hexvane.eterniamod.chat;

import com.hypixel.hytale.server.core.Message;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place that decides whether a player may speak at all. Every channel (plain chat, one shot
 * {@code /e chat <channel> <message>}, and {@code /e guildchat}) asks this before anything is sent.
 * Moderation installs its mute check here; nothing else needs to change.
 */
public final class ChatGate {
    /** Returns a short, friendly reason for the sender when they may not speak, or empty when they may. */
    @FunctionalInterface
    public interface Policy { Optional<Message> blockReason(UUID sender); }

    public static final Policy ALLOW_ALL = sender -> Optional.empty();
    private static volatile Policy policy = ALLOW_ALL;

    private ChatGate() {}

    public static void install(Policy next) { policy = next == null ? ALLOW_ALL : next; }

    /** Runs off the world thread; the policy may read storage. A failing policy blocks rather than lets a muted player through. */
    public static Optional<Message> blockReason(UUID sender) {
        try { return policy.blockReason(sender); }
        catch (RuntimeException failure) { return Optional.of(Message.translation("eternia_chat.fail.unavailable")); }
    }
}
