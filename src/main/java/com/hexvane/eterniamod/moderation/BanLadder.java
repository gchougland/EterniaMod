package com.hexvane.eterniamod.moderation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ban lengths used when staff give no time: the first offence takes the first step, and so on. Offences past the
 * end keep the last step. The default ships in {@code Server/EterniaMod/Moderation/BanLadder.json}; a server can
 * override it with {@code ban-ladder.json} in the plugin's data folder.
 */
public record BanLadder(List<BanDuration> steps) {
    public static final String RESOURCE = "Server/EterniaMod/Moderation/BanLadder.json";
    public static final String OVERRIDE_FILE = "ban-ladder.json";

    public BanLadder {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("The ban ladder needs at least one step");
    }

    /** Offence numbers start at 1. */
    public BanDuration forOffence(int offence) {
        return steps.get(Math.max(0, Math.min(offence, steps.size()) - 1));
    }

    public static BanLadder parse(Reader json) {
        JsonElement root = JsonParser.parseReader(json);
        if (!root.isJsonObject()) throw new IllegalArgumentException("The ban ladder must be a JSON object");
        JsonObject object = root.getAsJsonObject();
        if (!object.has("Steps") || !object.get("Steps").isJsonArray()) throw new IllegalArgumentException("The ban ladder needs a Steps list");
        JsonArray array = object.getAsJsonArray("Steps");
        List<BanDuration> steps = new ArrayList<>();
        for (JsonElement element : array) {
            String text = element.getAsString();
            steps.add(BanDuration.parse(text).orElseThrow(() -> new IllegalArgumentException("Unreadable ban ladder step: " + text)));
        }
        return new BanLadder(steps);
    }

    /** The data folder override when present, otherwise the bundled default. */
    public static BanLadder load(Path dataDirectory, ClassLoader loader) throws IOException {
        Path override = dataDirectory == null ? null : dataDirectory.resolve(OVERRIDE_FILE);
        if (override != null && Files.isRegularFile(override)) {
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) { return parse(reader); }
        }
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing " + RESOURCE);
            return parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}
