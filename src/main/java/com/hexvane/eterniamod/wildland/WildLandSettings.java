package com.hexvane.eterniamod.wildland;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Land claims in adventure worlds ({@code WildLand.json}): the hexagon size, Coin prices, the most hexagons a player or
 * a guild may claim, and the gap kept free around other people's land. The server copies the shipped file into its
 * data folder on first start; the copy there is the one to edit, and {@code /e admin land reload} reads it again.
 */
public record WildLandSettings(Set<String> worlds, int cellWidth, int bodyDepth, int bufferRings, double refundShare, Tier player, Tier guild) {
    public static final String RESOURCE = "Server/EterniaMod/Survival/WildLand.json";
    public static final String FILE = "WildLand.json";

    /**
     * Prices and limit for one kind of owner.
     *
     * @param prices optional price list for the 2nd, 3rd ... hexagon (the first is always free); past its end the last
     *               price plus {@code stepCoins} per hexagon. Empty uses extra and step. {@code firstCoins} is ignored and
     *               only kept so older files still read.
     */
    public record Tier(int maxCells, long firstCoins, long extraCoins, long stepCoins, List<Long> prices) {
        public Tier {
            maxCells = Math.max(1, Math.min(PlotCells.MAX_CELLS, maxCells));
            firstCoins = Math.max(0, firstCoins);
            extraCoins = Math.max(0, extraCoins);
            stepCoins = Math.max(0, stepCoins);
            prices = prices == null ? List.of() : prices.stream().map(p -> Math.max(0L, p)).toList();
        }

        /** What the next hexagon costs an owner who holds {@code owned} hexagons now. The first is always free. */
        public long price(int owned) {
            int n = Math.max(0, owned);
            if (n == 0) return 0;
            if (!prices.isEmpty()) {
                if (n - 1 < prices.size()) return prices.get(n - 1);
                return Math.addExact(prices.getLast(), Math.multiplyExact(stepCoins, (long) (n - prices.size())));
            }
            return Math.addExact(extraCoins, Math.multiplyExact(stepCoins, (long) (n - 1)));
        }
    }

    public static final WildLandSettings DEFAULTS = new WildLandSettings(Set.of(), 32, 20, 1, 0.5,
        new Tier(16, 0, 250, 100, List.of()), new Tier(40, 0, 500, 100, List.of()));

    private static volatile WildLandSettings current = DEFAULTS;

    public WildLandSettings {
        worlds = worlds == null ? Set.of() : Set.copyOf(worlds);
        if (cellWidth < 8 || cellWidth % 4 != 0 || cellWidth > 256) cellWidth = DEFAULTS == null ? 32 : DEFAULTS.cellWidth;
        if (bodyDepth < 4 || bodyDepth > 256) bodyDepth = DEFAULTS == null ? 20 : DEFAULTS.bodyDepth;
        bufferRings = Math.max(0, Math.min(4, bufferRings));
        refundShare = Double.isNaN(refundShare) ? 0 : Math.max(0, Math.min(1, refundShare));
        if (player == null) player = DEFAULTS.player;
        if (guild == null) guild = DEFAULTS.guild;
    }

    public static WildLandSettings current() { return current; }

    public static void set(WildLandSettings settings) { current = settings == null ? DEFAULTS : settings; }

    public Tier tier(boolean guildLand) { return guildLand ? guild : player; }

    /** The hexagon grid of every claim, centred on the world's origin. */
    public HexLattice lattice() { return new HexLattice(cellWidth, bodyDepth, 0, 0); }

    /** Coins given back for a hexagon that cost {@code paid}. */
    public long refund(long paid) { return (long) Math.floor(Math.max(0, paid) * refundShare); }

    /** Whether claims are open in this adventure world. */
    public boolean coversWorld(String world) { return worlds.isEmpty() || worlds.contains(world.toLowerCase(Locale.ROOT)); }

    /**
     * Loads the data folder copy, writing the shipped file there first when it is missing. Falls back to the shipped
     * file, then to {@link #DEFAULTS}, when a file cannot be read.
     */
    public static WildLandSettings install(ClassLoader loader, Path dataDirectory) {
        WildLandSettings loaded = load(loader, dataDirectory);
        set(loaded);
        return loaded;
    }

    public static WildLandSettings load(ClassLoader loader, Path dataDirectory) {
        if (dataDirectory != null) {
            Path file = dataDirectory.resolve(FILE);
            try {
                if (!Files.exists(file)) {
                    try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
                        if (input != null) { Files.createDirectories(dataDirectory); Files.copy(input, file); }
                    }
                }
                if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return parse(reader); }
            } catch (java.io.IOException | RuntimeException unreadable) {
                // Fall back to the shipped numbers below.
            }
        }
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) return DEFAULTS;
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return DEFAULTS;
        }
    }

    public static WildLandSettings parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        Set<String> worlds = new TreeSet<>();
        if (root.get("Worlds") instanceof JsonArray list) for (JsonElement e : list) if (e.isJsonPrimitive()) worlds.add(e.getAsString().toLowerCase(Locale.ROOT));
        JsonObject lattice = object(root, "Lattice");
        return new WildLandSettings(worlds, integer(lattice, "CellWidth", DEFAULTS.cellWidth), integer(lattice, "BodyDepth", DEFAULTS.bodyDepth),
            integer(root, "BufferRings", DEFAULTS.bufferRings), decimal(root, "RefundShare", DEFAULTS.refundShare),
            tier(object(root, "Player"), DEFAULTS.player), tier(object(root, "Guild"), DEFAULTS.guild));
    }

    private static Tier tier(JsonObject json, Tier d) {
        if (json == null) return d;
        List<Long> prices = new ArrayList<>();
        if (json.get("Prices") instanceof JsonArray list) for (JsonElement e : list) if (e.isJsonPrimitive()) prices.add(e.getAsLong());
        return new Tier(integer(json, "MaxCells", d.maxCells()), number(json, "FirstCoins", d.firstCoins()),
            number(json, "ExtraCoins", d.extraCoins()), number(json, "StepCoins", d.stepCoins()), prices);
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement e = json == null ? null : json.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        try { JsonElement e = json == null ? null : json.get(key); return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback; }
        catch (RuntimeException bad) { return fallback; }
    }

    private static long number(JsonObject json, String key, long fallback) {
        try { JsonElement e = json == null ? null : json.get(key); return e != null && e.isJsonPrimitive() ? e.getAsLong() : fallback; }
        catch (RuntimeException bad) { return fallback; }
    }

    private static double decimal(JsonObject json, String key, double fallback) {
        try { JsonElement e = json == null ? null : json.get(key); return e != null && e.isJsonPrimitive() ? e.getAsDouble() : fallback; }
        catch (RuntimeException bad) { return fallback; }
    }
}
