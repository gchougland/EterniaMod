package com.hexvane.eterniamod.collections;

import com.google.gson.*;
import com.hexvane.eterniamod.domain.CollectionService;
import com.hypixel.hytale.protocol.PlayerSkin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Composes a fresh protocol skin; the authenticated character-creator skin is never mutated. */
public final class SkinComposer {
    public static final Set<String> WEARABLE_SLOTS = Set.of("pants", "overpants", "undertop", "overtop", "shoes", "head_accessory", "face_accessory", "ear_accessory", "gloves", "cape");
    private static final Map<String,String> FIELDS = Map.ofEntries(
        Map.entry("body_characteristic","bodyCharacteristic"),Map.entry("underwear","underwear"),Map.entry("face","face"),Map.entry("ears","ears"),
        Map.entry("mouth","mouth"),Map.entry("eyes","eyes"),Map.entry("haircut","haircut"),Map.entry("eyebrows","eyebrows"),Map.entry("facial_hair","facialHair"),
        Map.entry("skin_feature","skinFeature"),Map.entry("pants","pants"),Map.entry("overpants","overpants"),Map.entry("undertop","undertop"),Map.entry("overtop","overtop"),
        Map.entry("shoes","shoes"),Map.entry("head_accessory","headAccessory"),Map.entry("face_accessory","faceAccessory"),Map.entry("ear_accessory","earAccessory"),Map.entry("gloves","gloves"),Map.entry("cape","cape"));
    private static final Gson JSON = new Gson();
    private SkinComposer() {}

    public static Map<String,String> outfit(Path dataDirectory, String id) {
        if (!id.matches("[a-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid outfit asset id");
        Path local = dataDirectory.resolve("Appearances").resolve(id + ".json");
        try {
            String text;
            if (Files.exists(local)) {
                if (Files.isSymbolicLink(local) || Files.size(local) > 32_768) throw new IOException("Unsafe or oversized outfit");
                text = Files.readString(local);
            } else try (var stream = SkinComposer.class.getClassLoader().getResourceAsStream("Server/EterniaMod/Appearances/" + id + ".json")) {
                if (stream == null) throw new IOException("Outfit asset is missing: " + id);
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            var object = JsonParser.parseString(text).getAsJsonObject();
            if (object.size() == 0) throw new IllegalArgumentException("An outfit needs at least one skin part");
            Map<String,String> parts = new HashMap<>();
            for (var field : object.entrySet()) {
                if (!FIELDS.containsKey(field.getKey())) throw new IllegalArgumentException("Unsupported outfit field: " + field.getKey());
                String value = field.getValue().isJsonNull() ? "" : field.getValue().getAsString();
                validatePart(value); parts.put(field.getKey(), value);
            }
            return Map.copyOf(parts);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static PlayerSkin compose(PlayerSkin original, Map<String,String> outfit, Map<String,CollectionService.Definition> wearables) {
        JsonObject copy = JSON.toJsonTree(new PlayerSkin(original)).getAsJsonObject();
        if (!outfit.isEmpty()) outfit.forEach((slot,value) -> put(copy, slot, value));
        else for (var entry : wearables.entrySet()) {
            if (!WEARABLE_SLOTS.contains(entry.getKey()) || !entry.getValue().slot().equals(entry.getKey())) throw new IllegalArgumentException("Unsupported wearable slot: " + entry.getKey());
            put(copy, entry.getKey(), entry.getValue().assetId());
        }
        return JSON.fromJson(copy, PlayerSkin.class);
    }
    private static void put(JsonObject skin, String slot, String value) {
        String field = FIELDS.get(slot);
        if (field == null) throw new IllegalArgumentException("Unsupported skin field: " + slot);
        validatePart(value);
        if (value.isEmpty()) skin.add(field, JsonNull.INSTANCE); else skin.addProperty(field,value);
    }
    private static void validatePart(String value) {
        if (!value.isEmpty() && !value.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]*){0,2}")) throw new IllegalArgumentException("Invalid native skin part id");
    }
}
