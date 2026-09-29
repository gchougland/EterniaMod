package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.domain.Owner;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class PropPlacementSession implements PrefabPreviewCacheHolder {
    @Nonnull
    private final World world;

    @Nonnull
    private final UUID plotId;

    @Nonnull
    private Vector3i anchor;

    private int rotationSteps;

    @Nonnull
    private String propId;
    /** Server-selected source; never decoded from a token, UI event or browser field. */
    @Nullable private final Owner custodyOwner;

    @Nullable
    private String clientPrefabPreviewPathKey;
    private int clientPrefabPreviewRotationSteps = -1;

    @Nullable
    private BuildingPlacementClientPrefabPreview.Payload clientPrefabPreviewPayload;

    public PropPlacementSession(
        @Nonnull World world,
        @Nonnull UUID plotId,
        @Nonnull Vector3i anchor,
        int rotationSteps,
        @Nonnull String propId
    ) {
        this(world,plotId,anchor,rotationSteps,propId,null);
    }

    public PropPlacementSession(@Nonnull World world,@Nonnull UUID plotId,@Nonnull Vector3i anchor,int rotationSteps,@Nonnull String propId,@Nullable Owner custodyOwner) {
        this.world = world;
        this.plotId = plotId;
        this.anchor = new Vector3i(anchor);
        this.rotationSteps = rotationSteps;
        this.propId = propId;
        this.custodyOwner = custodyOwner;
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
    public String getPropId() {
        return propId;
    }
    @Nullable public Owner getCustodyOwner(){return custodyOwner;}

    public void nudge(int dx, int dy, int dz) {
        anchor = anchor.add(dx, dy, dz);
    }

    @Override
    @Nullable
    public BuildingPlacementClientPrefabPreview.Payload getClientPrefabPreviewPayload() {
        return clientPrefabPreviewPayload;
    }

    @Override
    @Nullable
    public String getClientPrefabPreviewPathKey() {
        return clientPrefabPreviewPathKey;
    }

    @Override
    public int getClientPrefabPreviewRotationSteps() {
        return clientPrefabPreviewRotationSteps;
    }

    @Override
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
