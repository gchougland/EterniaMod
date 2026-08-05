package com.hexvane.eterniamod.placement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared client prefab preview cache for placement sessions. */
public interface PrefabPreviewCacheHolder {
    @Nullable
    BuildingPlacementClientPrefabPreview.Payload getClientPrefabPreviewPayload();

    @Nullable
    String getClientPrefabPreviewPathKey();

    int getClientPrefabPreviewRotationSteps();

    void setClientPrefabPreviewCache(
        @Nonnull String prefabPathKey,
        int rotationSteps,
        @Nonnull BuildingPlacementClientPrefabPreview.Payload payload
    );

    void clearClientPrefabPreviewCache();
}
