package com.hexvane.eterniamod.socialui;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeasonHallTest {
    private static SeasonHall.Tile tile(int level, boolean free) { return new SeasonHall.Tile(level, free, "Reward", "", "locked", ""); }

    @Test void tilesOnOneTrackNeverOverlapAndStayOnTheTrack() {
        var tiles = List.of(tile(1, false), tile(1, false), tile(2, false), tile(10, true), tile(29, false), tile(30, false), tile(30, false));
        int[] paid = SeasonHall.place(tiles, false, 30);
        assertEquals(6, paid.length);
        for (int i = 0; i < paid.length; i++) {
            assertTrue(paid[i] >= 0 && paid[i] + SeasonHall.TILE_WIDTH <= SeasonHall.TRACK_WIDTH, "tile " + i + " left the track");
            if (i > 0) assertTrue(paid[i] >= paid[i - 1] + SeasonHall.TILE_WIDTH, "tiles " + (i - 1) + " and " + i + " overlap");
        }
    }

    @Test void levelsSpanTheWholeTrack() {
        assertEquals(14, SeasonHall.center(1, 30));
        assertEquals(SeasonHall.TRACK_WIDTH - 14, SeasonHall.center(30, 30));
        assertEquals(SeasonHall.TRACK_WIDTH / 2, SeasonHall.center(1, 1));
    }

    @Test void everyPassStyleHasItsBannerArt() {
        assertEquals("Hearth", SeasonHall.style("eternia:foundations"));
        for (String id : List.of("eternia:foundations", "eternia:tides", "eternia:embers", "example:grove", "x")) {
            String style = SeasonHall.style(id);
            for (String file : List.of("Banner" + style, "Banner" + style + "Active"))
                assertTrue(Files.exists(Path.of("src/main/resources/Common/UI/Custom/EterniaMod/SeasonArt", file + "@2x.png")), file);
        }
    }
}
