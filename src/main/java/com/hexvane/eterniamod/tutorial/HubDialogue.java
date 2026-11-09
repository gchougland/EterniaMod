package com.hexvane.eterniamod.tutorial;

import com.hexvane.eterniamod.domain.TutorialService;
import com.hexvane.eterniamod.setup.HubNpcIdentity;
import java.util.List;
import javax.annotation.Nullable;

/**
 * What each Hub NPC says, as keys into eternia_tutorial.lang (prowl's lines). A first meeting shows the opening
 * lines on a dialogue page; later visits show the returning line on the NPC's desk.
 */
public final class HubDialogue {
    private static final String LANG = "eternia_tutorial.";

    private HubDialogue() {}

    /** The tutorial key for an NPC identity, or null for one outside the tutorial. */
    @Nullable
    public static String npcKey(HubNpcIdentity identity) {
        return switch (identity) {
            case GREETER -> TutorialService.GUIDE;
            case HOUSING -> "housing";
            case GUILD -> "guild";
            case SHOP -> "shop";
            case STORE -> "store";
            case MINIGAME -> "minigame";
            case GATEHOUSE -> "gatehouse";
            case RULES -> "rules";
        };
    }

    public static String key(String npc, String line) { return LANG + "npc." + npc + "." + line; }

    public static String text(String key) { return LANG + key; }

    /**
     * The dialogue pages to show for a meeting, or an empty list when the NPC's desk should open straight away:
     * the opening lines on a first meeting, and the guide's farewell when the tutorial has just been finished.
     */
    public static List<String> pages(String npc, TutorialService.Meeting meeting) {
        if (meeting.finishedNow()) return List.of(key(npc, "returning1"), key(npc, "returning2"));
        if (!meeting.first()) return List.of();
        if (npc.equals("rules")) return List.of(key(npc, "opening"), key(npc, "opening2"));
        return List.of(key(npc, "opening"));
    }

    /** A short display name for the walkthrough panel and chat, such as "Bramble Hearthleaf". */
    public static String name(String npc) {
        for (var identity : HubNpcIdentity.values()) if (npc.equals(npcKey(identity))) return identity.characterName();
        return npc;
    }

    public static String profession(String npc) {
        for (var identity : HubNpcIdentity.values()) if (npc.equals(npcKey(identity))) return identity.profession();
        return "";
    }
}
