package com.hexvane.eterniamod.collections;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * The colour each title gives its wearer's title and name, in chat and above their head. Data lives in
 * {@code Server/EterniaMod/Titles/colors.json}. A prefix title's colour wins over a suffix title's; a player wearing no
 * coloured title keeps the default colours.
 */
public final class TitleColors {
    public static final String RESOURCE = "Server/EterniaMod/Titles/colors.json";
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static volatile TitleColors loaded;

    private final Map<String, String> colours;

    TitleColors(Map<String, String> colours) { this.colours = Map.copyOf(colours); }

    /** The shipped colours, read once. Empty if the file is missing or unreadable, so names fall back to the defaults. */
    public static TitleColors get() {
        var known = loaded;
        if (known != null) return known;
        try (InputStream stream = TitleColors.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            known = stream == null ? new TitleColors(Map.of()) : parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            known = new TitleColors(Map.of());
        }
        loaded = known;
        return known;
    }

    static TitleColors parse(Reader json) {
        var root = JsonParser.parseReader(json).getAsJsonObject();
        var colours = new HashMap<String, String>();
        if (root.has("Titles")) for (var entry : root.getAsJsonObject("Titles").entrySet()) {
            String value = entry.getValue().getAsString();
            if (!HEX.matcher(value).matches()) throw new IllegalArgumentException("Title colour must be #rrggbb: " + entry.getKey());
            colours.put(entry.getKey(), value.toLowerCase(java.util.Locale.ROOT));
        }
        return new TitleColors(colours);
    }

    /** The colour for a title id, or null when it has none. */
    @Nullable
    public String of(@Nullable String titleId) { return titleId == null ? null : colours.get(titleId); }

    /** The colour a player shows with these titles worn: the prefix's, else the suffix's, else null. */
    @Nullable
    public String forWorn(@Nullable String prefixId, @Nullable String suffixId) {
        String prefix = of(prefixId);
        return prefix != null ? prefix : of(suffixId);
    }

    public Map<String, String> all() { return colours; }
}
