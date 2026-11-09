package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hexvane.eterniamod.ui.ToolHudHotkeyRows;
import com.hexvane.eterniamod.ui.ToolKeybindSlot;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Small corner panel while carrying a decoration: its name, whether it fits here, and the three keys. */
public final class PropCarryHud extends CustomUIHud {
    private final PlayerRef viewer;
    private boolean controls;

    private PropCarryHud(PlayerRef player) {
        super(player, PropCarry.HUD, 0);
        viewer = player;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commands) {
        commands.append("EterniaMod/PropCarryHud.ui");
    }

    static void show(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull PlayerRef player,
        @Nonnull PropDefinition def,
        @Nonnull PropPlacementSession session,
        @Nullable String error
    ) {
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        PropCarryHud hud;
        if (p.getHudManager().getCustomHud(PropCarry.HUD) instanceof PropCarryHud existing) {
            hud = existing;
        } else {
            hud = new PropCarryHud(player);
            p.getHudManager().addCustomHud(player, hud);
        }
        hud.refresh(def, session, error);
    }

    static void hide(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PlayerRef player) {
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p != null && p.getHudManager().getCustomHud(PropCarry.HUD) != null) {
            p.getHudManager().removeCustomHud(player, PropCarry.HUD);
        }
    }

    private void refresh(PropDefinition def, PropPlacementSession session, @Nullable String error) {
        UICommandBuilder commands = new UICommandBuilder();
        if (!controls) {
            commands.clear("#ControlRows");
            ToolHudHotkeyRows.appendRow(commands, "#ControlRows", 0, ToolKeybindSlot.SECONDARY, Message.translation("eterniamod_common.eterniamod.common.carryPlace"), viewer);
            ToolHudHotkeyRows.appendRow(commands, "#ControlRows", 1, ToolKeybindSlot.ABILITY3, Message.translation("eterniamod_common.eterniamod.common.carryRotate"), viewer);
            ToolHudHotkeyRows.appendRow(commands, "#ControlRows", 2, ToolKeybindSlot.ABILITY1, Message.translation("eterniamod_common.eterniamod.common.carryCancel"), viewer);
            controls = true;
        }
        String name = def.getDisplayName() == null ? UiPresentation.friendlyId(def.getId()) : def.getDisplayName();
        if (session.getCustodyOwner() != null && session.getCustodyOwner().kind() == Owner.Kind.GUILD) name += " · Guild";
        commands.set("#Name.Text", name);
        commands.set("#Ready.Visible", error == null);
        commands.set("#Problem.Visible", error != null);
        if (error != null) commands.set("#Problem.TextSpans", PlacementText.message(error));
        update(false, commands);
    }
}
