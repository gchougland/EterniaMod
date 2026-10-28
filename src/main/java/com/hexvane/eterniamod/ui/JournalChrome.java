package com.hexvane.eterniamod.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Shared pieces of the full-screen Citadel Codex frame (ServicesPage.ui): header, top tabs, sidebar tabs and tiles. */
public final class JournalChrome {
    private JournalChrome() {}

    public static void icon(UICommandBuilder commands, String selector, String icon) {
        commands.setObject(selector + ".Background", new PatchStyle(Value.of("EterniaMod/Icons/" + icon + ".png")));
    }

    /** The Journal shows the Eternia wordmark; an NPC desk shows the character's emblem, name and trade. */
    public static void header(UICommandBuilder commands, String title, String subtitle, String emblem) {
        boolean desk = emblem != null;
        commands.set("#DeskEmblem.Visible", desk);
        commands.set("#Wordmark.Visible", !desk);
        commands.set("#ServicesTitle.Visible", desk);
        commands.set("#ServicesTitle.TextSpans", Message.raw(title));
        commands.set("#ServicesSubtitle.TextSpans", Message.raw(subtitle));
        if (desk) icon(commands, "#DeskIcon", emblem);
    }

    public static void section(UICommandBuilder commands, String name, String icon, String note) {
        commands.set("#SectionName.TextSpans", Message.raw(name));
        commands.set("#SideNote.TextSpans", Message.raw(note));
        icon(commands, "#SectionIcon", icon);
    }

    /** Appends one top tab; returns its button selector for event binding. */
    public static String navTab(UICommandBuilder commands, int index, String label, String icon, boolean active, boolean disabled) {
        commands.append("#Navigation", active ? "EterniaMod/JournalTabActive.ui" : "EterniaMod/JournalTab.ui");
        String tab = "#Navigation[" + index + "]";
        commands.set(tab + " #NavLabel.TextSpans", Message.raw(label));
        icon(commands, tab + " #NavIcon", active ? icon : icon + "Muted");
        commands.set(tab + ".Disabled", disabled);
        return tab;
    }

    /** Appends one sidebar tab; an empty icon leaves the icon slot blank. */
    public static String sideTab(UICommandBuilder commands, int index, String label, String icon, boolean selected, boolean disabled) {
        return sideTab(commands, "#Tabs", index, label, icon, selected, disabled);
    }

    public static String sideTab(UICommandBuilder commands, String container, int index, String label, String icon, boolean selected, boolean disabled) {
        commands.append(container, selected ? "EterniaMod/SideTabSelected.ui" : "EterniaMod/SideTab.ui");
        String tab = container + "[" + index + "]";
        commands.set(tab + " #SideTabLabel.TextSpans", Message.raw(label));
        if (!icon.isEmpty()) icon(commands, tab + " #SideTabIcon", icon);
        commands.set(tab + ".Disabled", disabled);
        return tab;
    }

    /** A fitting pictogram for a generic list or card menu, chosen from its title. */
    public static String pageIcon(String title) {
        String t = title.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("travel") || t.contains("world") || t.contains("managed") || t.contains("npc")) return "worlds";
        if (t.contains("road") || t.contains("paving") || t.contains("path")) return "path";
        if (t.contains("guild")) return "guild";
        if (t.contains("plot") || t.startsWith("move")) return "plot";
        if (t.contains("customization") || t.contains("palette")) return "palette";
        if (t.contains("decoration")) return "furnishing";
        if (t.contains("house") || t.contains("housing") || t.contains("home")) return "housing";
        if (t.contains("upgrade")) return "addition";
        if (t.contains("recover") || t.contains("restore")) return "move";
        if (t.contains("playground") || t.contains("trial") || t.contains("test") || t.contains("route") || t.contains("explor")) return "quests";
        return "ledger";
    }

    /** Appends one NPC service tile into #DeskTiles. */
    public static String tile(UICommandBuilder commands, int index, String title, String detail, String icon, boolean primary, boolean disabled) {
        commands.append("#DeskTiles", primary ? "EterniaMod/DeskTilePrimary.ui" : "EterniaMod/DeskTile.ui");
        String tile = "#DeskTiles[" + index + "]";
        commands.set(tile + " #TileTitle.TextSpans", Message.raw(title));
        commands.set(tile + " #TileDetail.TextSpans", Message.raw(detail));
        icon(commands, tile + " #TileIcon", icon);
        commands.set(tile + ".Disabled", disabled);
        return tile;
    }
}
