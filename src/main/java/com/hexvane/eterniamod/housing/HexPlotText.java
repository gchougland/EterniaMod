package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Player text for the hex plot engine ({@code eternia_hexplots.plots.*} and the core's {@code reason.*}): a
 * {@link Message} for chat and pages, plus the same line resolved to English for logs, results and tests. Any thread.
 */
public final class HexPlotText {
    public static final String PREFIX = "eternia_hexplots.";

    private HexPlotText() {}

    /** One line of text: a translation key with its parameters and the English fallback used when no language is loaded. */
    public record Line(String key, Map<String, String> params, String fallback) {
        public Line {
            params = Map.copyOf(params);
        }

        public Message message() {
            Message message = Message.translation(key);
            params.forEach(message::param);
            return message;
        }

        /** English, from the loaded language files when the server has them, else the fallback. */
        public String english() {
            String text = null;
            try {
                I18nModule i18n = I18nModule.get();
                if (i18n != null) text = i18n.getMessage("en-US", key);
            } catch (RuntimeException | LinkageError ignored) {
                // Tests and early start: no language module.
            }
            if (text == null || text.isBlank() || text.equals(key)) text = fallback;
            for (var e : params.entrySet()) text = text.replace("{" + e.getKey() + "}", e.getValue());
            return text;
        }
    }

    /** A {@code plots.*} line. {@code params} alternate name, value. */
    public static Line plots(String id, String fallback, Object... params) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < params.length; i += 2) map.put(String.valueOf(params[i]), format(params[i + 1]));
        return new Line(PREFIX + "plots." + id, map, fallback);
    }

    /** A rule result as player text ({@code reason.*} with {@code {count}}). */
    public static Line reason(HexClaimRules.Result result) {
        return new Line(result.reason().langKey(), Map.of("count", format(result.count())), result.reason().message());
    }

    /** Whole numbers with thousands separators, the way every coin amount reads in Eternia. */
    public static String format(Object value) {
        if (value instanceof Long || value instanceof Integer) return String.format(Locale.ROOT, "%,d", ((Number) value).longValue());
        return String.valueOf(value);
    }
}
