package com.hexvane.eterniamod.runtime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/** Keeps a small number of PostgreSQL connections alive across menu and gameplay requests. */
final class DatabasePool {
    private DatabasePool() {}
    static HikariDataSource open(DataSource source) {
        var config = new HikariConfig();
        // Use the explicit bundled driver adapter, not DriverManager discovery in Hytale's loader.
        config.setDataSource(source);
        config.setPoolName("EterniaDatabase");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setIdleTimeout(300_000);
        config.setMaxLifetime(1_800_000);
        config.setKeepaliveTime(120_000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
