package com.hexvane.eterniamod.hexplots;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * House footprints measured in cells. A house occupies one of these shapes (in any of its turned
 * variants); the rest of the plot is yard. The largest square house each shape holds is computed by
 * {@link HexFit#largestSquareInEveryVariant}; with {@link HexLattice#DEFAULT} it is 14, 22, 38 and 54.
 */
public enum HexShape {
    /** Today's free house: up to 14 x 14. */
    SINGLE("Cottage", List.of(HexCoord.ORIGIN), 14),
    /** Today's upgraded house: up to 22 x 22. Three cells meeting at one corner. */
    TRIANGLE("Manor", List.of(HexCoord.ORIGIN, new HexCoord(1, 0), new HexCoord(0, 1)), 22),
    /** Tightest shape for a 38 x 38 keep, with no room to nudge it. Kept as an option. */
    DIAMOND("Hall", List.of(HexCoord.ORIGIN, new HexCoord(1, 0), new HexCoord(-1, 1), new HexCoord(0, 1)), 38),
    /** Guild keep and estate start shape: a centre cell and its six neighbours, up to 38 x 38 with room to spare. */
    FLOWER("Keep", HexCoord.disk(HexCoord.ORIGIN, 1), 38);

    private final String displayName;
    private final Set<HexCoord> cells;
    private final int houseSide;
    private final List<Set<HexCoord>> variants;

    HexShape(String displayName, List<HexCoord> cells, int houseSide) {
        this.displayName = displayName;
        this.cells = Set.copyOf(cells);
        this.houseSide = houseSide;
        this.variants = computeVariants(cells);
    }

    public String displayName() { return displayName; }

    public int size() { return cells.size(); }

    /** Cells relative to the shape's anchor cell (the origin). */
    public Set<HexCoord> cells() { return cells; }

    /** Largest square house side this shape is sold for (the catalog size, not the geometric maximum). */
    public int houseSide() { return houseSide; }

    /** Distinct turned and mirrored variants, each normalised so its first cell in reading order is the origin. */
    public List<Set<HexCoord>> variants() { return variants; }

    /** Every placement of every variant that includes {@code cell}. */
    public List<Set<HexCoord>> placementsCovering(HexCoord cell) {
        List<Set<HexCoord>> out = new ArrayList<>();
        for (Set<HexCoord> variant : variants) {
            for (HexCoord member : variant) {
                HexCoord shift = cell.minus(member);
                Set<HexCoord> placed = new LinkedHashSet<>();
                for (HexCoord c : variant) placed.add(c.plus(shift));
                out.add(Set.copyOf(placed));
            }
        }
        return List.copyOf(out);
    }

    /** Smallest shape whose catalog size holds a square house of {@code side} blocks. */
    public static HexShape forHouseSide(int side) {
        for (HexShape s : new HexShape[] {SINGLE, TRIANGLE, FLOWER}) if (side <= s.houseSide) return s;
        throw new IllegalArgumentException("No hex shape holds a " + side + " block house");
    }

    private static List<Set<HexCoord>> computeVariants(List<HexCoord> base) {
        Set<Set<HexCoord>> seen = new LinkedHashSet<>();
        List<HexCoord> current = base;
        for (int mirror = 0; mirror < 2; mirror++) {
            for (int turn = 0; turn < 6; turn++) {
                seen.add(Set.copyOf(HexCoord.normalize(current)));
                current = current.stream().map(HexCoord::rotateClockwise).toList();
            }
            current = base.stream().map(HexCoord::mirror).toList();
        }
        return List.copyOf(seen);
    }
}
