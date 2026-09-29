package com.hexvane.eterniamod.prop;

import com.google.gson.annotations.SerializedName;
import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.building.PrefabLocalOffset;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class PropDefinition {
    @SerializedName("id")
    private String id;

    @SerializedName("displayName")
    private String displayName;

    @SerializedName("prefabPath")
    private String prefabPath;

    @SerializedName("plotAnchorOffset")
    private int[] plotAnchorOffset = new int[] {0, 0, 0};

    @SerializedName("rotationYaw")
    private String rotationYaw = "None";
    private String category = "prop";
    public String getCategory() { return category == null ? "prop" : category; }

    @Nonnull
    public String getId() {
        return id != null ? id : "";
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    @Nonnull
    public String getPrefabPath() {
        return prefabPath != null ? prefabPath : "";
    }

    @Nonnull
    public int[] getPlotAnchorOffset() {
        return plotAnchorOffset != null ? plotAnchorOffset : new int[] {0, 0, 0};
    }

    @Nonnull
    public Rotation getDefaultRotationYaw() {
        if (rotationYaw == null || rotationYaw.isBlank()) {
            return Rotation.None;
        }
        try {
            return Rotation.valueOf(rotationYaw.trim());
        } catch (IllegalArgumentException e) {
            return Rotation.None;
        }
    }

    @Nonnull
    public static PropDefinition create(
        @Nonnull String id,
        @Nonnull String displayName,
        @Nonnull String prefabPath
    ) {
        PropDefinition def = new PropDefinition();
        def.id = id;
        def.displayName = displayName;
        def.prefabPath = prefabPath;
        def.plotAnchorOffset = new int[] {0, 0, 0};
        def.rotationYaw = "None";
        return def;
    }

    @Nonnull
    public Vector3i resolvePrefabAnchorWorld(@Nonnull Vector3i plotSignBlockWorldPos, @Nonnull Rotation placementYaw) {
        Vector3i logical =
            new Vector3i(
                plotSignBlockWorldPos.x,
                plotSignBlockWorldPos.y - EterniaModConstants.PLOT_SIGN_BLOCK_Y_ABOVE_LOGICAL_ANCHOR,
                plotSignBlockWorldPos.z
            );
        int[] o = getPlotAnchorOffset();
        Vector3i off = new Vector3i(o[0], o[1], o[2]);
        PrefabRotation.fromRotation(placementYaw).rotate(off);
        return new Vector3i(logical.x + off.x, logical.y + off.y, logical.z + off.z);
    }
}
