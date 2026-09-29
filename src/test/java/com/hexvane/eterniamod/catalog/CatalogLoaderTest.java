package com.hexvane.eterniamod.catalog;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogLoaderTest {
    @TempDir Path temp;

    private static String definition(String id, String display) {
        return "{\"id\":\"" + id + "\",\"displayName\":\"" + display + "\",\"prefabPath\":\"House.prefab.json\",\"plotAnchorOffset\":[0,0,0]}";
    }

    @Test void discoversIndexDeterministicallyAndOverridesOnlyAcrossLayers() throws Exception {
        Path resources = Files.createDirectory(temp.resolve("resources"));
        Files.writeString(resources.resolve("catalog.index"), "# explicit shipped entries\nb.json\na.json\n");
        Files.writeString(resources.resolve("a.json"), definition("a", "Bundled"));
        Files.writeString(resources.resolve("b.json"), definition("b", "Second"));
        Path overrides = Files.createDirectory(temp.resolve("overrides"));
        Files.writeString(overrides.resolve("custom.json"), definition("a", "Override"));
        try (var loader = new URLClassLoader(new java.net.URL[] {resources.toUri().toURL()}, null)) {
            var loaded = CatalogLoader.load(loader, "", overrides, JsonObject.class, false);
            assertEquals(List.of("a", "b"), List.copyOf(loaded.keySet()));
            assertEquals("Override", loaded.get("a").get("displayName").getAsString());
            Files.writeString(overrides.resolve("duplicate.json"), definition("a", "Ambiguous"));
            assertThrows(IllegalArgumentException.class, () -> CatalogLoader.load(loader, "", overrides, JsonObject.class, false));
        }
    }

    @Test void invalidOverridesFailWithFilenameRatherThanSilentlyGrantBundledContent() throws Exception {
        Path resources = Files.createDirectory(temp.resolve("resources"));
        Files.writeString(resources.resolve("catalog.index"), "a.json\n");
        Files.writeString(resources.resolve("a.json"), definition("a", "Bundled"));
        Path overrides = Files.createDirectory(temp.resolve("overrides"));
        Files.writeString(overrides.resolve("broken.json"), definition("a", "Override").replace("[0,0,0]", "[0,0]"));
        try (var loader = new URLClassLoader(new java.net.URL[] {resources.toUri().toURL()}, null)) {
            var error = assertThrows(IllegalArgumentException.class, () -> CatalogLoader.load(loader, "", overrides, JsonObject.class, false));
            assertTrue(error.getMessage().contains("broken.json"));
            assertTrue(error.getMessage().contains("exactly three"));
        }
    }

    @Test void shippedManifestContainsEveryBundledDefinition() throws Exception {
        for (String category : List.of("Buildings", "Props")) {
            String prefix = "Server/EterniaMod/" + category + "/";
            var loaded = CatalogLoader.load(getClass().getClassLoader(), prefix, temp.resolve("none"), JsonObject.class, category.equals("Buildings"));
            try (var files = Files.list(Path.of("src/main/resources").resolve(prefix))) {
                assertEquals(files.filter(p -> p.toString().endsWith(".json")).count(), loaded.size());
            }
            for (var definition : loaded.values()) {
                String reference = definition.get("prefabPath").getAsString();
                try (var stream = getClass().getClassLoader().getResourceAsStream("Server/Prefabs/" + reference)) {
                    if (stream == null) {
                        assertEquals("cacti", definition.get("id").getAsString(), "only the explicit native cacti seed may refer outside this pack");
                    } else {
                        var prefab = JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        assertTrue(CatalogContentValidator.validatePrefab(prefab).isEmpty(), reference);
                    }
                }
            }
        }
    }

    @Test void rejectsTraversalInvalidDimensionsAndUnknownRotation() {
        var json = JsonParser.parseString(definition("a", "A")).getAsJsonObject();
        json.addProperty("prefabPath", "../House.prefab.json");
        json.add("dimensions", JsonParser.parseString("[0,24,24]"));
        json.addProperty("rotationYaw", "Unexpected");
        assertEquals(3, CatalogContentValidator.validate(json, false).size());
        assertFalse(CatalogContentValidator.isRelativeAssetPath("C:/prefab.json"));
        assertFalse(CatalogContentValidator.isRelativeAssetPath("folder//prefab.json"));
        assertTrue(CatalogContentValidator.isRelativeAssetPath("Plants/Cacti/Cacti.prefab.json"));
        assertFalse(CatalogContentValidator.validatePrefab(JsonParser.parseString("{\"blocks\":[{\"x\":0,\"y\":0,\"z\":0,\"name\":\"Stone\"},{\"x\":513,\"y\":0,\"z\":0,\"name\":\"Stone\"}]}").getAsJsonObject()).isEmpty());
    }
}
