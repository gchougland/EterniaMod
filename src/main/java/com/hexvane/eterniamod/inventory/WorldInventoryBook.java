package com.hexvane.eterniamod.inventory;

import java.util.Objects;
import java.util.Optional;
import org.bson.BsonDocument;

/**
 * Which inventory a player is carrying and the ones they left behind, one per world group. Hytale-free so the
 * bookkeeping can be tested; {@link WorldInventories} saves it in the player's own document.
 *
 * <p>Groups: every Hub and housing world shares {@link #SHARED}; each adventure world has its own
 * ({@code "world:<name>"}). Any other world (an instance opened from an adventure world, a test world) keeps whatever
 * the player brought in.
 */
public final class WorldInventoryBook {
    public static final String SHARED = "shared";

    private String current;
    private BsonDocument stashes;

    public WorldInventoryBook() { this(null, new BsonDocument()); }

    public WorldInventoryBook(String current, BsonDocument stashes) {
        this.current = current == null || current.isBlank() ? null : current;
        this.stashes = stashes == null ? new BsonDocument() : stashes;
    }

    /** The group a world belongs to, from its Eternia role; empty when entering it should not change inventories. */
    public static Optional<String> groupFor(String worldName, Optional<String> role) {
        if (role.isEmpty()) return Optional.empty();
        return switch (role.get()) {
            case "hub", "housing" -> Optional.of(SHARED);
            case "adventure" -> Optional.of("world:" + worldName);
            default -> Optional.empty();
        };
    }

    /** The group of the inventory the player is carrying, or null before the first registered world. */
    public String current() { return current; }

    public BsonDocument stashes() { return stashes; }

    /** True when entering {@code target} means swapping what the player carries. */
    public boolean needsSwitch(String target) { return current != null && !current.equals(target); }

    /**
     * Moves to {@code target}. The first time, the player keeps what they carry and it becomes that group's inventory.
     * Otherwise {@code carried} (the encoded live inventory) is put away under the old group, and the target's saved
     * inventory is taken out and returned, or empty when the player has never carried one there. A saved inventory is
     * kept in one place only: it is removed from the book when it goes back on the player.
     */
    public Optional<BsonDocument> switchTo(String target, BsonDocument carried) {
        Objects.requireNonNull(target);
        if (current == null) { current = target; return Optional.empty(); }
        if (current.equals(target)) return Optional.empty();
        stashes.put(current, Objects.requireNonNull(carried));
        var saved = stashes.containsKey(target) ? stashes.getDocument(target) : null;
        stashes.remove(target);
        current = target;
        return Optional.ofNullable(saved);
    }

    public WorldInventoryBook copy() { return new WorldInventoryBook(current, stashes.clone()); }
}
