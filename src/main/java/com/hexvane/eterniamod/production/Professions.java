package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.production.ProductionMath.Mods;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The professions that exist, and how a station's upgrades fold into its multipliers. */
public final class Professions {
    /** Profession files to load, in the order they are offered. More professions are added here. */
    private static final List<String> FILES = List.of("Miner");
    private final Map<String, Profession> byId = new LinkedHashMap<>();

    public Professions(ClassLoader loader) {
        for (String file : FILES) {
            Profession p = Profession.load(loader, file);
            byId.put(p.id(), p);
        }
    }

    Professions(List<Profession> professions) { for (Profession p : professions) byId.put(p.id(), p); }

    public Profession get(String id) { return byId.get(id); }

    public List<Profession> all() { return List.copyOf(byId.values()); }

    /** How many cards of {@code path} the station holds. */
    public static int held(Profession p, StationProgress s, String path) {
        int n = 0;
        for (String id : s.talents) { var card = p.card(id); if (card != null && card.path().equals(path)) n++; }
        return n;
    }

    /**
     * Folds tools, talent cards and their set bonuses, the album, Renown and the plot's Estate ranks into one set of
     * multipliers. {@code estate} is ranks in path order (bounty, fortune, vigor); {@code boost} is any outside boost
     * to output (a good pair, feeding), 1 for none.
     */
    public static Mods mods(ProductionRules r, Profession p, StationProgress s, int[] estate, double boost) {
        var m = new Mods();
        m.pace = 1 - r.paceStep() * s.tools[ProductionRules.PACE];
        m.output = (1 + r.haulStep() * s.tools[ProductionRules.HAUL]) * boost;
        m.cap = 1 + r.storesStep() * s.tools[ProductionRules.STORES];
        m.luck = m.veinChance = 1 + r.fortuneStep() * s.tools[ProductionRules.FORTUNE];
        for (String id : s.talents) { var card = p.card(id); if (card != null) apply(m, card.effects()); }
        for (String path : Profession.PATHS) if (held(p, s, path) >= r.setSize()) apply(m, p.sets().getOrDefault(path, Map.of()));
        m.output *= (1 + r.findBonus() * s.finds.size()) * (1 + r.renownBonus() * s.renown);
        if (estate != null) {
            m.output *= 1 + 0.05 * estate[0];
            m.cap *= 1 + 0.10 * estate[0];
            m.luck *= 1 + 0.10 * estate[1];
            m.hand *= 1 + 0.10 * estate[2];
        }
        return m;
    }

    private static void apply(Mods m, Map<String, Double> effects) {
        for (var e : effects.entrySet()) {
            double v = e.getValue();
            switch (e.getKey()) {
                case "output" -> m.output *= v;
                case "cap" -> m.cap *= v;
                case "pace" -> m.pace *= v;
                case "luck" -> m.luck *= v;
                case "veinChance" -> m.veinChance *= v;
                case "rareValue" -> m.rareValue *= v;
                case "hand" -> m.hand *= v;
                case "firstCollect" -> m.firstCollect *= v;
                case "veinCycles" -> m.veinCycles += (int) v;
                case "rushSeconds" -> m.rushSeconds += v;
                default -> throw new IllegalStateException("Unknown talent effect " + e.getKey());
            }
        }
    }
}
