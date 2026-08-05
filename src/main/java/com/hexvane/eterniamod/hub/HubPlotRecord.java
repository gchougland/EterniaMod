package com.hexvane.eterniamod.hub;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class HubPlotRecord {
    @SerializedName("plotId")
    private UUID plotId;

    @SerializedName("worldName")
    private String worldName;

    @SerializedName("footprint")
    private HubPlotFootprint footprint;

    @SerializedName("ownerUuid")
    @Nullable
    private UUID ownerUuid;

    @SerializedName("building")
    @Nullable
    private HubPlotBuilding building;

    @SerializedName("props")
    @Nullable
    private List<HubPlotProp> props;

    public HubPlotRecord() {}

    public HubPlotRecord(
        @Nonnull UUID plotId,
        @Nonnull String worldName,
        @Nonnull HubPlotFootprint footprint,
        @Nullable UUID ownerUuid
    ) {
        this.plotId = plotId;
        this.worldName = worldName;
        this.footprint = footprint;
        this.ownerUuid = ownerUuid;
    }

    @Nonnull
    public UUID getPlotId() {
        return plotId;
    }

    @Nonnull
    public String getWorldName() {
        return worldName;
    }

    @Nonnull
    public HubPlotFootprint getFootprint() {
        return footprint;
    }

    @Nullable
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(@Nullable UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    @Nullable
    public HubPlotBuilding getBuilding() {
        return building;
    }

    public void setBuilding(@Nullable HubPlotBuilding building) {
        this.building = building;
    }

    public boolean hasBuilding() {
        return building != null && building.getBuildingId() != null && !building.getBuildingId().isBlank();
    }

    public boolean isOwnedBy(@Nonnull UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    @Nonnull
    public List<HubPlotProp> getProps() {
        return props != null ? props : Collections.emptyList();
    }

    public void addProp(@Nonnull HubPlotProp prop) {
        if (props == null) {
            props = new ArrayList<>();
        }
        props.add(prop);
    }

    public boolean removeProp(@Nonnull UUID instanceId) {
        if (props == null) {
            return false;
        }
        return props.removeIf(p -> p.getInstanceId().equals(instanceId));
    }
}
