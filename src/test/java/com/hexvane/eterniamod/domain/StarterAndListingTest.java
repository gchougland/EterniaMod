package com.hexvane.eterniamod.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The admin starter and shop menu: free starter items and taking store items down. */
class StarterAndListingTest {
    private final EterniaServices s = new EterniaServices(new InMemoryStore());

    @Test void theCabinsAreFreeUntilAnAdminChangesIt() {
        assertTrue(s.starters().free().containsAll(List.of("eternia:house/darkwood_cabin", "eternia:house/lightwood_cabin", "eternia:house/log_cabin")));
        assertFalse(s.starters().free().contains("eternia:house/hub_house"), "the placeholder house is gone");
        assertTrue(s.starters().setFree("eternia:house/log_cabin", false));
        assertFalse(s.starters().setFree("eternia:house/log_cabin", false), "nothing to do twice");
        assertFalse(s.starters().free().contains("eternia:house/log_cabin"), "turned off stays off: the defaults are written only once");
        assertTrue(s.starters().setFree("eternia:prop/cozy_desk", true));
        assertTrue(s.starters().free().contains("eternia:prop/cozy_desk"));
        assertThrows(DomainException.class, () -> s.starters().setFree("eternia:title/rank_admin", true), "only houses and decorations");
    }

    @Test void aStoreItemTakenDownLeavesTheStoreAndCannotBeBought() {
        UUID player = UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(player, "Shopper");
        s.premium().creditLocalExample(player, 1000, "local-playground:shopper");
        var item = new PremiumService.Item("eternia:store/cozy_desk", 1, "Cozy Desk", "A desk.", "Furnishings", 200,
            List.of(new CommerceService.Benefit("eternia:prop/cozy_desk", OwnershipService.Kind.QUANTITY, 1, false)));
        s.premium().register(item);
        assertEquals(1, s.premium().listed().size());
        s.premium().setListed(item.id(), false);
        assertTrue(s.premium().listed().isEmpty());
        assertEquals(1, s.premium().items().size(), "the item is kept so owners and history still know it");
        assertEquals(java.util.Set.of(item.id()), s.premium().retiredIds());
        var refused = assertThrows(DomainException.class, () -> s.premium().purchase(player, item.id(), 1, 200, "click"));
        assertEquals(DomainException.Code.INVALID_STATE, refused.code());
        s.premium().setListed(item.id(), true);
        assertEquals(1, s.premium().listed().size());
        s.premium().purchase(player, item.id(), 1, 200, "click");
        assertEquals(1, s.ownership().available(Owner.player(player), "eternia:prop/cozy_desk"));
    }
}
