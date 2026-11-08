package com.hexvane.eterniamod.hexplots;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Axial hex coordinate. {@code r} is the row (world +Z is the next row south), {@code q} the column
 * along world +X. Pure value type, safe on any thread.
 *
 * <p>Neighbour directions, in order: east, north east, north west, west, south west, south east.
 */
public record HexCoord(int q, int r) {
    public static final HexCoord ORIGIN = new HexCoord(0, 0);
    public static final List<HexCoord> DIRECTIONS = List.of(
        new HexCoord(1, 0), new HexCoord(1, -1), new HexCoord(0, -1),
        new HexCoord(-1, 0), new HexCoord(-1, 1), new HexCoord(0, 1));
    /** Stable reading order: row first, then column. */
    public static final Comparator<HexCoord> READING_ORDER = Comparator.comparingInt(HexCoord::r).thenComparingInt(HexCoord::q);

    /** Third cube coordinate; q + r + s == 0. */
    public int s() { return -q - r; }

    public HexCoord plus(HexCoord other) { return new HexCoord(Math.addExact(q, other.q), Math.addExact(r, other.r)); }

    public HexCoord minus(HexCoord other) { return new HexCoord(Math.subtractExact(q, other.q), Math.subtractExact(r, other.r)); }

    public HexCoord neighbor(int direction) { return plus(DIRECTIONS.get(Math.floorMod(direction, 6))); }

    public List<HexCoord> neighbors() {
        List<HexCoord> out = new ArrayList<>(6);
        for (HexCoord d : DIRECTIONS) out.add(plus(d));
        return out;
    }

    public int distance(HexCoord other) {
        long dq = (long) q - other.q, dr = (long) r - other.r, ds = (long) s() - other.s();
        return (int) ((Math.abs(dq) + Math.abs(dr) + Math.abs(ds)) / 2);
    }

    public boolean adjacent(HexCoord other) { return distance(other) == 1; }

    /** Rotates 60 degrees clockwise (seen from above with +Z pointing down the page) about the origin. */
    public HexCoord rotateClockwise() { return new HexCoord(-r, q + r); }

    /** Mirrors across the east/west axis (north and south swap). */
    public HexCoord mirror() { return new HexCoord(q + r, -r); }

    /** Cells at exactly {@code radius} steps from {@code center}, starting at the south west cell. */
    public static List<HexCoord> ring(HexCoord center, int radius) {
        if (radius < 0) throw new IllegalArgumentException("radius");
        if (radius == 0) return List.of(center);
        List<HexCoord> out = new ArrayList<>(radius * 6);
        HexCoord cell = new HexCoord(center.q + DIRECTIONS.get(4).q * radius, center.r + DIRECTIONS.get(4).r * radius);
        for (int side = 0; side < 6; side++) {
            for (int step = 0; step < radius; step++) {
                out.add(cell);
                cell = cell.neighbor(side);
            }
        }
        return out;
    }

    /** All cells within {@code radius} steps, centre first, then ring by ring. */
    public static List<HexCoord> disk(HexCoord center, int radius) {
        List<HexCoord> out = new ArrayList<>();
        for (int k = 0; k <= radius; k++) out.addAll(ring(center, k));
        return out;
    }

    /** Translates the cells so the first one in reading order sits on the origin. */
    public static Set<HexCoord> normalize(Collection<HexCoord> cells) {
        if (cells.isEmpty()) return Set.of();
        HexCoord first = cells.stream().min(READING_ORDER).orElseThrow();
        Set<HexCoord> out = new LinkedHashSet<>();
        cells.stream().sorted(READING_ORDER).forEach(c -> out.add(c.minus(first)));
        return out;
    }

    @Override
    public String toString() { return q + ":" + r; }

    /** Parses the {@code q:r} form used by {@link #toString()}, the form saved data would use. */
    public static HexCoord parse(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) throw new IllegalArgumentException("Expected q:r");
        return new HexCoord(Integer.parseInt(text.substring(0, colon).trim()), Integer.parseInt(text.substring(colon + 1).trim()));
    }
}
