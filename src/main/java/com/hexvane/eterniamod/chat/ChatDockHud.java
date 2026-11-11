package com.hexvane.eterniamod.chat;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.List;
import javax.annotation.Nonnull;

/** Recent chat lines shown for a few seconds while the dock is closed. It takes no input. */
final class ChatDockHud extends CustomUIHud {
    static final String ID = "eternia:chatdock";
    private List<ChatLine> shown = List.of();

    ChatDockHud(PlayerRef player) { super(player, ID, 0); }

    @Override
    protected void build(@Nonnull UICommandBuilder commands) { commands.append("EterniaMod/ChatDockHud.ui"); }

    void show(List<ChatLine> lines) {
        if (lines.equals(shown)) return;
        shown = lines;
        update(false, commands(lines));
    }

    /** The popup's commands for these lines, oldest first. Package private for the render check. */
    static UICommandBuilder commands(List<ChatLine> lines) {
        var c = new UICommandBuilder();
        c.clear("#ChatHud");
        for (int i = 0; i < lines.size(); i++) ChatLines.append(c, "#ChatHud", i, lines.get(i), true);
        return c;
    }
}
