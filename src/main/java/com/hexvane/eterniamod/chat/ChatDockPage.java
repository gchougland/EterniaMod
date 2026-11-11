package com.hexvane.eterniamod.chat;

import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * The open chat dock: channel tabs, the log, the message box and the emote grid. Sending closes it, as the game's
 * own chat does. Lines that arrive while it is open are added to the log without rebuilding the page.
 */
final class ChatDockPage extends EterniaInteractiveCustomUIPage<ChatDockPage.Data> {
    private final ChatDock dock;
    private ChatChannel channel;
    private boolean emotes;
    private String draft = "";
    private int rows;

    ChatDockPage(ChatDock dock, PlayerRef player, ChatChannel channel) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.dock = dock;
        this.channel = channel;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder c, @Nonnull UIEventBuilder e, @Nonnull Store<EntityStore> store) {
        List<ChatLine> lines = dock.history(playerRef.getUuid());
        commands(c, channel, emotes, draft, lines);
        rows = lines.size();
        for (ChatChannel option : ChatChannel.values()) {
            if (option != channel) bind(e, CustomUIEventBindingType.Activating, "#Tab" + cap(option), "Channel", option.id());
        }
        bind(e, CustomUIEventBindingType.Activating, "#DockSend", "Send", "");
        bind(e, CustomUIEventBindingType.Validating, "#DockInput", "Send", "");
        bind(e, CustomUIEventBindingType.Activating, "#EmoteToggle", "Emotes", "");
        for (int i = 0; i < ChatEmotes.NAMES.size(); i++) {
            bind(e, CustomUIEventBindingType.Activating, "#EmoteGrid[" + i + "]", "Emote", ChatEmotes.NAMES.get(i));
        }
    }

    /** Everything the page shows for a state. Package private so the render check sends exactly this. */
    static void commands(UICommandBuilder c, ChatChannel channel, boolean emotes, String draft, List<ChatLine> lines) {
        c.append("EterniaMod/ChatDock.ui");
        for (ChatChannel option : ChatChannel.values()) {
            boolean on = option == channel;
            c.set("#Tab" + cap(option) + ".Visible", !on);
            c.set("#Tab" + cap(option) + "On.Visible", on);
            c.set("#Chip" + cap(option) + ".Visible", on);
        }
        c.set("#DockInput.Value", draft);
        c.set("#EmotePanel.Visible", emotes);
        for (int i = 0; i < ChatEmotes.NAMES.size(); i++) {
            c.append("#EmoteGrid", "EterniaMod/ChatDockEmote.ui");
            c.setObject("#EmoteGrid[" + i + "] #EmotePic.Background",
                new PatchStyle(Value.of(ChatLines.ART + "Emote_" + ChatEmotes.NAMES.get(i) + ".png")));
        }
        for (int i = 0; i < lines.size(); i++) ChatLines.append(c, "#DockLog", i, lines.get(i), false);
    }

    private static void bind(UIEventBuilder e, CustomUIEventBindingType type, String selector, String action, String arg) {
        e.addEventBinding(type, selector,
            new EventData().append("Action", action).append("Arg", arg).append("@Text", "#DockInput.Value"), false);
    }

    private static String cap(ChatChannel channel) {
        return Character.toUpperCase(channel.id().charAt(0)) + channel.id().substring(1);
    }

    /** World thread. A line that arrived while the dock is open. */
    void onLine(ChatLine line) {
        if (isDismissed()) return;
        var c = new UICommandBuilder();
        ChatLines.append(c, "#DockLog", rows++, line, false);
        sendUpdate(c, null, false);
    }

    void shut() { close(); }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        if (data.action == null || isDismissed()) return;
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid() || isDismissed()) return;
            var component = store.getComponent(ref, Player.getComponentType());
            if (component == null || component.getPageManager().getCustomPage() != this) return;
            if (data.text != null) draft = data.text;
            ChatService service = ChatService.get();
            switch (data.action) {
                case "Send" -> {
                    String typed = draft;
                    draft = "";
                    close();
                    if (service != null && !typed.isBlank()) service.sendFromDock(playerRef, typed);
                }
                case "Channel" -> {
                    var picked = ChatChannel.parse(data.arg);
                    if (picked.isEmpty() || service == null) return;
                    service.switchFromDock(playerRef, picked.get()).thenAccept(now ->
                        ChatDock.onWorld(playerRef, (r, s) -> { channel = now; rebuild(); }));
                }
                case "Emotes" -> { emotes = !emotes; rebuild(); }
                case "Emote" -> { draft = ChatEmotes.add(draft, data.arg); rebuild(); }
                default -> { }
            }
        });
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        super.onDismiss(ref, store);
        dock.closed(playerRef, this);
    }

    public static final class Data {
        public String action, arg, text;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("Arg", Codec.STRING), (d, v) -> d.arg = v, d -> d.arg).add()
            .append(new KeyedCodec<>("@Text", Codec.STRING), (d, v) -> d.text = v, d -> d.text).add().build();
    }
}
