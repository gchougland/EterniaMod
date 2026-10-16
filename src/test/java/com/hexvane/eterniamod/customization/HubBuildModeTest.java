package com.hexvane.eterniamod.customization;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HubBuildModeTest {
    @Test void sessionsNeverGrantOtherPlayersOrWorldsAccess() {
        UUID admin=UUID.randomUUID(), visitor=UUID.randomUUID(), hub=UUID.randomUUID(), adventure=UUID.randomUUID();
        var sessions=new HubBuildMode.Sessions(admin::equals);
        assertThrows(IllegalStateException.class,()->sessions.enable(visitor,hub));
        sessions.enable(admin,hub);
        assertTrue(sessions.allows(admin,hub));
        assertFalse(sessions.allows(visitor,hub));
        assertFalse(sessions.allows(admin,adventure));
        assertFalse(sessions.allows(null,hub));
        sessions.forget(admin);
        assertFalse(sessions.any(hub));
    }
    @Test void revocationAndDisconnectDoNotDisableAnotherBuilder() {
        UUID first=UUID.randomUUID(),second=UUID.randomUUID(),hub=UUID.randomUUID();
        var authorized=new HashSet<>(Set.of(first,second));
        var sessions=new HubBuildMode.Sessions(authorized::contains);
        sessions.enable(first,hub);sessions.enable(second,hub);
        authorized.remove(first);
        assertFalse(sessions.allows(first,hub));
        authorized.add(first);
        assertFalse(sessions.allows(first,hub),"Permission restoration must not silently reenable building");
        assertTrue(sessions.any(hub));
        sessions.forget(second);
        assertFalse(sessions.any(hub));
        sessions.enable(first,hub);sessions.clear();
        assertFalse(sessions.allows(first,hub));
    }
}
