package com.hexvane.eterniamod.hub;

import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import javax.annotation.Nullable;

public final class HubPlotBuilding {
    @SerializedName("buildingId")
    private String buildingId;

    @SerializedName("anchorX")
    private int anchorX;

    @SerializedName("anchorY")
    private int anchorY;

    @SerializedName("anchorZ")
    private int anchorZ;

    @SerializedName("rotationYaw")
    private String rotationYaw = "None";

    public HubPlotBuilding() {}

    public HubPlotBuilding(
        @Nullable String buildingId,
        int anchorX,
        int anchorY,
        int anchorZ,
        @Nullable Rotation rotationYaw
    ) {
        this.buildingId = buildingId;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.rotationYaw = rotationYaw != null ? rotationYaw.name() : Rotation.None.name();
    }

    @Nullable
    public String getBuildingId() {
        return buildingId;
    }

    public int getAnchorX() {
        return anchorX;
    }

    public int getAnchorY() {
        return anchorY;
    }

    public int getAnchorZ() {
        return anchorZ;
    }

    public Rotation resolveRotationYaw() {
        if (rotationYaw == null || rotationYaw.isBlank()) {
            return Rotation.None;
        }
        try {
            return Rotation.valueOf(rotationYaw.trim());
        } catch (IllegalArgumentException e) {
            return Rotation.None;
        }
    }
}
