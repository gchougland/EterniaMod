package com.hexvane.eterniamod.runtime;
import com.hexvane.eterniamod.runtime.NativeTravelService.JoinTarget;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoginRuleTest {
    @Test void newPlayersMeetTheHubThenWaitInHousingThenComeHome() {
        assertEquals(JoinTarget.FIRST_JOIN,NativeTravelService.joinTarget(false,false));
        assertEquals(JoinTarget.NEWCOMER,NativeTravelService.joinTarget(true,false));
        assertEquals(JoinTarget.HOME,NativeTravelService.joinTarget(true,true));
        // A plot always wins, even for accounts that predate the introduction flag.
        assertEquals(JoinTarget.HOME,NativeTravelService.joinTarget(false,true));
    }
    @Test void aReturnWithinTenMinutesOfLoggingOutResumes() {
        Instant now=Instant.parse("2026-01-01T01:00:00Z");
        assertTrue(PlayerLifecycle.resumesWhereLeft(now.minusSeconds(600),now));
        assertFalse(PlayerLifecycle.resumesWhereLeft(now.minusSeconds(601),now));
        assertFalse(PlayerLifecycle.resumesWhereLeft(null,now));
    }
}
