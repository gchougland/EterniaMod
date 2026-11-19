package com.hexvane.eterniamod.hexplots;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * How many plots and hexagons players and guilds may have, what is free and what extra ones cost, from the
 * {@code Plots} section of {@code Server/EterniaMod/Housing/HexPlots.json} (Prowl's design of 2026-11-19). Missing values
 * fall back to {@link #DEFAULTS}. Immutable; the live copy is {@link #current()}.
 */
public record PlotRules(Tier player, Tier guild, String productionBonus) {
    /**
     * One kind of owner's allowances.
     *
     * @param maxPlots          plots one owner may hold, packed ones included
     * @param extraPlotCrowns   price of every plot after the first
     * @param houseFree         housing hexagons a plot gets free (the ones it is claimed with)
     * @param houseMax          most housing hexagons a plot may have
     * @param houseCrowns       price of each housing hexagon past the free ones
     * @param productionFree    production hexagons a plot gets free
     * @param productionMax     most production hexagons a plot may have
     * @param productionCrowns  price of each production hexagon past the free ones
     * @param unitsPerHexagon   production units one production hexagon holds
     * @param samePerPlot       how many of one production unit a plot may have
     */
    public record Tier(int maxPlots, long extraPlotCrowns, int houseFree, int houseMax, long houseCrowns,
                       int productionFree, int productionMax, long productionCrowns, int unitsPerHexagon, int samePerPlot) {
        public Tier {
            maxPlots = Math.max(1, maxPlots);
            extraPlotCrowns = Math.max(0, extraPlotCrowns);
            houseMax = Math.max(1, houseMax);
            houseFree = Math.max(1, Math.min(houseFree, houseMax));
            houseCrowns = Math.max(0, houseCrowns);
            productionMax = Math.max(1, productionMax);
            productionFree = Math.max(0, Math.min(productionFree, productionMax));
            productionCrowns = Math.max(0, productionCrowns);
            unitsPerHexagon = Math.max(1, unitsPerHexagon);
            samePerPlot = Math.max(1, samePerPlot);
        }

        /** Most hexagons a plot of this kind may have. */
        public int maxCells() { return houseMax + productionMax; }
    }

    public static final PlotRules DEFAULTS = new PlotRules(
        new Tier(5, 1500, 1, 4, 500, 1, 6, 500, 2, 1),
        new Tier(2, 2500, 4, 10, 1000, 2, 12, 1000, 2, 2),
        "eternia:plot/production_bonus");

    private static volatile PlotRules current = DEFAULTS;

    public PlotRules {
        if (player == null) player = DEFAULTS.player;
        if (guild == null) guild = DEFAULTS.guild;
        if (productionBonus == null || productionBonus.isBlank()) productionBonus = "eternia:plot/production_bonus";
    }

    public static PlotRules current() { return current; }

    /** Player plots and guild plots. */
    public Tier tier(boolean guildPlot) { return guildPlot ? guild : player; }

    public Tier tier(HexClaimRules.Kind kind) { return tier(kind == HexClaimRules.Kind.GUILD_ESTATE); }

    /**
     * Free production hexagons on one plot: the tier's, plus one on a player plot when its owner has the season bonus
     * (the first season's free pass).
     */
    public int productionFree(boolean guildPlot, boolean bonus) {
        Tier t = tier(guildPlot);
        return Math.min(t.productionMax(), t.productionFree() + (!guildPlot && bonus ? 1 : 0));
    }

    /**
     * What one more hexagon of {@code role} costs on a plot that has {@code have} of that role now: nothing while it is
     * one of the free ones.
     */
    public long hexagonPrice(boolean guildPlot, boolean production, int have, boolean bonus) {
        Tier t = tier(guildPlot);
        int free = production ? productionFree(guildPlot, bonus) : t.houseFree();
        return have < free ? 0 : production ? t.productionCrowns() : t.houseCrowns();
    }

    /** What claiming one more plot costs an owner who holds {@code plots} already: the first is free. */
    public long plotPrice(boolean guildPlot, int plots) { return plots <= 0 ? 0 : tier(guildPlot).extraPlotCrowns(); }

    public static PlotRules install(ClassLoader loader) {
        PlotRules loaded = load(loader);
        current = loaded;
        return loaded;
    }

    public static PlotRules load(ClassLoader loader) {
        try (InputStream input = loader.getResourceAsStream(HexPlotSettings.RESOURCE)) {
            if (input == null) return DEFAULTS;
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return DEFAULTS;
        }
    }

    public static PlotRules parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        JsonObject plots = object(root, "Plots");
        if (plots == null) return DEFAULTS;
        return new PlotRules(tier(object(plots, "Player"), DEFAULTS.player), tier(object(plots, "Guild"), DEFAULTS.guild),
            plots.has("ProductionBonus") ? plots.get("ProductionBonus").getAsString() : DEFAULTS.productionBonus);
    }

    private static Tier tier(JsonObject json, Tier d) {
        if (json == null) return d;
        JsonObject house = object(json, "Housing"), production = object(json, "Production");
        return new Tier(integer(json, "MaxPlots", d.maxPlots()), number(json, "ExtraPlotCrowns", d.extraPlotCrowns()),
            integer(house, "Free", d.houseFree()), integer(house, "Max", d.houseMax()), number(house, "Crowns", d.houseCrowns()),
            integer(production, "Free", d.productionFree()), integer(production, "Max", d.productionMax()), number(production, "Crowns", d.productionCrowns()),
            integer(production, "UnitsPerHexagon", d.unitsPerHexagon()), integer(production, "SameUnitPerPlot", d.samePerPlot()));
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
}
