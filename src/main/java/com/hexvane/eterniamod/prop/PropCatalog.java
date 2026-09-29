package com.hexvane.eterniamod.prop;

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

public final class PropCatalog {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, PropDefinition> byId;
    private final Path dataDirectory;

    private PropCatalog(Map<String, PropDefinition> byId, Path dataDirectory) {
        this.byId = byId;
        this.dataDirectory = dataDirectory;
    }

    @Nonnull
    public static PropCatalog load(@Nonnull ClassLoader classLoader, @Nonnull Path dataDirectory) {
        Map<String, PropDefinition> map = CatalogLoader.load(
            classLoader, "Server/EterniaMod/Props/", dataDirectory, PropDefinition.class, false);
        LOGGER.atInfo().log("EterniaMod loaded %s prop definition(s)", map.size());
        return new PropCatalog(map, dataDirectory);
    }

    @Nullable
    public PropDefinition get(@Nonnull String id) { return byId.get(id.trim()); }

    public boolean contains(@Nonnull String id) { return byId.containsKey(id.trim()); }

    public void register(@Nonnull PropDefinition def) {
        validate(def);
        byId.put(def.getId(), def);
    }

    public void persist(@Nonnull PropDefinition def) throws IOException {
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

    private static void validate(PropDefinition def) {
        CatalogContentValidator.requireValid(GSON.toJsonTree(def).getAsJsonObject(), def.getId(), false);
    }

    @Nonnull
    public Path getDataDirectory() { return dataDirectory; }

    @Nonnull
    public List<String> ids() { return new ArrayList<>(byId.keySet()); }

    @Nonnull
    public Map<String, PropDefinition> asMap() { return Collections.unmodifiableMap(byId); }
}
