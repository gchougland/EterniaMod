package com.hexvane.eterniamod.placement;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PropPlacementSessions {
    private static final Map<UUID, PropPlacementSession> BY_PLAYER = new ConcurrentHashMap<>();

    private PropPlacementSessions() {}

    @Nullable
    public static PropPlacementSession get(@Nonnull UUID playerUuid) {
        return BY_PLAYER.get(playerUuid);
    }

    public static void put(@Nonnull UUID playerUuid, @Nonnull PropPlacementSession session) {
        BY_PLAYER.put(playerUuid, session);
    }

    public static void remove(@Nonnull UUID playerUuid) {
        BY_PLAYER.remove(playerUuid);
    }
}
