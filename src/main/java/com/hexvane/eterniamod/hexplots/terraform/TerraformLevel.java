package com.hexvane.eterniamod.hexplots.terraform;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * The ground height (y of the top ground block) new hexagons are shaped to. Pure; safe on any thread.
 *
 * <ol>
 *   <li><b>Group:</b> hexagons joined to land of the same owner share one height. The owner is the player for a public
 *   plot, or the guild for its estate and every member plot attached to it. The plot being grown wins, then the guild
 *   estate, then the largest joined plot.</li>
 *   <li><b>Road:</b> otherwise the median height of the road or plaza beside the new hexagons' border.</li>
 *   <li><b>Ground:</b> with no road beside them either, the median natural ground under the new hexagons.</li>
 * </ol>
 */
public final class TerraformLevel {
    private TerraformLevel() {}

    public static final int UNKNOWN = Integer.MIN_VALUE;

    public enum Source { GROUP, ROAD, GROUND, NONE }

    /** The chosen height and where it came from ({@code plotId}: the group plot it was copied from). */
    public record Decision(int level, Source source, UUID plotId) {
        public boolean known() { return source != Source.NONE; }
    }

    /**
     * A plot already in the world. {@code level} is its saved shaped height, or null when it was never shaped (a
     * plot from before terraforming); then {@code measure} supplies a height read from its ground.
     */
    public record GroupPlot(UUID plotId, String group, Set<HexCoord> cells, Integer level, boolean estate) {
        public GroupPlot {
            Objects.requireNonNull(plotId);
            Objects.requireNonNull(group);
            cells = Set.copyOf(cells);
        }
    }

    /** Owner group key of a plot: its guild for an estate or attached member plot, else its player. */
    public static String group(HexLandView.PlotInfo plot) {
        return plot.guildId() != null ? guildGroup(plot.guildId()) : playerGroup(plot.ownerId());
    }

    public static String guildGroup(UUID guild) { return "guild:" + guild; }

    public static String playerGroup(UUID player) { return "player:" + player; }

    /**
     * Decides the height for {@code newCells}.
     *
     * @param group        owner group of the land being shaped
     * @param newCells     hexagons about to be claimed or added
     * @param growing      the plot that is growing, or null for a fresh claim
     * @param plots        plots in the world (any owner; others are ignored)
     * @param measure      height of a plot with no saved level, or {@link #UNKNOWN}
     * @param roadHeights  surface heights of road or plaza columns touching the new hexagons' border
     * @param groundHeights natural ground heights under the new hexagons
     */
    public static Decision decide(String group, Collection<HexCoord> newCells, UUID growing, Collection<GroupPlot> plots,
                                  ToIntFunction<GroupPlot> measure, int[] roadHeights, int[] groundHeights) {
        Set<HexCoord> fresh = Set.copyOf(newCells);
        List<GroupPlot> joined = new ArrayList<>();
        for (GroupPlot plot : plots) {
            if (!plot.group().equals(group)) continue;
            if (plot.plotId().equals(growing) || touches(plot.cells(), fresh)) joined.add(plot);
        }
        joined.sort(Comparator
            .comparing((GroupPlot p) -> !p.plotId().equals(growing))
            .thenComparing(p -> !p.estate())
            .thenComparing(p -> -p.cells().size())
            .thenComparing(p -> p.plotId().toString()));
        for (GroupPlot plot : joined) {
            int level = plot.level() != null ? plot.level() : measure.applyAsInt(plot);
            if (level != UNKNOWN) return new Decision(level, Source.GROUP, plot.plotId());
        }
        int road = median(roadHeights);
        if (road != UNKNOWN) return new Decision(road, Source.ROAD, null);
        int ground = median(groundHeights);
        if (ground != UNKNOWN) return new Decision(ground, Source.GROUND, null);
        return new Decision(UNKNOWN, Source.NONE, null);
    }

    /**
     * Surface heights of the road columns nearest the new land: those within {@code band} columns of the closest one,
     * measured as the Chebyshev distance to the nearest column of {@code cells}. {@code roads} holds {x, z, y}.
     */
    public static int[] nearestRoad(java.util.List<int[]> roads, com.hexvane.eterniamod.hexplots.HexLattice lattice, Collection<HexCoord> cells, int band) {
        if (roads == null || roads.isEmpty() || cells.isEmpty()) return new int[0];
        java.util.List<com.hexvane.eterniamod.hexplots.HexLattice.Span> spans = new ArrayList<>();
        for (HexCoord cell : cells) spans.addAll(lattice.spans(cell));
        int[] dist = new int[roads.size()];
        int best = Integer.MAX_VALUE;
        for (int r = 0; r < roads.size(); r++) {
            int x = roads.get(r)[0], z = roads.get(r)[1], d = Integer.MAX_VALUE;
            for (var s : spans) {
                int dx = x < s.fromX() ? s.fromX() - x : x >= s.toX() ? x - s.toX() + 1 : 0;
                d = Math.min(d, Math.max(dx, Math.abs(z - s.z())));
            }
            dist[r] = d; best = Math.min(best, d);
        }
        java.util.List<Integer> near = new ArrayList<>();
        for (int r = 0; r < roads.size(); r++) if (dist[r] <= best + band) near.add(roads.get(r)[2]);
        return near.stream().mapToInt(Integer::intValue).toArray();
    }

    /** True when some cell of {@code a} shares a side with a cell of {@code b} (or is in it). */
    public static boolean touches(Set<HexCoord> a, Set<HexCoord> b) {
        for (HexCoord cell : b) {
            if (a.contains(cell)) return true;
            for (HexCoord n : cell.neighbors()) if (a.contains(n)) return true;
        }
        return false;
    }

    /** Lower median of the known values ({@link #UNKNOWN} entries skipped), or {@link #UNKNOWN} when none. */
    public static int median(int[] values) {
        if (values == null) return UNKNOWN;
        int[] known = Arrays.stream(values).filter(v -> v != UNKNOWN).sorted().toArray();
        return known.length == 0 ? UNKNOWN : known[(known.length - 1) / 2];
    }
}
