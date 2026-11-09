package com.hexvane.eterniamod.hexplots.terraform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * How newly claimed hexagons are shaped, from {@code Server/EterniaMod/Housing/PlotTerraform.json}. An administrator can
 * switch the mode at run time ({@code /e admin terraform mode}); that choice is saved in the plugin's data folder and
 * wins over the file until it is cleared. Immutable; safe on any thread.
 *
 * @param mode           off, plateau (crisp hexagon steps) or smooth (edges blend into the land around)
 * @param skirtWidth     smooth mode: how far the blend reaches into free land outside the plot, in blocks
 * @param blendWidth     smooth mode: how far inside the plot the edge eases toward a neighbouring plot or road
 * @param roadClearance  free land this close to a road or plaza is never reshaped
 * @param smoothPasses   smooth mode: sculpt brush smoothing passes over the blend
 * @param fillBlock      block used deep inside raised ground (smooth mode)
 * @param plateauBlock   block used deep inside raised ground in plateau mode, the visible side of each step
 * @param maxColumnsPerTick columns reshaped in one world tick; the rest wait for later ticks
 */
public record TerraformSettings(
    Mode mode,
    int skirtWidth,
    int blendWidth,
    int roadClearance,
    int smoothPasses,
    String fillBlock,
    String plateauBlock,
    int maxColumnsPerTick
) {
    public static final String RESOURCE = "Server/EterniaMod/Housing/PlotTerraform.json";
    public static final String OVERRIDE_FILE = "plot-terraform-mode.txt";

    public enum Mode {
        OFF, PLATEAU, SMOOTH;

        public static Mode parse(String text, Mode fallback) {
            if (text == null) return fallback;
            try { return valueOf(text.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException bad) { return fallback; }
        }

        public String displayName() { return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT); }
    }

    public static final TerraformSettings DEFAULTS = new TerraformSettings(Mode.SMOOTH, 8, 5, 3, 6, "Rock_Stone", "Rock_Basalt", 1024);

    private static volatile TerraformSettings fileSettings = DEFAULTS;
    private static volatile Mode override;
    private static volatile Path overridePath;

    public TerraformSettings {
        if (mode == null) mode = DEFAULTS.mode;
        skirtWidth = clamp(skirtWidth, 0, 24);
        blendWidth = clamp(blendWidth, 0, 12);
        roadClearance = clamp(roadClearance, 0, 16);
        smoothPasses = clamp(smoothPasses, 0, 64);
        if (fillBlock == null || fillBlock.isBlank()) fillBlock = "Rock_Stone";
        if (plateauBlock == null || plateauBlock.isBlank()) plateauBlock = "Rock_Stone";
        maxColumnsPerTick = clamp(maxColumnsPerTick, 64, 16_384);
    }

    /** File settings with the administrator's saved mode, if any, on top. */
    public static TerraformSettings current() {
        var file = fileSettings;
        var chosen = override;
        return chosen == null ? file : file.withMode(chosen);
    }

    public TerraformSettings withMode(Mode next) {
        return new TerraformSettings(next, skirtWidth, blendWidth, roadClearance, smoothPasses, fillBlock, plateauBlock, maxColumnsPerTick);
    }

    /** True when an administrator's saved choice is in force. */
    public static boolean overridden() { return override != null; }

    /** Plugin start: loads the file, then the saved administrator choice from {@code dataDirectory}. */
    public static TerraformSettings install(ClassLoader loader, Path dataDirectory) {
        fileSettings = load(loader);
        overridePath = dataDirectory == null ? null : dataDirectory.resolve(OVERRIDE_FILE);
        override = null;
        if (overridePath != null && Files.isRegularFile(overridePath)) {
            try { override = Mode.parse(Files.readString(overridePath), null); } catch (IOException ignored) { override = null; }
        }
        return current();
    }

    /** Saves an administrator's mode (null clears it, so the file decides again). */
    public static synchronized void choose(Mode mode) throws IOException {
        override = mode;
        if (overridePath == null) return;
        if (mode == null) { Files.deleteIfExists(overridePath); return; }
        Files.createDirectories(overridePath.getParent());
        Path tmp = overridePath.resolveSibling(OVERRIDE_FILE + ".pending");
        Files.writeString(tmp, mode.name(), StandardCharsets.UTF_8);
        Files.move(tmp, overridePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static TerraformSettings load(ClassLoader loader) {
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(RESOURCE)) {
            if (in == null) return DEFAULTS;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) { return parse(reader); }
        } catch (IOException | RuntimeException bad) {
            return DEFAULTS;
        }
    }

    /** Reads the JSON shape of {@link #RESOURCE}; every missing or bad field falls back to {@link #DEFAULTS}. */
    public static TerraformSettings parse(Reader reader) {
        JsonObject root;
        try { root = JsonParser.parseReader(reader).getAsJsonObject(); } catch (RuntimeException bad) { return DEFAULTS; }
        var d = DEFAULTS;
        return new TerraformSettings(
            Mode.parse(string(root, "Mode", d.mode.name()), d.mode),
            integer(root, "SkirtWidth", d.skirtWidth),
            integer(root, "BlendWidth", d.blendWidth),
            integer(root, "RoadClearance", d.roadClearance),
            integer(root, "SmoothPasses", d.smoothPasses),
            string(root, "FillBlock", d.fillBlock),
            string(root, "PlateauBlock", d.plateauBlock),
            integer(root, "MaxColumnsPerTick", d.maxColumnsPerTick));
    }

    private static String string(JsonObject o, String key, String fallback) {
        try { return o.has(key) ? o.get(key).getAsString() : fallback; } catch (RuntimeException bad) { return fallback; }
    }

    private static int integer(JsonObject o, String key, int fallback) {
        try { return o.has(key) ? o.get(key).getAsInt() : fallback; } catch (RuntimeException bad) { return fallback; }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
