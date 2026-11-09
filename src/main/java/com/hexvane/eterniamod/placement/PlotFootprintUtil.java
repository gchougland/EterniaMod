package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hypixel.hytale.math.util.FastRandom;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import java.util.Random;
import javax.annotation.Nonnull;

public final class PlotFootprintUtil {
    private PlotFootprintUtil() {}

    @Nonnull
    public static HubPlotFootprint computeFootprint(@Nonnull Vector3i origin, @Nonnull Rotation yaw, @Nonnull IPrefabBuffer buffer) {
        return computeFootprint(origin, yaw, buffer, false);
    }

    /** Footprint of a decoration: its blocks and its entity furniture. */
    @Nonnull
    public static HubPlotFootprint computePropFootprint(@Nonnull Vector3i origin, @Nonnull Rotation yaw, @Nonnull IPrefabBuffer buffer) {
        return computeFootprint(origin, yaw, buffer, true);
    }

    @Nonnull
    private static HubPlotFootprint computeFootprint(@Nonnull Vector3i origin, @Nonnull Rotation yaw, @Nonnull IPrefabBuffer buffer, boolean entities) {
        PrefabRotation pr = PrefabRotation.fromRotation(yaw);
        Random random = new FastRandom();
        PrefabBufferCall call = new PrefabBufferCall(random, pr);
        final int[] b = {
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE
        };
        buffer.forEach(
            IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, supportValue, blockRotation, filler, t, fluidId, fluidLevel) -> {
                if (filler != 0 || blockId == 0) {
                    return;
                }
                int wx = origin.x + x;
                int wy = origin.y + y;
                int wz = origin.z + z;
                b[0] = Math.min(b[0], wx);
                b[1] = Math.min(b[1], wy);
                b[2] = Math.min(b[2], wz);
                b[3] = Math.max(b[3], wx);
                b[4] = Math.max(b[4], wy);
                b[5] = Math.max(b[5], wz);
            },
            // Entity furniture (chairs, plates, lamps) counts too, so a prop made mostly or only of entities gets a
            // real outline and its own placement checks.
            (x, z, holders, t) -> {
                if (!entities || holders == null) return;
                for (var holder : holders) {
                    var transform = holder.getComponent(com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
                    if (transform == null || transform.getPosition() == null) continue;
                    var at = new org.joml.Vector3d(transform.getPosition());
                    pr.rotate(at);
                    int wx = origin.x + (int) Math.floor(at.x), wy = origin.y + (int) Math.floor(at.y), wz = origin.z + (int) Math.floor(at.z);
                    b[0] = Math.min(b[0], wx);
                    b[1] = Math.min(b[1], wy);
                    b[2] = Math.min(b[2], wz);
                    b[3] = Math.max(b[3], wx);
                    b[4] = Math.max(b[4], wy);
                    b[5] = Math.max(b[5], wz);
                }
            },
            null,
            call
        );
        if (b[0] == Integer.MAX_VALUE) {
            return new HubPlotFootprint(origin.x, origin.y, origin.z, origin.x, origin.y, origin.z);
        }
        return new HubPlotFootprint(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** True when the prefab has anything to place: a block or an entity. */
    public static boolean hasSolidVoxels(@Nonnull Rotation yaw, @Nonnull IPrefabBuffer buffer) {
        PrefabRotation pr = PrefabRotation.fromRotation(yaw);
        Random random = new FastRandom();
        PrefabBufferCall call = new PrefabBufferCall(random, pr);
        final boolean[] any = {false};
        buffer.forEach(
            IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, supportValue, blockRotation, filler, t, fluidId, fluidLevel) -> {
                if (filler == 0 && blockId != 0) {
                    any[0] = true;
                }
            },
            (x, z, holders, t) -> {
                if (holders != null && holders.length > 0) any[0] = true;
            },
            null,
            call
        );
        return any[0];
    }
}
