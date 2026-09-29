package com.hexvane.eterniamod.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validation independent of a running Hytale asset registry. */
public final class CatalogContentValidator {
    private static final Set<String> ROTATIONS = Set.of("None", "Ninety", "OneEighty", "TwoSeventy");
    private CatalogContentValidator() {}

    public static void requireValid(JsonObject definition, String source, boolean building) {
        List<String> errors = validate(definition, building);
        if (!errors.isEmpty()) throw new IllegalArgumentException(source + ": " + String.join("; ", errors));
    }

    public static List<String> validate(JsonObject definition, boolean building) {
        List<String> errors = new ArrayList<>();
        if (definition == null) return List.of("definition must be an object");
        String id = string(definition, "id");
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
            errors.add("id must be a safe lowercase identifier (letters, digits, underscore, hyphen; at most 96 characters)");
        }
        String prefab = string(definition, "prefabPath");
        if (!isRelativeAssetPath(prefab) || !prefab.endsWith(".prefab.json")) {
            errors.add("prefabPath must be a relative .prefab.json asset path without traversal");
        }
        if (definition.has("displayName") && (string(definition, "displayName") == null
            || string(definition, "displayName").isBlank())) errors.add("displayName must be nonblank text");
        vector(definition, "plotAnchorOffset", false, false, errors);
        if (building) vector(definition, "managementBlockLocalPos", true, false, errors);
        if (building) {
            vector(definition, "spawnLocalPos", false, false, errors);
            if (definition.has("housingKind") && !Set.of("personal","guild").contains(String.valueOf(string(definition,"housingKind")))) errors.add("housingKind must be personal or guild");
        } else if (definition.has("category") && !Set.of("prop","addition").contains(String.valueOf(string(definition,"category")))) errors.add("category must be prop or addition");
        vector(definition, "dimensions", false, true, errors);
        if (definition.has("rotationYaw") && !ROTATIONS.contains(string(definition, "rotationYaw") == null
            ? "" : string(definition, "rotationYaw"))) errors.add("rotationYaw must be None, Ninety, OneEighty or TwoSeventy");
        return List.copyOf(errors);
    }

    public static boolean isRelativeAssetPath(String path) {
        if (path == null || path.isBlank() || !path.equals(path.trim()) || path.startsWith("/")
            || path.startsWith("\\") || path.indexOf(':') >= 0 || path.indexOf('\\') >= 0) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
            for (int i = 0; i < part.length(); i++) if (Character.isISOControl(part.charAt(i))) return false;
        }
        return true;
    }

    /** Bound actual authored dimensions, not only an optional definition hint. */
    public static List<String> validatePrefab(JsonObject prefab) {
        if (prefab == null || !prefab.has("blocks") || !prefab.get("blocks").isJsonArray()
            || prefab.getAsJsonArray("blocks").isEmpty()) return List.of("prefab must contain blocks");
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        String[] axes = {"x", "y", "z"};
        boolean solid = false;
        for (JsonElement entry : prefab.getAsJsonArray("blocks")) {
            if (!entry.isJsonObject()) return List.of("prefab block must be an object");
            JsonObject block = entry.getAsJsonObject();
            for (int axis = 0; axis < 3; axis++) {
                Integer coordinate = integer(block.get(axes[axis]));
                if (coordinate == null || Math.abs((long) coordinate) > 4096) return List.of("prefab coordinates must be integers within +/-4096");
                min[axis] = Math.min(min[axis], coordinate);
                max[axis] = Math.max(max[axis], coordinate);
            }
            String name = string(block, "name");
            solid |= name != null && !name.equals("Empty");
        }
        if (!solid) return List.of("prefab must contain at least one nonempty block");
        for (int axis = 0; axis < 3; axis++) {
            if ((long) max[axis] - min[axis] + 1 > 512) return List.of("prefab dimensions must not exceed 512 blocks on an axis");
        }
        return List.of();
    }

    private static void vector(JsonObject object, String key, boolean required, boolean positive, List<String> errors) {
        if (!object.has(key)) { if (required) errors.add(key + " is required"); return; }
        JsonElement value = object.get(key);
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) { errors.add(key + " must contain exactly three integers"); return; }
        for (JsonElement element : value.getAsJsonArray()) {
            Integer number = integer(element);
            if (number == null || (positive ? number < 1 || number > 512 : Math.abs((long) number) > 4096)) {
                errors.add(key + (positive ? " dimensions must be integers from 1 to 512" : " coordinates must be integers within +/-4096"));
                return;
            }
        }
    }

    private static Integer integer(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
        try { return element.getAsBigDecimal().intValueExact(); } catch (ArithmeticException | NumberFormatException ex) { return null; }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
    }
}
