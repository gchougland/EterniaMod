package com.hexvane.eterniamod.hexmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes plot names inside their plots on world map tiles, in a small 3 x 5 pixel capital font with a dark outline.
 * Pure and deterministic: every tile a label crosses computes the same placement, so a name split over tiles lines up.
 *
 * <p>A label is centred on its plot's middle. Each map pixel is one font pixel, so the text stays crisp at any map
 * scale. The name is fitted to the plot's inside, away from the border: on one line if it fits, otherwise on two lines
 * split at a space, otherwise cut short. A plot too small for two letters gets no label.
 */
public final class HexMapLabels {
    static final int GLYPH_W = 3, GLYPH_H = 5, ADVANCE = 4, LINE = 6;
    static final int TEXT_RGB = 0xF8FAFC, OUTLINE_RGB = 0x101418;
    static final float OUTLINE = .72f;
    private static final int MAX_CHARS = 16;

    private HexMapLabels() {}

    /** Largest font scale for a map with {@code bpp} blocks per pixel: font pixels up to two blocks wide. */
    static int maxScale(double bpp) { return Math.max(1, (int) Math.floor(2.0 / bpp + 1e-9)); }

    /** Outline thickness in pixels for a font scale: about half a font pixel, at least one map pixel. */
    static int outline(int scale) { return Math.max(1, scale / 2); }

    /** One plot's name: its land key, the text and spots to centre it on (block coordinates). */
    public record Spec(int key, String text, List<double[]> centres) {
        public Spec(int key, String text, double centerX, double centerZ) { this(key, text, List.of(new double[] {centerX, centerZ})); }
    }

    /** A placed label: lines, font scale and centre in global map pixels. */
    record Fit(String[] lines, int scale, int gcx, int gcz, int shown, boolean whole) {}

    /** Draws every label that touches the tile into {@code pixels} ({@code 0xRRGGBBAA}, row by row). */
    public static void draw(int[] pixels, int width, int height, int chunkX, int chunkZ, List<Spec> specs, HexMapPainter.Land land) {
        if (specs.isEmpty()) return;
        double bpp = HexMapPixels.TILE_BLOCKS / (double) width;
        int border = HexMapPainter.thickness(width);
        int tileX = chunkX * width, tileZ = chunkZ * height;
        int top = maxScale(bpp), reach = MAX_CHARS * ADVANCE * top / 2 + 8, rise = 2 * LINE * top + 4;
        for (Spec spec : specs) {
            boolean near = false;
            for (double[] c : spec.centres()) {
                int gx = (int) Math.floor(c[0] / bpp), gz = (int) Math.floor(c[1] / bpp);
                if (gx + reach >= tileX && gx - reach < tileX + width && gz + rise >= tileZ && gz - rise < tileZ + height) { near = true; break; }
            }
            if (!near) continue;
            Fit fit = place(spec, bpp, border, land);
            if (fit == null) continue;
            int s = fit.scale();
            int textH = fit.lines().length * LINE * s - s;
            int y0 = fit.gcz() - textH / 2;
            for (int l = 0; l < fit.lines().length; l++) {
                String line = fit.lines()[l];
                int textW = width(line) * s;
                stamp(pixels, width, height, tileX, tileZ, line, fit.gcx() - textW / 2, y0 + l * LINE * s, s);
            }
        }
    }

    /** The best placement over the spec's centres: whole names first, then bigger letters, then more letters shown. */
    static Fit place(Spec spec, double bpp, int border, HexMapPainter.Land land) {
        Fit best = null;
        int total = clean(spec.text()).replace(" ", "").length();
        for (double[] c : spec.centres()) {
            int gcx = (int) Math.floor(c[0] / bpp), gcz = (int) Math.floor(c[1] / bpp);
            for (int scale = maxScale(bpp); scale >= 1; scale--) {
                String[] lines = fit(spec, gcx, gcz, bpp, border, land, scale);
                if (lines == null) continue;
                int shown = 0;
                for (String l : lines) shown += l.replace(" ", "").length();
                boolean whole = shown == total;
                if (scale > 1 && !whole) continue;
                Fit f = new Fit(lines, scale, gcx, gcz, shown, whole);
                if (best == null || better(f, best)) best = f;
            }
        }
        return best;
    }

    private static boolean better(Fit a, Fit b) {
        if (a.whole() != b.whole()) return a.whole();
        if (a.scale() != b.scale()) return a.scale() > b.scale();
        if (a.shown() != b.shown()) return a.shown() > b.shown();
        return a.lines().length < b.lines().length;
    }

    /** The lines to write at {@code scale}, or null when nothing readable fits. Package private for tests. */
    static String[] fit(Spec spec, int gcx, int gcz, double bpp, int border, HexMapPainter.Land land, int scale) {
        String text = clean(spec.text());
        if (text.length() < 2) return null;
        int glyphH = GLYPH_H * scale, line = LINE * scale;
        int margin = border + outline(scale) - 1;
        int oneRoom = room(spec.key(), gcx, gcz - glyphH / 2 - 1, gcz + glyphH / 2 + 1, bpp, margin, land, scale) / scale;
        if (width(text) <= oneRoom) return new String[] {text};
        int twoRoom = room(spec.key(), gcx, gcz - line, gcz + line, bpp, margin, land, scale) / scale;
        int split = splitAt(text);
        String[] two = null;
        if (split > 0) {
            String a = text.substring(0, split).trim(), b = text.substring(split).trim();
            if (width(a) <= twoRoom && width(b) <= twoRoom) return new String[] {a, b};
            String ca = cut(a, twoRoom), cb = cut(b, twoRoom);
            if (ca.length() >= 2) two = cb.length() >= 2 ? new String[] {ca, cb} : new String[] {ca};
        }
        String cut = cut(text, oneRoom);
        String[] one = cut.length() >= 2 ? new String[] {cut} : null;
        if (two == null) return one;
        if (one == null) return two;
        int twoShown = two[0].length() + (two.length > 1 ? two[1].length() : 0);
        return twoShown > cut.replace(" ", "").length() ? two : one;
    }

    /** Usable text width in pixels around {@code gcx} for rows {@code z0..z1}: the narrowest row, kept off the border. */
    private static int room(int key, int gcx, int z0, int z1, double bpp, int border, HexMapPainter.Land land, int scale) {
        int limit = MAX_CHARS * ADVANCE * scale / 2 + border + 4;
        int best = Integer.MAX_VALUE;
        for (int gz = z0; gz <= z1; gz++) {
            if (key(land, gcx, gz, bpp) != key) return 0;
            int left = 0, right = 0;
            while (left < limit && key(land, gcx - left - 1, gz, bpp) == key) left++;
            while (right < limit && key(land, gcx + right + 1, gz, bpp) == key) right++;
            best = Math.min(best, Math.min(left, right));
        }
        // The text's outline may touch the pixel inside the border line, never the line itself.
        int half = best - border - 1;
        return half <= 0 ? 0 : half * 2 + 1;
    }

    private static int key(HexMapPainter.Land land, int gx, int gz, double bpp) {
        return land.keyAt((int) Math.floor((gx + .5) * bpp), (int) Math.floor((gz + .5) * bpp));
    }

    static int width(String text) { return text.isEmpty() ? 0 : text.length() * ADVANCE - 1; }

    private static String cut(String text, int room) {
        int max = Math.max(0, (room + 1) / ADVANCE);
        return text.length() <= max ? text : text.substring(0, max).trim();
    }

    private static int splitAt(String text) {
        int mid = text.length() / 2, best = -1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ' ' && (best < 0 || Math.abs(i - mid) < Math.abs(best - mid))) best = i;
        return best;
    }

    /** Capitals, digits, spaces and a few marks the font has; anything else is dropped. */
    static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (glyph(c) != null || c == ' ') out.append(c);
            else if (c == '_' || c == '-') out.append(' ');
        }
        String s = out.toString().trim().replaceAll(" +", " ");
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS).trim() : s;
    }

    private static void stamp(int[] pixels, int width, int height, int tileX, int tileZ, String line, int left, int top, int scale) {
        List<int[]> ink = new ArrayList<>();
        for (int i = 0; i < line.length(); i++) {
            int[] rows = glyph(line.charAt(i));
            if (rows == null) continue;
            int gx0 = left + i * ADVANCE * scale;
            for (int r = 0; r < GLYPH_H; r++) for (int c = 0; c < GLYPH_W; c++)
                if ((rows[r] >> (GLYPH_W - 1 - c) & 1) != 0)
                    for (int sz = 0; sz < scale; sz++) for (int sx = 0; sx < scale; sx++) ink.add(new int[] {gx0 + c * scale + sx, top + r * scale + sz});
        }
        // Outline first (eight neighbours), then the letters on top.
        int o = outline(scale);
        for (int[] p : ink) for (int dz = -o; dz <= o; dz++) for (int dx = -o; dx <= o; dx++) {
            int x = p[0] + dx - tileX, z = p[1] + dz - tileZ;
            if (x < 0 || z < 0 || x >= width || z >= height) continue;
            int i = z * width + x;
            if ((pixels[i] >>> 8) != TEXT_RGB) pixels[i] = HexMapPainter.mix(pixels[i], OUTLINE_RGB, OUTLINE);
        }
        for (int[] p : ink) {
            int x = p[0] - tileX, z = p[1] - tileZ;
            if (x < 0 || z < 0 || x >= width || z >= height) continue;
            int i = z * width + x;
            pixels[i] = HexMapPainter.withAlpha(TEXT_RGB, pixels[i]);
        }
    }

    /** Five rows of three bits, top row first. */
    static int[] glyph(char c) {
        return switch (c) {
            case 'A' -> new int[] {2, 5, 7, 5, 5};
            case 'B' -> new int[] {6, 5, 6, 5, 6};
            case 'C' -> new int[] {3, 4, 4, 4, 3};
            case 'D' -> new int[] {6, 5, 5, 5, 6};
            case 'E' -> new int[] {7, 4, 6, 4, 7};
            case 'F' -> new int[] {7, 4, 6, 4, 4};
            case 'G' -> new int[] {3, 4, 5, 5, 3};
            case 'H' -> new int[] {5, 5, 7, 5, 5};
            case 'I' -> new int[] {7, 2, 2, 2, 7};
            case 'J' -> new int[] {1, 1, 1, 5, 2};
            case 'K' -> new int[] {5, 5, 6, 5, 5};
            case 'L' -> new int[] {4, 4, 4, 4, 7};
            case 'M' -> new int[] {5, 7, 7, 5, 5};
            case 'N' -> new int[] {6, 5, 5, 5, 5};
            case 'O' -> new int[] {2, 5, 5, 5, 2};
            case 'P' -> new int[] {6, 5, 6, 4, 4};
            case 'Q' -> new int[] {2, 5, 5, 6, 3};
            case 'R' -> new int[] {6, 5, 6, 5, 5};
            case 'S' -> new int[] {3, 4, 2, 1, 6};
            case 'T' -> new int[] {7, 2, 2, 2, 2};
            case 'U' -> new int[] {5, 5, 5, 5, 7};
            case 'V' -> new int[] {5, 5, 5, 5, 2};
            case 'W' -> new int[] {5, 5, 7, 7, 5};
            case 'X' -> new int[] {5, 5, 2, 5, 5};
            case 'Y' -> new int[] {5, 5, 2, 2, 2};
            case 'Z' -> new int[] {7, 1, 2, 4, 7};
            case '0' -> new int[] {2, 5, 5, 5, 2};
            case '1' -> new int[] {2, 6, 2, 2, 7};
            case '2' -> new int[] {6, 1, 2, 4, 7};
            case '3' -> new int[] {6, 1, 2, 1, 6};
            case '4' -> new int[] {5, 5, 7, 1, 1};
            case '5' -> new int[] {7, 4, 6, 1, 6};
            case '6' -> new int[] {3, 4, 6, 5, 2};
            case '7' -> new int[] {7, 1, 2, 2, 2};
            case '8' -> new int[] {2, 5, 2, 5, 2};
            case '9' -> new int[] {2, 5, 3, 1, 6};
            case '\'' -> new int[] {2, 2, 0, 0, 0};
            case '.' -> new int[] {0, 0, 0, 0, 2};
            case '&' -> new int[] {2, 5, 2, 5, 3};
            default -> null;
        };
    }
}
