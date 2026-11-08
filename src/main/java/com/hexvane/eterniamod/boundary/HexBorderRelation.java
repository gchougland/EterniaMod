package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.hexplots.HexLandView;
import java.util.Locale;

/**
 * What a plot border is to the player looking at it. One colour each, taken from the shared hex palette
 * ({@link HexLandView#suggestedColor}), so the glow in the world, the world map overlay and the claim map agree.
 *
 * <p>Declaration order is the priority on a side shared by two plots: the viewer's own plot wins, then their guild,
 * then strangers. Claimable outlines lose to every plot.
 */
public enum HexBorderRelation {
    MINE(HexLandView.CellState.MINE, "Mine"),
    MY_GUILD(HexLandView.CellState.MY_GUILD, "Guild"),
    OTHER_PLAYER(HexLandView.CellState.OTHER_PLAYER, "Neighbor"),
    OTHER_GUILD(HexLandView.CellState.OTHER_GUILD, "Rival"),
    /** A free hexagon the viewer could claim now; drawn only while they hold the deed or grow a plot. */
    CLAIMABLE(HexLandView.CellState.FRONTAGE_FREE, "Claimable");

    /** Particle system id prefix; full ids are {@code prefix + name + intensity suffix}. */
    public static final String PARTICLE_PREFIX = "Eternia_Hex_Border_";

    private final HexLandView.CellState state;
    private final String assetName;

    HexBorderRelation(HexLandView.CellState state, String assetName) {
        this.state = state;
        this.assetName = assetName;
    }

    /** The cell state this relation is drawn for. */
    public HexLandView.CellState state() { return state; }

    /** {@code 0xRRGGBB} from the shared palette. */
    public int color() { return HexLandView.suggestedColor(state); }

    /** Asset name part, for example {@code Mine} in {@code Eternia_Hex_Border_Mine_Soft}. */
    public String assetName() { return assetName; }

    /** Relation for a cell state, or null when the state has no border (street, free, reserved). */
    public static HexBorderRelation of(HexLandView.CellState state) {
        if (state == null) return null;
        return switch (state) {
            case MINE -> MINE;
            case MY_GUILD -> MY_GUILD;
            case OTHER_PLAYER -> OTHER_PLAYER;
            case OTHER_GUILD -> OTHER_GUILD;
            case FRONTAGE_FREE -> CLAIMABLE;
            case FREE, STREET, RESERVED -> null;
        };
    }

    /**
     * The particle system for this relation at a fade strength in [0, 1] (see {@link BoundaryGeometry#intensity}).
     * Plot borders have three strengths; claimable outlines have one faint rune.
     */
    public String particle(double strength) {
        if (this == CLAIMABLE) return PARTICLE_PREFIX + assetName;
        String suffix = strength > .66 ? "" : strength > .25 ? "_Soft" : "_Faint";
        return PARTICLE_PREFIX + assetName + suffix;
    }

    /** Lower case name for logs and notes. */
    public String key() { return name().toLowerCase(Locale.ROOT); }
}
