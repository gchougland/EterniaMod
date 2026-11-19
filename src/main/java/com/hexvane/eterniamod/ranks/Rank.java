package com.hexvane.eterniamod.ranks;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Eternia's ranks. Every rank comes with a prefix title of the same name and colour, so a player shows either their
 * rank or a title they earned, not both (a nameplate line has room for one).
 *
 * <ul>
 *   <li>Staff ranks carry permissions and are kept as Hytale permission groups in the server's {@code permissions.json},
 *       so they survive the beta wipe; their titles are granted again at each login.</li>
 *   <li>Supporter ranks and Streamer are cosmetic and are kept with the player's data (they reset with the beta wipe).
 *       Founder is the top supporter rank and is only given out during the beta; the others go up from Wanderer to
 *       Eternal (Eternal will be a subscription).</li>
 * </ul>
 */
public enum Rank {
    ADMIN("admin", "Admin", Kind.STAFF),
    MOD("mod", "Mod", Kind.STAFF),
    BUILDER("builder", "Builder", Kind.STAFF),
    STREAMER("streamer", "Streamer", Kind.SPECIAL),
    FOUNDER("founder", "Founder", Kind.SUPPORTER),
    WANDERER("wanderer", "Wanderer", Kind.SUPPORTER),
    STARBORN("starborn", "Starborn", Kind.SUPPORTER),
    CELESTIAL("celestial", "Celestial", Kind.SUPPORTER),
    COSMIC("cosmic", "Cosmic", Kind.SUPPORTER),
    ETERNAL("eternal", "Eternal", Kind.SUPPORTER);

    public enum Kind { STAFF, SPECIAL, SUPPORTER }

    private final String id;
    private final String displayName;
    private final Kind kind;

    Rank(String id, String displayName, Kind kind) { this.id = id; this.displayName = displayName; this.kind = kind; }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public Kind kind() { return kind; }
    public boolean staff() { return kind == Kind.STAFF; }
    /** The prefix title that comes with this rank. */
    public String titleId() { return "eternia:title/rank_" + id; }

    /** A rank from its id or name, ignoring case. */
    public static Optional<Rank> find(String text) {
        if (text == null) return Optional.empty();
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(rank -> rank.id.equals(wanted)).findFirst();
    }
}
