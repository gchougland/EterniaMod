package com.hexvane.eterniamod.nameplate;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * What a player's floating nameplate shows: their worn title on a small line, their name, and their guild underneath.
 * The title and the name take the title's colour. Hytale-free so it can be tested.
 *
 * <p>Each line is drawn by the game's floating combat text, inside the client's fixed 200 pixel box, so a line too long
 * for its size ends in "…". The sizes are set in {@code Server/Entity/UI/Eternia_Nameplate_*.json}.
 */
public final class NameplateLines {
    public static final String TITLE_STYLE = "Eternia_Nameplate_Title";
    public static final String NAME_STYLE = "Eternia_Nameplate_Name";
    public static final String GUILD_STYLE = "Eternia_Nameplate_Guild";
    /** The name's colour when the player wears no coloured title, and the title's when it has no colour. */
    public static final String PLAIN = "#ffffff";
    public static final String GUILD_COLOUR = "#b5c3b8";

    /** One line: which text style draws it, the text and its colour as #rrggbb. */
    public record Line(String style, String text, String colour) {}

    private NameplateLines() {}

    /**
     * The lines for a player, top to bottom. {@code prefix} and {@code suffix} are the worn titles' text (empty when
     * none), {@code titleColour} their colour or null, {@code guild} the guild's name or null.
     */
    public static List<Line> of(String name, String prefix, String suffix, @Nullable String titleColour, @Nullable String guild) {
        var lines = new ArrayList<Line>(3);
        String colour = titleColour == null ? PLAIN : titleColour;
        String title = join(clean(prefix), clean(suffix));
        if (!title.isEmpty()) lines.add(new Line(TITLE_STYLE, title, colour));
        lines.add(new Line(NAME_STYLE, clean(name), colour));
        String guildName = clean(guild);
        if (!guildName.isEmpty()) lines.add(new Line(GUILD_STYLE, "<" + guildName + ">", GUILD_COLOUR));
        return List.copyOf(lines);
    }

    private static String join(String prefix, String suffix) {
        if (prefix.isEmpty()) return suffix;
        if (suffix.isEmpty()) return prefix;
        return prefix + " · " + suffix;
    }

    private static String clean(@Nullable String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").strip();
    }

    /** #rrggbb as the three bytes the game's colour takes. */
    static byte[] rgb(String colour) {
        int value = Integer.parseInt(colour.substring(1), 16);
        return new byte[] {(byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }
}
