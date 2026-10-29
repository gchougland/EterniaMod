package com.hexvane.eterniamod.runtime;
import com.hexvane.eterniamod.runtime.NativeTravelService.JoinTarget;
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
}
