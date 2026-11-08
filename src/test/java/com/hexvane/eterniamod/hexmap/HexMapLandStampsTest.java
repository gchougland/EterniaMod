package com.hexvane.eterniamod.hexmap;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandEvents;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HexMapLandStampsTest {
    private static final HexLattice L = HexLattice.DEFAULT;

    @AfterEach void clean() { HexLandEvents.clearListeners(); }

    @Test void aClaimMovesOnlyTheTilesUnderItsCells() {
        var stamps = new HexMapLandStamps();
        long under = stamps.stamp(0, 0), beside = stamps.stamp(1, 1), far = stamps.stamp(10, 10);
        stamps.apply(new HexLandEvents.Change("hub", Set.of(HexCoord.ORIGIN), HexLandEvents.Cause.CLAIM), L);
        // Cell (0,0) spans x 0..32, z 0..36 plus the 2 block shadow margin: tiles x -1..1, z -1..1.
        assertNotEquals(under, stamps.stamp(0, 0));
        assertNotEquals(beside, stamps.stamp(1, 1));
        assertNotEquals(beside, stamps.stamp(-1, -1));
        assertEquals(far, stamps.stamp(10, 10));
        assertEquals(stamps.stamp(2, 0), new HexMapLandStamps().stamp(2, 0), "x 64 is outside the margin");
        long once = stamps.stamp(0, 0);
        stamps.apply(new HexLandEvents.Change("hub", Set.of(HexCoord.ORIGIN), HexLandEvents.Cause.UNCLAIM), L);
        assertNotEquals(once, stamps.stamp(0, 0), "unclaiming repaints too");
    }

    @Test void latticeChangesMoveEveryTile() {
        var stamps = new HexMapLandStamps();
        long far = stamps.stamp(50, -50);
        stamps.apply(new HexLandEvents.Change("hub", Set.of(), HexLandEvents.Cause.LATTICE), L);
        assertNotEquals(far, stamps.stamp(50, -50));
        assertEquals(1, stamps.changes());
    }

    @Test void eventsFiredByTheCoreReachTheStamps() {
        var stamps = new HexMapLandStamps();
        Runnable stop = HexLandEvents.listen(change -> { if (change.world().equals("hub")) stamps.apply(change, L); });
        long before = stamps.stamp(0, 0);
        HexLandEvents.fire("other", Set.of(HexCoord.ORIGIN), HexLandEvents.Cause.CLAIM);
        assertEquals(before, stamps.stamp(0, 0), "another world's change is ignored");
        HexLandEvents.fire("hub", Set.of(HexCoord.ORIGIN), HexLandEvents.Cause.EXPAND);
        assertNotEquals(before, stamps.stamp(0, 0));
        stop.run();
        long after = stamps.stamp(0, 0);
        HexLandEvents.fire("hub", Set.of(HexCoord.ORIGIN), HexLandEvents.Cause.CLAIM);
        assertEquals(after, stamps.stamp(0, 0), "stopped listener");
    }

    @Test void candidateTilesCoverPlotsAndStreets() {
        var plot = new HexLandView.PlotInfo(UUID.randomUUID(), HexLandView.OwnerKind.PLAYER, UUID.randomUUID(), null, PlotCells.of(L, List.of(HexCoord.ORIGIN)));
        var view = new HexLandView("hub", L, Set.of(new HexCoord(5, 0)), Set.of(), Set.of(), List.of(plot));
        var tiles = HexMapOverlayService.candidateTiles(view);
        assertTrue(tiles.contains(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(0, 0)));
        assertTrue(tiles.contains(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(5, 0)), "street cell (5,0) spans x 160..192");
        assertFalse(tiles.contains(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(20, 20)));
        long[] order = HexMapOverlayService.nearest(tiles, tiles, 5, 0);
        assertEquals(5, com.hypixel.hytale.math.util.ChunkUtil.xOfChunkIndex(order[0]));
    }
}
