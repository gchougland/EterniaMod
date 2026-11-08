package com.hexvane.eterniamod.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Which creatures count as bosses, read from {@code Server/EterniaMod/Stats/bosses.json}. The game has no boss flag,
 * so each boss lists the NPC roles whose death ends the fight, with the difficulty each one stands for
 * (the Gobliterator's hard mode is its own role).
 */
public final class BossCatalog {
    public static final String RESOURCE = "Server/EterniaMod/Stats/bosses.json";
    public static final String NORMAL = "normal";
    public record Boss(String id, String name, Map<String, String> roles) {
        public Boss { roles = Map.copyOf(roles); }
    }
    public record Match(Boss boss, String difficulty) {}

    private final Map<String, Boss> byId;
    private final Map<String, Match> byRole;
    private final double creditRange;

    private BossCatalog(List<Boss> bosses, double creditRange) {
        var ids = new LinkedHashMap<String, Boss>(); var roles = new HashMap<String, Match>();
        for (var boss : bosses) {
            if (ids.put(boss.id(), boss) != null) throw new IllegalArgumentException("Boss " + boss.id() + " is listed twice");
            boss.roles().forEach((role, difficulty) -> { if (roles.put(role, new Match(boss, difficulty)) != null) throw new IllegalArgumentException("Role " + role + " belongs to two bosses"); });
        }
        byId = Collections.unmodifiableMap(ids); byRole = Map.copyOf(roles); this.creditRange = creditRange;
    }

    public static BossCatalog empty() { return new BossCatalog(List.of(), 64); }

    public static BossCatalog parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        double range = root.has("CreditRange") ? root.get("CreditRange").getAsDouble() : 64;
        if (!(range >= 0 && range <= 512)) throw new IllegalArgumentException("CreditRange must be between 0 and 512 blocks");
        var bosses = new ArrayList<Boss>();
        for (var element : root.getAsJsonArray("Bosses")) {
            var json = element.getAsJsonObject();
            String id = json.get("Id").getAsString().strip().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_]{1,48}")) throw new IllegalArgumentException("Boss id must be lower case letters, digits or underscores: " + id);
            String name = json.get("Name").getAsString().strip();
            if (name.isEmpty() || name.length() > 48) throw new IllegalArgumentException("Boss " + id + " needs a short name");
            var roles = new LinkedHashMap<String, String>();
            for (var entry : json.getAsJsonObject("Roles").entrySet()) {
                String difficulty = entry.getValue().getAsString().strip().toLowerCase(Locale.ROOT);
                if (!difficulty.matches("[a-z0-9_]{1,24}")) throw new IllegalArgumentException("Difficulty of " + entry.getKey() + " must be a short lower case word");
                roles.put(entry.getKey(), difficulty);
            }
            if (roles.isEmpty()) throw new IllegalArgumentException("Boss " + id + " needs at least one role");
            bosses.add(new Boss(id, name, roles));
        }
        return new BossCatalog(bosses, range);
    }

    public static BossCatalog load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) return empty();
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public Optional<Match> match(String role) { return role == null ? Optional.empty() : Optional.ofNullable(byRole.get(role)); }
    public Optional<Boss> boss(String id) { return Optional.ofNullable(byId.get(id)); }
    public Collection<Boss> bosses() { return byId.values(); }
    /** Players this close to a boss when it falls share the victory. */
    public double creditRange() { return creditRange; }
}
