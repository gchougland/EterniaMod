package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Road Designer draws in a hex world (pure, any thread): the hexagon grid around the builder, which
 * hexagons are street already, which ones this road would turn into street, and any claimed hexagon it would cover.
 * A road eats every hexagon it touches, so the guide lines through the middle of each row show where a road uses
 * only one row of land.
 */
public final class RoadLatticePreview {
    private RoadLatticePreview() {}

    /** Most hexagons drawn in one refresh (six lines each), on top of the disk around the builder. */
    public static final int MAX_CELLS = 48;

    public enum Tone {
        /** Free land the road leaves alone. */
        FREE(HexLandView.suggestedColor(HexLandView.CellState.FREE)),
        /** Street already (a public road or plaza touches it). */
        STREET(HexLandView.suggestedColor(HexLandView.CellState.STREET)),
        /** Someone's plot (or land held by an unfinished claim). Roads must go around it. */
        PLOT(HexLandView.suggestedColor(HexLandView.CellState.OTHER_PLAYER)),
        /** This road would turn it into street. */
        NEW_STREET(HexLandView.suggestedColor(HexLandView.CellState.RESERVED)),
        /** A claimed or reserved hexagon this road touches: the road will be refused. */
        CLAIMED(0xE5533D);

        private final int rgb;

        Tone(int rgb) { this.rgb = rgb; }

        /** 0xRRGGBB. */
        public int rgb() { return rgb; }
    }

    public record Cell(HexCoord cell, Tone tone) {}

    /** {@code rows}: z of each row's middle line near the builder (block edge coordinates). */
    public record Overlay(List<Cell> cells, List<Double> rowMiddles, int newStreet, int claimedHits) {
        /** One short line for the Road Designer panel. */
        public String summary() {
            if (claimedHits > 0) return claimedHits == 1 ? "Crosses a claimed hexagon. Move the road." : "Crosses " + claimedHits + " claimed hexagons. Move the road.";
            if (newStreet == 0) return "Hexagon grid: no new street.";
            return "Hexagon grid: turns " + newStreet + (newStreet == 1 ? " hexagon" : " hexagons") + " into street.";
        }
    }

    /**
     * @param street  hexagons that are street now
     * @param claimed hexagons owned by plots or held by unfinished claims
     * @param road    block columns the previewed road covers, as {x, z} pairs (empty: no road yet)
     * @param x       builder position
     */
    public static Overlay of(HexLattice lattice, Set<HexCoord> street, Set<HexCoord> claimed, Collection<int[]> road, double x, double z, int radius) {
        Set<HexCoord> touched = new LinkedHashSet<>();
        for (int[] column : road) touched.add(lattice.cellAt(column[0], column[1]));
        Set<HexCoord> fresh = new LinkedHashSet<>();
        int hits = 0;
        for (HexCoord c : touched) {
            if (claimed.contains(c)) hits++;
            else if (!street.contains(c)) fresh.add(c);
        }
        HexCoord here = lattice.cellAt((int) Math.floor(x), (int) Math.floor(z));
        Map<HexCoord, Tone> tones = new LinkedHashMap<>();
        // Road hexagons first so a long road still shows where it bites when the budget runs out.
        for (HexCoord c : touched) tones.put(c, claimed.contains(c) ? Tone.CLAIMED : fresh.contains(c) ? Tone.NEW_STREET : Tone.STREET);
        for (HexCoord c : HexCoord.disk(here, radius)) tones.putIfAbsent(c, claimed.contains(c) ? Tone.PLOT : street.contains(c) ? Tone.STREET : Tone.FREE);
        List<Cell> cells = new ArrayList<>();
        for (var e : tones.entrySet()) {
            if (cells.size() >= MAX_CELLS) break;
            cells.add(new Cell(e.getKey(), e.getValue()));
        }
        List<Double> rows = new ArrayList<>();
        for (int r = here.r() - 2; r <= here.r() + 2; r++) rows.add(rowMiddle(lattice, r));
        return new Overlay(List.copyOf(cells), List.copyOf(rows), fresh.size(), hits);
    }

    /** z of the middle of row {@code r}: a road centred here, narrower than the row's straight sides, stays in that row. */
    public static double rowMiddle(HexLattice lattice, int r) {
        return lattice.minZ(new HexCoord(0, r)) + lattice.cellDepth() / 2.0;
    }
}
