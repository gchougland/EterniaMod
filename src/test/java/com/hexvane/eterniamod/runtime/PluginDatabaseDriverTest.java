package com.hexvane.eterniamod.runtime;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.DriverManager;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class PluginDatabaseDriverTest {
    @Test void connectsWhenServerContextCannotDiscoverPluginDriver() throws Exception {
        String url = System.getenv("ETERNIA_TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank()
            && "true".equals(System.getenv("ETERNIA_TEST_DATABASE_ALLOW_WRITES")), "Requires isolated PostgreSQL test configuration");
        // Hytale can initialize JDBC before loading mods. The plugin's driver then
        // lives in a different loader from any driver already known to the server.
        DriverManager.getDrivers();
        var thread = Thread.currentThread();
        var originalContext = thread.getContextClassLoader();
        var platform = ClassLoader.getPlatformClassLoader();
        var urls = new URL[]{RuntimeConfig.class.getProtectionDomain().getCodeSource().getLocation(),
            org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation()};
        try (var plugin = new URLClassLoader(urls, platform)) {
            thread.setContextClassLoader(platform);
            var configClass = plugin.loadClass(RuntimeConfig.class.getName());
            var config = configClass.getMethod("from", Map.class).invoke(null, Map.of(
                "ETERNIA_DATABASE_URL", url,
                "ETERNIA_DATABASE_USER", System.getenv("ETERNIA_TEST_DATABASE_USER"),
                "ETERNIA_DATABASE_PASSWORD", System.getenv("ETERNIA_TEST_DATABASE_PASSWORD")));
            var constructor = plugin.loadClass(DriverDataSource.class.getName()).getDeclaredConstructor(configClass);
            constructor.setAccessible(true);
            var source = (DataSource) constructor.newInstance(config);
            try (var connection = source.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
                assertSame(plugin, connection.getClass().getClassLoader());
            }
            try (var connection = source.getConnection(System.getenv("ETERNIA_TEST_DATABASE_USER"),
                    System.getenv("ETERNIA_TEST_DATABASE_PASSWORD"))) {
                assertTrue(connection.isValid(5));
            }
        } finally {
            thread.setContextClassLoader(originalContext);
        }
    }
}
