package com.hexvane.eterniamod.boundary;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaygateAnimationAssetTest {
    @Test void bothGatesUseACompleteLoopingAnimation() throws Exception {
        for (String item : List.of("Eternia_World_Portal", "Eternia_Guild_Waygate")) {
            var block = resource("Server/Item/Items/Eternia/" + item + ".json").getAsJsonObject("BlockType");
            assertTrue(block.get("Looping").getAsBoolean());
            var model = resource("Common/" + block.get("CustomModel").getAsString());
            var animation = resource("Common/" + block.get("CustomModelAnimation").getAsString());
            assertFalse(animation.get("holdLastKeyframe").getAsBoolean());
            assertEquals(720, animation.get("duration").getAsInt());
            for (var entry : animation.getAsJsonObject("nodeAnimations").entrySet()) {
                assertTrue(model.getAsJsonArray("nodes").asList().stream()
                        .anyMatch(n -> entry.getKey().equals(n.getAsJsonObject().get("name").getAsString())),
                        "Animation must target an existing model node");
                var track = entry.getValue().getAsJsonObject();
                // The native client requires even the channels we do not animate.
                for (String channel : List.of("position", "orientation", "shapeStretch", "shapeVisible", "shapeUvOffset")) {
                    assertTrue(track.has(channel) && track.get(channel).isJsonArray(), channel);
                }
                var keys = track.getAsJsonArray("orientation");
                assertEquals(5, keys.size());
                for (int i = 0; i < keys.size(); i++) {
                    var key = keys.get(i).getAsJsonObject();
                    assertEquals(i * 180, key.get("time").getAsInt());
                    var q = key.getAsJsonObject("delta");
                    assertEquals(Math.sin(i * Math.PI / 4), q.get("z").getAsDouble(), 1e-6);
                    assertEquals(Math.cos(i * Math.PI / 4), q.get("w").getAsDouble(), 1e-6);
                    assertEquals("linear", key.get("interpolationType").getAsString());
                }
            }
        }
    }

    private JsonObject resource(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
