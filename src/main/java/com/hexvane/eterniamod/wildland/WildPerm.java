package com.hexvane.eterniamod.wildland;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** What a friend may do on someone's land in an adventure world. */
public enum WildPerm {
    /** Break and place blocks. */
    BUILD("Build", "wild.build"),
    /** Open chests and other storage. */
    CONTAINERS("Chests", "wild.containers"),
    /** Doors, beds, benches and everything else you use. */
    USE("Doors and benches", "wild.use");

    private final String label;
    private final String capability;

    WildPerm(String label, String capability) { this.label = label; this.capability = capability; }

    public String label() { return label; }

    /** The guild role capability that grants this on guild land. */
    public String capability() { return capability; }

    public static final Set<WildPerm> ALL = Set.copyOf(EnumSet.allOf(WildPerm.class));

    public static String text(Set<WildPerm> perms) {
        StringBuilder out = new StringBuilder();
        for (WildPerm p : WildPerm.values()) if (perms.contains(p)) out.append(out.isEmpty() ? "" : ",").append(p.name().toLowerCase(Locale.ROOT));
        return out.toString();
    }

    public static Set<WildPerm> parse(String text) {
        EnumSet<WildPerm> out = EnumSet.noneOf(WildPerm.class);
        if (text == null) return out;
        for (String part : text.split(",")) {
            for (WildPerm p : WildPerm.values()) if (p.name().equalsIgnoreCase(part.trim())) out.add(p);
        }
        return out;
    }
}
