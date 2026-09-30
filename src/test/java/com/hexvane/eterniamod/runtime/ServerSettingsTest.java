package com.hexvane.eterniamod.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ServerSettingsTest {
    @TempDir Path data;
    private Path file() { return data.resolve(ServerSettings.FILE_NAME); }

    @Test void hostedSettingsFileConnectsToPostgresWithoutProcessEnvironment() throws Exception {
        String url = System.getenv("ETERNIA_TEST_DATABASE_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && !url.isBlank()
            && "true".equals(System.getenv("ETERNIA_TEST_DATABASE_ALLOW_WRITES")), "Requires isolated PostgreSQL test configuration");
        var properties = new java.util.Properties();
        properties.setProperty("ETERNIA_MODE", "production");
        properties.setProperty("ETERNIA_DATABASE_URL", url);
        properties.setProperty("ETERNIA_DATABASE_USER", System.getenv("ETERNIA_TEST_DATABASE_USER"));
        properties.setProperty("ETERNIA_DATABASE_PASSWORD", System.getenv("ETERNIA_TEST_DATABASE_PASSWORD"));
        try (var writer = Files.newBufferedWriter(file())) { properties.store(writer, "Isolated test database only"); }
        var config = RuntimeConfig.from(ServerSettings.load(data, Map.of()));
        assertFalse(config.local()); assertTrue(config.postgres());
        try (var connection = new DriverDataSource(config).getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next()); assertEquals(1, result.getInt(1));
        }
    }

    @Test void firstStartCreatesEditableTemplateButDoesNotEnableLocalStorage() throws Exception {
        var settings = ServerSettings.load(data, Map.of());
        assertTrue(Files.isRegularFile(file()));
        assertEquals("production", settings.get("ETERNIA_MODE"));
        assertFalse(settings.containsKey("ETERNIA_DATABASE_URL"));
        var error = assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.from(settings));
        assertTrue(error.getMessage().contains(ServerSettings.FILE_NAME));
        assertEquals("not set", ServerSettings.source("ETERNIA_DATABASE_URL", Map.of(), settings));
    }

    @Test void hostedServerLoadsDatabaseAndCommerceWithoutEnvironmentAndPreservesFile() throws Exception {
        String contents = """
            # Operator configuration survives restart.
            ETERNIA_MODE=production
            ETERNIA_DATABASE_URL=jdbc:postgresql://db.example:1234/eternia?sslmode=require
            ETERNIA_DATABASE_USER=eternia
            ETERNIA_DATABASE_PASSWORD=a=b:#$!é
            ETERNIA_WEBSITE_URL=https://eternia.example
            ETERNIA_BRIDGE_TOKEN=01234567890123456789012345678901ab
            ETERNIA_TEBEX_WEBHOOK_SECRET=test-secret
            """;
        Files.writeString(file(), contents);
        var settings = ServerSettings.load(data, Map.of());
        var config = RuntimeConfig.from(settings);
        assertTrue(config.postgres()); assertFalse(config.local());
        assertEquals("jdbc:postgresql://db.example:1234/eternia?sslmode=require", config.databaseUrl());
        assertEquals("eternia", config.databaseUser());
        assertEquals("a=b:#$!é", config.databasePassword());
        assertEquals("https://eternia.example", config.websiteUrl());
        assertEquals("test-secret", settings.get("ETERNIA_TEBEX_WEBHOOK_SECRET"));
        assertEquals("settings file", ServerSettings.source("ETERNIA_DATABASE_URL", Map.of(), settings));
        assertEquals(settings, ServerSettings.load(data, Map.of()));
        assertEquals(contents, Files.readString(file()));
        assertFalse(config.toString().contains(config.databasePassword()));
        assertFalse(config.toString().contains(config.bridgeToken()));
    }

    @Test void nonblankEnvironmentOverridesFileButEmptyPanelEntriesDoNotMaskIt() throws Exception {
        Files.writeString(file(), "ETERNIA_DATABASE_URL=jdbc:postgresql://file/game\nETERNIA_DATABASE_PASSWORD=file-secret\n");
        var environment = Map.of("ETERNIA_DATABASE_URL", "jdbc:postgresql://environment/game",
            "ETERNIA_DATABASE_PASSWORD", "", "ETERNIA_BRIDGE_PORT", " ", "UNRELATED_SETTING", "retained");
        var settings = ServerSettings.load(data, environment);
        var config = RuntimeConfig.from(settings);
        assertEquals("jdbc:postgresql://environment/game", config.databaseUrl());
        assertEquals("file-secret", config.databasePassword());
        assertEquals(9010, config.bridgePort());
        assertEquals("retained", settings.get("UNRELATED_SETTING"));
        assertEquals("environment", ServerSettings.source("ETERNIA_DATABASE_URL", environment, settings));
        assertEquals("settings file", ServerSettings.source("ETERNIA_DATABASE_PASSWORD", environment, settings));
    }

    @Test void localLauncherStillSelectsDevelopmentModeAndBlankDefaultsAreIgnored() {
        var config = RuntimeConfig.from(ServerSettings.load(data, Map.of("ETERNIA_MODE", "local")));
        assertTrue(config.local()); assertFalse(config.postgres());
        assertEquals("127.0.0.1", config.bridgeAddress());
        assertEquals("http://127.0.0.1:3847", config.websiteUrl());
    }

    @Test void invalidPropertiesFailWithoutLeakingTheirContents() throws Exception {
        Files.writeString(file(), "ETERNIA_DATABASE_PASSWORD=private-secret" + "\\" + "uNOPE\n");
        var error = assertThrows(IllegalArgumentException.class, () -> ServerSettings.load(data, Map.of()));
        assertFalse(error.toString().contains("private-secret"));
        assertNull(error.getCause());
        assertTrue(error.getMessage().contains(ServerSettings.FILE_NAME));
    }

    @Test void unknownKeysAndOversizedFilesAreRejectedWithoutPrintingValues() throws Exception {
        Files.writeString(file(), "ETERNIA_DATABASE_PASWORD=private-secret\n");
        var error = assertThrows(IllegalArgumentException.class, () -> ServerSettings.load(data, Map.of()));
        assertFalse(error.toString().contains("private-secret"));
        Files.writeString(file(), "x".repeat(65537));
        assertThrows(IllegalArgumentException.class, () -> ServerSettings.load(data, Map.of()));
    }
}
