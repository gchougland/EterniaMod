package com.hexvane.eterniamod.chat;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A chat line that starts with {@code @} and a channel, like {@code @p on my way} or {@code @guild hello}, goes to that
 * channel once. Plain chat keeps every character, which commands cannot ({@code /e c p} trips over square brackets).
 * Anything else that starts with {@code @} (a name, an email) is ordinary chat.
 */
public record ChatShortcut(ChatChannel channel, String text) {
    private static final Pattern FORM = Pattern.compile("^@(\\p{Alpha}+)\\s+(\\S.*)$", Pattern.DOTALL);

    public static Optional<ChatShortcut> parse(String content) {
        if (content == null) return Optional.empty();
        var match = FORM.matcher(content.strip());
        if (!match.matches()) return Optional.empty();
        return ChatChannel.parse(match.group(1)).map(channel -> new ChatShortcut(channel, match.group(2).strip()));
    }
}
