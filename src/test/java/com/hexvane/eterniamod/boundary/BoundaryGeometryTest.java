package com.hexvane.eterniamod.boundary;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class BoundaryGeometryTest {
    private final BoundaryGeometry.Rectangle plot = new BoundaryGeometry.Rectangle(0, 0, 24, 24, 70);

    @Test void proximityMeasuresTheEdgeEvenInsideAPlot() {
        assertEquals(12, BoundaryGeometry.edgeDistance(12, 12, plot));
        assertTrue(BoundaryGeometry.nearby(List.of(plot), 12, 12).isEmpty());
        assertEquals(0, BoundaryGeometry.edgeDistance(0, 12, plot));
        assertEquals(3, BoundaryGeometry.edgeDistance(-3, 12, plot));
        assertEquals(5, BoundaryGeometry.edgeDistance(-3, -4, plot));
    }

    @Test void onlyNearbyPartsAreSampledAndBudgetsHoldForDenseClaims() {
        var result = BoundaryGeometry.nearby(List.of(plot, new BoundaryGeometry.Rectangle(24, 0, 48, 24, 70)), 24, 12);
        assertFalse(result.isEmpty());
        assertTrue(result.size() <= BoundaryGeometry.MAX_EMITTERS_PER_VIEWER);
        assertTrue(result.stream().allMatch(p -> p.distance() < BoundaryGeometry.FAR_DISTANCE));
        assertEquals(result.size(), new HashSet<>(result.stream().map(p -> p.x() + ":" + p.z()).toList()).size());
        assertTrue(result.stream().noneMatch(p -> p.x() == 0 || p.x() == 48));
    }

    @Test void sharedSeamsDoNotDoubleTheEmissionAndLargeBoundsAreBounded() {
        var left = BoundaryGeometry.nearby(List.of(plot), 24, 12);
        var shared = BoundaryGeometry.nearby(List.of(plot, new BoundaryGeometry.Rectangle(24, 0, 48, 24, 70)), 24, 12);
        assertEquals(left, shared);
        var huge = BoundaryGeometry.nearby(List.of(new BoundaryGeometry.Rectangle(-2_000_000, 0, 2_000_000, 24, 70)), 0, 0);
        assertFalse(huge.isEmpty());
        assertTrue(huge.size() <= BoundaryGeometry.MAX_EMITTERS_PER_VIEWER);
    }

    @Test void fadesAreMonotonicAndDistantOrMalformedPlotsEmitNothing() {
        double previous = 1;
        for (double distance = 0; distance < 12; distance += .1) {
            double intensity = BoundaryGeometry.intensity(distance);
            assertTrue(intensity >= 0 && intensity <= previous + .0000001);
            previous = intensity;
        }
        assertEquals(1, BoundaryGeometry.intensity(3));
        assertEquals(0, BoundaryGeometry.intensity(8));
        assertTrue(BoundaryGeometry.nearby(List.of(plot), -8, 12).isEmpty());
        assertTrue(BoundaryGeometry.nearby(List.of(new BoundaryGeometry.Rectangle(10, 0, 0, 24, 70)), 0, 0).isEmpty());
    }
}
