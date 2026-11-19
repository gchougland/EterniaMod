package com.hexvane.eterniamod.worldborder;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldBorderTest {
    @TempDir Path temp;

    @Test void aBorderIsASquareAroundItsCentre() {
        var b = new WorldBorders.Border(100, -50, 200);
        assertTrue(b.contains(100, -50));
        assertTrue(b.contains(-100, 150.9), "the outermost blocks are inside");
        assertFalse(b.contains(-100.1, 0));
        assertFalse(b.contains(0, 151));
        assertEquals(10, b.distanceInside(-90, 0), 1e-9);
        assertTrue(b.distanceInside(400, 0) < 0, "outside is negative");
        double[] back = b.clampInside(500, -50, 1.5);
        assertEquals(b.maxX() - 1.5, back[0], 1e-9);
        assertEquals(-50, back[1], 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new WorldBorders.Border(0, 0, 5), "too small to stand in");
    }

    @Test void bordersAreSavedPerWorldAndSurviveARestart() throws Exception {
        Path file = temp.resolve("world-borders.json");
        var borders = new WorldBorders(file);
        borders.load();
        assertTrue(borders.all().isEmpty(), "no file means no borders");
        borders.set("default", new WorldBorders.Border(0, 0, 3000));
        borders.set("Survival", new WorldBorders.Border(410, -400, 5000));
        var again = new WorldBorders(file);
        again.load();
        assertEquals(3000, again.get("default").orElseThrow().radius());
        assertEquals(410, again.get("Survival").orElseThrow().centerX());
        assertTrue(again.remove("default"));
        assertFalse(again.remove("default"));
        var third = new WorldBorders(file);
        third.load();
        assertTrue(third.get("default").isEmpty());
        assertTrue(third.get("Survival").isPresent());
        Files.writeString(file, "{\"bad\":{\"centerX\":0,\"centerZ\":0,\"radius\":1}}");
        assertThrows(java.io.IOException.class, new WorldBorders(file)::load, "a broken border is reported, not ignored");
    }

    @Test void theFieldShowsOnlyNearASideAndFacesAlongIt() {
        var b = new WorldBorders.Border(0, 0, 500);
        assertTrue(WorldBorderField.panels(b, 0, 64, 0).isEmpty(), "nothing in the middle");
        var west = WorldBorderField.panels(b, -490, 64, 10);
        assertFalse(west.isEmpty());
        assertTrue(west.stream().allMatch(p -> p.x() == b.minX() && p.yawDegrees() == 90f), "on the west side, facing along it");
        assertTrue(west.stream().allMatch(p -> Math.abs(p.z() - 10) <= WorldBorderField.HALF_SPAN + WorldBorderField.PANEL));
        assertTrue(west.size() <= 30, "a wall is a few dozen panels, not hundreds: " + west.size());
        var corner = WorldBorderField.panels(b, 495, 64, 495);
        assertTrue(corner.stream().anyMatch(p -> p.yawDegrees() == 90f) && corner.stream().anyMatch(p -> p.yawDegrees() == 0f), "both sides at a corner");
        assertTrue(corner.stream().allMatch(p -> p.x() <= b.maxX() && p.z() <= b.maxZ()), "the wall stops at the corner");
        var close = WorldBorderField.panels(b, -499, 64, 0).getFirst().strength();
        var far = WorldBorderField.panels(b, -480, 64, 0).getFirst().strength();
        assertTrue(close > far, "brighter up close");
    }
}
