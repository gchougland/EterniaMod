package com.hexvane.eterniamod.prefab;

import com.hypixel.hytale.server.core.prefab.PrefabStore;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PrefabResolveUtil {
    private PrefabResolveUtil() {}

    @Nullable
    public static Path resolvePrefabPath(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim();
        PrefabStore ps = PrefabStore.get();
        Path p = ps.findAssetPrefabPath(k);
        if (p != null) {
            return p;
        }
        if (!k.endsWith(".prefab.json")) {
            p = ps.findAssetPrefabPath(k + ".prefab.json");
            if (p != null) {
                return p;
            }
        }
        String dotted = k.replace('.', '/');
        if (!dotted.equals(k)) {
            p = ps.findAssetPrefabPath(dotted);
            if (p != null) {
                return p;
            }
            if (!dotted.endsWith(".prefab.json")) {
                p = ps.findAssetPrefabPath(dotted + ".prefab.json");
                if (p != null) {
                    return p;
                }
            }
        }
        return tryUnderscoreCasePrefabAliases(ps, k);
    }

    @Nullable
    private static Path tryUnderscoreCasePrefabAliases(@Nonnull PrefabStore ps, @Nonnull String key) {
        if (key.contains("_")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        String underscored = sb.toString();
        Path p = ps.findAssetPrefabPath(underscored);
        if (p != null) {
            return p;
        }
        if (!underscored.endsWith(".prefab.json")) {
            return ps.findAssetPrefabPath(underscored + ".prefab.json");
        }
        return null;
    }
}
