package com.hexvane.eterniamod.hub;

import com.google.gson.annotations.SerializedName;
import javax.annotation.Nonnull;

public final class HubPlotFootprint {
    @SerializedName("minX")
    private int minX;

    @SerializedName("minY")
    private int minY;

    @SerializedName("minZ")
    private int minZ;

    @SerializedName("maxX")
    private int maxX;

    @SerializedName("maxY")
    private int maxY;

    @SerializedName("maxZ")
    private int maxZ;

    public HubPlotFootprint() {}

    public HubPlotFootprint(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public boolean containsBlock(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean containsFootprint(@Nonnull HubPlotFootprint other) {
        return other.minX >= minX
            && other.maxX <= maxX
            && other.minY >= minY
            && other.maxY <= maxY
            && other.minZ >= minZ
            && other.maxZ <= maxZ;
    }

    public int horizontalCenterX() {
        return (int) Math.round((minX + maxX) / 2.0);
    }

    public int horizontalCenterZ() {
        return (int) Math.round((minZ + maxZ) / 2.0);
    }
}
