package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What each hexagon of a plot is: a house hexagon or a production hexagon. The two are separate allowances. The
 * hexagons a plot is claimed with are its house hexagons; production hexagons are added beside it, up to six, and are
 * the only place a station may stand. More house hexagons can be added too, up to the plot's own limit. A hexagon
 * keeps the kind it was added as: nothing turns one into the other. Decorations go anywhere.
 *
 * <p>Roles are saved as text beside the plot's production record: every hexagon the plot had when it was saved, one
 * of its house hexagons and the production hexagons, all as lattice coordinates. {@link #resolve} reads that against
 * the hexagons the plot has now, so the roles follow a plot that was moved (every hexagon shifted by the same amount)
 * and settle by themselves after a hexagon was added or sold. Plain rules, no server types: unit tested.
 */
public final class PlotRoles {
    public enum Role { HOUSE, PRODUCTION }

    public static final int MAX_PRODUCTION = 6;

    private final PlotCells cells;
    private final Set<HexCoord> production;

    private PlotRoles(PlotCells cells, Set<HexCoord> production) {
        this.cells = cells;
        this.production = production;
    }

    public PlotCells cells() { return cells; }

    public Role of(HexCoord cell) { return production.contains(cell) ? Role.PRODUCTION : Role.HOUSE; }

    public int count(Role role) {
        return role == Role.PRODUCTION ? production.size() : cells.size() - production.size();
    }

    /** Hexagons in reading order with their roles. */
    public Map<HexCoord, Role> all() {
        var out = new LinkedHashMap<HexCoord, Role>();
        for (HexCoord cell : cells.cells()) out.put(cell, of(cell));
        return out;
    }

    public static boolean touching(HexCoord a, HexCoord b) {
        for (HexCoord n : a.neighbors()) if (n.equals(b)) return true;
        return false;
    }

    // ---- reading saved roles against the plot as it is now ----

    /**
     * The plot's roles. {@code saved} is the text from {@link #text()} or empty for a plot that never had roles: its
     * hexagons are all house hexagons. {@code houseCells} are the hexagons the placed house stands on (always house
     * hexagons). {@code wanted} says what the player chose for hexagons that are being added; a new hexagon nobody
     * chose for is production while there is room for one.
     */
    public static PlotRoles resolve(PlotCells cells, String saved, Set<HexCoord> houseCells, Map<HexCoord, Role> wanted) {
        var production = new TreeSet<HexCoord>(HexCoord.READING_ORDER);
        Saved old = Saved.parse(saved);
        if (old != null) {
            // Same hexagons somewhere else means the plot was moved: shift the roles with it. Otherwise hexagons were
            // added or sold in place, and every hexagon that is still there keeps its role.
            HexCoord shift = new HexCoord(0, 0);
            boolean overlaps = old.cells.stream().anyMatch(cells::contains);
            if (!overlaps && old.cells.size() == cells.size()) {
                HexCoord first = cells.cells().getFirst(), was = first(old.cells);
                shift = new HexCoord(first.q() - was.q(), first.r() - was.r());
            }
            var added = new ArrayList<HexCoord>();
            for (HexCoord cell : cells.cells()) {
                boolean known = false, wasProduction = false;
                for (HexCoord o : old.cells) if (o.plus(shift).equals(cell)) { known = true; wasProduction = old.production.contains(o); }
                if (!known) added.add(cell);
                else if (wasProduction) production.add(cell);
            }
            for (HexCoord cell : added) {
                Role chosen = wanted == null ? null : wanted.get(cell);
                if (chosen == Role.PRODUCTION || chosen == null && production.size() < MAX_PRODUCTION) production.add(cell);
            }
        }
        for (HexCoord cell : houseCells) production.remove(cell);
        // A plot always keeps a house hexagon.
        if (production.size() >= cells.size()) production.remove(cells.cells().getFirst());
        return new PlotRoles(cells, production);
    }

    private static HexCoord first(List<HexCoord> cells) {
        var sorted = new TreeSet<HexCoord>(HexCoord.READING_ORDER);
        sorted.addAll(cells);
        return sorted.first();
    }

    // ---- what a player may do ----

    /** What a new hexagon at {@code cell} becomes when the player does not choose: production while that is allowed. */
    public Role suggested(HexCoord cell, int maxHouse) {
        return canAddAs(cell, Role.PRODUCTION, maxHouse) == null || canAddAs(cell, Role.HOUSE, maxHouse) != null ? Role.PRODUCTION : Role.HOUSE;
    }

    /**
     * Null when {@code cell} may join the plot as {@code role}, else why not. A plot holds up to six production
     * hexagons and {@code maxHouse} house hexagons. A house hexagon must touch another house hexagon, and a plot with
     * no production hexagon yet adds that one first (it is the free one).
     */
    public String canAddAs(HexCoord cell, Role role, int maxHouse) {
        if (role == Role.PRODUCTION) return count(Role.PRODUCTION) < MAX_PRODUCTION ? null : "roleProductionFull";
        if (count(Role.HOUSE) >= maxHouse) return "roleHouseFull";
        boolean touches = false;
        for (HexCoord own : cells.cells()) if (of(own) == Role.HOUSE && touching(own, cell)) touches = true;
        if (!touches) return "roleHouseTouch";
        return count(Role.PRODUCTION) > 0 ? null : "roleProductionFirst";
    }

    /** Null when {@code cell} may be sold, else why not: a plot keeps one hexagon of each kind. */
    public String canSell(HexCoord cell) {
        if (!cells.contains(cell)) return null;
        if (of(cell) == Role.PRODUCTION) return count(Role.PRODUCTION) > 1 || count(Role.HOUSE) == 0 ? null : "roleLastProduction";
        return count(Role.HOUSE) > 1 ? null : "roleLastHouse";
    }

    // ---- saved form ----

    /** {@code cells|a house hexagon|production}, each a list of {@code q:r} joined by semicolons. */
    public String text() {
        HexCoord house = cells.cells().stream().filter(c -> !production.contains(c)).findFirst().orElse(cells.cells().getFirst());
        return cells.text() + "|" + house + "|" + join(production);
    }

    private static String join(Iterable<HexCoord> cells) {
        var out = new StringBuilder();
        for (HexCoord c : cells) { if (!out.isEmpty()) out.append(';'); out.append(c); }
        return out.toString();
    }

    private record Saved(List<HexCoord> cells, Set<HexCoord> production) {
        static Saved parse(String text) {
            if (text == null || text.isBlank()) return null;
            try {
                String[] parts = text.split("\\|", -1);
                if (parts.length != 3) return null;
                var cells = list(parts[0]);
                if (cells.isEmpty()) return null;
                return new Saved(cells, Set.copyOf(list(parts[2])));
            } catch (RuntimeException unreadable) {
                return null;
            }
        }

        private static List<HexCoord> list(String text) {
            var out = new ArrayList<HexCoord>();
            for (String part : text.split(";")) if (!part.isBlank()) out.add(HexCoord.parse(part));
            return out;
        }
    }
}
