package com.hexvane.eterniamod.tutorial;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** The walkthrough panel in the Hub: which step this is, who to talk to and why, and an arrow pointing the way. */
final class TutorialHud extends CustomUIHud {
    static final String ID = "eternia:tutorial";

    /** Where the arrow points and how far away the NPC is; null when the NPC is not placed in this world. */
    record Pointer(int frame, int distance) {}

    private TutorialStep shownStep;
    private Pointer shownPointer;
    private boolean drawn;

    TutorialHud(PlayerRef player) { super(player, ID, 0); }

    @Override
    protected void build(@Nonnull UICommandBuilder commands) { commands.append("EterniaMod/TutorialHud.ui"); }

    void refresh(TutorialStep step, @Nullable Pointer pointer) {
        if (drawn && step.equals(shownStep) && Objects.equals(pointer, shownPointer)) return;
        boolean sameStep = drawn && step.equals(shownStep);
        shownStep = step;
        shownPointer = pointer;
        drawn = true;
        update(false, sameStep ? pointerCommands(new UICommandBuilder(), pointer) : commands(step, pointer));
    }

    /** The panel's commands for a step. Package private for the render check in tests. */
    static UICommandBuilder commands(TutorialStep step, @Nullable Pointer pointer) {
        var c = new UICommandBuilder();
        c.set("#Title.TextSpans", Message.translation(HubDialogue.text("hud.title")));
        c.set("#Count.TextSpans", Message.translation(HubDialogue.text("hud.step"))
            .param("number", String.valueOf(step.number())).param("total", String.valueOf(step.total())));
        Message instruction = switch (step.kind()) {
            case MEET_GUIDE -> Message.translation(HubDialogue.text("hud.meetGuide"));
            case MEET_NPC -> Message.translation(HubDialogue.text("hud.meetNpc"))
                .param("name", HubDialogue.name(step.npc())).param("profession", HubDialogue.profession(step.npc()));
            case RETURN_GUIDE -> Message.translation(HubDialogue.text("hud.returnGuide"));
        };
        c.set("#Step.TextSpans", instruction);
        c.set("#Why.TextSpans", Message.translation(HubDialogue.why(step)));
        c.set("#Footer.TextSpans", Message.translation(HubDialogue.text("hud.footer")));
        return pointerCommands(c, pointer);
    }

    private static UICommandBuilder pointerCommands(UICommandBuilder c, @Nullable Pointer pointer) {
        c.set("#Pointer.Visible", pointer != null);
        if (pointer == null) return c;
        c.setObject("#Arrow.Background", new PatchStyle(Value.of(arrowTexture(pointer.frame()))));
        c.set("#Distance.TextSpans", pointer.distance() <= 3
            ? Message.translation(HubDialogue.text("hud.here"))
            : Message.translation(HubDialogue.text("hud.distance")).param("distance", String.valueOf(pointer.distance())));
        return c;
    }

    static String arrowTexture(int frame) { return String.format("EterniaMod/Tutorial/Arrow%02d.png", Math.floorMod(frame, TutorialStep.FRAMES)); }
}
