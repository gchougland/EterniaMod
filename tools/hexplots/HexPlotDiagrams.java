import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexFit;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexShape;
import com.hexvane.eterniamod.housing.PlotRect;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Top down diagrams of the hex plot prototype, drawn from the real prototype classes so the pictures
 * cannot drift from the code. Run after compiling the main classes:
 *
 * <pre>
 *   .\gradlew.bat --no-daemon compileJava
 *   java -cp build/classes/java/main tools/hexplots/HexPlotDiagrams.java notes/pictures/hexplots
 * </pre>
 */
public final class HexPlotDiagrams {
    static final HexLattice L = HexLattice.DEFAULT;
    static final int K = HexLattice.HOUSE_SETBACK;

    static final Color GRASS = new Color(0xE4EBD6), GRID = new Color(0xC9D3B8), CELL_LINE = new Color(0xA9B596);
    static final Color STREET = new Color(0xD9D2C3), ROAD = new Color(0x9C8F7B), ROAD_LINE = new Color(0xF4EEDF);
    static final Color HOUSE = new Color(0x7A5638), SETBACK = new Color(0x7A5638, true), INK = new Color(0x2E2A24), MUTED = new Color(0x6B6457);
    static final Color BORDER = new Color(0x2F8F83);
    static final Color[] PLOT = {new Color(0x9CC5A1), new Color(0xE7C27D), new Color(0xA9B8E0), new Color(0xE3A6A1), new Color(0xC7B0DE), new Color(0x9FD0D6), new Color(0xD9CF8E)};

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "notes/pictures/hexplots");
        Files.createDirectories(out);
        oneCell(out.resolve("01-cell-outline.png"));
        manorPlot(out.resolve("02-seven-cell-plot-manor-22.png"));
        guildEstate(out.resolve("03-guild-estate-keep-38.png"));
        street(out.resolve("04-street-along-curved-road.png"));
        Files.writeString(out.resolve("cell-ascii.txt"), ascii());
        System.out.println("Wrote diagrams to " + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- canvas

    static final class Canvas {
        final BufferedImage image;
        final Graphics2D g;
        final double scale;
        final double originX, originZ;
        final int margin;
        static final int TOP = 70;
        final PlotRect area;

        Canvas(PlotRect area, double scale, int margin, int extraBottom) {
            this.scale = scale; this.margin = margin; this.area = area; originX = area.x(); originZ = area.z();
            image = new BufferedImage((int) Math.ceil(area.width() * scale) + 2 * margin, (int) Math.ceil(area.depth() * scale) + 2 * margin + TOP + extraBottom, BufferedImage.TYPE_INT_RGB);
            g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(GRASS); g.fillRect(0, 0, image.getWidth(), image.getHeight());
            clip();
        }
        void clip() { g.setClip(new Rectangle2D.Double(px(area.x()), pz(area.z()), area.width() * scale, area.depth() * scale)); }
        /** Map drawing is clipped to the area; call before titles and legends. */
        void unclip() { g.setClip(null); }
        double px(double x) { return margin + (x - originX) * scale; }
        double pz(double z) { return TOP + margin + (z - originZ) * scale; }
        /** Fills snap to whole pixels without antialiasing so neighbouring block rows never show seams. */
        void rect(double x0, double z0, double x1, double z1, Color c) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            int a = (int) Math.round(px(x0)), b = (int) Math.round(pz(z0));
            g.setColor(c); g.fillRect(a, b, (int) Math.round(px(x1)) - a, (int) Math.round(pz(z1)) - b);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        }
        void block(int x, int z, Color c) { rect(x, z, x + 1, z + 1, c); }
        void cellFill(HexCoord cell, Color c) { for (var s : L.spans(cell)) rect(s.fromX(), s.z(), s.toX(), s.z() + 1, c); }
        void outline(Collection<HexCoord> cells, Color c, float width) {
            g.setColor(c); g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (var e : L.borderEdges(cells)) {
                if (e.alongX()) g.draw(new Line2D.Double(px(e.x()), pz(e.z()), px(e.x() + 1), pz(e.z())));
                else g.draw(new Line2D.Double(px(e.x()), pz(e.z()), px(e.x()), pz(e.z() + 1)));
            }
        }
        void lattice(PlotRect area, float width) {
            Set<HexCoord> cells = new LinkedHashSet<>();
            for (int z = area.z(); z < area.endZ(); z += 4) for (int x = area.x(); x < area.endX(); x += 4) cells.add(L.cellAt(x, z));
            for (HexCoord c : cells) outline(List.of(c), CELL_LINE, width);
        }
        void house(PlotRect h, String label, float fontSize) {
            g.setStroke(new BasicStroke(Math.max(1f, (float) scale * .35f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {(float) scale * 1.5f, (float) scale}, 0f));
            g.setColor(new Color(0x7A5638));
            g.draw(new Rectangle2D.Double(px(h.x() - K), pz(h.z() - K), (h.width() + 2 * K) * scale, (h.depth() + 2 * K) * scale));
            g.setColor(new Color(122, 86, 56, 205));
            g.fill(new Rectangle2D.Double(px(h.x()), pz(h.z()), h.width() * scale, h.depth() * scale));
            g.setStroke(new BasicStroke(2f)); g.setColor(new Color(0x4A3322));
            g.draw(new Rectangle2D.Double(px(h.x()), pz(h.z()), h.width() * scale, h.depth() * scale));
            text(label, px(h.x() + h.width() / 2.0), pz(h.z() + h.depth() / 2.0), fontSize, Color.WHITE, true);
        }
        void text(String s, double cx, double cy, float size, Color c, boolean bold) {
            g.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, Math.round(size)));
            var m = g.getFontMetrics();
            String[] lines = s.split("\n");
            double y = cy - (lines.length * m.getHeight()) / 2.0 + m.getAscent();
            for (String line : lines) { g.setColor(c); g.drawString(line, (float) (cx - m.stringWidth(line) / 2.0), (float) y); y += m.getHeight(); }
        }
        void textLeft(String s, double x, double y, float size, Color c, boolean bold) {
            g.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, Math.round(size)));
            g.setColor(c); g.drawString(s, (float) x, (float) y);
        }
        void legend(double x, double y, Object... entries) {
            unclip();
            double cy = y;
            for (int i = 0; i < entries.length; i += 2) {
                Color c = (Color) entries[i];
                g.setColor(c); g.fill(new Rectangle2D.Double(x, cy - 13, 18, 16));
                g.setColor(MUTED); g.setStroke(new BasicStroke(1f)); g.draw(new Rectangle2D.Double(x, cy - 13, 18, 16));
                textLeft((String) entries[i + 1], x + 26, cy, 15, INK, false);
                cy += 24;
            }
        }
        void title(String title, String subtitle) {
            unclip();
            textLeft(title, margin, 30, 22, INK, true);
            if (subtitle != null) textLeft(subtitle, margin, 54, 15, MUTED, false);
            clip();
        }
        void save(Path path) throws IOException { g.dispose(); ImageIO.write(image, "png", path.toFile()); }
    }

    static PlotRect pad(PlotRect r, int by) { return new PlotRect(r.x() - by, r.z() - by, r.width() + 2 * by, r.depth() + 2 * by); }

    static Set<HexCoord> translate(Collection<HexCoord> cells, HexCoord by) {
        Set<HexCoord> out = new LinkedHashSet<>();
        for (HexCoord c : cells) out.add(c.plus(by));
        return out;
    }

    // ---------------------------------------------------------------- 1. one cell

    static void oneCell(Path path) throws IOException {
        HexCoord o = HexCoord.ORIGIN;
        PlotRect area = pad(L.bounds(o), 6);
        Canvas c = new Canvas(area, 18, 90, 150);
        c.title("One hexagon, cut from blocks", "Pointy ends face north and south. Every row is whole blocks, so hexagons tile with no gaps.");
        for (HexCoord n : o.neighbors()) c.cellFill(n, new Color(0xEDF1E4));
        c.cellFill(o, PLOT[0]);
        PlotRect house = HexFit.nearestFit(L, Set.of(o), 14, 14, K).orElseThrow();
        // Block grid
        c.g.setStroke(new BasicStroke(1f)); c.g.setColor(new Color(0, 0, 0, 28));
        for (int x = area.x(); x <= area.endX(); x++) c.g.draw(new Line2D.Double(c.px(x), c.pz(area.z()), c.px(x), c.pz(area.endZ())));
        for (int z = area.z(); z <= area.endZ(); z++) c.g.draw(new Line2D.Double(c.px(area.x()), c.pz(z), c.px(area.endX()), c.pz(z)));
        for (HexCoord n : o.neighbors()) c.outline(List.of(n), CELL_LINE, 2f);
        c.house(house, "14 x 14\nhouse", 20);
        c.outline(List.of(o), BORDER, 4f);
        c.g.setClip(null);
        // Dimensions
        PlotRect b = L.bounds(o);
        double top = c.pz(b.z()) - 22;
        c.g.setColor(INK); c.g.setStroke(new BasicStroke(1.5f));
        c.g.draw(new Line2D.Double(c.px(b.x()), c.pz(b.z() + 8) , c.px(b.x()), c.pz(b.z()) - 30));
        c.g.draw(new Line2D.Double(c.px(b.endX()), c.pz(b.z() + 8), c.px(b.endX()), c.pz(b.z()) - 30));
        c.g.draw(new Line2D.Double(c.px(b.x()), top, c.px(b.endX()), top));
        c.text("32 blocks across the flat sides", (c.px(b.x()) + c.px(b.endX())) / 2, top - 12, 16, INK, true);
        double right = c.px(b.endX()) + 40;
        c.g.draw(new Line2D.Double(right, c.pz(b.z()), right, c.pz(b.endZ())));
        c.g.draw(new Line2D.Double(c.px(b.x() + 16), c.pz(b.z()), right + 6, c.pz(b.z())));
        c.g.draw(new Line2D.Double(c.px(b.x() + 16), c.pz(b.endZ()), right + 6, c.pz(b.endZ())));
        var old = c.g.getTransform();
        c.g.rotate(Math.PI / 2, right + 16, c.pz(b.z() + 18));
        c.text("36 blocks tip to tip", right + 16, c.pz(b.z() + 18), 16, INK, true);
        c.g.setTransform(old);
        c.text("tip: 2 blocks", c.px(b.x() + 16), c.pz(b.z() + 3), 14, INK, false);
        c.text("2 : 1 stairs", c.px(b.x() + 4), c.pz(b.z() + 2), 14, INK, true);
        c.text("20 block\nflat side", c.px(b.x()) - 34, c.pz(b.z() + 18), 14, INK, true);
        double y = c.image.getHeight() - 120;
        c.legend(c.margin, y, PLOT[0], "One hexagon: 896 blocks (a free plot today is 24 x 24 = 576)",
            new Color(122, 86, 56, 205), "Largest square house: 14 x 14, same as today",
            new Color(0xEDF1E4), "Neighbouring hexagons share a side exactly, no gaps");
        c.textLeft("Dashed line: five block setback from the plot border, as today.", c.margin, y + 74, 15, MUTED, false);
        c.save(path);
    }

    // ---------------------------------------------------------------- 2. seven cell plot with a 22 house

    static void manorPlot(Path path) throws IOException {
        HexCoord centre = new HexCoord(1, 2);
        Set<HexCoord> plot = Set.copyOf(HexCoord.disk(centre, 1));
        Set<HexCoord> houseCells = Set.of(centre, centre.neighbor(0), centre.neighbor(5));
        PlotRect area = pad(L.bounds(plot), 22);
        Canvas c = new Canvas(area, 7, 40, 150);
        c.title("A plot of seven hexagons with a 22 x 22 manor", "The manor stands on three hexagons. The other four are yard for props and, later, generators.");
        // Street row to the north
        Set<HexCoord> street = new LinkedHashSet<>();
        int roadZ = L.minZ(centre) - L.rowPitch() - 6;
        PlotRect roadRect = new PlotRect(area.x(), roadZ, area.width(), 5);
        street.addAll(L.cellsTouching(roadRect));
        for (HexCoord s : street) c.cellFill(s, STREET);
        for (int z = roadRect.z(); z < roadRect.endZ(); z++) for (int x = roadRect.x(); x < roadRect.endX(); x++) c.block(x, z, ROAD);
        c.lattice(area, 1.2f);
        for (HexCoord h : plot) c.cellFill(h, houseCells.contains(h) ? PLOT[1] : PLOT[0]);
        for (HexCoord h : plot) c.outline(List.of(h), new Color(255, 255, 255, 170), 1.5f);
        PlotRect manor = HexFit.nearestFit(L, houseCells, 22, 22, K).orElseThrow();
        if (!HexFit.fits(L, plot, manor, K)) throw new IllegalStateException("manor must fit");
        c.house(manor, "22 x 22\nmanor", 15);
        for (HexCoord h : plot) if (!houseCells.contains(h)) {
            c.text("yard", c.px(L.centerX(h)), c.pz(L.centerZ(h)) - 6, 15, INK, true);
            props(c, h, 6, 11);
        }
        c.outline(plot, BORDER, 4f);
        c.text("public road", c.px(area.x() + area.width() / 2.0), c.pz(roadZ + 2.5), 14, ROAD_LINE, true);
        double y = c.image.getHeight() - 110;
        c.legend(c.margin, y, PLOT[1], "House hexagons (the manor's three)", PLOT[0], "Yard hexagons: props and generators, a set number per hexagon",
            STREET, "Street hexagons: public, never claimed", BORDER, "Plot border glow follows the outer edge only");
        c.save(path);
    }

    static void props(Canvas c, HexCoord cell, int count, long seed) {
        java.util.Random random = new java.util.Random(seed * 31 + cell.q() * 7L + cell.r());
        int placed = 0;
        var spans = L.spans(cell);
        while (placed < count) {
            var s = spans.get(4 + random.nextInt(spans.size() - 8));
            int x = s.fromX() + 3 + random.nextInt(Math.max(1, s.length() - 6));
            if (Math.abs(s.z() - L.centerZ(cell)) < 3) continue;
            double r = c.scale * 1.3;
            c.g.setColor(random.nextBoolean() ? new Color(0x4F7A4A) : new Color(0xB07A3E));
            c.g.fill(new Ellipse2D.Double(c.px(x + .5) - r, c.pz(s.z() + .5) - r, 2 * r, 2 * r));
            placed++;
        }
    }

    // ---------------------------------------------------------------- 3. guild estate

    static void guildEstate(Path path) throws IOException {
        HexCoord centre = new HexCoord(1, 3);
        Set<HexCoord> keepCells = Set.copyOf(HexCoord.disk(centre, 1));
        Set<HexCoord> estate = new LinkedHashSet<>(keepCells);
        // Three later expansions grown by the guild, each sharing a side with the estate.
        List<HexCoord> grown = List.of(centre.plus(new HexCoord(2, 0)), centre.plus(new HexCoord(2, -1)), centre.plus(new HexCoord(-1, 2)));
        estate.addAll(grown);
        int roadZ = L.minZ(centre) - 2 * L.rowPitch() + 14;
        PlotRect road = new PlotRect(L.minX(centre) - 140, roadZ, 320, 5);
        Set<HexCoord> street = new LinkedHashSet<>(L.cellsTouching(road));
        Map<HexCoord, UUID> owners = new HashMap<>();
        UUID estateId = UUID.randomUUID();
        var land = new HexClaimRules.Land(L, street, Map.of());
        require(HexClaimRules.claim(land, HexClaimRules.Kind.GUILD_ESTATE, false, keepCells, Set.of()));
        for (HexCoord k : keepCells) owners.put(k, estateId);
        for (HexCoord gcell : grown) {
            require(HexClaimRules.expand(new HexClaimRules.Land(L, street, owners), HexClaimRules.Kind.GUILD_ESTATE, estateId, gcell));
            owners.put(gcell, estateId);
        }
        // Member plots join the estate's side instead of needing the road.
        List<Set<HexCoord>> members = List.of(Set.of(centre.plus(new HexCoord(-2, 1))), Set.of(centre.plus(new HexCoord(0, 2)), centre.plus(new HexCoord(0, 3)), centre.plus(new HexCoord(1, 2))));
        for (Set<HexCoord> m : members) {
            require(HexClaimRules.claim(new HexClaimRules.Land(L, street, owners), HexClaimRules.Kind.GUILD_MEMBER, m.size() == 3, m, estate));
            UUID id = UUID.randomUUID();
            for (HexCoord h : m) owners.put(h, id);
        }
        Set<HexCoord> everything = new HashSet<>(estate);
        members.forEach(everything::addAll);
        PlotRect area = pad(L.bounds(everything), 10);
        area = new PlotRect(area.x(), Math.min(area.z(), roadZ - 30), area.width(), area.endZ() - Math.min(area.z(), roadZ - 30));
        Canvas c = new Canvas(area, 4.5, 40, 150);
        c.title("Guild estate: seven hexagons to start, a 38 x 38 keep in the middle", "Grown by three hexagons, with two member plots joined to its side.");
        for (HexCoord s : street) c.cellFill(s, STREET);
        for (int z = road.z(); z < road.endZ(); z++) for (int x = Math.max(road.x(), area.x()); x < Math.min(road.endX(), area.endX()); x++) c.block(x, z, ROAD);
        c.lattice(area, 1f);
        for (HexCoord h : keepCells) c.cellFill(h, PLOT[1]);
        for (HexCoord h : grown) c.cellFill(h, PLOT[0]);
        for (int i = 0; i < members.size(); i++) for (HexCoord h : members.get(i)) c.cellFill(h, PLOT[2 + i]);
        for (HexCoord h : everything) c.outline(List.of(h), new Color(255, 255, 255, 160), 1.2f);
        PlotRect keep = HexFit.nearestFit(L, keepCells, 38, 38, K).orElseThrow();
        c.house(keep, "38 x 38\nkeep", 16);
        for (HexCoord h : grown) { c.text("grown", c.px(L.centerX(h)), c.pz(L.centerZ(h)) - 4, 14, INK, true); props(c, h, 5, 3); }
        Set<HexCoord> m0 = members.get(0), m1 = members.get(1);
        PlotRect cottage = HexFit.nearestFit(L, m0, 9, 9, K).orElseThrow();
        c.house(cottage, "9 x 9", 11);
        PlotRect manor = HexFit.nearestFit(L, m1, 22, 22, K).orElseThrow();
        c.house(manor, "22 x 22", 12);
        c.outline(estate, BORDER, 3.5f);
        c.outline(m0, BORDER, 2.5f);
        c.outline(m1, BORDER, 2.5f);
        c.text("public road", c.px(area.x() + area.width() / 2.0), c.pz(roadZ + 2.5), 13, ROAD_LINE, true);
        double y = c.image.getHeight() - 110;
        c.legend(c.margin, y, PLOT[1], "Keep: the guild's starting seven, a flower around one centre", PLOT[0], "Hexagons the guild added later",
            PLOT[2], "Member plot of one hexagon, touching the estate", PLOT[3], "Member plot of three hexagons (manor)");
        c.save(path);
    }

    static void require(HexClaimRules.Result r) { if (!r.valid()) throw new IllegalStateException(r.code() + ": " + r.message()); }

    // ---------------------------------------------------------------- 4. street along a curved road

    static void street(Path path) throws IOException {
        double[][] ctrl = {{-160, 190}, {-60, 160}, {40, 120}, {130, 60}, {230, 70}, {320, 140}, {420, 150}, {520, 100}, {620, 80}};
        List<double[]> line = spline(ctrl, 40);
        Set<Long> surface = new HashSet<>();
        for (double[] p : line)
            for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
                int x = (int) Math.floor(p[0]) + dx, z = (int) Math.floor(p[1]) + dz;
                if (Math.hypot(x + .5 - p[0], z + .5 - p[1]) <= 2.5) surface.add(key(x, z));
            }
        Set<HexCoord> street = new LinkedHashSet<>();
        for (long k : surface) street.add(L.cellAt((int) (k >> 32), (int) k));
        var empty = new HexClaimRules.Land(L, street, Map.of());
        // Frontage cells ordered along the road.
        Map<HexCoord, Integer> order = new LinkedHashMap<>();
        Set<HexCoord> frontage = new LinkedHashSet<>();
        for (HexCoord s : street) for (HexCoord n : s.neighbors()) if (empty.frontage(n)) frontage.add(n);
        for (HexCoord f : frontage) {
            int best = 0; double bestD = Double.MAX_VALUE;
            for (int i = 0; i < line.size(); i++) { double d = Math.hypot(line.get(i)[0] - L.centerX(f), line.get(i)[1] - L.centerZ(f)); if (d < bestD) { bestD = d; best = i; } }
            order.put(f, best);
        }
        List<HexCoord> along = new ArrayList<>(frontage);
        along.sort(Comparator.comparingInt(order::get));
        PlotRect area = new PlotRect(0, 0, 460, 240);
        along.removeIf(f -> !area.contains((int) L.centerX(f), (int) L.centerZ(f)));

        Map<HexCoord, UUID> owners = new HashMap<>();
        List<Set<HexCoord>> plots = new ArrayList<>();
        List<String> houses = new ArrayList<>();
        // A mix: cottages, manors, a grown plot, a guild estate; some lots left open.
        String[] pattern = {"cottage", "manor", "open", "cottage", "grown", "guild", "cottage", "open", "manor", "cottage", "open", "manor", "cottage", "grown", "cottage", "open"};
        int p = 0;
        for (HexCoord f : along) {
            if (owners.containsKey(f) || street.contains(f)) continue;
            String want = pattern[p % pattern.length];
            Set<HexCoord> chosen = switch (want) {
                case "open" -> Set.of();
                case "cottage" -> Set.of(f);
                case "manor" -> pick(HexShape.TRIANGLE.placementsCovering(f), owners, street, frontage, empty);
                case "grown" -> grow(f, 4, owners, street, frontage);
                case "guild" -> pick(HexShape.FLOWER.placementsCovering(f), owners, street, frontage, empty);
                default -> throw new IllegalStateException();
            };
            p++;
            if (chosen == null || chosen.isEmpty()) continue;
            if (!chosen.stream().allMatch(h -> area.contains(L.bounds(h)))) continue;
            var kind = want.equals("guild") ? HexClaimRules.Kind.GUILD_ESTATE : HexClaimRules.Kind.PUBLIC;
            var land = new HexClaimRules.Land(L, street, owners);
            if (want.equals("grown")) {
                require(HexClaimRules.claim(land, kind, false, Set.of(f), Set.of()));
            } else require(HexClaimRules.claim(land, kind, want.equals("manor"), chosen, Set.of()));
            UUID id = UUID.randomUUID();
            for (HexCoord h : chosen) owners.put(h, id);
            plots.add(chosen); houses.add(want);
        }

        Canvas c = new Canvas(area, 3, 40, 150);
        c.title("A street of hexagon plots along a curved road", "Hexagons the road touches stay public. Every plot starts on a hexagon beside the street.");
        for (HexCoord s : street) c.cellFill(s, STREET);
        for (HexCoord f : frontage) if (!owners.containsKey(f)) c.cellFill(f, new Color(0xEEF2E2));
        for (long k : surface) c.block((int) (k >> 32), (int) k, ROAD);
        c.lattice(area, 1f);
        for (int i = 0; i < plots.size(); i++) {
            Color col = switch (houses.get(i)) { case "guild" -> PLOT[1]; case "manor" -> PLOT[2]; case "grown" -> PLOT[4]; default -> PLOT[0]; };
            for (HexCoord h : plots.get(i)) c.cellFill(h, col);
        }
        for (HexCoord f : frontage) if (!owners.containsKey(f) && area.contains((int) L.centerX(f), (int) L.centerZ(f)))
            c.text("open", c.px(L.centerX(f)), c.pz(L.centerZ(f)), 11, MUTED, false);
        for (int i = 0; i < plots.size(); i++) {
            Set<HexCoord> cells = plots.get(i);
            String h = houses.get(i);
            int side = switch (h) { case "guild" -> 38; case "manor" -> 22; default -> 14; };
            Set<HexCoord> houseCells = cells;
            if (h.equals("grown")) houseCells = Set.of(cells.iterator().next());
            PlotRect house = HexFit.nearestFit(L, houseCells, side, side, K).orElseThrow();
            c.g.setColor(new Color(122, 86, 56, 215));
            c.g.fill(new Rectangle2D.Double(c.px(house.x()), c.pz(house.z()), house.width() * c.scale, house.depth() * c.scale));
            c.outline(cells, BORDER, 2.2f);
        }
        // Road centre line
        c.g.setColor(ROAD_LINE); c.g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {6f, 6f}, 0f));
        for (int i = 1; i < line.size(); i++) c.g.draw(new Line2D.Double(c.px(line.get(i - 1)[0]), c.pz(line.get(i - 1)[1]), c.px(line.get(i)[0]), c.pz(line.get(i)[1])));
        double y = c.image.getHeight() - 112;
        c.legend(c.margin, y, PLOT[0], "Cottage plot: one hexagon", PLOT[2], "Manor plot: three hexagons", PLOT[4], "Grown plot: cottage plus three added hexagons",
            PLOT[1], "Guild estate: seven hexagons");
        c.legend(c.margin + 470, y, STREET, "Street hexagons (public)", new Color(0xEEF2E2), "Open lots beside the road", new Color(122, 86, 56, 215), "House footprint");
        c.save(path);
    }

    static Set<HexCoord> pick(List<Set<HexCoord>> options, Map<HexCoord, UUID> owners, Set<HexCoord> street, Set<HexCoord> frontage, HexClaimRules.Land land) {
        Set<HexCoord> best = null; long bestFrontage = Long.MAX_VALUE;
        for (Set<HexCoord> o : options) {
            if (o.stream().anyMatch(h -> owners.containsKey(h) || street.contains(h))) continue;
            long used = o.stream().filter(frontage::contains).count();
            if (used < bestFrontage) { best = o; bestFrontage = used; }
        }
        return best;
    }

    static Set<HexCoord> grow(HexCoord start, int size, Map<HexCoord, UUID> owners, Set<HexCoord> street, Set<HexCoord> frontage) {
        Set<HexCoord> cells = new LinkedHashSet<>(List.of(start));
        while (cells.size() < size) {
            HexCoord next = null;
            for (HexCoord f : com.hexvane.eterniamod.hexplots.HexContiguity.frontier(cells))
                if (!owners.containsKey(f) && !street.contains(f) && !frontage.contains(f)) { next = f; break; }
            if (next == null) return Set.of(start);
            cells.add(next);
        }
        return cells;
    }

    static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    static List<double[]> spline(double[][] p, int steps) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i + 3 < p.length; i++) {
            for (int s = 0; s < steps; s++) {
                double t = s / (double) steps, t2 = t * t, t3 = t2 * t;
                double[] q = new double[2];
                for (int k = 0; k < 2; k++)
                    q[k] = .5 * (2 * p[i + 1][k] + (-p[i][k] + p[i + 2][k]) * t + (2 * p[i][k] - 5 * p[i + 1][k] + 4 * p[i + 2][k] - p[i + 3][k]) * t2 + (-p[i][k] + 3 * p[i + 1][k] - 3 * p[i + 2][k] + p[i + 3][k]) * t3);
                out.add(q);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- ASCII

    static String ascii() {
        HexCoord o = HexCoord.ORIGIN;
        StringBuilder sb = new StringBuilder();
        PlotRect b = L.bounds(o);
        sb.append("One cell, 32 x 36 blocks, 896 blocks. '#' = this cell, '.' = neighbours. North is up.\n");
        for (int z = b.z(); z < b.endZ(); z++) {
            sb.append(String.format("%2d ", z - b.z()));
            for (int x = b.x(); x < b.endX(); x++) sb.append(L.contains(o, x, z) ? '#' : '.');
            sb.append('\n');
        }
        return sb.toString();
    }
}
