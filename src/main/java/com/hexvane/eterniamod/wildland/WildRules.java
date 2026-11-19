package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.hexplots.HexCoord;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The rules of land in adventure worlds, free of storage and the world: one connected patch of hexagons per owner per
 * world, new hexagons touch the patch, nobody claims within {@link WildLandSettings#bufferRings()} of someone else's
 * land, and giving a hexagon up never splits a patch in two.
 */
public final class WildRules {
    private WildRules() {}

    /** Why something about land was refused. {@code {count}} and {@code {name}} are filled in by the caller. */
    public enum Reason {
        TAKEN("Someone already owns this land."),
        TOO_CLOSE("This is too close to {name}'s land. Leave a gap between claims."),
        NOT_TOUCHING("New land has to touch your land."),
        MAX_CELLS("You already have {count} hexagons, the most you can have here."),
        COINS("You need {count} Coins for this."),
        GUILD_COINS("Your guild needs {count} Coins for this."),
        SPLITS("Giving this up would split your land in two."),
        CLOSED("Land cannot be claimed in this world."),
        PROTECTED("This spot is kept free for everyone."),
        OUTSIDE("This is past the edge of the world."),
        NOT_YOURS("This is not your land."),
        FORBIDDEN("Your guild role cannot manage guild land."),
        NO_GUILD("Join a guild first."),
        CHANGED("The land changed while you were choosing. Try again.");

        private final String text;

        Reason(String text) { this.text = text; }

        public String text(long count, String name) {
            return text.replace("{count}", String.format(java.util.Locale.ROOT, "%,d", count)).replace("{name}", name == null || name.isBlank() ? "someone" : name);
        }
    }

    /**
     * The owner whose land keeps {@code cell} from being claimed, because it lies within {@code rings} hexagons of it, or
     * null when nothing does. {@code friendly} says which owners may sit right beside the claimant (themselves, their
     * guild).
     */
    public static String blockedBy(Map<HexCoord, String> land, HexCoord cell, int rings, Predicate<String> friendly) {
        for (HexCoord near : HexCoord.disk(cell, rings)) {
            String owner = land.get(near);
            if (owner != null && !friendly.test(owner)) return owner;
        }
        return null;
    }

    /**
     * Whether {@code cell} may be added to {@code mine} (empty for a new claim). {@code blocked} is land kept free for
     * everyone (portals, roads, past the border). Returns null when it may.
     */
    public static Reason add(Map<HexCoord, String> land, Set<HexCoord> mine, HexCoord cell, String claimant, int rings,
                             Predicate<String> friendly, int maxCells, Predicate<HexCoord> blocked) {
        String owner = land.get(cell);
        if (owner != null) return Reason.TAKEN;
        if (blocked.test(cell)) return Reason.PROTECTED;
        if (mine.size() >= maxCells) return Reason.MAX_CELLS;
        if (!mine.isEmpty() && cell.neighbors().stream().noneMatch(mine::contains)) return Reason.NOT_TOUCHING;
        Predicate<String> near = o -> o.equals(claimant) || friendly.test(o);
        if (blockedBy(land, cell, rings, near) != null) return Reason.TOO_CLOSE;
        return null;
    }

    /** Whether {@code cell} may be given up from {@code mine}. The last hexagon may go (the claim ends). */
    public static Reason release(Set<HexCoord> mine, HexCoord cell) {
        if (!mine.contains(cell)) return Reason.NOT_YOURS;
        Set<HexCoord> rest = new HashSet<>(mine);
        rest.remove(cell);
        return connected(rest) ? null : Reason.SPLITS;
    }

    /** True when every hexagon of {@code cells} can be reached from every other through neighbours in the set. */
    public static boolean connected(Set<HexCoord> cells) {
        if (cells.size() <= 1) return true;
        Set<HexCoord> seen = new HashSet<>();
        ArrayDeque<HexCoord> queue = new ArrayDeque<>();
        HexCoord start = cells.iterator().next();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) for (HexCoord n : queue.poll().neighbors()) if (cells.contains(n) && seen.add(n)) queue.add(n);
        return seen.size() == cells.size();
    }

    /** Hexagons next to {@code mine} that are free of owners (a new claim may start anywhere, so this is for growing). */
    public static Set<HexCoord> growth(Map<HexCoord, String> land, Set<HexCoord> mine) {
        Set<HexCoord> out = new HashSet<>();
        for (HexCoord c : mine) for (HexCoord n : c.neighbors()) if (!land.containsKey(n)) out.add(n);
        return out;
    }
}
