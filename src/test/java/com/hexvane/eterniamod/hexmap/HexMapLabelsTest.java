package com.hexvane.eterniamod.hexmap;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.boundary.HexBorderRelation;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class HexMapLabelsTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final int GRASS = 0x5A8A3CFF;
    private static final Map<HexCoord, Integer> LAND = new HashMap<>();
    private static final List<HexMapLabels.Spec> SPECS = new ArrayList<>();

    static {
        plot(0, "Hexvane", new HexCoord(0, 0));                                                    // one cell
        plot(1, "Thornwick Smithy", new HexCoord(2, 0), new HexCoord(3, 0), new HexCoord(2, 1));   // three cells
        List<HexCoord> flower = HexCoord.disk(new HexCoord(-3, 2), 1);
        plot(2, "The Dawn Guard", flower.toArray(HexCoord[]::new));                                // seven cells
        plot(3, "Ka", new HexCoord(1, 2));                                                         // short name
        plot(4, "Bartholomew", new HexCoord(4, 2));                                                // long single word
    }

    private static void plot(int key, String name, HexCoord... cells) {
        List<double[]> centres = new ArrayList<>();
        for (HexCoord c : cells) {
            LAND.put(c, key);
            centres.add(new double[] {L.centerX(c), L.centerZ(c)});
            for (HexCoord o : cells) if (o.r() == c.r() && o.q() == c.q() + 1) centres.add(new double[] {(L.centerX(c) + L.centerX(o)) / 2, L.centerZ(c)});
        }
        SPECS.add(new HexMapLabels.Spec(key, name, centres));
    }

    private static int key(int x, int z) { return LAND.getOrDefault(L.cellAt(x, z), HexMapPainter.NONE); }

    private static int[] tile(int cx, int cz, int size) {
        int[] base = new int[size * size];
        java.util.Arrays.fill(base, GRASS);
        var geometry = HexMapPainter.geometry(cx, cz, size, size, HexMapLabelsTest::key);
        int[] out = HexMapPainter.paint(base, geometry, k -> HexMapPainter.Style.of(k == 0 ? HexBorderRelation.MINE : HexBorderRelation.OTHER_PLAYER));
        HexMapLabels.draw(out, size, size, cx, cz, SPECS, HexMapLabelsTest::key);
        return out;
    }

    private static int textPixels(int size) {
        int n = 0;
        for (int cz = -4; cz <= 4; cz++) for (int cx = -5; cx <= 5; cx++)
            for (int p : tile(cx, cz, size)) if ((p >>> 8) == HexMapLabels.TEXT_RGB) n++;
        return n;
    }

    @Test void namesAreWrittenAtBothMapScales() {
        assertTrue(textPixels(16) > 50);
        assertTrue(textPixels(32) > 50);
        assertTrue(textPixels(96) > 500);
    }

    @Test void longNamesWrapOrShortenToFitInsideThePlot() {
        double bpp = 2;
        var flower = HexMapLabels.place(SPECS.get(2), bpp, 1, HexMapLabelsTest::key);
        assertNotNull(flower);
        assertTrue(flower.whole());
        var single = HexMapLabels.place(SPECS.get(4), bpp, 1, HexMapLabelsTest::key);
        assertNotNull(single);
        assertFalse(single.whole());
        assertTrue(single.lines()[0].length() >= 3, single.lines()[0]);
    }

    @Test void sharpMapsWriteWholeNamesInBigLetters() {
        var one = HexMapLabels.place(SPECS.get(0), 1 / 3.0, 3, HexMapLabelsTest::key);
        assertNotNull(one);
        assertTrue(one.whole());
        assertTrue(one.scale() >= 2, "scale " + one.scale());
    }

    @Test void cleanKeepsLettersDigitsAndSpaces() {
        assertEquals("MIRA'S PLOT 2", HexMapLabels.clean("Mira's_plot  #2"));
    }

    /** Writes pictures to build/hex-labels for a look; not a check. */
    @Test void picture() throws Exception {
        Path dir = Path.of("build", "hex-labels");
        Files.createDirectories(dir);
        for (int size : new int[] {16, 32, 96}) {
            int zoom = size == 16 ? 6 : size == 32 ? 3 : 1, cx0 = -5, cz0 = 0, cols = 11, rows = 4;
            BufferedImage img = new BufferedImage(cols * size * zoom, rows * size * zoom, BufferedImage.TYPE_INT_RGB);
            for (int tz = 0; tz < rows; tz++) for (int tx = 0; tx < cols; tx++) {
                int[] px = tile(cx0 + tx, cz0 + tz, size);
                for (int z = 0; z < size; z++) for (int x = 0; x < size; x++)
                    for (int dz = 0; dz < zoom; dz++) for (int dx = 0; dx < zoom; dx++)
                        img.setRGB((tx * size + x) * zoom + dx, (tz * size + z) * zoom + dz, px[z * size + x] >>> 8);
            }
            ImageIO.write(img, "png", dir.resolve("labels-" + size + ".png").toFile());
        }
    }
}
