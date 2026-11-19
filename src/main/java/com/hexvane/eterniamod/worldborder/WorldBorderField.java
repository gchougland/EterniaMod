package com.hexvane.eterniamod.worldborder;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the force field panels go for one player: a wall of panels on each side of the border the player is near,
 * centred on the player along that side and around their height, brighter the closer they stand. Pure.
 */
public final class WorldBorderField {
    /** The field shows when a player is this many blocks or closer to a side. */
    public static final double REACH = 24;
    /** One panel is this many blocks square; panels sit edge to edge. */
    public static final int PANEL = 4;
    /** Panels reach this far along the side either way from the player. */
    public static final int HALF_SPAN = 14;
    /** Rows of panels below and above the player's feet. */
    public static final int ROWS_BELOW = 1, ROWS_ABOVE = 2;

    /** One panel: its centre, which way it faces (0 for a north or south side, 90 for east or west) and how bright. */
    public record Panel(double x, double y, double z, float yawDegrees, double strength) {}

    private WorldBorderField() {}

    /** The panels a player at (x, y, z) inside {@code border} should see, nearest first. Empty when no side is near. */
    public static List<Panel> panels(WorldBorders.Border border, double x, double y, double z) {
        var out = new ArrayList<Panel>();
        double baseY = Math.floor(y / PANEL) * PANEL;
        // West and east sides run north to south (constant x); north and south sides run west to east (constant z).
        side(out, x - border.minX(), border.minX(), z, border.minZ(), border.maxZ(), baseY, true);
        side(out, border.maxX() - x, border.maxX(), z, border.minZ(), border.maxZ(), baseY, true);
        side(out, z - border.minZ(), border.minZ(), x, border.minX(), border.maxX(), baseY, false);
        side(out, border.maxZ() - z, border.maxZ(), x, border.minX(), border.maxX(), baseY, false);
        out.sort((a, b) -> Double.compare(b.strength(), a.strength()));
        return out;
    }

    private static void side(List<Panel> out, double distance, double fixed, double along, double from, double to, double baseY, boolean constantX) {
        if (distance < 0 || distance > REACH) return;
        double strength = 1 - distance / REACH;
        double start = Math.max(from, Math.floor((along - HALF_SPAN) / PANEL) * PANEL);
        double end = Math.min(to, along + HALF_SPAN);
        for (double a = start; a + PANEL <= end + 1e-9; a += PANEL) {
            double middle = a + PANEL / 2.0;
            // Panels further along the side fade, so the wall has soft ends instead of a hard cut.
            double fade = strength * (1 - Math.min(1, Math.abs(middle - along) / (HALF_SPAN + PANEL)));
            if (fade <= 0.05) continue;
            for (int row = -ROWS_BELOW; row < ROWS_ABOVE; row++) {
                double py = baseY + row * PANEL + PANEL / 2.0;
                out.add(constantX ? new Panel(fixed, py, middle, 90f, fade) : new Panel(middle, py, fixed, 0f, fade));
            }
        }
    }
}
