package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The mailbox page's checks before sending, opening a letter, and taking a letter's items. */
class MailboxRulesTest {
    final EterniaServices services = new EterniaServices(new InMemoryStore(), Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC), UUID::randomUUID);
    UUID account(String name) { UUID id = UUID.randomUUID(); services.accounts().recordAuthenticatedLogin(id, name); return id; }
    String item(UUID player, long count) {
        var r = services.escrow().prepareDeposit(Owner.player(player), new EscrowService.Item("Ore_Iron", count, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}), true), "deposit:" + UUID.randomUUID());
        services.escrow().acknowledgeNativeRemoval(r.id(), "native:" + r.id()); return r.id();
    }

    @Test void checkSendRefusesBeforeAnythingMoves() {
        UUID a = account("Ann"), b = account("Bo");
        services.mail().checkSend(a, b, "Hi", "Hello :smile:\nSecond line", 2);
        assertThrows(DomainException.class, () -> services.mail().checkSend(a, a, "Hi", "Hello", 0));
        assertThrows(DomainException.class, () -> services.mail().checkSend(a, b, "Hi", "Hello", 6));
        assertThrows(DomainException.class, () -> services.mail().checkSend(a, b, " ", "Hello", 0));
        services.mail().block(b, a, true);
        assertThrows(DomainException.class, () -> services.mail().checkSend(a, b, "Hi", "Hello", 0));
    }

    @Test void checkSendRefusesAFullMailbox() {
        UUID a = account("Ann"), b = account("Bo");
        for (int i = 0; i < 20; i++) services.mail().send(a, b, "Letter " + i, "Body", List.of(), "r" + i);
        assertThrows(DomainException.class, () -> services.mail().checkSend(a, b, "One more", "Body", 0));
    }

    @Test void openingMarksReadButKeepsTheParcel() {
        UUID a = account("Ann"), b = account("Bo");
        var letter = services.mail().send(a, b, "Gift", "For you", List.of(item(a, 5)), "gift");
        services.mail().markOpened(b, letter.id());
        var read = services.mail().inbox(b).getFirst();
        assertTrue(read.read()); assertFalse(read.claimed());
        assertFalse(services.mail().recipientsWithUnread().contains(b));
    }

    @Test void takingReturnsEachDeliveryOnce() {
        UUID a = account("Ann"), b = account("Bo");
        var letter = services.mail().send(a, b, "Gift", "For you", List.of(item(a, 5), item(a, 2)), "gift");
        var deliveries = services.mail().claimDeliveries(b, letter.id());
        assertEquals(2, deliveries.size());
        assertEquals(List.of(5L, 2L), deliveries.stream().map(d -> d.item().quantity()).toList());
        assertTrue(services.mail().claimDeliveries(b, letter.id()).isEmpty());
        assertEquals(2, services.escrow().deliveries(Owner.player(b)).size());
        assertThrows(DomainException.class, () -> services.mail().claimDeliveries(a, letter.id()));
    }
}
