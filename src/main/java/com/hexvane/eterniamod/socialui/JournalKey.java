package com.hexvane.eterniamod.socialui;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.ChatMessage;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Set;

/**
 * Opens the Journal from the game mode key. The client sends that key as the chat command "/gm c" or "/gm a".
 * Only players who may not run /gm are redirected; for everyone else the packet passes untouched and /gm works.
 */
final class JournalKey implements AutoCloseable {
    private static final Set<String> KEY_COMMANDS = Set.of("/gm c", "/gm a");
    private final PacketFilter filter;

    JournalKey() { filter = PacketAdapters.registerInbound((PlayerPacketFilter) JournalKey::intercept); }

    /** Runs on the network thread; the page itself opens on the player's world thread. */
    private static boolean intercept(PlayerRef player, Packet packet) {
        if (!(packet instanceof ChatMessage chat) || chat.message == null || !KEY_COMMANDS.contains(chat.message)) return false;
        AbstractCommand gameMode = CommandManager.get().resolveCommand("gm");
        if (gameMode != null && gameMode.hasPermission(player)) return false;
        var ref = player.getReference();
        if (ref == null || !ref.isValid()) return true;
        var store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> { if (ref.isValid()) SocialUiBootstrap.toggleJournal(ref, store, player); });
        return true;
    }

    @Override public void close() { PacketAdapters.deregisterInbound(filter); }
}
