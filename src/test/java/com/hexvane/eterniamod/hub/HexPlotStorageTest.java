package com.hexvane.eterniamod.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HexPlotStorageTest {
    @TempDir Path temp;
    private static final HexLattice L = new HexLattice(32, 20, 4, 8);

    @Test void hexFootprintRoundTripsAndKeepsItsBox() throws Exception {
        var cells = PlotCells.of(L, HexCoord.disk(new HexCoord(2, 3), 1));
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        var plot = new HubPlotRecord(id, "housing", HubPlotFootprint.forCells(cells, 70), owner);
        var file = new HubPlotWorldFile();
        file.setPlots(List.of(plot, new HubPlotRecord(UUID.randomUUID(), "housing", HubPlotFootprint.forCreate(0, 23, 0, 23, 64), UUID.randomUUID())));
        Path path = temp.resolve("hub_plots.json");
        file.write(path);
        String json = Files.readString(path);
        assertTrue(json.contains("\"cells\": \"" + cells.text() + "\""), json);
        assertTrue(json.contains("\"lattice\": \"hex:32:20:4:8\""), json);

        var back = HubPlotWorldFile.readOrEmpty(path).getPlots();
        var hex = back.get(0);
        assertTrue(hex.isHex());
        assertEquals(cells, hex.getCells());
        var box = cells.bounds();
        assertEquals(box.x(), hex.getFootprint().getMinX());
        assertEquals(box.endX() - 1, hex.getFootprint().getMaxX());
        assertEquals(box.z(), hex.getFootprint().getMinZ());
        assertEquals(box.endZ() - 1, hex.getFootprint().getMaxZ());
        assertEquals(70, hex.getFootprint().resolveVisualCenterY());

        // Columns: every column of every cell is in, box corners belonging to neighbours are out.
        for (HexCoord c : cells.cells())
            for (var span : L.spans(c)) {
                assertTrue(hex.containsColumn(span.fromX(), span.z()));
                assertTrue(hex.containsColumn(span.toX() - 1, span.z()));
            }
        assertFalse(hex.containsColumn(box.x(), box.z()), "box corner is not plot land");
        assertFalse(hex.getFootprint().containsBlock(box.x(), 70, box.z()));

        var rect = back.get(1);
        assertFalse(rect.isHex());
        assertNull(rect.getCells());
        assertTrue(rect.containsColumn(0, 0));
        assertTrue(rect.containsColumn(23, 23));

        var grown = hex.getFootprint().withCells(cells.with(new HexCoord(4, 3)));
        assertEquals(8, grown.getCells().size());
        assertEquals(70, grown.resolveVisualCenterY());
    }

    @Test void legacyFilesWithoutCellsStillRead() throws Exception {
        Path path = temp.resolve("legacy.json");
        Files.writeString(path, """
            {"plots":[{"plotId":"6c1f5e44-1111-4c1b-9b3a-1d2e3f4a5b6c","worldName":"hub","ownerUuid":"6c1f5e44-2222-4c1b-9b3a-1d2e3f4a5b6c",
              "footprint":{"minX":0,"minY":60,"minZ":0,"maxX":23,"maxY":90,"maxZ":23,"visualCenterY":64}}]}""");
        var plot = HubPlotWorldFile.readOrEmpty(path).getPlots().getFirst();
        assertFalse(plot.isHex());
        assertTrue(plot.containsColumn(12, 12));
        assertFalse(plot.containsColumn(24, 12));
    }

    @Test void corruptCellsFailLoudlyInsteadOfFallingBackToTheBox() throws Exception {
        Path path = temp.resolve("corrupt.json");
        Files.writeString(path, """
            {"plots":[{"plotId":"6c1f5e44-1111-4c1b-9b3a-1d2e3f4a5b6c","worldName":"hub",
              "footprint":{"minX":0,"minY":60,"minZ":0,"maxX":31,"maxY":90,"maxZ":35,"lattice":"hex:32:20:0:0","cells":"zero"}}]}""");
        var plot = HubPlotWorldFile.readOrEmpty(path).getPlots().getFirst();
        assertThrows(IllegalStateException.class, () -> plot.containsColumn(16, 16));
    }
}
