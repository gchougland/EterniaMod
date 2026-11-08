package com.hexvane.eterniamod.chat;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Chat channels. Plain chat follows the sender's chosen channel; {@code /e chat <channel> <message>} sends one
 * message elsewhere. Server chat keeps the engine's own delivery (only the look changes); every other channel is
 * delivered here so hints can follow the message and nothing leaks to public chat.
 * Store reads and party lookups run on this service's own threads, never the world or network thread.
 */
public final class ChatService implements AutoCloseable {
    private static volatile ChatService active;
    private final EterniaServices services;
    private final ChatPreferences preferences;
    private final ChatSettings settings;
    private final HytaleLogger logger;
    private final Set<UUID> reminded = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(2,
        Thread.ofPlatform().daemon(true).name("eternia-chat-", 0).factory());

    public ChatService(EterniaServices services, ChatSettings settings, HytaleLogger logger) {
        this.services = services;
        this.preferences = services.chatPreferences();
        this.settings = settings;
        this.logger = logger;
        preferences.setDefaultChannel(settings.defaultChannel());
        active = this;
    }

    public static ChatService get() { return active; }
    public ChatSettings settings() { return settings; }

    // ---- plain chat ----

    /** The engine's chat event. Runs after earlier listeners, so a cancelled message (a mute) stays cancelled. */
    public CompletableFuture<PlayerChatEvent> onChat(PlayerChatEvent event) {
        if (event.isCancelled()) return CompletableFuture.completedFuture(event);
        PlayerRef sender = event.getSender();
        return CompletableFuture.supplyAsync(() -> {
            var blocked = ChatGate.blockReason(sender.getUuid());
            if (blocked.isPresent()) {
                event.setCancelled(true);
                sender.sendMessage(ChatFormat.warn(blocked.get()));
                return event;
            }
            // "@p see you there" sends one message to Party without switching, keeping every character as typed.
            var shortcut = ChatShortcut.parse(event.getContent());
            if (shortcut.isPresent()) {
                event.setCancelled(true);
                dispatch(sender, shortcut.get().channel(), shortcut.get().text(), event.getTargets());
                return event;
            }
            ChatChannel channel = preferences.current(sender.getUuid());
            if (channel == ChatChannel.SERVER) {
                // The engine delivers to everyone in every world; only the look is ours.
                event.setFormatter((player, text) -> ChatFormat.line(ChatChannel.SERVER, ChatFormat.speaker(player), text));
                return event;
            }
            event.setCancelled(true);
            dispatch(sender, channel, event.getContent(), event.getTargets());
            return event;
        }, executor).exceptionally(failure -> {
            logger.atWarning().withCause(failure).log("Chat could not be routed for %s", sender.getUsername());
            event.setCancelled(true);
            sender.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.unavailable")));
            return event;
        });
    }

    // ---- commands ----

    /** {@code /e chat <channel> <message>}: one message, without switching. */
    public void sendOnce(PlayerRef sender, ChatChannel channel, String typed) {
        var cleaned = ChatText.clean(typed);
        if (cleaned.isEmpty()) { sender.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.empty"))); return; }
        var problem = ChatText.problem(cleaned.get());
        if (problem.isPresent()) { sender.sendMessage(ChatFormat.warn(Message.translation(problem.get()))); return; }
        run(sender, () -> {
            var blocked = ChatGate.blockReason(sender.getUuid());
            if (blocked.isPresent()) { sender.sendMessage(ChatFormat.warn(blocked.get())); return; }
            dispatch(sender, channel, cleaned.get(), audience(sender, Universe.get().getPlayers()));
        });
    }

    /** {@code /e chat <channel>}: remember the channel for plain chat. */
    public void switchTo(PlayerRef player, ChatChannel channel) {
        run(player, () -> {
            UUID id = player.getUuid();
            if (channel == ChatChannel.PARTY && services.parties().find(id).isEmpty()) {
                player.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.noPartySwitch"))); return;
            }
            if (channel == ChatChannel.GUILD && services.guilds().membership(id).isEmpty()) {
                player.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.noGuildSwitch"))); return;
            }
            if (preferences.current(id) == channel) {
                player.sendMessage(ChatFormat.note(Message.translation("eternia_chat.switch.already").param("channel", ChatFormat.channelName(channel))));
                return;
            }
            preferences.select(id, channel);
            player.sendMessage(Message.join(ChatFormat.tag(channel), Message.raw(" "),
                ChatFormat.note(Message.translation("eternia_chat.switch.done").param("channel", ChatFormat.channelName(channel))
                    .param("about", about(channel)))));
        });
    }

    /** {@code /e chat}: where you are talking, and how to change it. */
    public void showStatus(PlayerRef player) {
        run(player, () -> {
            ChatChannel current = preferences.current(player.getUuid());
            player.sendMessage(ChatFormat.note(Message.translation("eternia_chat.status.current").param("channel", ChatFormat.channelName(current))));
            for (ChatChannel channel : ChatChannel.values()) {
                player.sendMessage(Message.join(
                    Message.raw(channel == current ? "  > " : "    ").color(channel.color()),
                    Message.raw("/e c " + channel.shortAlias() + "  ").color(channel.color()).bold(true),
                    ChatFormat.channelName(channel), Message.raw("  "),
                    ChatFormat.note(about(channel))));
            }
            player.sendMessage(ChatFormat.note(Message.translation("eternia_chat.help.usage")));
            player.sendMessage(ChatFormat.note(Message.translation("eternia_chat.help.shortcut")));
        });
    }

    // ---- lifecycle ----

    /** Once per session, after joining a world: say so when plain chat is not going to everyone. */
    public void onReady(PlayerRef player) {
        if (!reminded.add(player.getUuid())) return;
        run(player, () -> {
            ChatChannel current = preferences.current(player.getUuid());
            if (current == ChatChannel.SERVER) return;
            player.sendMessage(Message.join(ChatFormat.tag(current), Message.raw(" "),
                ChatFormat.note(Message.translation("eternia_chat.join.reminder").param("channel", ChatFormat.channelName(current)))));
        });
    }

    public void onDisconnect(UUID player) { reminded.remove(player); preferences.forget(player); }

    @Override public void close() {
        if (active == this) active = null;
        executor.shutdown();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    // ---- delivery ----

    private void dispatch(PlayerRef sender, ChatChannel channel, String text, Collection<PlayerRef> audience) {
        if (channel == ChatChannel.GUILD) { GuildChannel.send(services, sender, text); return; }
        Set<UUID> members = Set.of();
        if (channel == ChatChannel.PARTY) {
            var party = services.parties().find(sender.getUuid());
            if (party.isEmpty()) { sender.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.noParty"))); return; }
            members = new HashSet<>(party.get().members());
        }
        var byId = new HashMap<UUID, PlayerRef>();
        var listeners = new ArrayList<ChatRouting.Listener>();
        for (PlayerRef ref : audience) { byId.put(ref.getUuid(), ref); listeners.add(listener(ref)); }
        byId.put(sender.getUuid(), sender);
        List<UUID> recipients = ChatRouting.recipients(channel, listener(sender), listeners, members, settings.nearbyRadius());
        Message line = ChatFormat.line(channel, ChatFormat.speaker(sender), text);
        for (UUID id : recipients) byId.get(id).sendMessage(line);
        log(channel, sender, text);
        if (ChatRouting.onlySender(recipients)) {
            switch (channel) {
                case NEARBY -> sender.sendMessage(ChatFormat.note(Message.translation("eternia_chat.hint.nobodyNearby").param("radius", settings.nearbyBlocks())));
                case PARTY -> sender.sendMessage(ChatFormat.note(Message.translation("eternia_chat.hint.partyAlone")));
                case WORLD -> sender.sendMessage(ChatFormat.note(Message.translation("eternia_chat.hint.worldAlone")));
                default -> {}
            }
        }
    }

    /** Online players who have not hidden the sender, as the engine's own chat does. */
    static List<PlayerRef> audience(PlayerRef sender, Collection<PlayerRef> online) {
        var result = new ArrayList<PlayerRef>(online.size());
        for (PlayerRef ref : online) {
            if (ref.getUuid().equals(sender.getUuid()) || !ref.getHiddenPlayersManager().isPlayerHidden(sender.getUuid())) result.add(ref);
        }
        return result;
    }

    private static ChatRouting.Listener listener(PlayerRef ref) {
        var position = ref.getTransform().getPosition();
        return new ChatRouting.Listener(ref.getUuid(), ref.getWorldUuid(), position.x, position.y, position.z);
    }

    private Message about(ChatChannel channel) {
        var message = Message.translation(channel.langKey("about"));
        return channel == ChatChannel.NEARBY ? message.param("radius", settings.nearbyBlocks()) : message;
    }

    /** Keeps a console record of channel chat, as the engine does for its own chat. */
    static void log(ChatChannel channel, PlayerRef sender, String text) {
        var service = active;
        if (service != null) service.logger.atInfo().log("[%s] %s: %s", channel.id(), sender.getUsername(), text);
    }

    private void run(PlayerRef player, Runnable work) {
        try {
            CompletableFuture.runAsync(work, executor).exceptionally(failure -> {
                logger.atWarning().withCause(failure).log("Chat action failed for %s", player.getUsername());
                player.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.unavailable")));
                return null;
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            player.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.unavailable")));
        }
    }
}
