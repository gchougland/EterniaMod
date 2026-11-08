package com.hexvane.eterniamod.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Who hears a message. Pure, so it is tested without a server. */
public final class ChatRouting {
    private ChatRouting() {}

    /** A player who could hear a message: where they are when it is sent. World may be null while travelling. */
    public record Listener(UUID id, UUID world, double x, double y, double z) {}

    /**
     * The players who receive a message, the sender always first and exactly once.
     *
     * @param audience online players who have not hidden the sender, in any world
     * @param members for party and guild, the group's members allowed to read it; ignored otherwise
     */
    public static List<UUID> recipients(ChatChannel channel, Listener sender, Collection<Listener> audience,
        Set<UUID> members, double nearbyRadius) {
        var result = new LinkedHashSet<UUID>();
        result.add(sender.id());
        double radiusSquared = nearbyRadius * nearbyRadius;
        for (Listener listener : audience) {
            if (listener.id().equals(sender.id())) continue;
            boolean hears = switch (channel) {
                case SERVER -> true;
                case WORLD -> sameWorld(sender, listener);
                case NEARBY -> sameWorld(sender, listener) && distanceSquared(sender, listener) <= radiusSquared;
                case PARTY, GUILD -> members.contains(listener.id());
            };
            if (hears) result.add(listener.id());
        }
        return new ArrayList<>(result);
    }

    /** True when the sender is the only one who got the message. */
    public static boolean onlySender(List<UUID> recipients) { return recipients.size() <= 1; }

    static boolean sameWorld(Listener a, Listener b) { return a.world() != null && a.world().equals(b.world()); }

    static double distanceSquared(Listener a, Listener b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
