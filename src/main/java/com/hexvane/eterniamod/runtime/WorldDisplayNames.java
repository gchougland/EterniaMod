package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.ui.UiPresentation;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import java.util.Objects;

/** Presentation only: world IDs remain the keys for travel, housing and saved locations. */
public final class WorldDisplayNames {
    private WorldDisplayNames() {}

    public static String name(String worldId) {
        var universe = Universe.get();
        var world = universe == null ? null : universe.getWorld(worldId);
        return label(worldId, world == null ? null : world.getWorldConfig().getDisplayName());
    }

    public static String label(String worldId, String displayName) {
        return displayName == null || displayName.isBlank() ? UiPresentation.friendlyId(worldId) : displayName;
    }

    /** Call on the world's thread. Blank input restores the normal world label. */
    public static void set(WorldConfig config, String input) {
        String name = normalize(input);
        if (!Objects.equals(config.getDisplayName(), name)) {
            config.setDisplayName(name);
            config.markChanged();
        }
    }

    public static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("Enter a name or leave the field empty.");
        if (input.length() > 64 || input.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
            throw new IllegalArgumentException("Use one line with at most 64 characters.");
        String name = input.strip();
        if (name.isEmpty()) name = null;
        return name;
    }
}
