package com.hexvane.eterniamod.hexmap;

import com.hexvane.eterniamod.boundary.HexBorderRelation;
import com.hexvane.eterniamod.hexplots.HexLandView;

/**
 * Paints hex plots onto one world map tile. Pure and deterministic, so the pictures in the notes and the tests are
 * drawn by exactly this code.
 *
 * <p>Two steps. {@link #geometry} decides, for every pixel, which plot (or street) owns it and whether it is a plot's
 * border, a plot's inside, a shadow just outside a plot or street ground. That depends only on the land, so it is
 * shared by every viewer. {@link #paint} then colours it for one viewer: a crisp border in the relation's palette
 * colour (two blocks wide, so 1 pixel at the default map scale, 2 at full scale), a soft see-through fill, a thin dark
 * shadow outside the border so the line reads on grass, sand or snow, and a faint stone tint on street cells.
 * Neighbouring plots each draw their own border, so a shared side shows both colours.
 */
public final class HexMapPainter {
    public static final int NONE = -1;
    public static final int STREET = -2;

    public static final byte KIND_NONE = 0, KIND_FILL = 1, KIND_BORDER = 2, KIND_SHADOW = 3, KIND_STREET = 4;

    /** Fill strength for the viewer's own and guild land, and for everyone else's. */
    public static final float FILL_OWN = .32f, FILL_OTHER = .22f;
    public static final float SHADOW = .38f, STREET_TINT = .22f;

    private HexMapPainter() {}

    /** Who owns a block column: a stable plot key (0 or more), {@link #STREET} or {@link #NONE}. */
    @FunctionalInterface
    public interface Land {
        int keyAt(int x, int z);
    }

    /** How one viewer sees a plot key: palette colour {@code 0xRRGGBB} and fill strength, or null for nothing. */
    @FunctionalInterface
    public interface Styler {
        Style style(int key);
    }

    public record Style(int rgb, float fill) {
        public static Style of(HexBorderRelation relation) {
            boolean own = relation == HexBorderRelation.MINE || relation == HexBorderRelation.MY_GUILD;
            return new Style(relation.color(), own ? FILL_OWN : FILL_OTHER);
        }
    }

    /** Viewer independent layout of one tile. */
    public record Geometry(int width, int height, int[] keys, byte[] kinds) {
        public boolean isEmpty() {
            for (byte kind : kinds) if (kind != KIND_NONE) return false;
            return true;
        }
    }

    /** Border thickness in pixels: about two blocks, at least one pixel, at most three. */
    public static int thickness(int width) {
        double pixelsPerBlock = width / (double) HexMapPixels.TILE_BLOCKS;
        return Math.max(1, Math.min(3, (int) Math.round(2 * pixelsPerBlock)));
    }

    public static Geometry geometry(int chunkX, int chunkZ, int width, int height, Land land) {
        int minX = chunkX * HexMapPixels.TILE_BLOCKS, minZ = chunkZ * HexMapPixels.TILE_BLOCKS;
        double stepX = HexMapPixels.TILE_BLOCKS / (double) width, stepZ = HexMapPixels.TILE_BLOCKS / (double) height;
        int t = thickness(width);
        // Keys for the tile plus a margin of t pixels, so borders at the tile edge match the next tile.
        int w = width + 2 * t, h = height + 2 * t;
        int[] around = new int[w * h];
        for (int pz = -t; pz < height + t; pz++) {
            int z = minZ + (int) Math.floor((pz + .5) * stepZ);
            for (int px = -t; px < width + t; px++) {
                int x = minX + (int) Math.floor((px + .5) * stepX);
                around[(pz + t) * w + px + t] = land.keyAt(x, z);
            }
        }
        int[] keys = new int[width * height];
        byte[] kinds = new byte[width * height];
        for (int pz = 0; pz < height; pz++) {
            for (int px = 0; px < width; px++) {
                int key = around[(pz + t) * w + px + t];
                keys[pz * width + px] = key;
                byte kind;
                if (key >= 0) {
                    kind = differs(around, w, px + t, pz + t, key, t) ? KIND_BORDER : KIND_FILL;
                } else if (touchesPlot(around, w, px + t, pz + t)) {
                    kind = KIND_SHADOW;
                } else {
                    kind = key == STREET ? KIND_STREET : KIND_NONE;
                }
                kinds[pz * width + px] = kind;
            }
        }
        return new Geometry(width, height, keys, kinds);
    }

    private static boolean differs(int[] around, int w, int x, int z, int key, int t) {
        for (int d = 1; d <= t; d++) {
            if (around[z * w + x - d] != key || around[z * w + x + d] != key
                || around[(z - d) * w + x] != key || around[(z + d) * w + x] != key) return true;
        }
        return false;
    }

    private static boolean touchesPlot(int[] around, int w, int x, int z) {
        return around[z * w + x - 1] >= 0 || around[z * w + x + 1] >= 0 || around[(z - 1) * w + x] >= 0 || around[(z + 1) * w + x] >= 0;
    }

    /** Colours a tile for one viewer. {@code base} is {@code 0xRRGGBBAA} pixels; a new array is returned. */
    public static int[] paint(int[] base, Geometry geometry, Styler styler) {
        int[] out = base.clone();
        int stone = HexLandView.suggestedColor(HexLandView.CellState.STREET);
        for (int i = 0; i < out.length; i++) {
            switch (geometry.kinds()[i]) {
                case KIND_BORDER -> {
                    Style style = styler.style(geometry.keys()[i]);
                    if (style != null) out[i] = withAlpha(style.rgb(), base[i]);
                }
                case KIND_FILL -> {
                    Style style = styler.style(geometry.keys()[i]);
                    if (style != null) out[i] = mix(base[i], style.rgb(), style.fill());
                }
                case KIND_SHADOW -> out[i] = mix(base[i], 0x101418, SHADOW);
                case KIND_STREET -> out[i] = mix(base[i], stone, STREET_TINT);
                default -> { }
            }
        }
        return out;
    }

    /** Blends {@code rgb} over an {@code 0xRRGGBBAA} pixel, keeping its alpha. */
    static int mix(int rgba, int rgb, float t) {
        int r = (rgba >>> 24) & 0xFF, g = (rgba >>> 16) & 0xFF, b = (rgba >>> 8) & 0xFF, a = rgba & 0xFF;
        int r2 = (rgb >> 16) & 0xFF, g2 = (rgb >> 8) & 0xFF, b2 = rgb & 0xFF;
        r = Math.round(r + (r2 - r) * t); g = Math.round(g + (g2 - g) * t); b = Math.round(b + (b2 - b) * t);
        return (r << 24) | (g << 16) | (b << 8) | a;
    }

    static int withAlpha(int rgb, int rgbaForAlpha) {
        return (rgb << 8) | (rgbaForAlpha & 0xFF);
    }
}
