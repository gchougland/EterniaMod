package com.hexvane.eterniamod.runtime;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeConfigTest {
    @Test void productionCannotSilentlyUseDevelopmentDatabase() { assertThrows(IllegalArgumentException.class,()->RuntimeConfig.from(Map.of())); }
    @Test void localIsExplicitAndCannotBindPublicly() {
        assertTrue(RuntimeConfig.from(Map.of("ETERNIA_MODE","local")).local());
        assertThrows(IllegalArgumentException.class,()->RuntimeConfig.from(Map.of("ETERNIA_MODE","local","ETERNIA_BRIDGE_ADDRESS","0.0.0.0")));
    }
    @Test void secretsNeverAppearInDiagnostics() {
        var c=RuntimeConfig.from(Map.of("ETERNIA_MODE","local","ETERNIA_DATABASE_PASSWORD","sensitive"));
        assertFalse(c.toString().contains("sensitive"));
    }
    @Test void localPostgresUsesRealDatabaseWithoutProductionWebRestrictions() {
        var file = RuntimeConfig.from(Map.of("ETERNIA_MODE","local"));
        assertFalse(file.postgres());
        var postgres = RuntimeConfig.from(Map.of("ETERNIA_MODE","local","ETERNIA_DATABASE_URL","jdbc:postgresql://127.0.0.1:55432/eternia_game_dev","ETERNIA_WEBSITE_URL","http://127.0.0.1:3848"));
        assertTrue(postgres.local());
        assertTrue(postgres.postgres());
        assertThrows(IllegalArgumentException.class,()->RuntimeConfig.from(Map.of("ETERNIA_MODE","local","ETERNIA_DATABASE_URL","postgres://wrong-jdbc-format")));
        assertTrue(RuntimeConfig.from(Map.of("ETERNIA_DATABASE_URL","jdbc:postgresql://db/eternia")).postgres());
    }
}
