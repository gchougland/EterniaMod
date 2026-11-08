import com.hexvane.eterniamod.boundary.BoundaryGeometry;
import com.hexvane.eterniamod.boundary.HexBorderPlanner;
import com.hexvane.eterniamod.boundary.HexBorderIndex;
import com.hexvane.eterniamod.boundary.HexBorderRelation;
import com.hexvane.eterniamod.hexmap.HexMapOverlayService;
import com.hexvane.eterniamod.hexmap.HexMapPixels;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Pictures of the hex plot borders, drawn by the real border and map overlay code (BoundaryGeometry,
 * HexBorderPlanner, HexMapOverlayService.render, HexMapPixels). Run after compiling:
 *
 * <pre>
 *   .\gradlew.bat --no-daemon compileJava
 *   java -cp "build/classes/java/main;<HytaleServer.jar>" tools/hexborders/HexBorderPictures.java notes/pictures/hex-borders
 * </pre>
 */
public class HexBorderPictures {
    static final HexLattice L = HexLattice.DEFAULT;
    static final UUID ME = UUID.randomUUID(), MY_GUILD = UUID.randomUUID(), RIVAL = UUID.randomUUID();
    static final HexLandView.Viewer VIEWER = HexLandView.Viewer.browsing(ME, MY_GUILD);

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "notes/pictures/hex-borders");
        Files.createDirectories(out);
        HexLandView typical = typical(), crowded = crowded(), empty = empty();
        // Viewer on the street, beside the neighbour's plot, deed in hand.
        HexCoord at = new HexCoord(3, 1);
        double vx = L.minX(at) + 3, vz = L.minZ(at) + 12;
        world(typical, vx, vz, out.resolve("01-world-borders-typical.png"), "Mixed street seen in the world: viewer holds the deed");
        world(crowded, L.centerX(new HexCoord(0, 1)) + 15, L.centerZ(new HexCoord(0, 1)), out.resolve("02-world-borders-crowded.png"), "Crowded street: four plots near the viewer get the staircase, the rest six lines");
        map(typical, 16, out.resolve("03-map-typical-default-scale.png"), "World map, default scale (16 px per chunk), typical street");
        map(typical, 32, out.resolve("04-map-typical-full-scale.png"), "World map, full scale (32 px per chunk), typical street");
        map(crowded, 16, out.resolve("05-map-crowded-default-scale.png"), "World map, default scale, crowded street");
        map(empty, 16, out.resolve("06-map-empty-default-scale.png"), "World map, default scale, a street with no plots yet");
        System.out.println("Pictures written to " + out.toAbsolutePath());
    }

    // ---- land ------------------------------------------------------------------------------------------------

    static Set<HexCoord> road(int from, int to) {
        Set<HexCoord> s = new HashSet<>();
        for (int q = from; q <= to; q++) s.add(new HexCoord(q, 0));
        return s;
    }

    static HexLandView.PlotInfo player(UUID owner, UUID guild, int... qr) {
        return new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.PLAYER, owner, guild, cells(qr));
    }

    static HexLandView.PlotInfo estate(UUID guild, int... qr) {
        return new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.GUILD, guild, guild, cells(qr));
    }

    static PlotCells cells(int... qr) {
        List<HexCoord> out = new ArrayList<>();
        for (int i = 0; i < qr.length; i += 2) out.add(new HexCoord(qr[i], qr[i + 1]));
        return PlotCells.of(L, out);
    }

    static HexLandView typical() {
        List<HexLandView.PlotInfo> plots = List.of(
            player(ME, null, 0, 1, 1, 1, 0, 2),                         // mine: a three cell manor
            player(UUID.randomUUID(), null, 2, 1),                       // a neighbour
            estate(MY_GUILD, -4, 2, -3, 1, -2, 1, -3, 2, -4, 3, -3, 3, -2, 2), // my guild's estate (flower)
            player(UUID.randomUUID(), MY_GUILD, -1, 1),                  // a guild mate's home
            estate(RIVAL, 0, -1, 1, -1, 1, -2),                          // another guild
            player(UUID.randomUUID(), RIVAL, -1, -1),                    // a member of the other guild
            player(UUID.randomUUID(), null, 4, -1, 5, -2));              // another player across the road
        return new HexLandView("hub", L, road(-6, 6), Set.of(), Set.of(), plots);
    }

    static HexLandView crowded() {
        List<HexLandView.PlotInfo> plots = new ArrayList<>(typical().plots().values());
        UUID[] others = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        plots.add(player(others[0], null, 3, 1, 3, 2));
        plots.add(player(others[1], null, 4, 1));
        plots.add(player(others[2], MY_GUILD, 5, 1, 4, 2));
        plots.add(player(others[3], RIVAL, -2, -1, -2, -2));
        plots.add(player(UUID.randomUUID(), null, 2, -1, 2, -2));
        plots.add(player(UUID.randomUUID(), null, 3, -1, 3, -2));
        plots.add(player(UUID.randomUUID(), null, -3, -1));
        plots.add(player(UUID.randomUUID(), RIVAL, -4, -1, -3, -2));
        plots.add(player(UUID.randomUUID(), null, 6, -1, 6, -2));
        plots.add(player(UUID.randomUUID(), null, 1, 2, 2, 2));
        return new HexLandView("hub", L, road(-6, 6), Set.of(), Set.of(), plots);
    }

    static HexLandView empty() { return new HexLandView("hub", L, road(-6, 6), Set.of(), Set.of(), List.of()); }

    // ---- in world borders --------------------------------------------------------------------------------------

    static final int MIN_X = -150, MAX_X = 240, MIN_Z = -80, MAX_Z = 120;

    static void world(HexLandView view, double vx, double vz, Path file, String title) throws Exception {
        int scale = 4, w = (MAX_X - MIN_X) * scale, h = (MAX_Z - MIN_Z) * scale + 120;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x3d5a36)); g.fillRect(0, 0, w, h);
        // Street cells as stone, plot cells darker green so the outlines read.
        for (int z = MIN_Z; z < MAX_Z; z++) for (int x = MIN_X; x < MAX_X; x++) {
            HexCoord c = L.cellAt(x, z);
            int rgb = view.isStreet(c) ? 0x6f6a60 : view.plotAt(c) != null ? 0x35502f : ((x * 7 + z * 13) % 11 == 0 ? 0x42603a : 0x3d5a36);
            g.setColor(new Color(rgb)); g.fillRect((x - MIN_X) * scale, (z - MIN_Z) * scale, scale, scale);
        }
        // Plot outlines as the outline overlay draws them: staircase for the 4 nearest, six lines for the rest.
        List<HexLandView.PlotInfo> plots = new ArrayList<>(view.plots().values());
        plots.sort(Comparator.comparingDouble(p -> distance(p.cells(), vx, vz)));
        int detailed = 0, staircaseLines = 0, straightLines = 0;
        for (HexLandView.PlotInfo p : plots) {
            boolean stair = detailed < 4 && distance(p.cells(), vx, vz) <= 64;
            if (stair) detailed++;
            var lines = BoundaryGeometry.outline(p.cells(), stair);
            if (stair) staircaseLines += lines.size(); else straightLines += lines.size();
            g.setColor(color(HexBorderPlanner.relation(view, p, VIEWER).color(), 230));
            g.setStroke(new BasicStroke(stair ? 2.2f : 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, stair ? null : new float[] {7, 5}, 0));
            for (var l : lines) g.draw(new Line2D.Double(sx(l.x1(), scale), sz(l.z1(), scale), sx(l.x2(), scale), sz(l.z2(), scale)));
        }
        // Glow emitters for this viewer at this moment, and the claimable outline.
        var index = new HexBorderIndex(view.plots().values().stream().map(p -> new HexBorderIndex.PlotEntry(p.plotId(), p.cells(), 70)).toList());
        var plotSamples = HexBorderPlanner.plotSamples(view, index, VIEWER, vx, vz);
        var claimable = HexBorderPlanner.claimableCells(view, VIEWER, vx, vz, null, true);
        var claimSamples = HexBorderPlanner.claimableSamples(view, claimable, 70, plotSamples, vx, vz);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 255, 255, 70));
        circle(g, vx, vz, BoundaryGeometry.FAR_DISTANCE, scale);
        circle(g, vx, vz, BoundaryGeometry.CLAIMABLE_DISTANCE, scale);
        for (var s : claimSamples) dot(g, s, color(HexBorderRelation.CLAIMABLE.color(), 255), scale, 5);
        HexBorderRelation[] rel = HexBorderRelation.values();
        for (var s : plotSamples) dot(g, s, color(rel[s.style()].color(), (int) (90 + 165 * BoundaryGeometry.intensity(s.distance()))), scale, 7);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(sx(vx, scale) - 6, sz(vz, scale) - 6, 12, 12));
        legend(g, h - 120, w, title, String.format("glow: %d plot emitters + %d claimable (budget %d + %d) | outline overlay: %d staircase lines, %d straight lines",
            plotSamples.size(), claimSamples.size(), BoundaryGeometry.MAX_EMITTERS_PER_VIEWER, BoundaryGeometry.MAX_CLAIMABLE_EMITTERS_PER_VIEWER, staircaseLines, straightLines));
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    static double distance(PlotCells cells, double x, double z) {
        var b = cells.bounds();
        double dx = Math.max(Math.max(b.x() - x, 0), x - b.endX()), dz = Math.max(Math.max(b.z() - z, 0), z - b.endZ());
        return Math.hypot(dx, dz);
    }

    static double sx(double x, int s) { return (x - MIN_X) * s; }
    static double sz(double z, int s) { return (z - MIN_Z) * s; }

    static void circle(Graphics2D g, double x, double z, double r, int s) {
        g.draw(new Ellipse2D.Double(sx(x - r, s), sz(z - r, s), 2 * r * s, 2 * r * s));
    }

    static void dot(Graphics2D g, BoundaryGeometry.Sample s, Color c, int scale, int size) {
        g.setColor(c);
        g.fill(new Ellipse2D.Double(sx(s.x(), scale) - size / 2.0, sz(s.z(), scale) - size / 2.0, size, size));
    }

    static Color color(int rgb, int alpha) { return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.max(0, Math.min(255, alpha))); }

    static void legend(Graphics2D g, int y, int w, String title, String line) {
        g.setColor(new Color(0x1b1f24)); g.fillRect(0, y, w, 120);
        g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 22)); g.drawString(title, 16, y + 30);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16)); g.setColor(new Color(0xd0d4da)); g.drawString(line, 16, y + 56);
        int x = 16;
        for (HexBorderRelation r : HexBorderRelation.values()) {
            g.setColor(color(r.color(), 255)); g.fillRoundRect(x, y + 74, 26, 26, 6, 6);
            g.setColor(Color.WHITE); g.drawString(r.key().replace('_', ' '), x + 34, y + 93);
            x += 190;
        }
        g.setColor(new Color(0xa39e93)); g.fillRoundRect(x, y + 74, 26, 26, 6, 6);
        g.setColor(Color.WHITE); g.drawString("street", x + 34, y + 93);
    }

    // ---- world map tiles -------------------------------------------------------------------------------------

    /** Plausible terrain for a plain map tile: rolling grass with a gravel road down the street row. */
    static int terrain(int x, int z) {
        double n = Math.sin(x * .09) * Math.cos(z * .07) + .5 * Math.sin((x + z) * .23) + .25 * Math.cos(x * .41 - z * .37);
        boolean road = Math.abs(z - (L.minZ(new HexCoord(0, 0)) + 18) - 3 * Math.sin(x * .03)) < 3.5;
        int r, g, b;
        if (road) { r = 0x9a; g = 0x8b; b = 0x70; }
        else if (n > 1.1) { r = 0x6b; g = 0x8f; b = 0x46; }
        else if (n < -1.0) { r = 0x4a; g = 0x74; b = 0x36; }
        else { r = 0x5a; g = 0x84; b = 0x3e; }
        int shade = (int) (n * 6);
        r = clamp(r + shade); g = clamp(g + shade); b = clamp(b + shade);
        return (r << 24) | (g << 16) | (b << 8) | 0xFF;
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    static MapImage baseTile(int cx, int cz, int size) {
        int[] px = new int[size * size];
        double step = 32.0 / size;
        for (int pz = 0; pz < size; pz++) for (int pxx = 0; pxx < size; pxx++)
            px[pz * size + pxx] = terrain(cx * 32 + (int) Math.floor((pxx + .5) * step), cz * 32 + (int) Math.floor((pz + .5) * step));
        return HexMapPixels.pack(size, size, px);
    }

    static void map(HexLandView view, int size, Path file, String title) throws Exception {
        int c0x = Math.floorDiv(MIN_X, 32), c1x = Math.floorDiv(MAX_X, 32), c0z = Math.floorDiv(MIN_Z, 32), c1z = Math.floorDiv(MAX_Z, 32);
        int tilesX = c1x - c0x + 1, tilesZ = c1z - c0z + 1;
        int zoom = size == 16 ? 6 : 3;
        int pw = tilesX * size * zoom, ph = tilesZ * size * zoom;
        BufferedImage img = new BufferedImage(pw * 2 + 24, ph + 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x1b1f24)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        int painted = 0, bytes = 0, maxPalette = 0;
        for (int cz = c0z; cz <= c1z; cz++) for (int cx = c0x; cx <= c1x; cx++) {
            MapImage base = baseTile(cx, cz, size);
            MapImage done = HexMapOverlayService.render(view, base, cx, cz, VIEWER);
            if (done != null) { painted++; bytes += done.packedIndices.length + 4 * done.palette.length; maxPalette = Math.max(maxPalette, done.palette.length); }
            int[] before = HexMapPixels.unpack(base), after = HexMapPixels.unpack(done == null ? base : done);
            for (int pz = 0; pz < size; pz++) for (int px = 0; px < size; px++) {
                int ox = ((cx - c0x) * size + px) * zoom, oz = ((cz - c0z) * size + pz) * zoom;
                g.setColor(new Color(before[pz * size + px] >>> 8)); g.fillRect(ox, oz, zoom, zoom);
                g.setColor(new Color(after[pz * size + px] >>> 8)); g.fillRect(pw + 24 + ox, oz, zoom, zoom);
            }
        }
        legend(g, ph, img.getWidth(), title, String.format("left: plain tiles | right: as the viewer gets them | %d of %d tiles painted, largest palette %d colours, %d bytes of pixel data",
            painted, tilesX * tilesZ, maxPalette, bytes));
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }
}
