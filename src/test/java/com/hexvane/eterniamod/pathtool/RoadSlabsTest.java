package com.hexvane.eterniamod.pathtool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoadSlabsTest {
    @Test
    void halfBlocksSoftenOneBlockSteps() {
        // A road along x: flat, then up one, then up two at once.
        var ground = List.of(new SplineGeometry.Cell(0, 10, 0), new SplineGeometry.Cell(1, 10, 0), new SplineGeometry.Cell(2, 11, 0), new SplineGeometry.Cell(3, 13, 0));
        Map<SplineGeometry.Column, String> materials = new HashMap<>();
        for (var c : ground) materials.put(c.column(), "Rock_Stone_Cobble");
        var steps = RoadSlabs.steps(ground, materials);
        assertEquals(Map.of(new SplineGeometry.Cell(1, 11, 0), "Rock_Stone_Cobble_Half"), steps, "only the one block step gets a half block, on the lower cell");
    }
}
