package com.hexvane.eterniamod.runtime;

import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorldDisplayNamesTest {
    @Test void portalDescriptionUsesCustomTextAndFallsBackWhenCleared() {
        var plan = new com.hexvane.eterniamod.housing.HousingInfrastructure.WorldPlan("adventure", java.util.List.of(), java.util.List.of(), null);
        String fallback = WorldDestinationsMenu.description(plan);
        assertTrue(fallback.contains("Explore"));
        assertEquals("Your first adventure awaits.", WorldDestinationsMenu.description(plan.withDescription("Your first adventure awaits.")));
        assertEquals(fallback, WorldDestinationsMenu.description(plan.withDescription(" ")));
    }
    @Test void nativeDisplayNamePersistenceAndFallback(@TempDir Path temp) throws Exception {
        String cp = String.join(java.io.File.pathSeparator,
            Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(WorldDisplayNames.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(WorldConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path log = temp.resolve("names.log"), failure = temp.resolve("failure.txt");
        String java = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", java).toString(),
            "-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager",
            "-cp", cp, Probe.class.getName(), failure.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Native display name test timed out");
            assertEquals(0, process.exitValue(), Files.exists(failure) ? Files.readString(failure) : Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static final class Probe {
        public static void main(String[] args) {
            try {
                com.hexvane.eterniamod.customization.HousingGameplaySyncTest.Probe.initializeNative();
                verify();
            } catch (Throwable failure) {
                var text = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(text));
                try { Files.writeString(Path.of(args[0]), text.toString()); } catch (Exception ignored) {}
                System.exit(1);
            }
        }

        private static void verify() {
            var config = new WorldConfig();
            var uuid = config.getUuid();
            config.consumeHasChanged();
            WorldDisplayNames.set(config, "  Chapter 1  ");
            check("Chapter 1".equals(config.getDisplayName()) && config.consumeHasChanged(), "Save trimmed name");
            check(uuid.equals(config.getUuid()), "Preserve world identity");
            var encoded = WorldConfig.CODEC.encode(config, new com.hypixel.hytale.codec.ExtraInfo());
            var loaded = WorldConfig.CODEC.decode(new org.bson.BsonDocument("DisplayName", encoded.get("DisplayName")), new com.hypixel.hytale.codec.ExtraInfo());
            check("Chapter 1".equals(WorldDisplayNames.label("Chapter1", loaded.getDisplayName())), "Saved name survives loading and appears verbatim");
            WorldDisplayNames.set(config, "Chapter 1");
            check(!config.consumeHasChanged(), "Unchanged name does not schedule another save");
            for (String invalid : new String[]{null, "x".repeat(65), "Chapter\n1", "Chapter\t1", "Chapter\u20281"}) {
                boolean rejected = false;
                try { WorldDisplayNames.set(config, invalid); } catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected && "Chapter 1".equals(config.getDisplayName()) && !config.consumeHasChanged(), "Invalid input leaves saved name intact");
            }
            WorldDisplayNames.set(config, "  ");
            check(config.getDisplayName() == null && config.consumeHasChanged(), "Blank restores default");
            check("Chapter1".equals(WorldDisplayNames.label("Chapter1", config.getDisplayName())), "Legacy and cleared worlds use existing fallback");
            check("THE Hub".equals(WorldDisplayNames.label("default", "THE Hub")), "Keep custom capitalization");
        }
        private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    }
}
