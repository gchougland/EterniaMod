package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.domain.CommerceService;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.domain.PremiumService;
import java.util.ArrayList;
import java.util.List;

/** What the starter and shop menu ({@link CatalogManagerPage}) shows and does, free of the game's UI classes. */
final class CatalogListing {
    static final long MAX_PRICE = 1_000_000;

    private CatalogListing() {}

    /** One house or decoration. Guild houses are never starter items, since players do not own them. */
    record Entry(String id, String contentId, String name, boolean house, boolean guild, String category) {
        String storeId() { return "eternia:store/" + id; }
    }

    /** The row's second line: what players get now. */
    static String describe(Entry entry, boolean free, PremiumService.Item item, boolean retired) {
        var parts = new ArrayList<String>();
        if (entry.guild()) parts.add("Guild house");
        else parts.add(free ? "Free at start" : "Not free");
        parts.add(item == null || retired ? "Not in the store" : "In the store for " + item.price() + " Crowns");
        return entry.name() + "\n" + String.join("  ·  ", parts);
    }

    static long price(String text) {
        if (text == null) return -1;
        try {
            long value = Long.parseLong(text.strip());
            return value >= 1 && value <= MAX_PRICE ? value : -1;
        } catch (NumberFormatException bad) {
            return -1;
        }
    }

    /** Lists the item at this price: a first revision, a new revision when the price changed, then shown in the store. */
    static String sell(EterniaServices services, Entry entry, long price) {
        var premium = services.premium();
        var latest = premium.items().stream().filter(i -> i.id().equals(entry.storeId())).findFirst().orElse(null);
        if (latest == null) {
            var benefit = new CommerceService.Benefit(entry.contentId(), entry.house() ? OwnershipService.Kind.UNLOCK : OwnershipService.Kind.QUANTITY, 1, false);
            String description = entry.house() ? "A house style for your plot. Place it from your housing inventory." : "A decoration for your home. Place it from your housing inventory.";
            premium.register(new PremiumService.Item(entry.storeId(), 1, entry.name(), description, entry.category(), price, List.of(benefit)));
        } else if (latest.price() != price) {
            premium.register(new PremiumService.Item(latest.id(), latest.revision() + 1, latest.name(), latest.description(), latest.category(), price, latest.benefits(), latest.season()));
        }
        premium.setListed(entry.storeId(), true);
        return entry.name() + " is in the Crown store for " + price + " Crowns.";
    }

}
