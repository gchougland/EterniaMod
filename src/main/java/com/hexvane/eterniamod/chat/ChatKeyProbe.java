package com.hexvane.eterniamod.chat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.FormattedMessageImage;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.protocol.packets.interface_.HudWidgetSlot;
import com.hypixel.hytale.protocol.packets.interface_.UIObjectDataValue;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.ui.CommandDefinition;
import com.hypixel.hytale.server.core.modules.ui.UIHudWidget;
import com.hypixel.hytale.server.core.modules.ui.UIModule;
import com.hypixel.hytale.server.core.modules.ui.Ui;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * TEMPORARY probe for the chat dock: finds out, in a real client, whether the game's Chat key can be caught by a
 * server driven HUD widget, whether the game's own chat still opens when its HUD part is hidden, and whether chat
 * lines can carry pictures. {@code /e chat probe on|hide|off}. Needs the server started with
 * {@code -Dhytale.serverside_ui_preview=true}. Remove once the dock is built.
 */
final class ChatKeyProbe extends AbstractPlayerCommand {
    static final String PREVIEW_FLAG = "hytale.serverside_ui_preview";
    private static final String WIDGET = "Eternia.ChatProbe";
    private static final String TYPE = "Server.Eternia.ChatProbe";
    private static volatile boolean typesRegistered;

    ChatKeyProbe() {
        super("probe", "eternia_chat.command.help");
        addSubCommand(new Mode("on", false, true));
        addSubCommand(new Mode("hide", true, true));
        addSubCommand(new Mode("off", false, false));
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
        apply(store, ref, player, false, true);
    }

    private static final class Mode extends AbstractPlayerCommand {
        private final boolean hideNative;
        private final boolean show;

        Mode(String name, boolean hideNative, boolean show) {
            super(name, "eternia_chat.command.help");
            this.hideNative = hideNative;
            this.show = show;
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            apply(store, ref, player, hideNative, show);
        }
    }

    private static void apply(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, boolean hideNative, boolean show) {
        if (System.getProperty(PREVIEW_FLAG) == null) {
            player.sendMessage(Message.raw("Probe: start the server with -D" + PREVIEW_FLAG + "=true first."));
            return;
        }
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        var huds = component.getHudManager();
        if (huds.getWidgets().containsKey(WIDGET)) huds.dev_unregisterWidget(player, WIDGET);
        if (!show) {
            huds.showHudComponents(player, HudComponent.Chat);
            player.sendMessage(Message.raw("Probe off. The game's chat is shown again."));
            return;
        }
        registerTypes();
        var widget = new Widget();
        huds.dev_registerWidget(player, widget);
        huds.dev_setWidgetVisible(player, widget, true);
        if (hideNative) huds.hideHudComponents(player, HudComponent.Chat);
        else huds.showHudComponents(player, HudComponent.Chat);
        player.sendMessage(Message.join(Message.raw("Probe on. Picture tests: "),
            picture("UI/Custom/EterniaMod/ClaimMap/SwatchMyGuild.png"), Message.raw(" | "),
            picture("EterniaMod/ClaimMap/SwatchMyGuild.png"), Message.raw(" | "),
            picture("Common/UI/Custom/EterniaMod/ClaimMap/SwatchMyGuild.png"), Message.raw(" (three paths)")));
    }

    private static void registerTypes() {
        if (typesRegistered) return;
        typesRegistered = true;
        UIModule.get().defineClass(TYPE)
            .prop("Presses", "Int32")
            .prop("Status", "FormattedMessage")
            .prop("ChatCommand", "ICommand")
            .prop("SendCommand", "ICommand")
            .register();
    }

    static Message picture(String path) {
        var message = new FormattedMessage();
        message.image = new FormattedMessageImage(path, 18, 18);
        return new Message(message);
    }

    private static final class Widget extends UIHudWidget {
        private int presses;
        private final CommandDefinition chatCommand;
        private final CommandDefinition sendCommand;

        Widget() {
            super(WIDGET, "UI/Noesis/EterniaMod/ChatProbe.xaml", HudWidgetSlot.Overlay, 0);
            chatCommand = registerCommand("ChatCommand", (ref, store, param) -> {
                presses++;
                setProperty("Presses", presses);
                setProperty("Status", Message.raw("Caught the chat key on the server."));
                if (playerRef != null) playerRef.sendMessage(Message.raw("Probe: chat key caught (" + presses + ")"));
            });
            sendCommand = registerCommand("SendCommand", (ref, store, param) -> {
                setProperty("Status", Message.join(Message.raw("Typed in the probe box: "), Message.raw(String.valueOf(param)).bold(true)));
                if (playerRef != null) playerRef.sendMessage(Message.raw("Probe: box sent \"" + param + "\""));
            });
        }

        @Override
        public UIObjectDataValue createDataContext(Ref<EntityStore> ref, Store<EntityStore> store) {
            return Ui.obj(TYPE)
                .put("Presses", presses)
                .put("Status", Message.join(Message.raw("Press your chat key. Picture in text: "),
                    picture("UI/Custom/EterniaMod/ClaimMap/SwatchMyGuild.png")))
                .put("ChatCommand", chatCommand)
                .put("SendCommand", sendCommand)
                .build();
        }
    }
}
