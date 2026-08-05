package com.hexvane.eterniamod.placement;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

public final class BuildingPlacementSession implements PrefabPreviewCacheHolder {
    @Nonnull
    private final World world;

    @Nonnull
    private final UUID plotId;

    @Nonnull
    private Vector3i anchor;

    private int rotationSteps;

    @Nonnull
    private String buildingId;

    private boolean birdsEyeSnapshotValid;
    private double birdsEyeSnapshotX;
    private double birdsEyeSnapshotY;
    private double birdsEyeSnapshotZ;
    private double birdsEyePanX;
    private double birdsEyePanZ;

    @javax.annotation.Nullable
    private String clientPrefabPreviewPathKey;
    private int clientPrefabPreviewRotationSteps = -1;

    @javax.annotation.Nullable
    private BuildingPlacementClientPrefabPreview.Payload clientPrefabPreviewPayload;

    public BuildingPlacementSession(
        @Nonnull World world,
        @Nonnull UUID plotId,
        @Nonnull Vector3i anchor,
        int rotationSteps,
        @Nonnull String buildingId
    ) {
        this.world = world;
        this.plotId = plotId;
        this.anchor = new Vector3i(anchor);
        this.rotationSteps = rotationSteps;
        this.buildingId = buildingId;
    }

    @Nonnull
    public World getWorld() {
        return world;
    }

    @Nonnull
    public UUID getPlotId() {
        return plotId;
    }

    @Nonnull
    public Vector3i getAnchor() {
        return new Vector3i(anchor);
    }

    public void setAnchor(@Nonnull Vector3i anchor) {
        this.anchor = new Vector3i(anchor);
    }

    public int getRotationSteps() {
        return rotationSteps;
    }

    public void setRotationSteps(int rotationSteps) {
        this.rotationSteps = (rotationSteps % 4 + 4) % 4;
    }

    public void rotateClockwise90() {
        setRotationSteps(rotationSteps + 1);
    }

    @Nonnull
    public Rotation getPrefabYaw() {
        return switch (rotationSteps) {
            case 1 -> Rotation.Ninety;
            case 2 -> Rotation.OneEighty;
            case 3 -> Rotation.TwoSeventy;
            default -> Rotation.None;
        };
    }

    @Nonnull
    public String getBuildingId() {
        return buildingId;
    }

    public void nudge(int dx, int dy, int dz) {
        anchor = anchor.add(dx, dy, dz);
    }

    public void clearBirdsEyeSnapshot() {
        birdsEyeSnapshotValid = false;
    }

    public void setBirdsEyeSnapshot(double x, double y, double z) {
        birdsEyeSnapshotX = x;
        birdsEyeSnapshotY = y;
        birdsEyeSnapshotZ = z;
        birdsEyeSnapshotValid = true;
    }

    public boolean hasBirdsEyeSnapshot() {
        return birdsEyeSnapshotValid;
    }

    public double getBirdsEyeSnapshotX() {
        return birdsEyeSnapshotX;
    }

    public double getBirdsEyeSnapshotY() {
        return birdsEyeSnapshotY;
    }

    public double getBirdsEyeSnapshotZ() {
        return birdsEyeSnapshotZ;
    }

    public void resetBirdsEyePan() {
        birdsEyePanX = 0.0;
        birdsEyePanZ = 0.0;
    }

    public void addBirdsEyePan(double dx, double dz) {
        birdsEyePanX += dx;
        birdsEyePanZ += dz;
    }

    public double getBirdsEyePanX() {
        return birdsEyePanX;
    }

    public double getBirdsEyePanZ() {
        return birdsEyePanZ;
    }

    @javax.annotation.Nullable
    public BuildingPlacementClientPrefabPreview.Payload getClientPrefabPreviewPayload() {
        return clientPrefabPreviewPayload;
    }

    @javax.annotation.Nullable
    public String getClientPrefabPreviewPathKey() {
        return clientPrefabPreviewPathKey;
    }

    public int getClientPrefabPreviewRotationSteps() {
        return clientPrefabPreviewRotationSteps;
    }

    public void setClientPrefabPreviewCache(
        @Nonnull String prefabPathKey,
        int rotationSteps,
        @Nonnull BuildingPlacementClientPrefabPreview.Payload payload
    ) {
        clientPrefabPreviewPathKey = prefabPathKey;
        clientPrefabPreviewRotationSteps = (rotationSteps % 4 + 4) % 4;
        clientPrefabPreviewPayload = payload;
    }

    @Override
    public void clearClientPrefabPreviewCache() {
        clientPrefabPreviewPathKey = null;
        clientPrefabPreviewRotationSteps = -1;
        clientPrefabPreviewPayload = null;
    }
}
