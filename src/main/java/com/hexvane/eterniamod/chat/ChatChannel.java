package com.hexvane.eterniamod.chat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Where a player's plain chat goes. The id is what players type and what is saved. */
public enum ChatChannel {
    SERVER("server", "#c9d3cb", List.of("s", "all", "global")),
    WORLD("world", "#85c5b3", List.of("w")),
    PARTY("party", "#8fb8e8", List.of("p")),
    GUILD("guild", "#d8b66b", List.of("g")),
    NEARBY("nearby", "#a9d18e", List.of("n", "local", "l"));

    private final String id;
    private final String color;
    private final List<String> aliases;

    ChatChannel(String id, String color, List<String> aliases) {
        this.id = id;
        this.color = color;
        this.aliases = aliases;
    }

    public String id() { return id; }
    /** Tag and channel name colour, from the Citadel palette, readable on the dark chat backdrop. */
    public String color() { return color; }
    public List<String> aliases() { return aliases; }
    /** The shortest name, used in help text: {@code /e c p}. */
    public String shortAlias() { return aliases.getFirst(); }
    /** Server, party and guild reach players in every world; world and nearby stay in the sender's world. */
    public boolean crossesWorlds() { return this == SERVER || this == PARTY || this == GUILD; }
    /** Party and guild only reach members of a group the sender belongs to. */
    public boolean needsGroup() { return this == PARTY || this == GUILD; }

    public String langKey(String part) { return "eternia_chat.channel." + id + "." + part; }

    /** Accepts the id or any alias, in any case. Unknown text gives empty, never a guess. */
    public static Optional<ChatChannel> parse(String text) {
        if (text == null) return Optional.empty();
        String key = text.strip().toLowerCase(Locale.ROOT);
        for (ChatChannel channel : values()) {
            if (channel.id.equals(key) || channel.aliases.contains(key)) return Optional.of(channel);
        }
        return Optional.empty();
    }
}
