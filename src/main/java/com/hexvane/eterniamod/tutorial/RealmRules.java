package com.hexvane.eterniamod.tutorial;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The rules of the realm the Master of Arms shows, read from Server/EterniaMod/Rules/Realm_Rules.json. */
public final class RealmRules {
    public static final String RESOURCE = "Server/EterniaMod/Rules/Realm_Rules.json";
    private static volatile List<String> cached;

    private static final class File { int version; List<String> rules; }

    private RealmRules() {}

    public static List<String> all() {
        List<String> rules = cached;
        if (rules == null) cached = rules = load();
        return rules;
    }

    static List<String> load() {
        try (var stream = RealmRules.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("The rules list " + RESOURCE + " is missing from the mod");
            var file = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), File.class);
            if (file == null || file.version != 1 || file.rules == null || file.rules.isEmpty()) throw new IOException("Unsupported rules list");
            return List.copyOf(file.rules.stream().map(rule -> rule.strip()).filter(rule -> !rule.isEmpty()).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
