package com.hexvane.eterniamod.housing.relocation;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.PlotRect;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

class HexRelocationTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    /** Row 0 (cells q 0..3) is a public road. */
    private static final Set<HexCoord> STREET = HexClaimRules.streetCells(L, List.of(new PlotRect(0, 15, 128, 5)), List.of());
    private static final List<HexCoord> TRIANGLE = List.of(new HexCoord(0, 1), new HexCoord(1, 1), new HexCoord(0, 2));

    private static PlotCells cells(List<HexCoord> list) { return PlotCells.of(L, list); }

    private static HexClaimRules.Land land(Map<HexCoord, UUID> owners) {
        return new HexClaimRules.Land(L, STREET, owners);
    }

    @Test void sameShapeMovesByAnExactBlockOffsetThatCarriesTheBox() {
        var from = cells(TRIANGLE);
        var to = from.translate(new HexCoord(3, 0));
        var move = HexRelocation.move(from, to);
        assertEquals(new HexCoord(3, 0), move.cellOffset());
        assertEquals(96, move.dx());
        assertEquals(0, move.dz());
        // A move to the next row is half a cell east: odd rows are shifted 16 blocks.
        var down = HexRelocation.move(from, from.translate(new HexCoord(0, 1)));
        assertEquals(16, down.dx());
        assertEquals(28, down.dz());
        // Every column of the source maps to the destination, column for column.
        for (int x = from.bounds().x(); x < from.bounds().endX(); x++)
            for (int z = from.bounds().z(); z < from.bounds().endZ(); z++)
                assertEquals(from.containsColumn(x, z), to.containsColumn(x + move.dx(), z + move.dz()));
    }

    @Test void turnedShapesOtherGridsAndOtherSizesAreRefused() {
        var from = cells(TRIANGLE);
        var turned = cells(List.of(new HexCoord(1, 1), new HexCoord(0, 2), new HexCoord(1, 2)));
        assertThrows(IllegalStateException.class, () -> HexRelocation.move(from, turned));
        assertThrows(IllegalStateException.class, () -> HexRelocation.move(from, PlotCells.of(L.withOrigin(4, 0), TRIANGLE)));
        assertThrows(IllegalStateException.class, () -> HexRelocation.move(from, cells(List.of(new HexCoord(0, 1)))));
        assertEquals(HexClaimRules.Reason.RESTORE_SHAPE, HexRelocation.check(land(Map.of()), HexClaimRules.Kind.PUBLIC, from, turned.cells(), Set.of()).reason());
    }

    @Test void destinationCellsMustBeFreeAndKeepTheRoadOrGuildRule() {
        var packed = cells(TRIANGLE);
        var free = land(Map.of());
        assertTrue(HexRelocation.check(free, HexClaimRules.Kind.PUBLIC, packed, TRIANGLE, Set.of()).valid());
        var onStreet = packed.translate(new HexCoord(0, -1));
        assertEquals(HexClaimRules.Reason.STREET, HexRelocation.check(free, HexClaimRules.Kind.PUBLIC, packed, onStreet.cells(), Set.of()).reason());
        var taken = land(Map.of(new HexCoord(1, 1), UUID.randomUUID()));
        assertEquals(HexClaimRules.Reason.TAKEN, HexRelocation.check(taken, HexClaimRules.Kind.PUBLIC, packed, TRIANGLE, Set.of()).reason());
        var reserved = new HexClaimRules.Land(L, STREET, Map.of(), Set.of(), Set.of(new HexCoord(0, 2)));
        assertEquals(HexClaimRules.Reason.RESERVED, HexRelocation.check(reserved, HexClaimRules.Kind.PUBLIC, packed, TRIANGLE, Set.of()).reason());
        var blocked = new HexClaimRules.Land(L, STREET, Map.of(), Set.of(new HexCoord(0, 2)), Set.of());
        assertEquals(HexClaimRules.Reason.BLOCKED, HexRelocation.check(blocked, HexClaimRules.Kind.PUBLIC, packed, TRIANGLE, Set.of()).reason());
        var farFromRoad = packed.translate(new HexCoord(0, 4));
        assertEquals(HexClaimRules.Reason.ROAD, HexRelocation.check(free, HexClaimRules.Kind.PUBLIC, packed, farFromRoad.cells(), Set.of()).reason());
        // A guild member plot joins guild land instead of the road.
        Set<HexCoord> estate = Set.of(new HexCoord(-1, 5));
        assertTrue(HexRelocation.check(free, HexClaimRules.Kind.GUILD_MEMBER, packed, farFromRoad.cells(), estate).valid());
        assertEquals(HexClaimRules.Reason.ANCHOR, HexRelocation.check(free, HexClaimRules.Kind.GUILD_MEMBER, packed, TRIANGLE, estate).reason());
    }

    @Test void destinationsListOnlyValidPlacementsOfTheSameTurnNearestFirst() {
        var packed = cells(TRIANGLE);
        UUID neighbour = UUID.randomUUID();
        var owners = Map.of(new HexCoord(1, 1), neighbour, new HexCoord(2, 1), neighbour);
        var land = land(owners);
        var list = HexRelocation.destinations(land, HexClaimRules.Kind.PUBLIC, packed, Set.of(), new HexCoord(1, 1), 3, 50);
        assertFalse(list.isEmpty());
        Set<PlotCells> unique = new HashSet<>(list);
        assertEquals(list.size(), unique.size(), "no placement listed twice");
        int last = -1;
        for (PlotCells option : list) {
            assertTrue(HexClaimRules.sameShape(packed.cells(), option.cells()), "same shape, same turn");
            assertTrue(HexRelocation.check(land, HexClaimRules.Kind.PUBLIC, packed, option.cells(), Set.of()).valid());
            assertTrue(option.cells().stream().noneMatch(owners::containsKey));
            int d = HexRelocation.distance(option, new HexCoord(1, 1));
            assertTrue(d >= last, "nearest first");
            last = d;
        }
        assertEquals(2, HexRelocation.destinations(land, HexClaimRules.Kind.PUBLIC, packed, Set.of(), new HexCoord(1, 1), 3, 2).size());
        assertTrue(HexRelocation.destinations(land, HexClaimRules.Kind.PUBLIC, packed, Set.of(), new HexCoord(0, 9), 1, 10).isEmpty(), "far from any road");
    }

    @Test void columnMasksSeparateNeighboursWhoseBoxesOverlap() {
        var north = cells(List.of(new HexCoord(0, 1)));
        var south = cells(List.of(new HexCoord(0, 2)));
        assertTrue(north.bounds().overlaps(south.bounds()), "tips interleave");
        var mask = ColumnMask.of(north);
        int overlapColumns = 0;
        for (int x = north.bounds().x(); x < north.bounds().endX(); x++)
            for (int z = north.bounds().z(); z < north.bounds().endZ(); z++) {
                assertFalse(mask.contains(x, z) && south.containsColumn(x, z), "no column in both");
                if (south.containsColumn(x, z)) overlapColumns++;
            }
        assertTrue(overlapColumns > 0);
        assertTrue(ColumnMask.ALL.contains(Integer.MIN_VALUE, Integer.MAX_VALUE));
        var moved = mask.shifted(32, 0);
        var b = north.bounds();
        assertEquals(mask.contains(b.x() + 15, b.z()), moved.contains(b.x() + 47, b.z()));
    }

    @Test void snapshotContentOutsideTheCellsIsRefusedOrTrimmed() {
        var plot = cells(List.of(new HexCoord(0, 1)));
        var box = plot.bounds();
        // Local (0, 0) is the box's north west corner: outside a pointy top cell. (16, 0) is its north tip.
        var doc = new BsonDocument("blocks", new BsonArray(List.of(block(16, 5, 0), block(0, 5, 0))));
        assertThrows(IllegalStateException.class, () -> HexRelocation.requireInside(doc, ColumnMask.of(plot), box.x(), box.z()));
        var kept = HexRelocation.keepInside(doc, ColumnMask.of(plot), box.x(), box.z());
        assertEquals(1, kept.getArray("blocks").size());
        assertEquals(16, kept.getArray("blocks").get(0).asDocument().getInt32("x").getValue());
        assertDoesNotThrow(() -> HexRelocation.requireInside(kept, ColumnMask.of(plot), box.x(), box.z()));
        assertDoesNotThrow(() -> HexRelocation.requireInside(doc, ColumnMask.ALL, box.x(), box.z()), "rectangle snapshots keep their whole box");
        assertEquals(2, doc.getArray("blocks").size(), "input unchanged");
    }

    @Test void grownBaselineKeepsOldGroundAndAddsTheNewHexagon() {
        var old = cells(List.of(new HexCoord(0, 1)));
        var grown = old.with(new HexCoord(1, 1));
        var added = cells(List.of(new HexCoord(1, 1)));
        var ob = bounds(old.bounds());
        var ab = bounds(added.bounds());
        var gb = bounds(grown.bounds());
        var oldDoc = new BsonDocument("blocks", new BsonArray(List.of(block(16, 3, 10))));
        var addedDoc = new BsonDocument("blocks", new BsonArray(List.of(block(16, 3, 10))));
        var merged = HexRelocation.mergeBaseline(oldDoc, ob, addedDoc, ab, gb);
        var blocks = merged.getArray("blocks");
        assertEquals(2, blocks.size());
        assertEquals(16, blocks.get(0).asDocument().getInt32("x").getValue());
        assertEquals(48, blocks.get(1).asDocument().getInt32("x").getValue());
        assertDoesNotThrow(() -> HexRelocation.requireInside(merged, ColumnMask.of(grown), gb.minX(), gb.minZ()));
        assertThrows(IllegalStateException.class, () -> HexRelocation.mergeBaseline(oldDoc, ob, oldDoc, ob, gb), "same ground twice");
        assertEquals(1, oldDoc.getArray("blocks").size(), "input unchanged");
    }

    private static NativeSnapshotStore.Bounds bounds(PlotRect r) {
        return new NativeSnapshotStore.Bounds(r.x(), 0, r.z(), r.endX(), NativeSnapshotStore.MAX_Y, r.endZ());
    }

    private static BsonDocument block(int x, int y, int z) {
        return new BsonDocument("x", new BsonInt32(x)).append("y", new BsonInt32(y)).append("z", new BsonInt32(z)).append("name", new BsonString("Rock_Stone"));
    }
}
