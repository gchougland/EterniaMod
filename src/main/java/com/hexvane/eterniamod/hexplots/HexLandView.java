package com.hexvane.eterniamod.hexplots;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * An immutable picture of one hex world's land: lattice, street cells, blocked and reserved cells, and every plot.
 * Pure and safe on any thread once built. Get one from {@link HexLand#view}; it stays valid until the next
 * {@link HexLandEvents} change for its world (then ask {@link HexLand} again, which rebuilds only what changed).
 *
 * <p>Answers "what is this cell to this viewer" for one cell or a whole disk; a 300 cell disk is a few hundred map
 * lookups, cheap enough for the world thread every tick a page refreshes.
 */
public final class HexLandView {
    /** What a cell is to one viewer. Colours for each are in {@link #suggestedColor}. */
    public enum CellState {
        /** Public road or plaza land. Never claimable. */
        STREET,
        /** Free, and a new plot of this viewer that includes it would meet the road or guild rule. */
        FRONTAGE_FREE,
        /** Free but not claimable as a start cell by this viewer now (no frontage, blocked, or viewer can't claim). */
        FREE,
        /** Part of the viewer's own plot. */
        MINE,
        /** The viewer's guild estate, or a member plot of the viewer's guild. */
        MY_GUILD,
        /** Another player's plot (not attached to a guild). */
        OTHER_PLAYER,
        /** Another guild's estate or one of its member plots. */
        OTHER_GUILD,
        /** Held by an unfinished claim, move or expansion. */
        RESERVED
    }

    public enum OwnerKind { PLAYER, GUILD }

    /**
     * One plot. {@code ownerId} is a player for {@link OwnerKind#PLAYER}, a guild for {@link OwnerKind#GUILD};
     * {@code guildId} is the estate's guild, or the guild a member plot is attached to, else null.
     */
    public record PlotInfo(UUID plotId, OwnerKind ownerKind, UUID ownerId, UUID guildId, PlotCells cells) {
        public PlotInfo {
            Objects.requireNonNull(plotId);
            Objects.requireNonNull(ownerKind);
            Objects.requireNonNull(cells);
        }
    }

    /**
     * Who is looking. {@code intent} is the kind of plot they could start now ({@code null} when they cannot claim:
     * they already have a plot, lack permission, or are just browsing); it decides {@link CellState#FRONTAGE_FREE}.
     */
    public record Viewer(UUID player, UUID guild, HexClaimRules.Kind intent) {
        public static Viewer browsing(UUID player, UUID guild) { return new Viewer(player, guild, null); }
    }

    /** A cell as one viewer sees it. Owner fields are null for unowned cells; {@code ownerName} may be null when not yet known. */
    public record CellInfo(HexCoord cell, CellState state, UUID plotId, OwnerKind ownerKind, UUID ownerId, String ownerName) {}

    private final String world;
    private final HexLattice lattice;
    private final Set<HexCoord> street;
    private final Set<HexCoord> blocked;
    private final Set<HexCoord> reserved;
    private final Map<UUID, PlotInfo> plots;
    private final Map<HexCoord, PlotInfo> byCell;
    private final Map<UUID, Set<HexCoord>> guildLandCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile HexClaimRules.Land land;

    public HexLandView(String world, HexLattice lattice, Set<HexCoord> street, Set<HexCoord> blocked, Set<HexCoord> reserved, Collection<PlotInfo> plots) {
        this.world = Objects.requireNonNull(world);
        this.lattice = Objects.requireNonNull(lattice);
        this.street = Set.copyOf(street);
        this.blocked = Set.copyOf(blocked);
        this.reserved = Set.copyOf(reserved);
        Map<UUID, PlotInfo> p = new HashMap<>();
        Map<HexCoord, PlotInfo> c = new HashMap<>();
        for (PlotInfo plot : plots) {
            p.put(plot.plotId(), plot);
            for (HexCoord cell : plot.cells().cells()) c.put(cell, plot);
        }
        this.plots = Map.copyOf(p);
        this.byCell = Map.copyOf(c);
    }

    public String world() { return world; }

    public HexLattice lattice() { return lattice; }

    public Set<HexCoord> streetCells() { return street; }

    public Set<HexCoord> blockedCells() { return blocked; }

    public Set<HexCoord> reservedCells() { return reserved; }

    public Map<UUID, PlotInfo> plots() { return plots; }

    public PlotInfo plotAt(HexCoord cell) { return byCell.get(cell); }

    public PlotInfo plotAtColumn(int x, int z) { return byCell.get(lattice.cellAt(x, z)); }

    public boolean isStreet(HexCoord cell) { return street.contains(cell); }

    /** Not street and shares a side with a street cell. */
    public boolean isFrontage(HexCoord cell) { return land().frontage(cell); }

    /** Not street, owned, reserved or blocked. */
    public boolean isFree(HexCoord cell) { return land().free(cell); }

    /** The land as {@link HexClaimRules} sees it, for previews with the exact commit rules. */
    public HexClaimRules.Land land() {
        HexClaimRules.Land l = land;
        if (l == null) {
            Map<HexCoord, UUID> owners = new HashMap<>();
            byCell.forEach((cell, plot) -> owners.put(cell, plot.plotId()));
            l = new HexClaimRules.Land(lattice, street, owners, blocked, reserved);
            land = l;
        }
        return l;
    }

    /** Cells a new member plot of {@code guild} may touch: its estate plus member plots joined to it. */
    public Set<HexCoord> guildLand(UUID guild) {
        if (guild == null) return Set.of();
        return guildLandCache.computeIfAbsent(guild, g -> {
            Set<HexCoord> estate = Set.of();
            List<Set<HexCoord>> members = new ArrayList<>();
            for (PlotInfo plot : plots.values()) {
                if (!g.equals(plot.guildId())) continue;
                if (plot.ownerKind() == OwnerKind.GUILD) estate = plot.cells().asSet();
                else members.add(plot.cells().asSet());
            }
            return HexClaimRules.joinedGuildLand(estate, members);
        });
    }

    /** Cells {@code plotId} could grow into now (free, sharing a side, under the cap). */
    public Set<HexCoord> expansionOptions(UUID plotId, HexClaimRules.Kind kind, HexPlotSettings settings) {
        PlotInfo plot = plots.get(plotId);
        if (plot == null) return Set.of();
        return HexClaimRules.expansionOptions(land(), kind, plot.cells().asSet(), settings);
    }

    /** Kind of a plot for expansion caps: estate, guild member or public. */
    public static HexClaimRules.Kind kindOf(PlotInfo plot) {
        if (plot.ownerKind() == OwnerKind.GUILD) return HexClaimRules.Kind.GUILD_ESTATE;
        return plot.guildId() != null ? HexClaimRules.Kind.GUILD_MEMBER : HexClaimRules.Kind.PUBLIC;
    }

    /** The state of one cell for one viewer. {@code names} maps owner ids to display names (may return null). */
    public CellInfo cell(HexCoord cell, Viewer viewer, Function<UUID, String> names) {
        PlotInfo plot = byCell.get(cell);
        if (plot != null) {
            String name = plot.ownerId() == null || names == null ? null : names.apply(plot.ownerId());
            return new CellInfo(cell, ownedState(plot, viewer), plot.plotId(), plot.ownerKind(), plot.ownerId(), name);
        }
        return new CellInfo(cell, freeState(cell, viewer), null, null, null, null);
    }

    /** {@link #cell} for every cell within {@code radius} steps of {@code center}, centre first then ring by ring. */
    public List<CellInfo> disk(HexCoord center, int radius, Viewer viewer, Function<UUID, String> names) {
        if (radius < 0 || radius > 40) throw new IllegalArgumentException("radius must be 0 to 40");
        List<HexCoord> cells = HexCoord.disk(center, radius);
        List<CellInfo> out = new ArrayList<>(cells.size());
        for (HexCoord c : cells) out.add(cell(c, viewer, names));
        return out;
    }

    /** {@link #disk} around the cell holding block column (x, z). */
    public List<CellInfo> around(int x, int z, int radius, Viewer viewer, Function<UUID, String> names) {
        return disk(lattice.cellAt(x, z), radius, viewer, names);
    }

    private CellState ownedState(PlotInfo plot, Viewer viewer) {
        UUID me = viewer == null ? null : viewer.player();
        UUID myGuild = viewer == null ? null : viewer.guild();
        if (plot.ownerKind() == OwnerKind.PLAYER && plot.ownerId() != null && plot.ownerId().equals(me)) return CellState.MINE;
        if (plot.guildId() != null) return plot.guildId().equals(myGuild) ? CellState.MY_GUILD : CellState.OTHER_GUILD;
        return CellState.OTHER_PLAYER;
    }

    private CellState freeState(HexCoord cell, Viewer viewer) {
        if (reserved.contains(cell)) return CellState.RESERVED;
        if (street.contains(cell)) return CellState.STREET;
        if (blocked.contains(cell) || viewer == null || viewer.intent() == null) return CellState.FREE;
        boolean claimable = switch (viewer.intent()) {
            case GUILD_MEMBER -> {
                Set<HexCoord> anchors = guildLand(viewer.guild());
                yield cell.neighbors().stream().anyMatch(anchors::contains);
            }
            case PUBLIC, GUILD_ESTATE -> land().frontage(cell);
        };
        return claimable ? CellState.FRONTAGE_FREE : CellState.FREE;
    }

    /**
     * One palette for the claim map, the world map overlay and the border glow, as {@code 0xRRGGBB}. The selected
     * cell (claim map, add a hexagon) is drawn bright white {@code 0xF8FAFC} on top of its state colour.
     */
    public static int suggestedColor(CellState state) {
        return switch (state) {
            case MINE -> 0x2EC4B6;          // teal
            case MY_GUILD -> 0xE9B949;      // gold
            case OTHER_PLAYER -> 0x8FB8DE;  // soft blue
            case OTHER_GUILD -> 0x9B7ED9;   // violet
            case FRONTAGE_FREE -> 0x6CC56C; // claimable green
            case FREE -> 0x5E6B57;          // muted moss, unclaimable for now
            case STREET -> 0xA39E93;        // stone
            case RESERVED -> 0xE58E4C;      // amber, work in progress
        };
    }

    /** Colour of the selected cell outline, drawn over the state colour. */
    public static final int SELECTED_COLOR = 0xF8FAFC;
}
