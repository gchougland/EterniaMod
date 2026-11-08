package com.hexvane.eterniamod.hexplots;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Claim, expansion and yard rules for hex plots. Pure (no game types, no I/O): safe on any thread, and the same code
 * runs for the claim map preview (world thread, {@code HexLand}) and inside the durable commit
 * ({@code HousingService.claimCells / expandPlot}). Every limit comes from {@link HexPlotSettings}.
 *
 * <ul>
 *   <li>Street cells: any cell a public road surface or public plaza touches. Public land, never claimable.</li>
 *   <li>Frontage cells: claimable cells that share a side with a street cell.</li>
 *   <li>A public plot or guild estate must include a frontage cell. A guild member plot must touch the guild estate
 *       or a member plot already joined to it.</li>
 *   <li>A new plot has exactly its start shape (any turn); expansion adds one free cell sharing a side, up to the cap.</li>
 * </ul>
 *
 * <p>Rejections are a {@link Reason}; show players {@link Reason#langKey()} (file {@code eternia_hexplots.lang}, param
 * {@code {count}} from {@link Result#count()}), never the id.
 */
public final class HexClaimRules {
    public enum Kind { PUBLIC, GUILD_ESTATE, GUILD_MEMBER }

    /** Why a claim, expansion or placement was allowed or refused. {@link #message()} is the English fallback. */
    public enum Reason {
        ALLOWED("allowed", "This plot can be claimed."),
        EXPAND_ALLOWED("expandAllowed", "This hexagon can join your plot."),
        PLACE_ALLOWED("placeAllowed", "There is room here."),
        NO_LATTICE("lattice", "This world does not use hexagon plots."),
        WORLD("world", "Use your plot deed in a world with housing."),
        HAS_PLOT("hasPlot", "You already have a plot. Open My Plots to find it or move it."),
        RECOVERY("recovery", "Your plot needs a moment to recover. Open My Plots to see how it is doing."),
        PERMISSION("permission", "Your guild role cannot claim the guild estate."),
        UPGRADE("upgrade", "Unlock the larger plot before claiming it."),
        PACKED("packed", "Your packed plot is waiting. Open My Plots to set it down again."),
        LEGACY("legacy", "Your old house needs to move before you can claim another plot."),
        ROAD_WORK("roadWork", "Builders are still finishing a road here. Try again soon."),
        SIZE_ONE("sizeOne", "Choose one hexagon for your plot."),
        SIZE("size", "Choose {count} hexagons that touch each other."),
        SHAPE("shape", "Your hexagons need to make the shape your plot allows."),
        RESTORE_SHAPE("restoreShape", "Your packed plot needs the same shape it had before. Pick hexagons in that shape."),
        CONTIGUOUS("contiguous", "Your hexagons must all touch each other."),
        STREET("street", "Streets and plazas stay open to everyone. Pick a hexagon beside the road."),
        TAKEN("taken", "Someone already lives on this hexagon."),
        RESERVED("reserved", "Someone is claiming this hexagon right now. Pick another one."),
        BLOCKED("blocked", "Builders are working on this ground. Pick another hexagon for now."),
        ROAD("road", "Your plot needs a hexagon right beside a public road."),
        ANCHOR("anchor", "Pick a hexagon touching your guild's land."),
        MEMBERS("members", "A guild needs at least {count} members to claim its estate."),
        GUILD_SLOT("guildSlot", "Your guild already has an estate."),
        NO_PLOT("noPlot", "You need a plot before you can grow it."),
        LIMIT("limit", "Your plot is as large as it can grow."),
        OWNED("owned", "This hexagon is already part of your plot."),
        TOUCH("touch", "New hexagons must share a side with your plot."),
        CHANGED("changed", "Your plot changed a moment ago. Look again and try once more."),
        COINS("coins", "You need {count} coins for this hexagon."),
        OUTSIDE("outside", "This goes on your own plot."),
        CELL_FULL("cellFull", "This hexagon is full. Try another part of your plot, or grow your plot."),
        GENERATORS_FULL("generatorsFull", "This hexagon has all the generators it can hold. Try another part of your plot."),
        SETBACK("setback", "Houses need {count} clear blocks inside the plot border."),
        ROAD_SETBACK("roadSetback", "Houses need five clear blocks from roads.");

        private final String id;
        private final String message;

        Reason(String id, String message) {
            this.id = id;
            this.message = message;
        }

        /** Stable id, also the old {@code HousingRules.Result.code()} form. Not player text. */
        public String id() { return id; }

        /** Translation key in {@code eternia_hexplots.lang}; pass {@link Result#count()} as {@code count}. */
        public String langKey() { return "eternia_hexplots.reason." + id; }

        /** English fallback, with {@code {count}} still in place. */
        public String message() { return message; }

        public boolean allowed() { return this == ALLOWED || this == EXPAND_ALLOWED || this == PLACE_ALLOWED; }
    }

    /** Outcome of a rule check. {@code count} fills {@code {count}} in the message (cells, members, coins, blocks). */
    public record Result(Reason reason, long count) {
        public Result {
            Objects.requireNonNull(reason);
        }

        public static Result of(Reason reason) { return new Result(reason, 0); }

        public static Result of(Reason reason, long count) { return new Result(reason, count); }

        public boolean valid() { return reason.allowed(); }

        /** Same as {@link Reason#id()}; kept for callers written against {@code HousingRules.Result}. */
        public String code() { return reason.id(); }

        /** English fallback text with the count filled in. Players get {@link Reason#langKey()} instead. */
        public String message() { return reason.message().replace("{count}", String.format(Locale.ROOT, "%,d", count)); }
    }

    /**
     * What the rules know about a world's land: its lattice, public street cells, cells that can't be claimed right
     * now ({@code blocked}: unfinished road work, guild community roads), cells held by unfinished claims or moves
     * ({@code reserved}), and which plot owns each claimed cell.
     */
    public record Land(HexLattice lattice, Set<HexCoord> street, Map<HexCoord, UUID> owners, Set<HexCoord> blocked, Set<HexCoord> reserved) {
        public Land {
            Objects.requireNonNull(lattice);
            street = Set.copyOf(street);
            owners = Map.copyOf(owners);
            blocked = blocked == null ? Set.of() : Set.copyOf(blocked);
            reserved = reserved == null ? Set.of() : Set.copyOf(reserved);
        }

        public Land(HexLattice lattice, Set<HexCoord> street, Map<HexCoord, UUID> owners) { this(lattice, street, owners, Set.of(), Set.of()); }

        /** Street cells are every cell a road surface or plaza rectangle touches. */
        public static Land of(HexLattice lattice, List<PlotRect> roadSurfaces, List<PlotRect> plazas, Map<HexCoord, UUID> owners) {
            return new Land(lattice, streetCells(lattice, roadSurfaces, plazas), owners);
        }

        public boolean frontage(HexCoord cell) {
            if (street.contains(cell)) return false;
            for (HexCoord n : cell.neighbors()) if (street.contains(n)) return true;
            return false;
        }

        /** Not street, not owned, not reserved and not blocked. */
        public boolean free(HexCoord cell) {
            return !street.contains(cell) && !owners.containsKey(cell) && !reserved.contains(cell) && !blocked.contains(cell);
        }

        public Set<HexCoord> cellsOf(UUID plot) {
            Set<HexCoord> out = new HashSet<>();
            owners.forEach((cell, owner) -> {
                if (owner.equals(plot)) out.add(cell);
            });
            return out;
        }
    }

    /**
     * Who is claiming. {@code guildMembers} and {@code guildHasEstate} matter only for {@link Kind#GUILD_ESTATE};
     * {@code localTesting} lets a solo guild claim in the local playground (all geometry rules still apply).
     */
    public record Candidate(Kind kind, boolean upgraded, int guildMembers, boolean guildHasEstate, boolean localTesting) {
        public Candidate {
            Objects.requireNonNull(kind);
        }

        public static Candidate of(Kind kind, boolean upgraded) { return new Candidate(kind, upgraded, Integer.MAX_VALUE, false, false); }
    }

    private HexClaimRules() {}

    /** Street cells for a lattice: every cell any road surface or plaza rectangle touches. */
    public static Set<HexCoord> streetCells(HexLattice lattice, Collection<PlotRect> roadSurfaces, Collection<PlotRect> plazas) {
        Set<HexCoord> street = new LinkedHashSet<>(lattice.cellsTouching(roadSurfaces));
        street.addAll(lattice.cellsTouching(plazas));
        return street;
    }

    /**
     * A new plot. {@code anchors} are the cells a guild member plot may join (the estate and member plots already
     * joined to it, see {@link #joinedGuildLand}); ignored for public and estate claims.
     */
    public static Result claim(Land land, Candidate candidate, Collection<HexCoord> cells, Set<HexCoord> anchors, HexPlotSettings settings) {
        HexShape shape = settings.startShape(candidate.kind(), candidate.upgraded());
        Set<HexCoord> set = Set.copyOf(cells);
        if (set.size() != shape.size()) return shape.size() == 1 ? Result.of(Reason.SIZE_ONE) : Result.of(Reason.SIZE, shape.size());
        if (!HexContiguity.isConnected(set)) return Result.of(Reason.CONTIGUOUS);
        if (!hasShape(set, shape)) return Result.of(Reason.SHAPE);
        for (HexCoord c : set) {
            Result taken = cellProblem(land, c);
            if (taken != null) return taken;
        }
        switch (candidate.kind()) {
            case GUILD_ESTATE -> {
                int needed = candidate.localTesting() ? 1 : settings.guildEstateMembers();
                if (candidate.guildMembers() < needed) return Result.of(Reason.MEMBERS, needed);
                if (candidate.guildHasEstate()) return Result.of(Reason.GUILD_SLOT);
                return set.stream().anyMatch(land::frontage) ? Result.of(Reason.ALLOWED) : Result.of(Reason.ROAD);
            }
            case GUILD_MEMBER -> {
                boolean joined = set.stream().anyMatch(c -> c.neighbors().stream().anyMatch(anchors::contains));
                return joined ? Result.of(Reason.ALLOWED) : Result.of(Reason.ANCHOR);
            }
            default -> {
                return set.stream().anyMatch(land::frontage) ? Result.of(Reason.ALLOWED) : Result.of(Reason.ROAD);
            }
        }
    }

    /** Short form with {@link HexPlotSettings#DEFAULTS} and no guild member checks (pictures, tools, tests). */
    public static Result claim(Land land, Kind kind, boolean upgraded, Collection<HexCoord> cells, Set<HexCoord> anchors) {
        return claim(land, Candidate.of(kind, upgraded), cells, anchors, HexPlotSettings.DEFAULTS);
    }

    /** Adds one cell to an existing plot whose cells are {@code land.cellsOf(plot)}. */
    public static Result expand(Land land, Kind kind, UUID plot, HexCoord cell, HexPlotSettings settings) {
        return expand(land, kind, land.cellsOf(plot), cell, settings);
    }

    /** Adds one cell to a plot with {@code current} cells. */
    public static Result expand(Land land, Kind kind, Set<HexCoord> current, HexCoord cell, HexPlotSettings settings) {
        if (current.isEmpty()) return Result.of(Reason.NO_PLOT);
        if (current.size() >= settings.maxCells(kind)) return Result.of(Reason.LIMIT, settings.maxCells(kind));
        if (current.contains(cell)) return Result.of(Reason.OWNED);
        Result problem = cellProblem(land, cell);
        if (problem != null) return problem;
        if (!HexContiguity.canAdd(current, cell)) return Result.of(Reason.TOUCH);
        return Result.of(Reason.EXPAND_ALLOWED);
    }

    public static Result expand(Land land, Kind kind, UUID plot, HexCoord cell) { return expand(land, kind, plot, cell, HexPlotSettings.DEFAULTS); }

    /** Every cell the plot could grow into right now, in reading order. Empty at the cap. */
    public static Set<HexCoord> expansionOptions(Land land, Kind kind, Set<HexCoord> current, HexPlotSettings settings) {
        Set<HexCoord> out = new LinkedHashSet<>();
        if (current.isEmpty() || current.size() >= settings.maxCells(kind)) return out;
        HexContiguity.frontier(current).stream().sorted(HexCoord.READING_ORDER).filter(land::free).forEach(out::add);
        return out;
    }

    /** Yard allowance check for one more prop standing on {@code cell}. */
    public static Result placeProp(Set<HexCoord> plotCells, Map<HexCoord, Integer> propsPerCell, HexCoord cell, HexPlotSettings settings) {
        if (!plotCells.contains(cell)) return Result.of(Reason.OUTSIDE);
        if (propsPerCell.getOrDefault(cell, 0) >= settings.propsPerCell()) return Result.of(Reason.CELL_FULL);
        return Result.of(Reason.PLACE_ALLOWED);
    }

    public static Result placeProp(Set<HexCoord> plotCells, Map<HexCoord, Integer> propsPerCell, HexCoord cell) {
        return placeProp(plotCells, propsPerCell, cell, HexPlotSettings.DEFAULTS);
    }

    /** Generator allowance check for one more generator standing on {@code cell}. */
    public static Result placeGenerator(Set<HexCoord> plotCells, Map<HexCoord, Integer> generatorsPerCell, HexCoord cell, HexPlotSettings settings) {
        if (!plotCells.contains(cell)) return Result.of(Reason.OUTSIDE);
        if (generatorsPerCell.getOrDefault(cell, 0) >= settings.generatorsPerCell()) return Result.of(Reason.GENERATORS_FULL);
        return Result.of(Reason.PLACE_ALLOWED);
    }

    /**
     * A house or addition footprint: inside the cells with the setback from the outer border, and five blocks clear of
     * roads (the hex form of {@code HousingRules.structure}).
     */
    public static Result structure(PlotCells plot, PlotRect building, List<PlotRect> roads, HexPlotSettings settings) {
        if (!HexFit.fits(plot.lattice(), plot.cells(), building, settings.houseSetback())) return Result.of(Reason.SETBACK, settings.houseSetback());
        if (roads.stream().anyMatch(r -> r.overlaps(building) || r.gap(building) < 5)) return Result.of(Reason.ROAD_SETBACK);
        return Result.of(Reason.PLACE_ALLOWED);
    }

    /** True when {@code cells} is {@code shape} in one of its turns, at any position. */
    public static boolean hasShape(Collection<HexCoord> cells, HexShape shape) {
        if (cells.size() != shape.size()) return false;
        Set<HexCoord> normal = Set.copyOf(HexCoord.normalize(cells));
        return shape.variants().contains(normal);
    }

    /** True when {@code b} is {@code a} moved (never turned): what a packed plot needs to be restored. */
    public static boolean sameShape(Collection<HexCoord> a, Collection<HexCoord> b) {
        return a.size() == b.size() && HexCoord.normalize(a).equals(HexCoord.normalize(b));
    }

    /**
     * Guild land a member plot may touch: the estate's cells plus every member plot connected to the estate through
     * shared sides, directly or through other joined member plots.
     */
    public static Set<HexCoord> joinedGuildLand(Collection<HexCoord> estate, Collection<? extends Collection<HexCoord>> memberPlots) {
        Set<HexCoord> joined = new HashSet<>(estate);
        if (joined.isEmpty()) return Set.of();
        List<Set<HexCoord>> pending = new java.util.ArrayList<>();
        for (Collection<HexCoord> plot : memberPlots) pending.add(Set.copyOf(plot));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                Set<HexCoord> plot = it.next();
                if (plot.stream().anyMatch(c -> c.neighbors().stream().anyMatch(joined::contains))) {
                    joined.addAll(plot);
                    it.remove();
                    changed = true;
                }
            }
        }
        return Set.copyOf(joined);
    }

    private static Result cellProblem(Land land, HexCoord c) {
        if (land.street().contains(c)) return Result.of(Reason.STREET);
        if (land.owners().containsKey(c)) return Result.of(Reason.TAKEN);
        if (land.reserved().contains(c)) return Result.of(Reason.RESERVED);
        if (land.blocked().contains(c)) return Result.of(Reason.BLOCKED);
        return null;
    }
}
