package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalServiceTest {
    private record F(PortalService portals, UUID aria, UUID bram, UUID cass) {}
    private F fixture() {
        var s = new EterniaServices(new InMemoryStore());
        return new F(s.portals(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test void placingAddsItToTheOwnersList() {
        var f = fixture();
        var p = f.portals.place(f.aria, "Aria", "Orbis", 10, 64, -3);
        assertEquals("Aria's portal", p.name());
        assertEquals(List.of(p.id()), f.portals.known(f.aria).stream().map(PortalService.Portal::id).toList());
        assertTrue(f.portals.known(f.bram).isEmpty(), "nobody else knows it before visiting");
        assertEquals("Aria's portal 2", f.portals.place(f.aria, "Aria", "Orbis", 20, 64, 0).name());
    }

    @Test void visitingAddsSomeoneElsesPortalOnce() {
        var f = fixture();
        var p = f.portals.place(f.aria, "Aria", "Orbis", 0, 64, 0);
        assertEquals(PortalService.Visit.OWN, f.portals.visit(f.aria, p.id()));
        assertEquals(PortalService.Visit.ADDED, f.portals.visit(f.bram, p.id()));
        assertEquals(PortalService.Visit.KNOWN, f.portals.visit(f.bram, p.id()));
        assertEquals(1, f.portals.known(f.bram).size());
    }

    @Test void privatePortalsCannotBeAddedOrUsedUnlessAllowed() {
        var f = fixture();
        var p = f.portals.place(f.aria, "Aria", "Orbis", 0, 64, 0);
        f.portals.visit(f.cass, p.id());
        f.portals.setPrivate(f.aria, false, p.id(), true);
        assertEquals(PortalService.Visit.PRIVATE, f.portals.visit(f.bram, p.id()));
        assertTrue(f.portals.known(f.bram).isEmpty());
        assertTrue(f.portals.known(f.cass).isEmpty(), "going private hides it from players who already knew it");

        f.portals.allow(f.aria, false, p.id(), f.bram);
        assertTrue(f.portals.known(f.bram).isEmpty(), "allowed players still have to visit once");
        assertEquals(PortalService.Visit.ADDED, f.portals.visit(f.bram, p.id()));
        assertEquals(1, f.portals.known(f.bram).size());

        f.portals.disallow(f.aria, false, p.id(), f.bram);
        assertTrue(f.portals.known(f.bram).isEmpty(), "taking access away hides it again");
        f.portals.setPrivate(f.aria, false, p.id(), false);
        assertEquals(1, f.portals.known(f.cass).size(), "public again: it comes back without revisiting");
        assertEquals(1, f.portals.known(f.bram).size());
    }

    @Test void onlyTheOwnerOrStaffChangeAPortal() {
        var f = fixture();
        var p = f.portals.place(f.aria, "Aria", "Orbis", 0, 64, 0);
        assertThrows(DomainException.class, () -> f.portals.rename(f.bram, false, p.id(), "Mine now"));
        assertThrows(DomainException.class, () -> f.portals.setPrivate(f.bram, false, p.id(), true));
        assertThrows(DomainException.class, () -> f.portals.allow(f.bram, false, p.id(), f.cass));
        assertEquals("Lake Dock", f.portals.rename(f.aria, false, p.id(), "  Lake   Dock ").name());
        assertEquals("Staff name", f.portals.rename(f.cass, true, p.id(), "Staff name").name());
        assertThrows(DomainException.class, () -> f.portals.rename(f.aria, false, p.id(), ""));
        assertThrows(DomainException.class, () -> f.portals.rename(f.aria, false, p.id(), "x".repeat(25)));
        assertThrows(DomainException.class, () -> f.portals.allow(f.aria, false, p.id(), f.aria));
    }

    @Test void brokenPortalsLeaveEveryList() {
        var f = fixture();
        var p = f.portals.place(f.aria, "Aria", "Orbis", 0, 64, 0);
        f.portals.visit(f.bram, p.id());
        f.portals.remove(p.id());
        assertTrue(f.portals.known(f.aria).isEmpty());
        assertTrue(f.portals.known(f.bram).isEmpty());
        assertTrue(f.portals.find(p.id()).isEmpty());
        assertThrows(DomainException.class, () -> f.portals.visit(f.bram, p.id()));
        var again = f.portals.place(f.cass, "Cass", "Orbis", 0, 64, 0);
        assertTrue(f.portals.known(f.bram).isEmpty(), "a new pad on the same spot is not known through the old one");
        assertEquals(1, f.portals.known(f.cass).size());
        assertEquals(again.id(), p.id());
    }

    @Test void aPadPlacedAgainOnTheSameSpotStartsUnknown() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-11-25T10:00:00Z"));
        var clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        var portals = new EterniaServices(new InMemoryStore(), clock, UUID::randomUUID).portals();
        UUID aria = UUID.randomUUID(), bram = UUID.randomUUID(), cass = UUID.randomUUID();
        var first = portals.place(aria, "Aria", "Orbis", 0, 64, 0);
        portals.visit(bram, first.id());
        portals.remove(first.id());
        now.set(now.get().plusSeconds(60));
        // Nobody reads Bram's list in between, so only the placement time can tell the two pads apart.
        portals.place(cass, "Cass", "Orbis", 0, 64, 0);
        assertTrue(portals.known(bram).isEmpty());
        assertTrue(portals.known(aria).isEmpty());
        assertEquals(PortalService.Visit.ADDED, portals.visit(bram, first.id()), "visiting the new pad adds it fresh");
    }

    @Test void forgettingKeepsYourOwn() {
        var f = fixture();
        var p = f.portals.place(f.aria, "Aria", "Orbis", 0, 64, 0);
        f.portals.visit(f.bram, p.id());
        f.portals.forget(f.bram, p.id());
        assertTrue(f.portals.known(f.bram).isEmpty());
        assertThrows(DomainException.class, () -> f.portals.forget(f.aria, p.id()));
    }

    @Test void oldPadsAreAdopted() {
        var f = fixture();
        var traced = f.portals.adopt("Orbis", 1, 2, 3, f.aria, "Aria");
        assertTrue(traced.ownedBy(f.aria));
        assertEquals(traced.owner(), f.portals.adopt("Orbis", 1, 2, 3, f.bram, "Bram").owner(), "an existing record wins");
        var orphan = f.portals.adopt("Orbis", 9, 9, 9, null, null);
        assertNull(orphan.owner());
        assertFalse(PortalService.mayManage(orphan, f.aria, false));
        assertTrue(PortalService.mayManage(orphan, f.aria, true));
        assertEquals(PortalService.Visit.ADDED, f.portals.visit(f.aria, orphan.id()));
    }

    @Test void ownPortalsListFirst() {
        var f = fixture();
        var theirs = f.portals.place(f.bram, "Bram", "Orbis", 0, 64, 0);
        f.portals.rename(f.bram, false, theirs.id(), "Alpha");
        var mine = f.portals.place(f.aria, "Aria", "Orbis", 5, 64, 0);
        f.portals.visit(f.aria, theirs.id());
        assertEquals(List.of(mine.id(), theirs.id()), f.portals.known(f.aria).stream().map(PortalService.Portal::id).toList());
    }
}
