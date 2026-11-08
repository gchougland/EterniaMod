package com.hexvane.eterniamod.hexmap;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.boundary.HexBorderRelation;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HexMapPainterTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final int GRASS = 0x5A8A3CFF;

    /** Plot 0 on cell (0,0), plot 1 on its east neighbour (1,0), street on (0,1). */
    private static int key(int x, int z) {
        HexCoord c = L.cellAt(x, z);
        if (c.equals(HexCoord.ORIGIN)) return 0;
        if (c.equals(new HexCoord(1, 0))) return 1;
        if (c.equals(new HexCoord(0, 1))) return HexMapPainter.STREET;
        return HexMapPainter.NONE;
    }

    private static final Map<Integer, HexMapPainter.Style> STYLES = Map.of(
        0, HexMapPainter.Style.of(HexBorderRelation.MINE), 1, HexMapPainter.Style.of(HexBorderRelation.OTHER_PLAYER));

    private static int[] grass(int n) { int[] p = new int[n]; java.util.Arrays.fill(p, GRASS); return p; }

    @Test void defaultScaleTileHasOnePixelBordersBothColoursOnASharedSideAndASoftFill() {
        // Tile (0,0) covers blocks 0..31: the origin cell's west half, its east neighbour's west part at x >= 32? no:
        // the shared flat side of (0,0) and (1,0) is at x = 32, the tile's east edge, so use tile 0 and tile 1.
        var west = HexMapPainter.geometry(0, 0, 16, 16, HexMapPainterTest::key);
        var east = HexMapPainter.geometry(1, 0, 16, 16, HexMapPainterTest::key);
        assertEquals(1, HexMapPainter.thickness(16));
        int row = 8; // block z = 17, inside the flat body
        assertEquals(HexMapPainter.KIND_BORDER, west.kinds()[row * 16 + 15], "last pixel before x = 32 is plot 0's border");
        assertEquals(HexMapPainter.KIND_FILL, west.kinds()[row * 16 + 14], "one pixel wide");
        assertEquals(HexMapPainter.KIND_BORDER, east.kinds()[row * 16], "first pixel after x = 32 is plot 1's border");
        assertEquals(HexMapPainter.KIND_FILL, east.kinds()[row * 16 + 1]);
        assertEquals(HexMapPainter.KIND_BORDER, west.kinds()[row * 16], "the cell's own west side at x = 0");

        int[] painted = HexMapPainter.paint(grass(256), west, k -> STYLES.get(k));
        assertEquals((0x2EC4B6 << 8) | 0xFF, painted[row * 16 + 15], "border is the exact palette colour");
        int fill = painted[row * 16 + 7];
        assertNotEquals(GRASS, fill);
        assertEquals(HexMapPainter.mix(GRASS, 0x2EC4B6, HexMapPainter.FILL_OWN), fill, "fill is a see through tint, not a flat colour");
        int[] paintedEast = HexMapPainter.paint(grass(256), east, k -> STYLES.get(k));
        assertEquals((0x8FB8DE << 8) | 0xFF, paintedEast[row * 16]);
    }

    @Test void shadowOutsidePlotsStreetTintAndUntouchedLand() {
        // Tile (-1,0) holds the west neighbour cell (-1,0) (free land) and touches the origin plot at x = 0.
        var g = HexMapPainter.geometry(-1, 0, 16, 16, HexMapPainterTest::key);
        int row = 8;
        assertEquals(HexMapPainter.KIND_SHADOW, g.kinds()[row * 16 + 15], "dark line just outside the border");
        assertEquals(HexMapPainter.KIND_NONE, g.kinds()[row * 16 + 10]);
        int[] p = HexMapPainter.paint(grass(256), g, k -> STYLES.get(k));
        assertEquals(GRASS, p[row * 16 + 10], "free land is untouched");
        assertTrue(brightness(p[row * 16 + 15]) < brightness(GRASS));
        // Street cell (0,1): its centre is at x 16 + 16 = 32? (0,1) is in an odd row, shifted 16 east: spans x 16..48, z 28..64.
        var s = HexMapPainter.geometry(1, 1, 16, 16, HexMapPainterTest::key);
        assertEquals(HexMapPainter.KIND_STREET, s.kinds()[8 * 16 + 1]);
        int[] sp = HexMapPainter.paint(grass(256), s, k -> STYLES.get(k));
        assertEquals(HexMapPainter.mix(GRASS, 0xA39E93, HexMapPainter.STREET_TINT), sp[8 * 16 + 1]);
    }

    @Test void emptyTilesAndViewersWithoutAStyleChangeNothing() {
        var far = HexMapPainter.geometry(40, 40, 16, 16, HexMapPainterTest::key);
        assertTrue(far.isEmpty());
        var west = HexMapPainter.geometry(0, 0, 16, 16, HexMapPainterTest::key);
        int[] p = HexMapPainter.paint(grass(256), west, k -> null);
        for (int i = 0; i < p.length; i++) if (west.kinds()[i] == HexMapPainter.KIND_BORDER || west.kinds()[i] == HexMapPainter.KIND_FILL) assertEquals(GRASS, p[i]);
    }

    @Test void fullScaleTilesUseTwoPixelBordersAndPackRoundTrips() {
        assertEquals(2, HexMapPainter.thickness(32));
        var g = HexMapPainter.geometry(0, 0, 32, 32, HexMapPainterTest::key);
        int row = 17;
        assertEquals(HexMapPainter.KIND_BORDER, g.kinds()[row * 32 + 31]);
        assertEquals(HexMapPainter.KIND_BORDER, g.kinds()[row * 32 + 30]);
        assertEquals(HexMapPainter.KIND_FILL, g.kinds()[row * 32 + 29]);
        int[] base = grass(1024);
        base[5] = 0x223344FF;
        int[] painted = HexMapPainter.paint(base, g, k -> STYLES.get(k));
        var image = HexMapPixels.pack(32, 32, painted);
        assertArrayEquals(painted, HexMapPixels.unpack(image));
        assertTrue(HexMapPixels.hasPixelData(image));
        assertEquals(4, HexMapPixels.bitsFor(16));
        assertEquals(8, HexMapPixels.bitsFor(17));
    }

    private static int brightness(int rgba) { return ((rgba >>> 24) & 0xFF) + ((rgba >>> 16) & 0xFF) + ((rgba >>> 8) & 0xFF); }
}
