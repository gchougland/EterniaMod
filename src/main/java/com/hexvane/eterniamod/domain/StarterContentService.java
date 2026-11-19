package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/**
 * The houses and decorations every player gets free, chosen by admins in the starter and shop menu. One
 * {@code starter_content} row per content id ({@code state} is {@code free} or {@code off}), so turning an item off
 * keeps its row and the defaults are never brought back. Rows start from {@link #DEFAULTS} the first time the list is
 * read. Players receive the free items when they join, each once ({@code starter-v1} receipts).
 */
public final class StarterContentService extends DomainSupport {
    static final String NAMESPACE = "starter_content";
    /** The free starter items before an admin changes anything. */
    public static final List<String> DEFAULTS = List.of(
        "eternia:house/darkwood_cabin", "eternia:house/lightwood_cabin", "eternia:house/log_cabin",
        "eternia:prop/aqua_lamp", "eternia:prop/station_miner");

    StarterContentService(TransactionalStore store, Clock clock, Supplier<UUID> ids) { super(store, clock, ids); }

    /** Content ids that are free at the start, in id order. */
    public List<String> free() { return store.transaction(StarterContentService::freeIn); }

    /** Content ids that are free at the start, read inside an open transaction. */
    public static List<String> freeIn(TransactionalStore.Transaction tx) {
        seed(tx);
        var out = new ArrayList<String>();
        for (var row : tx.scan(NAMESPACE)) if (row.value("state").equals("free")) out.add(row.key());
        out.sort(null);
        return List.copyOf(out);
    }

    /** Makes an item free at the start or not. Returns true when anything changed. */
    public boolean setFree(String contentId, boolean free) {
        require(contentId != null && (contentId.startsWith("eternia:house/") || contentId.startsWith("eternia:prop/")),
            DomainException.Code.INVALID_INPUT, "Only houses and decorations can be starter items");
        content(contentId);
        return store.transaction(tx -> {
            seed(tx);
            String state = free ? "free" : "off";
            var row = tx.find(NAMESPACE, contentId);
            if (row.isPresent() && row.get().value("state").equals(state)) return false;
            if (row.isEmpty()) tx.save(NAMESPACE, contentId, 0, fields("state", state));
            else tx.save(NAMESPACE, contentId, row.get().revision(), changed(row.get(), "state", state));
            return true;
        });
    }

    /** Writes the defaults once, the first time the list is used; a marker row remembers it was done. */
    private static void seed(TransactionalStore.Transaction tx) {
        if (tx.find(NAMESPACE, "~seeded").isPresent()) return;
        for (String id : DEFAULTS) if (tx.find(NAMESPACE, id).isEmpty()) tx.save(NAMESPACE, id, 0, fields("state", "free"));
        tx.save(NAMESPACE, "~seeded", 0, fields("state", "marker"));
    }
}
