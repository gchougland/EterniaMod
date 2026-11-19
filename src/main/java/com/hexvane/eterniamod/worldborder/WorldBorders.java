package com.hexvane.eterniamod.worldborder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One square border per world, kept in {@code world-borders.json} in the mod's data folder so it works in any world,
 * set up for Eternia or not. A border is a centre and a radius: players stay within {@code radius} blocks of the centre
 * on both axes, which keeps them from walking into land the world has not made yet. Safe from any thread.
 */
public final class WorldBorders {
    /** The smallest and largest radius an admin may set, in blocks. */
    public static final int MIN_RADIUS = 16, MAX_RADIUS = 1_000_000;

    /** A square border: everything within {@code radius} blocks of the centre on both axes is inside. */
    public record Border(int centerX, int centerZ, int radius) {
        public Border {
            if (radius < MIN_RADIUS || radius > MAX_RADIUS) throw new IllegalArgumentException("The border radius must be " + MIN_RADIUS + " to " + MAX_RADIUS + " blocks");
        }
        public double minX() { return centerX - radius; }
        public double maxX() { return centerX + radius + 1; }
        public double minZ() { return centerZ - radius; }
        public double maxZ() { return centerZ + radius + 1; }

        /** True when the point is inside the border. */
        public boolean contains(double x, double z) { return x >= minX() && x < maxX() && z >= minZ() && z < maxZ(); }

        /** Blocks from the point to the nearest side (negative when outside). */
        public double distanceInside(double x, double z) {
            return Math.min(Math.min(x - minX(), maxX() - x), Math.min(z - minZ(), maxZ() - z));
        }

        /** The point moved {@code margin} blocks inside the border, or unchanged when already that far in. */
        public double[] clampInside(double x, double z, double margin) {
            double m = Math.min(margin, radius);
            return new double[] {Math.max(minX() + m, Math.min(maxX() - m, x)), Math.max(minZ() + m, Math.min(maxZ() - m, z))};
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<String, Border> borders = new ConcurrentHashMap<>();

    public WorldBorders(Path file) { this.file = file; }

    /** Reads the file; a missing file means no borders. A broken file is kept aside and replaced with nothing. */
    public synchronized void load() throws IOException {
        borders.clear();
        if (!Files.exists(file)) return;
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        for (var entry : root.entrySet()) {
            try { borders.put(entry.getKey(), GSON.fromJson(entry.getValue(), Border.class)); }
            catch (RuntimeException bad) { throw new IOException("The border for world " + entry.getKey() + " is not valid: " + bad.getMessage(), bad); }
        }
    }

    public Optional<Border> get(String world) { return Optional.ofNullable(borders.get(world)); }

    public Map<String, Border> all() { return Map.copyOf(borders); }

    /** Sets or replaces a world's border and saves the file. */
    public synchronized void set(String world, Border border) throws IOException {
        var next = new TreeMap<>(borders);
        next.put(world, border);
        save(next);
        borders.put(world, border);
    }

    /** Removes a world's border and saves the file. Returns false when it had none. */
    public synchronized boolean remove(String world) throws IOException {
        if (!borders.containsKey(world)) return false;
        var next = new TreeMap<>(borders);
        next.remove(world);
        save(next);
        borders.remove(world);
        return true;
    }

    private void save(Map<String, Border> next) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(next), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
