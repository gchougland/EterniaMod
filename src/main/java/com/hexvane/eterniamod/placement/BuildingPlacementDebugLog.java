package com.hexvane.eterniamod.placement;

import com.hypixel.hytale.logger.HytaleLogger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Temporary diagnostics for building placement UI and prefab preview. */
public final class BuildingPlacementDebugLog {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private BuildingPlacementDebugLog() {}

    public static void openAttempt(@Nonnull String playerName, @Nullable String buildingId, boolean opened) {
        LOGGER.atInfo().log(
            "Building placement open for %s building=%s opened=%s",
            playerName,
            buildingId != null ? buildingId : "?",
            opened
        );
    }

    public static void pageBuilt(@Nonnull String playerName, int eventBindings) {
        LOGGER.atInfo().log("Building placement page built for %s (eventBindings=%d)", playerName, eventBindings);
    }

    public static void uiEvent(
        @Nonnull String playerName,
        @Nullable String action,
        @Nullable Boolean birdsEye,
        @Nullable Float birdsEyeDistance,
        @Nullable String constructionId
    ) {
        LOGGER.atInfo().log(
            "Building placement UI event from %s action=%s birdsEye=%s birdsEyeDistance=%s constructionId=%s",
            playerName,
            action,
            birdsEye,
            birdsEyeDistance,
            constructionId
        );
    }

    public static void previewResult(
        @Nonnull String playerName,
        @Nonnull String prefabPath,
        boolean ok,
        @Nullable String detail
    ) {
        if (ok) {
            LOGGER.atInfo().log("Building placement preview OK for %s prefab=%s %s", playerName, prefabPath, detail);
        } else {
            LOGGER.atWarning().log("Building placement preview FAILED for %s prefab=%s %s", playerName, prefabPath, detail);
        }
    }

    public static void commonAssetsPushed(int assetCount, @Nonnull String packId) {
        LOGGER.atInfo().log("Pushed %d common assets to joining client for pack %s", assetCount, packId);
    }
}
