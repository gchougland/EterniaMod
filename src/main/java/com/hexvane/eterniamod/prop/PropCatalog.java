package com.hexvane.eterniamod.prop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PropCatalog {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PROPS_RESOURCE_PREFIX = "Server/EterniaMod/Props/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, PropDefinition> byId;
    @Nonnull
    private final Path dataDirectory;

    private PropCatalog(@Nonnull Map<String, PropDefinition> byId, @Nonnull Path dataDirectory) {
        this.byId = byId;
        this.dataDirectory = dataDirectory;
    }

    @Nonnull
    public static PropCatalog load(@Nonnull ClassLoader classLoader, @Nonnull Path dataDirectory) {
        Map<String, PropDefinition> map = new LinkedHashMap<>();
        loadClasspath(classLoader, map);
        loadDirectory(dataDirectory, map);
        LOGGER.atInfo().log("EterniaMod loaded %s prop definition(s)", map.size());
        return new PropCatalog(map, dataDirectory);
    }

    private static void loadClasspath(
        @Nonnull ClassLoader classLoader,
        @Nonnull Map<String, PropDefinition> map
    ) {
        loadOneFromClasspath(classLoader, map, "aqua_lamp.json");
    }

    private static void loadDirectory(@Nonnull Path directory, @Nonnull Map<String, PropDefinition> map) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(
                    path -> {
                        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                            PropDefinition def = GSON.fromJson(reader, PropDefinition.class);
                            if (def != null && def.getId() != null && !def.getId().isBlank()) {
                                map.put(def.getId(), def);
                            }
                        } catch (Exception e) {
                            LOGGER.atWarning().withCause(e).log("Failed loading prop definition %s", path);
                        }
                    }
                );
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed listing prop definitions in %s", directory);
        }
    }

    private static void loadOneFromClasspath(
        @Nonnull ClassLoader classLoader,
        @Nonnull Map<String, PropDefinition> map,
        @Nonnull String fileName
    ) {
        String path = PROPS_RESOURCE_PREFIX + fileName;
        try (InputStream in = classLoader.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.atWarning().log("Missing prop definition resource %s", path);
                return;
            }
            PropDefinition def = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), PropDefinition.class);
            if (def != null && def.getId() != null && !def.getId().isBlank()) {
                map.put(def.getId(), def);
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed loading prop definition %s", fileName);
        }
    }

    @Nullable
    public PropDefinition get(@Nonnull String id) {
        return byId.get(id.trim());
    }

    public boolean contains(@Nonnull String id) {
        return byId.containsKey(id.trim());
    }

    public void register(@Nonnull PropDefinition def) {
        byId.put(def.getId(), def);
    }

    public void persist(@Nonnull PropDefinition def) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(def.getId() + ".json");
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(def, writer);
        }
    }

    @Nonnull
    public Path getDataDirectory() {
        return dataDirectory;
    }

    @Nonnull
    public List<String> ids() {
        return new ArrayList<>(byId.keySet());
    }

    @Nonnull
    public Map<String, PropDefinition> asMap() {
        return Collections.unmodifiableMap(byId);
    }
}
