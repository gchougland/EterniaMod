package com.hexvane.eterniamod.chat;

import java.util.Optional;

/** Checks a typed message: one line, 1 to 500 characters. Problems come back as lang keys. */
public final class ChatText {
    public static final int MAX_LENGTH = 500;
    private ChatText() {}

    /** The cleaned message, or empty when there is nothing to send. */
    public static Optional<String> clean(String text) {
        if (text == null) return Optional.empty();
        String result = text.strip();
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    /** The lang key of the reason the message cannot be sent, or empty when it is fine. */
    public static Optional<String> problem(String cleaned) {
        if (cleaned.length() > MAX_LENGTH) return Optional.of("eternia_chat.fail.tooLong");
        if (cleaned.chars().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
            return Optional.of("eternia_chat.fail.oneLine");
        return Optional.empty();
    }
}
