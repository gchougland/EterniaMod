package com.hexvane.eterniamod.chat;

import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live guild chat, shared by {@code /e guildchat}, {@code /e chat guild <message>} and plain chat on the Guild channel.
 * Ephemeral and private: it never falls back to public chat, and both sender and each reader must still hold
 * {@code guild.chat} when the message is delivered. Two seconds between messages per sender.
 */
public final class GuildChannel {
    private static final long COOLDOWN_NANOS = 2_000_000_000L;
    private static final ConcurrentHashMap<UUID, Long> lastSent = new ConcurrentHashMap<>();

    private GuildChannel() {}

    /** Sends {@code text} (already checked by {@link ChatText}) to the sender's online guild members. Failures are told to the sender. */
    public static CompletableFuture<Void> send(EterniaServices services, PlayerRef sender, String text) {
        UUID actor = sender.getUuid();
        return SocialUiBootstrap.<Void>supply(services, () -> {
            if (Universe.get().getPlayer(actor) != sender) return null;
            var blocked = ChatGate.blockReason(actor);
            if (blocked.isPresent()) { sender.sendMessage(ChatFormat.warn(blocked.get())); return null; }
            long now = System.nanoTime();
            if (lastSent.size() >= 4096) lastSent.entrySet().removeIf(entry -> now - entry.getValue() > 60_000_000_000L);
            if (lastSent.size() >= 4096 && !lastSent.containsKey(actor)) throw new Refused("eternia_chat.fail.guildBusy");
            lastSent.compute(actor, (id, previous) -> {
                if (previous != null && now - previous < COOLDOWN_NANOS) throw new Refused("eternia_chat.fail.guildCooldown");
                return now;
            });
            List<UUID> members = services.batch(() -> {
                var guild = services.guilds().membership(actor).orElseThrow(() -> new Refused("eternia_chat.fail.noGuild")).guildId();
                if (!services.guilds().can(actor, guild, "guild.chat")) throw new Refused("eternia_chat.fail.guildRole");
                return services.guilds().roster(actor).stream().map(member -> member.playerId())
                    .filter(id -> services.guilds().can(id, guild, "guild.chat")).toList();
            });
            var line = ChatLine.chat(ChatChannel.GUILD, ChatFormat.speakerName(sender), ChatFormat.rank(sender), text);
            var allowed = new HashSet<>(members);
            int heard = 0;
            for (PlayerRef recipient : ChatService.audience(sender, Universe.get().getPlayers())) {
                if (!SocialUiBootstrap.isOpen(services) || Universe.get().getPlayer(actor) != sender) break;
                if (!allowed.contains(recipient.getUuid())) continue;
                ChatDock.deliver(recipient, line);
                if (!recipient.getUuid().equals(actor)) heard++;
            }
            ChatService.log(ChatChannel.GUILD, sender, text);
            if (heard == 0) sender.sendMessage(ChatFormat.note(Message.translation("eternia_chat.hint.guildAlone")));
            return null;
        }).exceptionally(error -> {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            if (Universe.get().getPlayer(actor) != sender) return null;
            Message reason = cause instanceof Refused refused ? Message.translation(refused.key)
                : cause instanceof DomainException ? Message.raw(cause.getMessage())
                : Message.translation("eternia_chat.fail.unavailable");
            sender.sendMessage(ChatFormat.warn(reason));
            return null;
        });
    }

    /** A refusal the sender should read, as a lang key. */
    static final class Refused extends RuntimeException {
        final String key;
        Refused(String key) { super(key, null, false, false); this.key = key; }
    }
}
