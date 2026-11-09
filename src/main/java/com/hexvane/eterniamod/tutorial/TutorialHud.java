package com.hexvane.eterniamod.tutorial;

import com.hexvane.eterniamod.domain.TutorialService;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

/** The walkthrough panel in the Hub: the next thing to do and every townsperson, green once met. */
final class TutorialHud extends CustomUIHud {
    static final String ID = "eternia:tutorial";
    private static final String MET = "#7fbf8a", TODO = "#e6dcc6";
    private TutorialService.State shown;
    private java.util.List<String> shownPeople;

    TutorialHud(PlayerRef player) { super(player, ID, 0); }

    @Override
    protected void build(@Nonnull UICommandBuilder commands) { commands.append("EterniaMod/TutorialHud.ui"); }

    void refresh(TutorialService.State state, java.util.List<String> people) {
        if (state.equals(shown) && people.equals(shownPeople)) return;
        shown = state;
        shownPeople = people;
        update(false, commands(state, people));
    }

    /** The panel's commands for a state. Package private for the render check in tests. */
    static UICommandBuilder commands(TutorialService.State state, java.util.List<String> people) {
        var c = new UICommandBuilder();
        c.set("#Title.TextSpans", Message.translation(HubDialogue.text("hud.title")));
        Message step;
        if (!state.met().contains(TutorialService.GUIDE)) step = Message.translation(HubDialogue.text("hud.meetGuide"));
        else if (!state.metEveryone(people)) step = Message.translation(HubDialogue.text("hud.meetTown"))
            .param("count", String.valueOf(state.townspeopleMet(people))).param("total", String.valueOf(people.size()));
        else step = Message.translation(HubDialogue.text("hud.returnGuide"));
        c.set("#Step.TextSpans", step);
        for (int i = 0; i < 7; i++) {
            String selector = "#Town" + i;
            if (i >= people.size()) { c.set(selector + ".Visible", false); continue; }
            String npc = people.get(i);
            boolean met = state.met().contains(npc);
            c.set(selector + ".Visible", true);
            c.set(selector + ".TextSpans", Message.raw((met ? "Met  " : "")).color(MET)
                .insert(Message.raw(HubDialogue.name(npc) + ", " + HubDialogue.profession(npc)).color(met ? MET : TODO)));
        }
        c.set("#Footer.TextSpans", Message.translation(HubDialogue.text("hud.footer")));
        return c;
    }
}
