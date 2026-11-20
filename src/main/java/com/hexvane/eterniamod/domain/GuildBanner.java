package com.hexvane.eterniamod.domain;

import java.util.*;

/**
 * A guild's banner: a background colour, a pattern in a second colour and an emblem in a third. The ids match the
 * layer pictures drawn by tools/art/guild_banners.py under UI/Custom/EterniaMod/Guild/Banner.
 */
public record GuildBanner(String field, String pattern, String patternColor, String emblem, String emblemColor) {
    public record Choice(String id, String name) {}
    public record Colour(String id, String name, String hex) {}

    public static final List<Colour> COLOURS = List.of(
        new Colour("crimson", "Crimson", "#9e2b2b"), new Colour("scarlet", "Scarlet", "#c8402f"), new Colour("rust", "Rust", "#a4552c"),
        new Colour("orange", "Orange", "#d9822b"), new Colour("amber", "Amber", "#e3a83a"), new Colour("gold", "Gold", "#d8b66b"),
        new Colour("cream", "Cream", "#efe4c8"), new Colour("white", "White", "#f4f1ea"), new Colour("lime", "Lime", "#9cc44a"),
        new Colour("green", "Green", "#4f9a4c"), new Colour("forest", "Forest", "#2f5f3a"), new Colour("teal", "Teal", "#2f7f7a"),
        new Colour("cyan", "Cyan", "#4fb3c4"), new Colour("sky", "Sky", "#7fb6e0"), new Colour("blue", "Blue", "#3f6fc0"),
        new Colour("navy", "Navy", "#26375f"), new Colour("indigo", "Indigo", "#4a3f8f"), new Colour("purple", "Purple", "#73408f"),
        new Colour("magenta", "Magenta", "#b0458f"), new Colour("pink", "Pink", "#e08fb0"), new Colour("brown", "Brown", "#6b4a33"),
        new Colour("tan", "Tan", "#bfa07a"), new Colour("grey", "Grey", "#8a8f94"), new Colour("charcoal", "Charcoal", "#2e3236"));

    public static final List<Choice> PATTERNS = choices(
        "plain:Plain", "per_pale:Halves", "per_fess:Top and bottom", "quarterly:Quarters", "per_bend:Diagonal", "per_bend_sinister:Other diagonal",
        "per_chevron:Low point", "chevron:Chevron", "cross:Cross", "saltire:Saltire", "pale:Centre stripe", "fess:Band", "chief:Top band",
        "base:Bottom band", "flanks:Side bands", "bordure:Border", "paly:Stripes", "barry:Bars", "bendy:Diagonal stripes", "chequy:Checks",
        "lozenge:Diamond", "roundel:Circle", "gyronny:Pinwheel", "pile:Wedge", "per_saltire:Hourglass", "stripes:Two bars");

    public static final List<Choice> EMBLEMS = choices(
        "sword:Sword", "swords:Crossed swords", "axe:Axe", "hammer:Hammer", "hammers:Crossed hammers", "pickaxe:Pickaxe", "spear:Spear",
        "bow:Bow", "shield:Shield", "crown:Crown", "star:Star", "stars:Three stars", "sun:Sun", "moon:Moon", "pine:Pine", "oak:Oak",
        "leaf:Leaf", "rose:Flower", "clover:Clover", "wheat:Wheat", "mushroom:Mushroom", "anvil:Anvil", "key:Key", "tower:Tower",
        "castle:Castle", "anchor:Anchor", "wave:Waves", "flame:Flame", "drop:Drop", "snowflake:Snowflake", "lightning:Lightning",
        "heart:Heart", "skull:Skull", "eye:Eye", "paw:Paw", "feather:Feather", "fish:Fish", "bird:Bird", "wolf:Wolf", "stag:Stag",
        "owl:Owl", "bell:Bell", "gem:Gem", "hourglass:Hourglass", "compass:Compass", "cross:Cross", "mountains:Mountains",
        "potion:Potion", "lantern:Lantern", "book:Book", "gear:Gear", "chalice:Chalice", "fleur:Lily");

    public static final String ART = "EterniaMod/Guild/Banner/";

    public GuildBanner {
        require(colour(field), "background colour"); require(choice(PATTERNS, pattern), "pattern"); require(colour(patternColor), "pattern colour");
        require(choice(EMBLEMS, emblem), "emblem"); require(colour(emblemColor), "emblem colour");
    }

    /** A guild made before banners existed gets one picked from its id, so old guilds still look different. */
    public static GuildBanner forGuild(UUID guild) {
        long bits = guild.getMostSignificantBits() ^ guild.getLeastSignificantBits();
        int a = (int) Math.floorMod(bits, 1_000_003L);
        String field = DARK.get(a % DARK.size());
        String emblem = EMBLEMS.get((a / 7) % EMBLEMS.size()).id();
        return new GuildBanner(field, "plain", "gold", emblem, (a / 11) % 2 == 0 ? "cream" : "gold");
    }

    /** A random banner, for the dice button. */
    public static GuildBanner random(Random random) {
        String field = COLOURS.get(random.nextInt(COLOURS.size())).id();
        String pattern = PATTERNS.get(random.nextInt(PATTERNS.size())).id();
        String patternColor = contrast(field, random);
        String emblemColor = contrast(field, random);
        return new GuildBanner(field, pattern, patternColor, EMBLEMS.get(random.nextInt(EMBLEMS.size())).id(), emblemColor);
    }

    /** Stored as one line: field;pattern;patternColor;emblem;emblemColor. */
    public String encode() { return String.join(";", field, pattern, patternColor, emblem, emblemColor); }

    public static Optional<GuildBanner> decode(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String[] p = text.split(";");
        if (p.length != 5) return Optional.empty();
        try { return Optional.of(new GuildBanner(p[0], p[1], p[2], p[3], p[4])); } catch (DomainException bad) { return Optional.empty(); }
    }

    public GuildBanner withField(String v) { return new GuildBanner(v, pattern, patternColor, emblem, emblemColor); }
    public GuildBanner withPattern(String v) { return new GuildBanner(field, v, patternColor, emblem, emblemColor); }
    public GuildBanner withPatternColor(String v) { return new GuildBanner(field, pattern, v, emblem, emblemColor); }
    public GuildBanner withEmblem(String v) { return new GuildBanner(field, pattern, patternColor, v, emblemColor); }
    public GuildBanner withEmblemColor(String v) { return new GuildBanner(field, pattern, patternColor, emblem, v); }

    public static String hex(String colour) { return COLOURS.stream().filter(c -> c.id().equals(colour)).map(Colour::hex).findFirst().orElse("#ffffff"); }
    public static String colourName(String colour) { return COLOURS.stream().filter(c -> c.id().equals(colour)).map(Colour::name).findFirst().orElse(colour); }
    public static String name(List<Choice> list, String id) { return list.stream().filter(c -> c.id().equals(id)).map(Choice::name).findFirst().orElse(id); }

    private static final List<String> DARK = List.of("crimson", "rust", "forest", "teal", "navy", "indigo", "purple", "brown", "charcoal", "blue", "green");
    private static final Set<String> LIGHT = Set.of("amber", "gold", "cream", "white", "lime", "cyan", "sky", "pink", "tan");

    private static String contrast(String field, Random random) {
        boolean light = LIGHT.contains(field);
        List<String> pool = COLOURS.stream().map(Colour::id).filter(id -> LIGHT.contains(id) != light).toList();
        return pool.get(random.nextInt(pool.size()));
    }
    private static boolean colour(String id) { return id != null && COLOURS.stream().anyMatch(c -> c.id().equals(id)); }
    private static boolean choice(List<Choice> list, String id) { return id != null && list.stream().anyMatch(c -> c.id().equals(id)); }
    private static void require(boolean ok, String what) { if (!ok) throw new DomainException(DomainException.Code.INVALID_INPUT, "That banner " + what + " is not available."); }
    private static List<Choice> choices(String... pairs) {
        var out = new ArrayList<Choice>();
        for (String pair : pairs) { int i = pair.indexOf(':'); out.add(new Choice(pair.substring(0, i), pair.substring(i + 1))); }
        return List.copyOf(out);
    }
}
