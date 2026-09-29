package com.hexvane.eterniamod.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit packaged indexes work equally in a jar and a development resource directory. */
public final class CatalogLoader {
    private static final Gson GSON = new Gson();
    private CatalogLoader() {}

    public static <T> Map<String, T> load(ClassLoader loader, String prefix, Path overrides, Class<T> type, boolean building) {
        Map<String, T> definitions = new LinkedHashMap<>();
        Set<String> packagedIds = new HashSet<>();
        String indexPath = prefix + "catalog.index";
        try (InputStream stream = loader.getResourceAsStream(indexPath)) {
            if (stream == null) throw new IllegalArgumentException("Missing catalog index " + indexPath);
            List<String> names;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                names = reader.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
            }
            if (new HashSet<>(names).size() != names.size()) throw new IllegalArgumentException("Duplicate entry in " + indexPath);
            for (String name : names.stream().sorted().toList()) {
                if (!CatalogContentValidator.isRelativeAssetPath(name) || !name.endsWith(".json")) throw new IllegalArgumentException("Invalid catalog index entry " + name);
                String source = prefix + name;
                try (InputStream resource = loader.getResourceAsStream(source)) {
                    if (resource == null) throw new IllegalArgumentException("Missing indexed definition " + source);
                    try (Reader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                        read(reader, source, definitions, packagedIds, type, building);
                    }
                }
            }
            if (Files.isDirectory(overrides)) {
                Set<String> overrideIds = new HashSet<>();
                try (var files = Files.list(overrides)) {
                    for (Path path : files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted().toList()) {
                        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                            read(reader, path.toString(), definitions, overrideIds, type, building);
                        }
                    }
                }
            }
            return definitions;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read catalog " + prefix, ex);
        }
    }

    private static <T> void read(Reader reader, String source, Map<String, T> definitions, Set<String> layerIds, Class<T> type, boolean building) {
        JsonObject json;
        try { json = JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (RuntimeException ex) { throw new IllegalArgumentException(source + ": malformed JSON definition", ex); }
        CatalogContentValidator.requireValid(json, source, building);
        String id = json.get("id").getAsString();
        if (!layerIds.add(id)) throw new IllegalArgumentException(source + ": duplicate id in catalog layer: " + id);
        definitions.put(id, GSON.fromJson(json, type));
    }
}
