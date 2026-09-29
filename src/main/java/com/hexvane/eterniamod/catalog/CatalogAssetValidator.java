package com.hexvane.eterniamod.catalog;

import com.google.gson.JsonParser;
import com.hexvane.eterniamod.building.BuildingCatalog;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prop.PropCatalog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Invoke after asset packs start, since native/external prefab references are not available in setup(). */
public final class CatalogAssetValidator {
    private CatalogAssetValidator() {}

    public static List<String> validateResolvedPrefabs(BuildingCatalog buildings, PropCatalog props) {
        List<String> errors = new ArrayList<>();
        buildings.asMap().forEach((id, def) -> validate(id, def.getPrefabPath(), errors));
        props.asMap().forEach((id, def) -> validate(id, def.getPrefabPath(), errors));
        return List.copyOf(errors);
    }

    private static void validate(String id, String reference, List<String> errors) {
        try {
            Path path = PrefabResolveUtil.resolvePrefabPath(reference);
            if (path == null) { errors.add(id + ": missing prefab " + reference); return; }
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                for (String error : CatalogContentValidator.validatePrefab(JsonParser.parseReader(reader).getAsJsonObject())) {
                    errors.add(id + ": " + error + " (" + reference + ")");
                }
            }
        } catch (Exception ex) {
            errors.add(id + ": cannot validate prefab " + reference + ": " + ex.getMessage());
        }
    }
}
