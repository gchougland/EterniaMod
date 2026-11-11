package com.hexvane.eterniamod.chat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.ServerMessage;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Eternia's own chat. Every line a player would have read in the game's chat box is kept here instead: chat lines
 * arrive through {@link #deliver}, and anything else the server prints is caught on its way out and kept as a
 * system line. Lines show in the dock page while it is open, and pop up in a small HUD for a few seconds when it
 * is closed. Nothing is sent to the game's chat box.
 * <p>TEMPORARY OPENER: the walk key opens and closes the dock until the game's chat key can be taken over.
 */
public final class ChatDock implements AutoCloseable {
    static final int HISTORY = 60;
    static final int POPUP_LINES = 5;
    static final long POPUP_MILLIS = 9_000;
    private static volatile ChatDock active;

    private final HytaleLogger logger;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Set<UUID> walking = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("eternia-chat-dock").factory());
    private final PacketFilter outbound;
    private final PacketFilter inbound;

    /** One player's lines and open page. The deque is guarded by itself; the page is only touched on the world thread. */
    static final class State {
        final ArrayDeque<ChatLine> lines = new ArrayDeque<>();
        ChatDockPage page;
    }

    ChatDock(HytaleLogger logger) {
        this.logger = logger;
        outbound = PacketAdapters.registerOutbound((PlayerPacketFilter) this::capture);
        inbound = PacketAdapters.registerInbound((PlayerPacketFilter) this::walkKey);
        active = this;
    }

    public static ChatDock get() { return active; }

    /** Shows a chat line to {@code recipient}. Falls back to the game's chat when the dock is not running. */
    public static void deliver(PlayerRef recipient, ChatLine line) {
        ChatDock dock = active;
        if (dock != null) { dock.add(recipient, line); return; }
        recipient.sendMessage(line.isSystem() ? line.system()
            : ChatFormat.line(line.channel(), Message.raw(line.speaker()), line.text()));
    }

    // ---- capture: everything headed for the game's chat box ----

    /** Network thread. Returning true drops the packet, so the game's chat box stays empty. */
    private boolean capture(PlayerRef player, Packet packet) {
        if (!(packet instanceof ServerMessage message) || message.message == null) return false;
        add(player, ChatLine.system(new Message(message.message)));
        return true;
    }

    /** Network thread. The walk key going down toggles the dock. Movement packets always pass. */
    private boolean walkKey(PlayerRef player, Packet packet) {
        if (!(packet instanceof ClientMovement movement) || movement.movementStates == null) return false;
        UUID id = player.getUuid();
        if (!movement.movementStates.walking) walking.remove(id);
        else if (walking.add(id)) onWorld(player, (ref, store) -> toggle(ref, store, player));
        return false;
    }

    // ---- lines ----

    private void add(PlayerRef player, ChatLine line) {
        State state = states.computeIfAbsent(player.getUuid(), id -> new State());
        synchronized (state.lines) {
            state.lines.addLast(line);
            while (state.lines.size() > HISTORY) state.lines.removeFirst();
        }
        onWorld(player, (ref, store) -> {
            if (state.page != null) state.page.onLine(line);
            else popup(ref, store, player, state);
        });
        try {
            timer.schedule(() -> onWorld(player, (ref, store) -> popup(ref, store, player, state)),
                POPUP_MILLIS + 250, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closing) { /* shutting down */ }
    }

    List<ChatLine> history(UUID player) {
        State state = states.get(player);
        if (state == null) return List.of();
        synchronized (state.lines) { return new ArrayList<>(state.lines); }
    }

    /** The newest lines still young enough to pop up, oldest first. */
    static List<ChatLine> recent(List<ChatLine> lines, long now) {
        var result = new ArrayList<ChatLine>();
        for (int i = lines.size() - 1; i >= 0 && result.size() < POPUP_LINES; i--) {
            if (now - lines.get(i).at() > POPUP_MILLIS) break;
            result.addFirst(lines.get(i));
        }
        return result;
    }

    private void popup(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, State state) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        var huds = component.getHudManager();
        ChatDockHud hud;
        if (huds.getCustomHud(ChatDockHud.ID) instanceof ChatDockHud existing) hud = existing;
        else { hud = new ChatDockHud(player); huds.addCustomHud(player, hud); }
        hud.show(state.page != null ? List.of() : recent(history(player.getUuid()), System.currentTimeMillis()));
    }

    // ---- the page ----

    /** World thread. Opens the dock, or closes it when it is the page already open. Other pages are left alone. */
    void toggle(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        var current = component.getPageManager().getCustomPage();
        if (current instanceof ChatDockPage open) { open.shut(); return; }
        if (current != null) return;
        open(player);
    }

    /** Any thread. Reads the player's channel off the world thread, then opens the page on it. */
    public void open(PlayerRef player) {
        ChatService service = ChatService.get();
        if (service == null) return;
        service.channelOf(player).thenAccept(channel -> onWorld(player, (ref, store) -> {
            Player component = store.getComponent(ref, Player.getComponentType());
            if (component == null || component.getPageManager().getCustomPage() != null) return;
            State state = states.computeIfAbsent(player.getUuid(), id -> new State());
            var page = new ChatDockPage(this, player, channel);
            state.page = page;
            component.getPageManager().openCustomPage(ref, store, page);
            popup(ref, store, player, state);
        })).exceptionally(failure -> {
            logger.atWarning().withCause(failure).log("Chat dock could not open for %s", player.getUsername());
            return null;
        });
    }

    /** World thread, from the page when it goes away. */
    void closed(PlayerRef player, ChatDockPage page) {
        State state = states.get(player.getUuid());
        if (state != null && state.page == page) state.page = null;
    }

    /** Any thread, when a player arrives in a world. Puts the HUD up so the key hints show before any message. */
    void onReady(PlayerRef player) {
        State state = states.computeIfAbsent(player.getUuid(), id -> new State());
        onWorld(player, (ref, store) -> popup(ref, store, player, state));
    }

    void onDisconnect(UUID player) { states.remove(player); walking.remove(player); }

    @Override public void close() {
        if (active == this) active = null;
        PacketAdapters.deregisterOutbound(outbound);
        PacketAdapters.deregisterInbound(inbound);
        timer.shutdownNow();
    }

    /** Runs {@code work} on the player's world thread, if they are still in a world. */
    static void onWorld(PlayerRef player, BiConsumer<Ref<EntityStore>, Store<EntityStore>> work) {
        var ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        var store = ref.getStore();
        try {
            store.getExternalData().getWorld().execute(() -> { if (ref.isValid()) work.accept(ref, store); });
        } catch (RuntimeException closing) { /* the world is going away */ }
    }
}
