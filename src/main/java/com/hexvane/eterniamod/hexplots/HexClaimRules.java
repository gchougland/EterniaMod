package com.hexvane.eterniamod.hexplots;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Proposed claim and expansion rules for hex plots, kept pure so they can be tested and drawn before any
 * live code changes. Mirrors {@code HousingRules}: the result codes are stable, the messages are player text.
 *
 * <ul>
 *   <li>Street cells: any cell a public road surface or public plaza touches. Public land, never claimable.</li>
 *   <li>Frontage cells: claimable cells that share an edge with a street cell.</li>
 *   <li>A public or guild estate claim must include a frontage cell. A guild member claim must touch the
 *       guild estate or a member plot already joined to it.</li>
 *   <li>Expansion adds one free cell that shares an edge with the plot, up to the plot's cell limit.</li>
 * </ul>
 */
public final class HexClaimRules {
    public enum Kind { PUBLIC, GUILD_ESTATE, GUILD_MEMBER }

    /** Proposed cell limits: start size and the most cells a plot may grow to. */
    public static int startCells(Kind kind, boolean upgraded) {
        return switch (kind) {
            case GUILD_ESTATE -> HexShape.FLOWER.size();
            case PUBLIC, GUILD_MEMBER -> upgraded ? HexShape.TRIANGLE.size() : HexShape.SINGLE.size();
        };
    }

    public static int maxCells(Kind kind) { return kind == Kind.GUILD_ESTATE ? 19 : 7; }

    /** Proposed yard allowance: placed props (and later idle generators) per cell, counted where each one stands. */
    public static final int PROPS_PER_CELL = 24;
    public static final int GENERATORS_PER_CELL = 2;

    public record Result(boolean valid, String code, String message) {
        static Result yes(String message) { return new Result(true, "allowed", message); }
        static Result no(String code, String message) { return new Result(false, code, message); }
    }

    /** What the claim page knows about a world's lattice: public street cells and who owns each claimed cell. */
    public record Land(HexLattice lattice, Set<HexCoord> street, Map<HexCoord, UUID> owners) {
        public Land {
            Objects.requireNonNull(lattice);
            street = Set.copyOf(street);
            owners = Map.copyOf(owners);
        }

        public static Land of(HexLattice lattice, List<PlotRect> roadSurfaces, List<PlotRect> plazas, Map<HexCoord, UUID> owners) {
            Set<HexCoord> street = new HashSet<>();
            for (PlotRect r : roadSurfaces) street.addAll(lattice.cellsTouching(r));
            for (PlotRect p : plazas) street.addAll(lattice.cellsTouching(p));
            return new Land(lattice, street, owners);
        }

        public boolean frontage(HexCoord cell) {
            return !street.contains(cell) && cell.neighbors().stream().anyMatch(street::contains);
        }

        public boolean free(HexCoord cell) { return !street.contains(cell) && !owners.containsKey(cell); }

        public Set<HexCoord> cellsOf(UUID plot) {
            Set<HexCoord> out = new HashSet<>();
            owners.forEach((cell, owner) -> { if (owner.equals(plot)) out.add(cell); });
            return out;
        }
    }

    private HexClaimRules() {}

    /**
     * A new plot. {@code anchors} are the cells a guild member plot may join (the estate and member plots
     * already joined to it); ignored for public and estate claims.
     */
    public static Result claim(Land land, Kind kind, boolean upgraded, Collection<HexCoord> cells, Set<HexCoord> anchors) {
        int expected = startCells(kind, upgraded);
        if (cells.size() != expected) return Result.no("size", expected == 1 ? "Choose one hexagon for your plot." : "Choose " + expected + " hexagons that touch each other.");
        if (!HexContiguity.isConnected(cells)) return Result.no("contiguous", "Your hexagons must all touch each other.");
        for (HexCoord c : cells) {
            if (land.street().contains(c)) return Result.no("street", "Streets and plazas stay open to everyone. Pick a hexagon beside the road.");
            if (land.owners().containsKey(c)) return Result.no("taken", "Someone already lives on this hexagon.");
        }
        if (kind == Kind.GUILD_MEMBER) {
            boolean joined = cells.stream().anyMatch(c -> c.neighbors().stream().anyMatch(anchors::contains));
            return joined ? Result.yes("This plot joins your guild's land.") : Result.no("anchor", "Pick a hexagon touching your guild's land.");
        }
        if (cells.stream().noneMatch(land::frontage)) return Result.no("road", "Your plot needs a hexagon right beside a public road.");
        return Result.yes("This plot can be claimed.");
    }

    /** Adds one cell to an existing plot. */
    public static Result expand(Land land, Kind kind, UUID plot, HexCoord cell) {
        Set<HexCoord> current = land.cellsOf(plot);
        if (current.isEmpty()) return Result.no("noPlot", "You need a plot before you can grow it.");
        if (current.size() >= maxCells(kind)) return Result.no("limit", "Your plot is as large as it can grow.");
        if (current.contains(cell)) return Result.no("owned", "This hexagon is already part of your plot.");
        if (land.street().contains(cell)) return Result.no("street", "Streets and plazas stay open to everyone.");
        if (land.owners().containsKey(cell)) return Result.no("taken", "Someone already lives on this hexagon.");
        if (!HexContiguity.canAdd(current, cell)) return Result.no("touch", "New hexagons must share a side with your plot.");
        return Result.yes("This hexagon can join your plot.");
    }

    /** Yard allowance check for one more prop standing on {@code cell}. */
    public static Result placeProp(Set<HexCoord> plotCells, Map<HexCoord, Integer> propsPerCell, HexCoord cell) {
        if (!plotCells.contains(cell)) return Result.no("outside", "Props go on your own plot.");
        int used = propsPerCell.getOrDefault(cell, 0);
        if (used >= PROPS_PER_CELL) return Result.no("cellFull", "This hexagon is full. Try another part of your plot, or grow your plot.");
        return Result.yes("Placed.");
    }
}
