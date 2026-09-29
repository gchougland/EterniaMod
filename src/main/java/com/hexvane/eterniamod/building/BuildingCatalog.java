package com.hexvane.eterniamod.building;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hexvane.eterniamod.catalog.CatalogContentValidator;
import com.hexvane.eterniamod.catalog.CatalogLoader;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BuildingCatalog {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, BuildingDefinition> byId;
    private final Path dataDirectory;

    private BuildingCatalog(Map<String, BuildingDefinition> byId, Path dataDirectory) {
        this.byId = byId;
        this.dataDirectory = dataDirectory;
    }

    @Nonnull
    public static BuildingCatalog load(@Nonnull ClassLoader classLoader, @Nonnull Path dataDirectory) {
        Map<String, BuildingDefinition> map = CatalogLoader.load(
            classLoader, "Server/EterniaMod/Buildings/", dataDirectory, BuildingDefinition.class, true);
        LOGGER.atInfo().log("EterniaMod loaded %s building definition(s)", map.size());
        return new BuildingCatalog(map, dataDirectory);
    }

    @Nullable
    public BuildingDefinition get(@Nonnull String id) { return byId.get(id.trim()); }

    public boolean contains(@Nonnull String id) { return byId.containsKey(id.trim()); }

    public void register(@Nonnull BuildingDefinition def) {
        validate(def);
        byId.put(def.getId(), def);
    }

    public void persist(@Nonnull BuildingDefinition def) throws IOException {
        validate(def);
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(def.getId() + ".json");
        Path temp = Files.createTempFile(dataDirectory, def.getId() + "-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { GSON.toJson(def, writer); }
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private static void validate(BuildingDefinition def) {
        CatalogContentValidator.requireValid(GSON.toJsonTree(def).getAsJsonObject(), def.getId(), true);
    }

    @Nonnull
    public Path getDataDirectory() { return dataDirectory; }

    @Nonnull
    public List<String> ids() { return new ArrayList<>(byId.keySet()); }

    @Nonnull
    public Map<String, BuildingDefinition> asMap() { return Collections.unmodifiableMap(byId); }
}
