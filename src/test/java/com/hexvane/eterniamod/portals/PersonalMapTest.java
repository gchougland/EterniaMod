package com.hexvane.eterniamod.portals;

import com.hexvane.eterniamod.domain.PortalService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonalMapTest {
    private static PortalService.Portal portal(String world, int x) {
        return new PortalService.Portal(PortalService.id(world, x, 64, 0), world, x, 64, 0, UUID.randomUUID(), "Aria", "P" + x, false, Set.of(), Instant.EPOCH);
    }

    @Test void mapShowsOnlyThisWorldsPortalsFromTheViewersOwnList() {
        var list = List.of(portal("Orbis", 1), portal("Hub", 2), portal("Orbis", 3));
        assertEquals(List.of("P1", "P3"), PortalIndex.visible(list, "Orbis").stream().map(PortalService.Portal::name).toList());
        assertTrue(PortalIndex.visible(List.of(), "Orbis").isEmpty());
    }

    @Test void sharedMarkersShowToTheirMakerOnly() {
        UUID me = UUID.randomUUID(), them = UUID.randomUUID();
        assertTrue(PersonalMapMarkers.sharedVisibleTo(me, me));
        assertFalse(PersonalMapMarkers.sharedVisibleTo(me, them));
        assertFalse(PersonalMapMarkers.sharedVisibleTo(me, null));
    }

    @Test void newMarkersAreAlwaysSavedPersonal() {
        var packet = new com.hypixel.hytale.protocol.packets.worldmap.CreateUserMarker(1, 2, "Camp", "User1.png", null, true);
        assertFalse(PersonalMapMarkers.personalOnly(null, packet), "the packet still goes through");
        assertFalse(packet.shared);
    }

    @Test void arrivingOnAPadIgnoresTheFirstStepOnly() {
        var now = new AtomicLong(1000);
        var guard = new PortalArrivals(now::get);
        UUID p = UUID.randomUUID();
        var pad = new Vector3i(5, 64, 5);
        assertFalse(guard.justArrived(p, "Orbis", pad));
        guard.arrived(p, "Orbis", pad);
        assertFalse(guard.justArrived(p, "Orbis", new Vector3i(6, 64, 5)), "another pad opens as usual");
        assertTrue(guard.justArrived(p, "Orbis", pad));
        assertFalse(guard.justArrived(p, "Orbis", pad), "stepping back on opens the menu");
        guard.arrived(p, "Orbis", pad);
        now.addAndGet(PortalArrivals.WINDOW_MILLIS + 1);
        assertFalse(guard.justArrived(p, "Orbis", pad), "it wears off");
    }

    @Test void closingTheMenuAtTheDepartureDoesNotWakeTheArrivalPad() {
        var guard = new PortalArrivals(() -> 1000);
        UUID p = UUID.randomUUID();
        var from = new Vector3i(0, 64, 0); var to = new Vector3i(300, 70, 10);
        guard.arrived(p, "Orbis", to);      // sent to the destination
        guard.arrived(p, "Orbis", from);    // then the menu closed at the departure pad
        assertTrue(guard.justArrived(p, "Orbis", to), "landing on the destination stays quiet");
        assertTrue(guard.justArrived(p, "Orbis", from));
    }

    @Test void portalBlockIds() {
        assertTrue(PortalBootstrap.isPortalBlock("Teleporter"));
        assertTrue(PortalBootstrap.isPortalBlock("*Teleporter_State_Definitions_Active"));
        assertFalse(PortalBootstrap.isPortalBlock("GoblinLab_Teleporter"));
        assertFalse(PortalBootstrap.isPortalBlock(null));
    }

    @Test void distanceText() {
        assertEquals("12 blocks away", PortalText.blocks(12.2));
        assertEquals("1.5k blocks away", PortalText.blocks(1499.6));
    }
}
