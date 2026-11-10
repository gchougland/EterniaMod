package com.hexvane.eterniamod.town;

import com.hexvane.eterniamod.claimmap.ClaimMapRenderer;
import com.hexvane.eterniamod.claimmap.ClaimMapTiles;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.pathtool.SplineGeometry;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.World;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A top-down picture of a planned town: the world map underneath, the hexagon grid, the roads and plazas already there
 * in gold, planned roads in green and planned bridges in blue. Painted off the world thread.
 */
final class TownRoadPicture {
    private TownRoadPicture() {}

    static final int SIZE = 680;

    /** The world area the picture shows: a square round the network's hexagons. */
    record Frame(double x0, double z0, double span) {
        double px(double x) { return (x - x0) / span * SIZE; }
        double py(double z) { return (z - z0) / span * SIZE; }
    }

    static Frame frame(HexLattice lattice, List<HexCoord> disk) {
        var box = lattice.bounds(disk);
        double span = Math.max(box.width(), box.depth()) + 16;
        double cx = box.x() + box.width() / 2.0, cz = box.z() + box.depth() / 2.0;
        return new Frame(cx - span / 2, cz - span / 2, span);
    }

    /** The world map tiles under the frame, waiting a few seconds for any the map has not drawn yet. Background thread. */
    static Map<Long, ClaimMapRenderer.Tile> tiles(World world, Frame f) {
        var maps = world.getWorldMapManager();
        Map<Long, ClaimMapRenderer.Tile> out = new HashMap<>();
        if (maps == null || !maps.isWorldMapEnabled()) return out;
        int b = ClaimMapRenderer.TILE_BLOCKS;
        int cx0 = Math.floorDiv((int) Math.floor(f.x0()), b), cz0 = Math.floorDiv((int) Math.floor(f.z0()), b);
        int cx1 = Math.floorDiv((int) Math.ceil(f.x0() + f.span()) - 1, b), cz1 = Math.floorDiv((int) Math.ceil(f.z0() + f.span()) - 1, b);
        Map<Long, MapImage> images = TownBuilder.supply(world, () -> {
            Map<Long, MapImage> found = new HashMap<>();
            for (int z = cz0; z <= cz1; z++) for (int x = cx0; x <= cx1; x++) { var image = maps.getImageIfInMemory(x, z); if (image != null) found.put(ClaimMapTiles.key(x, z), image); }
            return found;
        });
        List<CompletableFuture<?>> waits = new ArrayList<>();
        for (int z = cz0; z <= cz1; z++) for (int x = cx0; x <= cx1; x++) {
            long k = ClaimMapTiles.key(x, z);
            if (images.containsKey(k)) continue;
            waits.add(maps.getImageAsync(x, z).thenAccept(image -> { if (image != null) synchronized (images) { images.put(k, image); } }).exceptionally(e -> null));
        }
        try { CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).get(8, TimeUnit.SECONDS); } catch (Exception ignored) { /* Paint what there is. */ }
        synchronized (images) { images.forEach((k, image) -> { var t = ClaimMapTiles.decode(image); if (t != null) out.put(k, t); }); }
        return out;
    }

    /** Paints the picture as PNG bytes. */
    static byte[] paint(HexLattice lattice, List<HexCoord> disk, Frame f, Map<Long, ClaimMapRenderer.Tile> tiles, List<PlotRect> oldRoads, List<PlotRect> plazas,
                        PlotRect plaza, List<TownRoadNetwork.Road> roads) {
        var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(new Color(0x1b2a2b));
            g.fillRect(0, 0, SIZE, SIZE);
            int b = ClaimMapRenderer.TILE_BLOCKS;
            for (var e : tiles.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                var t = e.getValue();
                var tile = new BufferedImage(t.width(), t.height(), BufferedImage.TYPE_INT_ARGB);
                tile.setRGB(0, 0, t.width(), t.height(), t.argb(), 0, t.width());
                int x0 = (int) Math.floor(f.px(cx * b)), y0 = (int) Math.floor(f.py(cz * b)), x1 = (int) Math.ceil(f.px((cx + 1) * b)), y1 = (int) Math.ceil(f.py((cz + 1) * b));
                g.drawImage(tile, x0, y0, x1 - x0, y1 - y0, null);
            }
            g.setColor(new Color(11, 20, 22, 70));
            g.fillRect(0, 0, SIZE, SIZE);
            // The hexagons the town may grow into.
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(255, 255, 255, 40));
            for (HexCoord c : disk) g.draw(hex(lattice, c, f));
            // What is already there.
            g.setColor(new Color(0xe2c47c));
            for (PlotRect r : oldRoads) fill(g, r, f);
            g.setStroke(new BasicStroke(2.5f));
            for (PlotRect r : plazas) outline(g, r, f);
            g.setColor(new Color(0xf0d890));
            g.setStroke(new BasicStroke(3.5f));
            outline(g, plaza, f);
            // The plan.
            float width = (float) Math.max(3, TownRoadNetwork.WIDTH / f.span() * SIZE);
            for (var road : roads) {
                g.setColor(new Color(20, 30, 25, 200));
                g.setStroke(new BasicStroke(width + 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(path(road, f));
            }
            for (var road : roads) {
                g.setColor(road.chain().bridge() ? new Color(0x7fb4f0) : new Color(0x8fd99b));
                g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(path(road, f));
            }
        } finally { g.dispose(); }
        return ClaimMapRenderer.png(image);
    }

    private static Path2D hex(HexLattice lattice, HexCoord c, Frame f) {
        var corners = lattice.corners(c);
        var p = new Path2D.Double();
        for (int i = 0; i < corners.length; i++) {
            double x = f.px(corners[i][0]), y = f.py(corners[i][1]);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    /** A road as it will be built: the Road Designer's curve through its points. */
    private static Path2D path(TownRoadNetwork.Road road, Frame f) {
        List<SplineGeometry.Node> nodes = new ArrayList<>();
        for (double[] n : road.nodes()) nodes.add(new SplineGeometry.Node(n[0], 64, n[2]));
        List<SplineGeometry.Node> samples;
        try { samples = road.chain().bridge() ? nodes : SplineGeometry.sample(nodes); } catch (RuntimeException e) { samples = nodes; }
        var p = new Path2D.Double();
        for (int i = 0; i < samples.size(); i++) {
            double x = f.px(samples.get(i).x()), y = f.py(samples.get(i).z());
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        return p;
    }

    private static void fill(Graphics2D g, PlotRect r, Frame f) {
        int x0 = (int) Math.floor(f.px(r.x())), y0 = (int) Math.floor(f.py(r.z()));
        int x1 = (int) Math.ceil(f.px(r.endX())), y1 = (int) Math.ceil(f.py(r.endZ()));
        g.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
    }

    private static void outline(Graphics2D g, PlotRect r, Frame f) {
        g.drawRect((int) Math.round(f.px(r.x())), (int) Math.round(f.py(r.z())), (int) Math.round(r.width() / f.span() * SIZE), (int) Math.round(r.depth() / f.span() * SIZE));
    }
}
