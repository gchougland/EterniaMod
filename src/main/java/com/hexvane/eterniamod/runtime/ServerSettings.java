package com.hexvane.eterniamod.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Private operator settings for hosts that cannot pass environment variables to Java. */
public final class ServerSettings {
    public static final String FILE_NAME = "eternia-server.properties";
    private static final Set<String> KEYS = Set.of(
        "ETERNIA_MODE", "ETERNIA_DATABASE_URL", "ETERNIA_DATABASE_USER", "ETERNIA_DATABASE_PASSWORD",
        "ETERNIA_WEBSITE_URL", "ETERNIA_BRIDGE_ADDRESS", "ETERNIA_BRIDGE_PORT", "ETERNIA_BRIDGE_TOKEN",
        "ETERNIA_TEBEX_WEBHOOK_SECRET");
    private static final String TEMPLATE = """
        # Eternia server settings. Keep this file private and outside source control.
        # Fill in values after = without surrounding quotes, then stop and start the server.
        # Nonblank environment variables override this file. Blank values use defaults.
        # This is a Java properties file: write a literal backslash as two backslashes.
        # Production requires PostgreSQL. Local mode is only for isolated development.
        ETERNIA_MODE=production
        ETERNIA_DATABASE_URL=
        ETERNIA_DATABASE_USER=
        ETERNIA_DATABASE_PASSWORD=
        ETERNIA_WEBSITE_URL=

        # Optional website and Tebex connection. Leave blank until configured.
        ETERNIA_BRIDGE_ADDRESS=
        ETERNIA_BRIDGE_PORT=
        ETERNIA_BRIDGE_TOKEN=
        ETERNIA_TEBEX_WEBHOOK_SECRET=
        """;

    private ServerSettings() {}

    public static Map<String,String> load(Path dataDirectory, Map<String,String> environment) {
        Path path = dataDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (Files.getFileStore(dataDirectory).supportsFileAttributeView("posix"))
                        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    else Files.createFile(path);
                    Files.writeString(path, TEMPLATE, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException ignored) {
                    // A concurrently created operator file must never be overwritten.
                }
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536)
                throw new IllegalArgumentException(FILE_NAME + " must be a regular file smaller than 64 KiB");
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            } catch (IllegalArgumentException invalid) {
                // Properties parser errors can include input. Never copy secret values to logs.
                throw new IllegalArgumentException("Could not read " + FILE_NAME + "; check its properties format");
            }
            var merged = new HashMap<String,String>(environment);
            for (String key : properties.stringPropertyNames()) {
                if (!KEYS.contains(key)) throw new IllegalArgumentException("Unrecognized setting in " + FILE_NAME + "; use the names in the generated template");
                String value = properties.getProperty(key);
                if (!value.isBlank()) merged.put(key, value);
            }
            for (String key : KEYS) {
                String value = environment.get(key);
                if (value != null && !value.isBlank()) merged.put(key, value);
                else if (merged.getOrDefault(key, "").isBlank()) merged.remove(key);
            }
            return Map.copyOf(merged);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read server settings at " + path.toAbsolutePath(), failure);
        }
    }

    /** Logs origin only, never a URL, password or token. */
    public static String source(String key, Map<String,String> environment, Map<String,String> settings) {
        if (!environment.getOrDefault(key, "").isBlank()) return "environment";
        return settings.containsKey(key) ? "settings file" : "not set";
    }
}
