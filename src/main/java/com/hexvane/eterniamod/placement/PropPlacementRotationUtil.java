package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.prop.PropDefinition;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import javax.annotation.Nonnull;

public final class PropPlacementRotationUtil {
    private PropPlacementRotationUtil() {}

    @Nonnull
    public static Vector3d footprintCenterAtSignOrigin(
        @Nonnull PropDefinition def, @Nonnull Rotation yaw, @Nonnull IPrefabBuffer buf
    ) {
        Vector3i prefabOrigin = def.resolvePrefabAnchorWorld(new Vector3i(0, 1, 0), yaw);
        HubPlotFootprint fp = PlotFootprintUtil.computeFootprint(prefabOrigin, yaw, buf);
        return new Vector3d(
            (fp.getMinX() + fp.getMaxX() + 1) * 0.5,
            (fp.getMinY() + fp.getMaxY() + 1) * 0.5,
            (fp.getMinZ() + fp.getMaxZ() + 1) * 0.5
        );
    }

    public static void rotateClockwise90PreservingFootprintCenter(
        @Nonnull PropPlacementSession session, @Nonnull PropDefinition def, @Nonnull IPrefabBuffer buf
    ) {
        Rotation oldYaw = session.getPrefabYaw();
        Vector3d k0 = footprintCenterAtSignOrigin(def, oldYaw, buf);
        Vector3i sign0 = session.getAnchor();
        session.rotateClockwise90();
        Rotation newYaw = session.getPrefabYaw();
        Vector3d k1 = footprintCenterAtSignOrigin(def, newYaw, buf);
        session.setAnchor(
            new Vector3i(
                (int) Math.round(sign0.x + k0.x - k1.x),
                (int) Math.round(sign0.y + k0.y - k1.y),
                (int) Math.round(sign0.z + k0.z - k1.z)
            )
        );
    }
}
