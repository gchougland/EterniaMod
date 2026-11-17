package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.production.ProductionRuntime.Station;
import com.hypixel.hytale.protocol.packets.interface_.Notification;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.UUID;

/** Notices that reach a station's owner wherever they are. Any thread. */
final class ProductionFeedback {
    private ProductionFeedback() {}

    /** Once per fill: tells the owner, in whichever world they are, that the stockpile is waiting. */
    static void full(ProductionRuntime runtime, Station s) {
        if (s.toldFull) return;
        s.toldFull = true;
        PlayerRef owner = owner(s);
        if (owner == null) return;
        toast(owner, "eternia:production:full:" + s.row.id, "Your " + runtime.profession(s).station() + " is full", "Come home to collect it.");
        ProductionActions.sound(owner, "SFX_Discovery_Z1_Short");
    }

    static void lookKept(ProductionRuntime runtime, Station s, String reason) {
        PlayerRef owner = owner(s);
        if (owner != null) toast(owner, "eternia:production:look:" + s.row.id, "Your " + runtime.profession(s).station() + " could not be rebuilt yet",
            reason == null || reason.isBlank() ? "Move what stands on it, then upgrade once more." : reason);
    }

    static PlayerRef owner(Station s) {
        Universe universe = Universe.get();
        if (universe == null || !s.row.owner.startsWith("PLAYER:")) return null;
        try {
            PlayerRef player = universe.getPlayer(UUID.fromString(s.row.owner.substring(7)));
            return player == null || player.getWorldUuid() == null ? null : player;
        } catch (IllegalArgumentException bad) { return null; }
    }

    static void toast(PlayerRef player, String tag, String title, String text) {
        var packet = new Notification();
        packet.style = NotificationStyle.Success;
        packet.tag = tag;
        packet.icon = "UI/Custom/EterniaMod/Icons/coins.png";
        packet.message = Message.raw(title).getFormattedMessage();
        packet.secondaryMessage = Message.raw(text).getFormattedMessage();
        player.getPacketHandler().writeNoCache(packet);
    }
}
