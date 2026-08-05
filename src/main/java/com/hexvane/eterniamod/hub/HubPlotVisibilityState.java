package com.hexvane.eterniamod.hub;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

public final class HubPlotVisibilityState {
    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

    private HubPlotVisibilityState() {}

    public static boolean toggle(@Nonnull UUID playerUuid) {
        if (ENABLED.contains(playerUuid)) {
            ENABLED.remove(playerUuid);
            return false;
        }
        ENABLED.add(playerUuid);
        return true;
    }

    public static boolean isEnabled(@Nonnull UUID playerUuid) {
        return ENABLED.contains(playerUuid);
    }

    public static void disable(@Nonnull UUID playerUuid) {
        ENABLED.remove(playerUuid);
    }
}
