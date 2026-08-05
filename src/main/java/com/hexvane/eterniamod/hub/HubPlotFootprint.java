package com.hexvane.eterniamod.hub;

import com.google.gson.annotations.SerializedName;
import com.hexvane.eterniamod.EterniaModConstants;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    /** Y coordinate where the plot was created; outline is centered on this (± half visual height). */
    @SerializedName("visualCenterY")
    @Nullable
    private Integer visualCenterY;

    public HubPlotFootprint() {}

    public HubPlotFootprint(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this(minX, minY, minZ, maxX, maxY, maxZ, null);
    }

    public HubPlotFootprint(
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        @Nullable Integer visualCenterY
    ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.visualCenterY = visualCenterY;
    }

    @Nonnull
    public static HubPlotFootprint forCreate(
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int creationCenterY
    ) {
        int half = EterniaModConstants.HUB_PLOT_VISUAL_HEIGHT / 2;
        int visualMinY = creationCenterY - half;
        int visualMaxY = creationCenterY + half - 1;
        return new HubPlotFootprint(minX, visualMinY, minZ, maxX, visualMaxY, maxZ, creationCenterY);
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

    public int resolveVisualCenterY() {
        if (visualCenterY != null) {
            return visualCenterY;
        }
        return (minY + maxY) / 2;
    }

    public int getVisualMinY() {
        return resolveVisualCenterY() - EterniaModConstants.HUB_PLOT_VISUAL_HEIGHT / 2;
    }

    public int getVisualMaxY() {
        return resolveVisualCenterY() + EterniaModConstants.HUB_PLOT_VISUAL_HEIGHT / 2 - 1;
    }

    public boolean containsBlock(int x, int y, int z) {
        return containsHorizontal(x, z) && y >= getVisualMinY() && y <= getVisualMaxY();
    }

    public boolean containsHorizontal(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean containsFootprint(@Nonnull HubPlotFootprint other) {
        return other.minX >= minX
            && other.maxX <= maxX
            && other.minY >= minY
            && other.maxY <= maxY
            && other.minZ >= minZ
            && other.maxZ <= maxZ;
    }

    /** True when {@code other} fits inside this plot's X/Z bounds (Y is unrestricted). */
    public boolean containsFootprintHorizontal(@Nonnull HubPlotFootprint other) {
        return other.minX >= minX && other.maxX <= maxX && other.minZ >= minZ && other.maxZ <= maxZ;
    }

    public int horizontalCenterX() {
        return (int) Math.round((minX + maxX) / 2.0);
    }

    public int horizontalCenterZ() {
        return (int) Math.round((minZ + maxZ) / 2.0);
    }
}
