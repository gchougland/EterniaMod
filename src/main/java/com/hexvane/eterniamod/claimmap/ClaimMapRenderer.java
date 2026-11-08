package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView.CellState;
import com.hexvane.eterniamod.housing.PlotRect;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Paints the claim map picture: the world map tiles under the window, stitched and scaled, then the roads, the hexagon
 * grid tinted by state, plot borders, rings on claimable cells and the player's position. Pure Java2D with no game
 * types, so it runs on a worker thread and in tests. The selected cell and the shape preview are drawn by the page on
 * top of this picture (so selecting is instant and needs no new picture).
 */
public final class ClaimMapRenderer {
    /** Blocks per world map tile (one chunk). */
    public static final int TILE_BLOCKS = 32;

    /** One world map tile as ARGB pixels, row by row, north at the top. */
    public record Tile(int width, int height, int[] argb) {
        public Tile {
            Objects.requireNonNull(argb);
            if (width <= 0 || height <= 0 || argb.length < width * height) throw new IllegalArgumentException("Bad tile size");
        }
    }

    /** Map tiles by chunk; null where the world has no picture (not explored, or past the edge). */
    @FunctionalInterface
    public interface TileSource {
        Tile tile(int chunkX, int chunkZ);
    }

    /**
     * Everything one picture shows. {@code states} and {@code plots} cover {@link ClaimMapGeometry#visibleCells()};
     * a missing state paints as plain open land. {@code roads} are road and plaza surfaces (block rectangles).
     * {@code playerX/Z} are null when the player is not in this world.
     */
    public record Scene(ClaimMapGeometry geometry, Map<HexCoord, CellState> states, Map<HexCoord, UUID> plots,
                        List<PlotRect> roads, Double playerX, Double playerZ) {
        public Scene {
            Objects.requireNonNull(geometry);
            states = Map.copyOf(states);
            plots = Map.copyOf(plots);
            roads = List.copyOf(roads);
        }
    }

    private ClaimMapRenderer() {}

    public static BufferedImage render(Scene scene, TileSource tiles) {
        ClaimMapGeometry g = scene.geometry();
        int w = g.pixelWidth(), h = g.pixelHeight();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = image.createGraphics();
        try {
            gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gfx.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            gfx.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            paintUnexplored(gfx, w, h);
            paintTerrain(gfx, g, tiles);
            // A light veil keeps bright grass and sand from fighting the tints.
            fill(gfx, 0x1F0B1416, 0, 0, w, h);
            paintRoads(gfx, g, scene.roads());
            paintCells(gfx, g, scene);
            paintPlayer(gfx, g, scene.playerX(), scene.playerZ());
        } finally {
            gfx.dispose();
        }
        return image;
    }

    /** PNG bytes of a picture. */
    public static byte[] png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024)) {
            if (!ImageIO.write(image, "png", out)) throw new IOException("No PNG writer");
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void paintUnexplored(Graphics2D gfx, int w, int h) {
        fill(gfx, ClaimMapPalette.UNEXPLORED, 0, 0, w, h);
        gfx.setColor(argb(ClaimMapPalette.UNEXPLORED_HATCH));
        gfx.setStroke(new BasicStroke(2f));
        for (int d = -h; d < w; d += 14) gfx.draw(new Line2D.Double(d, h, d + h, 0));
    }

    /** Stitches every tile under the window into one raster at tile resolution, then scales it once (no seams). */
    private static void paintTerrain(Graphics2D gfx, ClaimMapGeometry g, TileSource tiles) {
        if (tiles == null) return;
        int cx0 = Math.floorDiv((int) Math.floor(g.worldX0()), TILE_BLOCKS);
        int cz0 = Math.floorDiv((int) Math.floor(g.worldZ0()), TILE_BLOCKS);
        int cx1 = Math.floorDiv((int) Math.ceil(g.worldX0() + g.worldWidth()) - 1, TILE_BLOCKS);
        int cz1 = Math.floorDiv((int) Math.ceil(g.worldZ0() + g.worldHeight()) - 1, TILE_BLOCKS);
        int columns = cx1 - cx0 + 1, rows = cz1 - cz0 + 1;
        Tile[][] found = new Tile[rows][columns];
        int resolution = 0;
        for (int z = 0; z < rows; z++)
            for (int x = 0; x < columns; x++) {
                Tile t = tiles.tile(cx0 + x, cz0 + z);
                found[z][x] = t;
                if (t != null) resolution = Math.max(resolution, Math.max(t.width(), t.height()));
            }
        if (resolution == 0) return;
        BufferedImage raster = new BufferedImage(columns * resolution, rows * resolution, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < rows; z++)
            for (int x = 0; x < columns; x++) {
                Tile t = found[z][x];
                if (t != null) stamp(raster, t, x * resolution, z * resolution, resolution);
            }
        double scale = ClaimMapGeometry.SCALE * TILE_BLOCKS / resolution;
        var at = new AffineTransform();
        at.translate((cx0 * (double) TILE_BLOCKS - g.worldX0()) * ClaimMapGeometry.SCALE, (cz0 * (double) TILE_BLOCKS - g.worldZ0()) * ClaimMapGeometry.SCALE);
        at.scale(scale, scale);
        gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gfx.drawImage(raster, at, null);
    }

    /** Copies a tile into the raster, nearest neighbour when its size differs from the raster cell. */
    private static void stamp(BufferedImage raster, Tile t, int ox, int oy, int size) {
        for (int y = 0; y < size; y++) {
            int sy = Math.min(t.height() - 1, y * t.height() / size);
            for (int x = 0; x < size; x++) {
                int sx = Math.min(t.width() - 1, x * t.width() / size);
                raster.setRGB(ox + x, oy + y, t.argb()[sy * t.width() + sx]);
            }
        }
    }

    private static void paintRoads(Graphics2D gfx, ClaimMapGeometry g, List<PlotRect> roads) {
        if (roads.isEmpty()) return;
        gfx.setColor(argb(ClaimMapPalette.ROAD));
        double x0 = g.worldX0(), z0 = g.worldZ0(), x1 = x0 + g.worldWidth(), z1 = z0 + g.worldHeight();
        Path2D.Double all = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (PlotRect r : roads) {
            if (r.endX() <= x0 || r.x() >= x1 || r.endZ() <= z0 || r.z() >= z1) continue;
            var a = g.pixel(r.x(), r.z());
            all.append(new java.awt.geom.Rectangle2D.Double(a.x(), a.y(), r.width() * ClaimMapGeometry.SCALE, r.depth() * ClaimMapGeometry.SCALE), false);
        }
        // One fill for the whole network so overlapping pieces do not stack into darker patches.
        gfx.fill(all);
    }

    private static void paintCells(Graphics2D gfx, ClaimMapGeometry g, Scene scene) {
        List<HexCoord> cells = g.visibleCells();
        // 1. Tints and dimming.
        for (HexCoord cell : cells) {
            CellState state = scene.states().getOrDefault(cell, CellState.FREE);
            Path2D hex = hexPath(g, cell, 0);
            float fill = ClaimMapPalette.fillAlpha(state), dim = ClaimMapPalette.dimAlpha(state);
            if (dim > 0) paint(gfx, hex, 0x0B1416, dim);
            if (fill > 0) paint(gfx, hex, ClaimMapPalette.color(state), fill);
        }
        // 2. The faint lattice everywhere.
        gfx.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        gfx.setColor(argb(ClaimMapPalette.GRID_LINE));
        for (HexCoord cell : cells) gfx.draw(hexPath(g, cell, 0));
        // 3. Rings on claimable cells.
        for (HexCoord cell : cells) {
            CellState state = scene.states().getOrDefault(cell, CellState.FREE);
            if (!ClaimMapPalette.ringsCell(state)) continue;
            // A dark hairline under a bright ring keeps it readable on any grass.
            gfx.setStroke(new BasicStroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gfx.setColor(new Color(11, 20, 22, 110));
            gfx.draw(hexPath(g, cell, 4.0));
            gfx.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gfx.setColor(withAlpha(brighter(brighter(ClaimMapPalette.color(state))), 1f));
            gfx.draw(hexPath(g, cell, 4.0));
        }
        // 4. One strong border around each plot: an edge is drawn where the cell on the other side is not the same plot.
        gfx.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (HexCoord cell : cells) {
            CellState state = scene.states().getOrDefault(cell, CellState.FREE);
            if (!ClaimMapPalette.outlinesPlot(state)) continue;
            UUID plot = scene.plots().get(cell);
            double[][] p = corners(g, cell, 1.6);
            double[][] c = corners(g, cell, 0);
            for (int i = 0; i < 6; i++) {
                int j = (i + 1) % 6;
                // Step just over the middle of this edge to find the neighbour on the other side.
                double mx = (c[i][0] + c[j][0]) / 2, my = (c[i][1] + c[j][1]) / 2;
                var mid = g.centre(cell);
                double ox = mx + (mx - mid.x()) * 0.15, oy = my + (my - mid.y()) * 0.15;
                HexCoord other = g.cellAtPixel(ox, oy);
                UUID otherPlot = scene.plots().get(other);
                boolean same = plot != null ? plot.equals(otherPlot) : scene.states().getOrDefault(other, CellState.FREE) == state && otherPlot == null;
                if (same) continue;
                gfx.setColor(withAlpha(brighter(ClaimMapPalette.color(state)), 1f));
                gfx.draw(new Line2D.Double(p[i][0], p[i][1], p[j][0], p[j][1]));
            }
        }
    }

    private static void paintPlayer(Graphics2D gfx, ClaimMapGeometry g, Double x, Double z) {
        if (x == null || z == null) return;
        var p = g.pixel(x, z);
        if (!g.onPicture(p.x(), p.y())) return;
        gfx.setColor(argb(ClaimMapPalette.PLAYER_HALO));
        gfx.fill(new Ellipse2D.Double(p.x() - 12, p.y() - 12, 24, 24));
        gfx.setColor(argb(ClaimMapPalette.PLAYER_RING));
        gfx.fill(new Ellipse2D.Double(p.x() - 8.5, p.y() - 8.5, 17, 17));
        gfx.setColor(argb(ClaimMapPalette.PLAYER_FILL));
        gfx.fill(new Ellipse2D.Double(p.x() - 5.5, p.y() - 5.5, 11, 11));
    }

    /** The cell's ideal outline in picture pixels, pulled in by {@code inset} pixels towards its centre. */
    static Path2D hexPath(ClaimMapGeometry g, HexCoord cell, double inset) {
        double[][] c = corners(g, cell, inset);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(c[0][0], c[0][1]);
        for (int i = 1; i < 6; i++) path.lineTo(c[i][0], c[i][1]);
        path.closePath();
        return path;
    }

    static double[][] corners(ClaimMapGeometry g, HexCoord cell, double inset) {
        double[][] world = g.lattice().corners(cell);
        var mid = g.centre(cell);
        double[][] out = new double[6][2];
        for (int i = 0; i < 6; i++) {
            var p = g.pixel(world[i][0], world[i][1]);
            double dx = p.x() - mid.x(), dy = p.y() - mid.y(), len = Math.hypot(dx, dy);
            double k = len == 0 ? 0 : Math.max(0, len - inset) / len;
            out[i][0] = mid.x() + dx * k;
            out[i][1] = mid.y() + dy * k;
        }
        return out;
    }

    private static void paint(Graphics2D gfx, java.awt.Shape shape, int rgb, float alpha) {
        var old = gfx.getComposite();
        gfx.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        gfx.setColor(new Color(rgb & 0xFFFFFF));
        gfx.fill(shape);
        gfx.setComposite(old);
    }

    private static void fill(Graphics2D gfx, int argb, int x, int y, int w, int h) {
        gfx.setColor(argb(argb));
        gfx.fillRect(x, y, w, h);
    }

    private static Color argb(int argb) { return new Color(argb, true); }

    private static Color withAlpha(int rgb, float alpha) { return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, Math.round(alpha * 255)); }

    private static int brighter(int rgb) {
        int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = r + (255 - r) / 5; gr = gr + (255 - gr) / 5; b = b + (255 - b) / 5;
        return (r << 16) | (gr << 8) | b;
    }
}
