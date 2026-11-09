package com.hexvane.eterniamod.hexplots;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hex plot tuning from {@code Server/EterniaMod/Housing/HexPlots.json}: start shapes, growth caps, prices and per cell
 * allowances. Every number here is a placeholder tuned in data, never in code. Missing or bad values fall back to
 * {@link #DEFAULTS} one field at a time. Immutable; safe on any thread. The live copy is {@link #current()}.
 *
 * @param latticeWidth            cell width across the flats for new lattices (multiple of 4)
 * @param latticeBody             straight east and west side length of a new lattice's cells
 * @param startShapes             shape a new plot must have, by kind and entitlement
 * @param maxPersonal             most cells a personal or guild member plot can grow to
 * @param maxGuildEstate          most cells a guild estate can grow to
 * @param claimCoins              coins charged when a plot is first claimed (0 = free)
 * @param expandCoins             flat coins per added cell
 * @param expandCoinsPerOwnedCell coins per added cell for every cell the plot already has
 * @param propsPerCell            placed props allowed per cell, counted where each prop stands
 * @param generatorsPerCell       generators allowed per cell
 * @param houseSetback            clear blocks between a house and the plot's outer border
 * @param guildEstateMembers      members a guild needs before it can claim its estate (local playtests allow 1)
 * @param expandCrowns            Crowns per added cell; when above zero, added cells cost Crowns instead of coins
 */
public record HexPlotSettings(
    int latticeWidth,
    int latticeBody,
    Map<StartKey, HexShape> startShapes,
    int maxPersonal,
    int maxGuildEstate,
    long claimCoins,
    long expandCoins,
    long expandCoinsPerOwnedCell,
    int propsPerCell,
    int generatorsPerCell,
    int houseSetback,
    int guildEstateMembers,
    long expandCrowns
) {
    /** Coin priced settings (no Crowns price). */
    public HexPlotSettings(int latticeWidth, int latticeBody, Map<StartKey, HexShape> startShapes, int maxPersonal, int maxGuildEstate,
                           long claimCoins, long expandCoins, long expandCoinsPerOwnedCell, int propsPerCell, int generatorsPerCell,
                           int houseSetback, int guildEstateMembers) {
        this(latticeWidth, latticeBody, startShapes, maxPersonal, maxGuildEstate, claimCoins, expandCoins, expandCoinsPerOwnedCell,
            propsPerCell, generatorsPerCell, houseSetback, guildEstateMembers, 0);
    }

    public static final String RESOURCE = "Server/EterniaMod/Housing/HexPlots.json";

    /** Which start shape applies: plot kind plus whether the owner holds the larger plot entitlement. */
    public enum StartKey {
        PUBLIC("Public"), PUBLIC_UPGRADED("PublicUpgraded"),
        GUILD_ESTATE("GuildEstate"), GUILD_ESTATE_UPGRADED("GuildEstateUpgraded"),
        GUILD_MEMBER("GuildMember"), GUILD_MEMBER_UPGRADED("GuildMemberUpgraded");

        final String json;

        StartKey(String json) { this.json = json; }

        public static StartKey of(HexClaimRules.Kind kind, boolean upgraded) {
            return switch (kind) {
                case PUBLIC -> upgraded ? PUBLIC_UPGRADED : PUBLIC;
                case GUILD_ESTATE -> upgraded ? GUILD_ESTATE_UPGRADED : GUILD_ESTATE;
                case GUILD_MEMBER -> upgraded ? GUILD_MEMBER_UPGRADED : GUILD_MEMBER;
            };
        }
    }

    /** Fallback when the data file is missing or unreadable (coin priced); the shipped HexPlots.json prices hexagons in Crowns. */
    public static final HexPlotSettings DEFAULTS = new HexPlotSettings(32, 20, defaultShapes(), 7, 19, 0, 0, 1000, 24, 2, 5, 5);

    private static volatile HexPlotSettings current = DEFAULTS;

    public HexPlotSettings {
        EnumMap<StartKey, HexShape> shapes = new EnumMap<>(StartKey.class);
        Map<StartKey, HexShape> fallback = defaultShapes();
        for (StartKey key : StartKey.values()) {
            HexShape given = startShapes == null ? null : startShapes.get(key);
            shapes.put(key, given != null ? given : fallback.get(key));
        }
        startShapes = Map.copyOf(shapes);
        try {
            new HexLattice(latticeWidth, latticeBody, 0, 0);
        } catch (IllegalArgumentException bad) {
            latticeWidth = 32;
            latticeBody = 20;
        }
        maxPersonal = clamp(maxPersonal, 1, PlotCells.MAX_CELLS);
        maxGuildEstate = clamp(maxGuildEstate, 1, PlotCells.MAX_CELLS);
        claimCoins = Math.max(0, claimCoins);
        expandCoins = Math.max(0, expandCoins);
        expandCoinsPerOwnedCell = Math.max(0, expandCoinsPerOwnedCell);
        expandCrowns = Math.max(0, expandCrowns);
        propsPerCell = clamp(propsPerCell, 0, 10_000);
        generatorsPerCell = clamp(generatorsPerCell, 0, 10_000);
        houseSetback = clamp(houseSetback, 0, 16);
        guildEstateMembers = clamp(guildEstateMembers, 1, 1000);
    }

    /** The settings loaded at plugin start (defaults until then). */
    public static HexPlotSettings current() { return current; }

    /** Plugin start only: loads {@link #RESOURCE} from the plugin's class loader and makes it {@link #current()}. */
    public static HexPlotSettings install(ClassLoader loader) {
        HexPlotSettings loaded = load(loader);
        current = loaded;
        return loaded;
    }

    /** Lattice a world gets when it first becomes a hex housing world (origin 0, 0 until an admin moves it). */
    public HexLattice defaultLattice() { return new HexLattice(latticeWidth, latticeBody, 0, 0); }

    public HexShape startShape(HexClaimRules.Kind kind, boolean upgraded) { return startShapes.get(StartKey.of(kind, upgraded)); }

    public int startCells(HexClaimRules.Kind kind, boolean upgraded) { return startShape(kind, upgraded).size(); }

    public int maxCells(HexClaimRules.Kind kind) {
        int cap = kind == HexClaimRules.Kind.GUILD_ESTATE ? maxGuildEstate : maxPersonal;
        // A cap below the start shape would make a fresh plot illegal; never let data do that.
        return Math.max(cap, Math.max(startCells(kind, false), startCells(kind, true)));
    }

    /** Coins back for selling one cell of a plot with {@code ownedCells}: what its newest cell cost, so growing then selling breaks even. */
    public long sellRefund(int ownedCells) {
        return ownedCells <= 1 ? 0 : expansionPrice(ownedCells - 1);
    }

    /** The price of adding one cell to a plot that already has {@code ownedCells}, in Crowns when Crown priced, else coins. */
    public long expansionPrice(int ownedCells) {
        if (crownPriced()) return expandCrowns;
        return Math.addExact(expandCoins, Math.multiplyExact(expandCoinsPerOwnedCell, (long) Math.max(0, ownedCells)));
    }

    /** True when added hexagons cost Crowns. */
    public boolean crownPriced() { return expandCrowns > 0; }

    /** A hexagon price for players: "500 Crowns" or "1,000 Coins". */
    public String priceText(long amount) {
        return String.format(Locale.ROOT, "%,d", amount) + (crownPriced() ? (amount == 1 ? " Crown" : " Crowns") : (amount == 1 ? " Coin" : " Coins"));
    }

    public static HexPlotSettings load(ClassLoader loader) {
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) return DEFAULTS;
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return DEFAULTS;
        }
    }

    public static HexPlotSettings parse(Reader reader) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        HexPlotSettings d = DEFAULTS;
        JsonObject lattice = object(json, "Lattice"), starts = object(json, "StartShapes"), max = object(json, "MaxCells");
        JsonObject claim = object(json, "ClaimPrice"), expand = object(json, "ExpansionPrice"), perCell = object(json, "PerCell");
        EnumMap<StartKey, HexShape> shapes = new EnumMap<>(StartKey.class);
        for (StartKey key : StartKey.values()) {
            String name = string(starts, key.json);
            HexShape shape = null;
            if (name != null) {
                try {
                    shape = HexShape.valueOf(name.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    shape = null;
                }
            }
            shapes.put(key, shape != null ? shape : d.startShapes.get(key));
        }
        return new HexPlotSettings(
            integer(lattice, "CellWidth", d.latticeWidth), integer(lattice, "BodyDepth", d.latticeBody), shapes,
            integer(max, "Personal", d.maxPersonal), integer(max, "GuildEstate", d.maxGuildEstate),
            number(claim, "Coins", d.claimCoins), number(expand, "Coins", d.expandCoins),
            number(expand, "CoinsPerOwnedCell", d.expandCoinsPerOwnedCell),
            integer(perCell, "Props", d.propsPerCell), integer(perCell, "Generators", d.generatorsPerCell),
            integer(json, "HouseSetback", d.houseSetback), integer(json, "GuildEstateMembers", d.guildEstateMembers),
            number(expand, "Crowns", d.expandCrowns));
    }

    private static Map<StartKey, HexShape> defaultShapes() {
        EnumMap<StartKey, HexShape> m = new EnumMap<>(StartKey.class);
        m.put(StartKey.PUBLIC, HexShape.SINGLE);
        m.put(StartKey.PUBLIC_UPGRADED, HexShape.TRIANGLE);
        m.put(StartKey.GUILD_ESTATE, HexShape.FLOWER);
        m.put(StartKey.GUILD_ESTATE_UPGRADED, HexShape.FLOWER);
        m.put(StartKey.GUILD_MEMBER, HexShape.SINGLE);
        m.put(StartKey.GUILD_MEMBER_UPGRADED, HexShape.TRIANGLE);
        return m;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement e = json == null ? null : json.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String string(JsonObject json, String key) {
        JsonElement e = json == null ? null : json.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        try {
            JsonElement e = json == null ? null : json.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
        } catch (RuntimeException bad) {
            return fallback;
        }
    }

    private static long number(JsonObject json, String key, long fallback) {
        try {
            JsonElement e = json == null ? null : json.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : fallback;
        } catch (RuntimeException bad) {
            return fallback;
        }
    }
}
