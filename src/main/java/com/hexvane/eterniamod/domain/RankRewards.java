package com.hexvane.eterniamod.domain;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * What each supporter rank gives once ({@code Server/EterniaMod/Ranks/rewards.json}). A slot with no content yet is
 * skipped and given later, at the player's next login, once the file names it.
 */
public final class RankRewards {
    public static final String RESOURCE = "Server/EterniaMod/Ranks/rewards.json";
    public static final String PLOT = "eternia:voucher/plot", HOUSE_HEX = "eternia:voucher/house_hex",
        PRODUCTION_HEX = "eternia:voucher/production_hex", GUILD_PLOT = "eternia:voucher/guild_plot";

    /**
     * {@code whileActive}: held only while the rank is (Eternal's pet and titles), given again on coming back.
     * {@code months}: paid months of a subscription rank before it is given.
     * {@code keepAfter}: given at once but only on trial: if the rank ends before this many paid months it is taken
     * back, and a guild plot it already paid for is packed away; from then on it is kept for good (Eternal's guild
     * plot voucher: 3). 0 for none.
     */
    public record Reward(String slot, String content, OwnershipService.Kind kind, long quantity, boolean whileActive, int months, int keepAfter) {
        public Reward(String slot, String content, OwnershipService.Kind kind, long quantity) { this(slot, content, kind, quantity, false, 1, 0); }
        public Reward(String slot, String content, OwnershipService.Kind kind, long quantity, boolean whileActive, int months) { this(slot, content, kind, quantity, whileActive, months, 0); }
        public Reward {
            if (months < 1 || months > 120) throw new IllegalArgumentException("Bad months in " + slot);
            if (whileActive && months > 1) throw new IllegalArgumentException("A while active reward has no months in " + slot);
            if (keepAfter != 0 && (keepAfter < 2 || keepAfter > 120 || whileActive || months > 1 || kind != OwnershipService.Kind.QUANTITY))
                throw new IllegalArgumentException("Bad keepAfter in " + slot);
            if (slot == null || !slot.matches("[a-z0-9_]{1,40}")) throw new IllegalArgumentException("Bad reward slot: " + slot);
            content = content == null ? "" : content;
            if (!content.isEmpty()) DomainSupport.content(content);
            Objects.requireNonNull(kind);
            if (quantity < 1 || kind != OwnershipService.Kind.QUANTITY && quantity != 1) throw new IllegalArgumentException("Bad reward quantity in " + slot);
        }
        public boolean decided() { return !content.isEmpty(); }
    }

    private final Map<String, List<Reward>> byRank;

    public RankRewards(Map<String, List<Reward>> byRank) {
        var copy = new TreeMap<String, List<Reward>>();
        byRank.forEach((rank, list) -> {
            if (!SupporterRanks.ALL.contains(rank)) throw new IllegalArgumentException("Unknown rank in rewards: " + rank);
            var slots = new HashSet<String>();
            for (var r : list) if (!slots.add(r.slot())) throw new IllegalArgumentException("Slot " + r.slot() + " twice for " + rank);
            copy.put(rank, List.copyOf(list));
        });
        this.byRank = Collections.unmodifiableMap(copy);
    }

    public static final RankRewards NONE = new RankRewards(Map.of());

    public List<Reward> of(String rank) { return byRank.getOrDefault(rank, List.of()); }

    /** The bundled file; nothing at all if it is missing, so a broken file never blocks logins. */
    public static RankRewards load() {
        try (InputStream in = RankRewards.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) return NONE;
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static RankRewards parse(Reader json) {
        var ranks = JsonParser.parseReader(json).getAsJsonObject().getAsJsonObject("Ranks");
        var out = new LinkedHashMap<String, List<Reward>>();
        for (var entry : ranks.entrySet()) {
            var list = new ArrayList<Reward>();
            for (var e : entry.getValue().getAsJsonArray()) {
                var o = e.getAsJsonObject();
                list.add(new Reward(o.get("slot").getAsString(), o.has("content") ? o.get("content").getAsString() : "",
                    OwnershipService.Kind.valueOf(o.get("kind").getAsString()), o.has("quantity") ? o.get("quantity").getAsLong() : 1,
                    o.has("whileActive") && o.get("whileActive").getAsBoolean(), o.has("months") ? o.get("months").getAsInt() : 1,
                    o.has("keepAfter") ? o.get("keepAfter").getAsInt() : 0));
            }
            out.put(entry.getKey(), list);
        }
        return new RankRewards(out);
    }
}
