package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.debug.DebugFootprintCubeUtil;
import com.hexvane.eterniamod.hub.HubPlotBoundaryWireframe;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Plot boundary during prop placement, plus a red cube when placement is invalid. */
public final class PropPlacementWireframeOverlay {
    private static final float OUTLINE_DISPLAY_SECONDS = 6f * 60f * 60f;
    private static final float INVALID_CUBE_OPACITY = 0.35f;

    private PropPlacementWireframeOverlay() {}

    public static void clearFor(@Nullable PlayerRef player) {
        if (player == null) {
            return;
        }
        player.getPacketHandler().write(new ClearDebugShapes());
    }

    public static void send(
        @Nonnull PlayerRef player,
        @Nonnull HubPlotFootprint plotFootprint,
        @Nonnull HubPlotFootprint propFootprint,
        boolean placementValid
    ) {
        clearFor(player);
        HubPlotBoundaryWireframe.sendPlotBoundary(player, plotFootprint, DebugUtils.COLOR_WHITE);
        if (!placementValid) {
            DebugFootprintCubeUtil.sendFootprintCube(
                player,
                propFootprint,
                DebugUtils.COLOR_RED,
                INVALID_CUBE_OPACITY,
                OUTLINE_DISPLAY_SECONDS,
                EterniaModConstants.PROP_BOUNDS_PADDING
            );
        }
    }
}
