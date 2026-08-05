package com.hexvane.eterniamod.hub;

import com.google.gson.annotations.SerializedName;
import javax.annotation.Nonnull;

/** World block saved before the prefab modified this cell at placement time. */
public final class ReplacedBlockCell {
    @SerializedName("x")
    private int x;

    @SerializedName("y")
    private int y;

    @SerializedName("z")
    private int z;

    @SerializedName("blockId")
    private String blockId;

    @SerializedName("rotationIndex")
    private int rotationIndex;

    public ReplacedBlockCell() {}

    public ReplacedBlockCell(int x, int y, int z, @Nonnull String blockId, int rotationIndex) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockId = blockId;
        this.rotationIndex = rotationIndex;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    @Nonnull
    public String getBlockId() {
        return blockId != null ? blockId : "";
    }

    public int getRotationIndex() {
        return rotationIndex;
    }

    public long packPos() {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | (long) z & 0x3FFFFFFL;
    }
}
