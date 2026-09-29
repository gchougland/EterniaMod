package com.hexvane.eterniamod.runtime;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoginRuleTest {
    @Test void thirtyMinutesIsStrictAndRecentHeartbeatHandlesCrash() {
        Instant now=Instant.parse("2026-01-01T01:00:00Z");
        assertFalse(PlayerLifecycle.returnHomeAfter(now.minusSeconds(1800),null,now));
        assertTrue(PlayerLifecycle.returnHomeAfter(now.minusSeconds(1801),null,now));
        assertFalse(PlayerLifecycle.returnHomeAfter(now.minusSeconds(5000),now.minusSeconds(30),now));
    }
}
