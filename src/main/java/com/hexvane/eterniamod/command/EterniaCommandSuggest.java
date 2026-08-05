package com.hexvane.eterniamod.command;

import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class EterniaCommandSuggest {
    public static final int MAX = 20;

    private EterniaCommandSuggest() {}

    public static void suggestPrefix(@Nonnull SuggestionResult result, @Nullable String partial, @Nonnull String... values) {
        suggestPrefix(result, partial, java.util.List.of(values));
    }

    public static void suggestPrefix(@Nonnull SuggestionResult result, @Nullable String partial, @Nonnull Iterable<String> values) {
        String lower = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (lower.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.suggest(value);
                if (++count >= MAX) {
                    return;
                }
            }
        }
    }

    @Nullable
    public static PlayerRef playerRef(@Nonnull CommandSender sender) {
        return sender instanceof PlayerRef ref ? ref : null;
    }

    @Nullable
    public static World playerWorld(@Nonnull CommandSender sender) {
        PlayerRef ref = playerRef(sender);
        if (ref == null || ref.getWorldUuid() == null) {
            return null;
        }
        return Universe.get().getWorld(ref.getWorldUuid());
    }
}
