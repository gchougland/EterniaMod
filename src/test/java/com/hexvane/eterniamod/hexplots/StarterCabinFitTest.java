package com.hexvane.eterniamod.hexplots;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The starter cabins must fit the one hexagon every new player claims, in either turn. */
class StarterCabinFitTest {
    static int[] size(String file) throws Exception {
        var prefab = JsonParser.parseString(Files.readString(Path.of("src/main/resources/Server/Prefabs/EterniaMod/Houses/" + file))).getAsJsonObject();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (var entry : prefab.getAsJsonArray("blocks")) {
            var block = entry.getAsJsonObject();
            if (block.get("name").getAsString().equals("Empty")) continue;
            int x = block.get("x").getAsInt(), z = block.get("z").getAsInt();
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return new int[] {maxX - minX + 1, maxZ - minZ + 1};
    }

    @Test void everyStarterCabinFitsOneHexagon() throws Exception {
        var one = Set.of(HexCoord.ORIGIN);
        var report = new StringBuilder();
        for (String cabin : new String[] {"House_Darkwood_Cabin.prefab.json", "House_Lightwood_Cabin.prefab.json", "House_Log_Cabin.prefab.json"}) {
            int[] s = size(cabin);
            boolean fits = HexFit.nearestFit(HexLattice.DEFAULT, one, s[0], s[1], HexLattice.HOUSE_SETBACK).isPresent()
                && HexFit.nearestFit(HexLattice.DEFAULT, one, s[1], s[0], HexLattice.HOUSE_SETBACK).isPresent();
            report.append(cabin).append(' ').append(s[0]).append('x').append(s[1]).append(fits ? " fits" : " too big").append('\n');
        }
        System.out.println(report);
        assertFalse(report.toString().contains("too big"), report.toString());
    }
}
