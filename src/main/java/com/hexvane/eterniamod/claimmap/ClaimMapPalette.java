package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLandView.CellState;

/**
 * How each cell state is painted on the claim map. Hues always come from the shared palette
 * ({@link HexLandView#suggestedColor}), so the map, the world map overlay and the border glow agree; this class only
 * decides how strongly each one is laid over the terrain. Pure.
 */
public final class ClaimMapPalette {
    private ClaimMapPalette() {}

    /** Colour of a state as {@code 0xRRGGBB}. */
    public static int color(CellState state) { return HexLandView.suggestedColor(state); }

    /** Colour of a state as {@code #RRGGBB} for markup. */
    public static String hex(CellState state) { return String.format("#%06X", color(state) & 0xFFFFFF); }

    /** Opacity of the tint laid over the terrain (0 to 1). Owned land is strong, open land barely tinted. */
    public static float fillAlpha(CellState state) {
        return switch (state) {
            case MINE -> 0.56f;
            case MY_GUILD -> 0.54f;
            case OTHER_PLAYER, OTHER_GUILD -> 0.48f;
            case RESERVED -> 0.50f;
            case FRONTAGE_FREE -> 0.24f;
            case STREET -> 0.36f;
            case FREE -> 0f;
        };
    }

    /** Open land that cannot be claimed is darkened instead of tinted, so the claimable cells stand out. */
    public static float dimAlpha(CellState state) { return state == CellState.FREE ? 0.38f : 0f; }

    /** Owned and reserved land gets a strong border around each plot (not between its own cells). */
    public static boolean outlinesPlot(CellState state) {
        return switch (state) {
            case MINE, MY_GUILD, OTHER_PLAYER, OTHER_GUILD, RESERVED -> true;
            default -> false;
        };
    }

    /** Claimable cells get their own bright inner ring. */
    public static boolean ringsCell(CellState state) { return state == CellState.FRONTAGE_FREE; }

    /** Faint lattice line drawn around every cell, ARGB. */
    public static final int GRID_LINE = 0x7310201F;
    /** Background where the world has no map picture yet (unexplored or beyond the edge). */
    public static final int UNEXPLORED = 0xFF1B2A2B;
    /** Hatching over unexplored ground. */
    public static final int UNEXPLORED_HATCH = 0xFF223536;
    /** Road surface highlight, ARGB (warm light stone). */
    public static final int ROAD = 0x9EEDE3C8;
    /** Player marker fill (Citadel brass) and ring. */
    public static final int PLAYER_FILL = 0xFFD8B66B;
    public static final int PLAYER_RING = 0xFFF8FAFC;
    public static final int PLAYER_HALO = 0x99101E22;
}
