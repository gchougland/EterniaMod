package com.hexvane.eterniamod.building;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BuildingCatalog {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String BUILDINGS_RESOURCE_PREFIX = "Server/EterniaMod/Buildings/";

    private final Map<String, BuildingDefinition> byId;

    private BuildingCatalog(@Nonnull Map<String, BuildingDefinition> byId) {
        this.byId = byId;
    }

    @Nonnull
    public static BuildingCatalog loadFromClasspath(@Nonnull ClassLoader classLoader) {
        Gson gson = new GsonBuilder().create();
        Map<String, BuildingDefinition> map = new LinkedHashMap<>();
        loadOne(classLoader, gson, map, "hub_house.json");
        LOGGER.atInfo().log("EterniaMod loaded %s building definition(s)", map.size());
        return new BuildingCatalog(Collections.unmodifiableMap(map));
    }

    private static void loadOne(
        @Nonnull ClassLoader classLoader,
        @Nonnull Gson gson,
        @Nonnull Map<String, BuildingDefinition> map,
        @Nonnull String fileName
    ) {
        String path = BUILDINGS_RESOURCE_PREFIX + fileName;
        try (InputStream in = classLoader.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.atWarning().log("Missing building definition resource %s", path);
                return;
            }
            BuildingDefinition def = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), BuildingDefinition.class);
            if (def != null && def.getId() != null && !def.getId().isBlank()) {
                map.put(def.getId(), def);
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed loading building definition %s", fileName);
        }
    }

    @Nullable
    public BuildingDefinition get(@Nonnull String id) {
        return byId.get(id.trim());
    }

    @Nonnull
    public List<String> ids() {
        return new ArrayList<>(byId.keySet());
    }
}
