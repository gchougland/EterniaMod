package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.hexplots.HexCoord;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hexagons a flow wants outlined for one player in the world, for example the cells a plot can grow into while
 * the player is choosing one, or the cells picked on the claim map. They are drawn as faint claimable outlines
 * instead of the default "free frontage cells around you while you hold the deed". Any thread.
 */
public final class HexBorderFocus {
    private static final Map<UUID, Focus> FOCUS = new ConcurrentHashMap<>();

    private record Focus(String world, Set<HexCoord> cells, long until) {}

    private HexBorderFocus() {}

    /** Outlines {@code cells} of {@code world} for {@code player} for {@code millis} (renew to keep it). */
    public static void show(UUID player, String world, Set<HexCoord> cells, long millis) {
        FOCUS.put(player, new Focus(world, Set.copyOf(cells), System.currentTimeMillis() + Math.max(0, millis)));
    }

    /** Stops outlining (page closed, expansion done). */
    public static void clear(UUID player) { FOCUS.remove(player); }

    /** The focused cells in {@code world}, or null when there is no live focus. */
    public static Set<HexCoord> cells(UUID player, String world) {
        Focus focus = FOCUS.get(player);
        if (focus == null) return null;
        if (focus.until < System.currentTimeMillis()) { FOCUS.remove(player, focus); return null; }
        return focus.world.equals(world) ? focus.cells : null;
    }

    public static void clearAll() { FOCUS.clear(); }
}
