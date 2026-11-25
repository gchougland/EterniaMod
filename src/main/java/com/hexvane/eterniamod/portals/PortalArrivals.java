package com.hexvane.eterniamod.portals;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.joml.Vector3i;

/**
 * Stops a pad from opening the menu again by itself: the pad a player has just been sent to (vanilla's
 * {@code UsedTeleporter} does the same job) and the pad they just closed the menu on. Each pad is quiet once, for a
 * short time; stepping off and back on opens the menu as usual. Pads are kept apart, so closing the menu at the
 * departure pad never wakes the arrival pad.
 */
final class PortalArrivals {
    static final long WINDOW_MILLIS = 8_000;
    private final Map<UUID, Map<String, Long>> quiet = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    PortalArrivals(LongSupplier clock) { this.clock = clock; }

    private static String key(String world, Vector3i pad) { return world + "@" + pad.x + "," + pad.y + "," + pad.z; }

    void arrived(UUID player, String world, Vector3i pad) {
        long now = clock.getAsLong();
        var pads = quiet.computeIfAbsent(player, id -> new ConcurrentHashMap<>());
        pads.values().removeIf(at -> now - at > WINDOW_MILLIS);
        pads.put(key(world, pad), now);
    }

    /** True once for a quiet pad, within the window. */
    boolean justArrived(UUID player, String world, Vector3i pad) {
        var pads = quiet.get(player);
        if (pads == null) return false;
        Long at = pads.remove(key(world, pad));
        return at != null && clock.getAsLong() - at <= WINDOW_MILLIS;
    }

    void forget(UUID player) { quiet.remove(player); }
}
