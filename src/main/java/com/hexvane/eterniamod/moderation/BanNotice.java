package com.hexvane.eterniamod.moderation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a banned player reads when they are refused or removed. Shown before the client has the mod's translations,
 * so the text is resolved on the server from {@code eternia_moderation.lang}, with English built in as a fallback.
 */
public final class BanNotice {
    static final String PREFIX = "eternia_moderation.";
    static final Map<String, String> FALLBACK = Map.of(
        "notice.title", "You are banned from Eternia.",
        "notice.reason", "Reason: {reason}",
        "notice.noReason", "No reason was given.",
        "notice.ends", "Your ban ends in {time}.",
        "notice.permanent", "This ban is permanent.",
        "notice.appeal", "If you think this is a mistake, please contact the Eternia team.");

    private BanNotice() {}

    /** The login decision: the refusal text for a running ban, empty to let the player in. */
    public static Optional<String> refusal(Optional<ModerationService.Ban> ban, Instant now, Function<String, String> lang) {
        return ban.filter(b -> !b.endedBy(now)).map(b -> text(b, now, lang));
    }

    public static String text(ModerationService.Ban ban, Instant now, Function<String, String> lang) {
        List<String> lines = new ArrayList<>();
        lines.add(line(lang, "notice.title"));
        lines.add(ban.reason().isBlank() ? line(lang, "notice.noReason") : line(lang, "notice.reason").replace("{reason}", ban.reason()));
        lines.add(ban.permanent() ? line(lang, "notice.permanent") : line(lang, "notice.ends").replace("{time}", TimeWords.between(now, ban.until())));
        lines.add(line(lang, "notice.appeal"));
        return String.join("\n", lines);
    }

    private static String line(Function<String, String> lang, String key) {
        String value = lang == null ? null : lang.apply(PREFIX + key);
        return value == null || value.isBlank() ? FALLBACK.get(key) : value;
    }
}
