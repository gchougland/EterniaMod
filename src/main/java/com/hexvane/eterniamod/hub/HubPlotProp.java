package com.hexvane.eterniamod.hub;

import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class HubPlotProp {
    @SerializedName("instanceId")
    private UUID instanceId;

    @SerializedName("propId")
    private String propId;

    @SerializedName("anchorX")
    private int anchorX;

    @SerializedName("anchorY")
    private int anchorY;

    @SerializedName("anchorZ")
    private int anchorZ;

    @SerializedName("rotationYaw")
    private String rotationYaw = "None";

    public HubPlotProp() {}

    public HubPlotProp(
        @Nonnull UUID instanceId,
        @Nonnull String propId,
        int anchorX,
        int anchorY,
        int anchorZ,
        @Nullable Rotation rotationYaw
    ) {
        this.instanceId = instanceId;
        this.propId = propId;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.rotationYaw = rotationYaw != null ? rotationYaw.name() : Rotation.None.name();
    }

    @Nonnull
    public UUID getInstanceId() {
        return instanceId;
    }

    @Nonnull
    public String getPropId() {
        return propId != null ? propId : "";
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

    @Nonnull
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
