package com.hexvane.eterniamod.command;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import org.junit.jupiter.api.Test;

class CatalogListingTest {
    private final EterniaServices s = new EterniaServices(new InMemoryStore());
    private final CatalogListing.Entry desk = new CatalogListing.Entry("cozy_desk", "eternia:prop/cozy_desk", "Cozy Desk", false, false, "Furnishings");
    private final CatalogListing.Entry cabin = new CatalogListing.Entry("log_cabin", "eternia:house/log_cabin", "Log Cabin", true, false, "Housing");

    @Test void sellingListsTheItemAndAPriceChangeMakesANewRevision() {
        CatalogListing.sell(s, desk, 150);
        var first = s.premium().listed().getFirst();
        assertEquals(1, first.revision());
        assertEquals(150, first.price());
        assertEquals(OwnershipService.Kind.QUANTITY, first.benefits().getFirst().kind());
        CatalogListing.sell(s, desk, 150);
        assertEquals(1, s.premium().items().getFirst().revision(), "the same price changes nothing");
        CatalogListing.sell(s, desk, 220);
        assertEquals(2, s.premium().items().getFirst().revision());
        assertEquals(220, s.premium().listed().getFirst().price());
        s.premium().setListed(desk.storeId(), false);
        CatalogListing.sell(s, desk, 220);
        assertEquals(1, s.premium().listed().size(), "selling again puts it back");
    }

    @Test void housesUnlockAndPricesAreWholeCrowns() {
        CatalogListing.sell(s, cabin, 900);
        assertEquals(OwnershipService.Kind.UNLOCK, s.premium().listed().getFirst().benefits().getFirst().kind());
        assertEquals("Housing", s.premium().listed().getFirst().category());
        assertEquals(500, CatalogListing.price(" 500 "));
        assertEquals(-1, CatalogListing.price("0"));
        assertEquals(-1, CatalogListing.price("abc"));
        assertEquals(-1, CatalogListing.price("2000000"));
    }

    @Test void rowsSayWhatPlayersGetWithoutDashes() {
        String text = CatalogListing.describe(cabin, true, null, true);
        assertEquals("Log Cabin\nFree at start  ·  Not in the store", text);
        assertFalse(text.contains("-"));
    }
}
