package com.hexvane.eterniamod.hub;

import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
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

    @SerializedName("replacedBlocks")
    @Nullable
    private List<ReplacedBlockCell> replacedBlocks;

    public HubPlotBuilding() {}

    public HubPlotBuilding(
        @Nullable String buildingId,
        int anchorX,
        int anchorY,
        int anchorZ,
        @Nullable Rotation rotationYaw,
        @Nullable List<ReplacedBlockCell> replacedBlocks
    ) {
        this.buildingId = buildingId;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.rotationYaw = rotationYaw != null ? rotationYaw.name() : Rotation.None.name();
        this.replacedBlocks = replacedBlocks != null ? new ArrayList<>(replacedBlocks) : null;
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

    @Nonnull
    public List<ReplacedBlockCell> getReplacedBlocks() {
        return replacedBlocks != null ? replacedBlocks : Collections.emptyList();
    }
}
