package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prefab.PropPrefabOps;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Path;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class PropPlacementValidator {
    private PropPlacementValidator() {}

    @Nullable
    public static String validate(
        @Nonnull World world,
        @Nonnull HubPlotRecord plot,
        @Nonnull UUID playerUuid,
        @Nonnull Vector3i previewSignAnchor,
        @Nonnull Rotation prefabYaw,
        @Nonnull PropDefinition def
    ) {
        if (!plot.isOwnedBy(playerUuid)) {
            return "notYourPlot";
        }
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            return "prefabMissing";
        }
        IPrefabBuffer buf = PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());
        if (buf == null) {
            return "prefabMissing";
        }
        Vector3i propAnchor = def.resolvePrefabAnchorWorld(previewSignAnchor, prefabYaw);
        HubPlotFootprint propFp = PlotFootprintUtil.computeFootprint(propAnchor, prefabYaw, buf);
        HubPlotFootprint plotFp = plot.getFootprint();
        if (!plotFp.containsFootprintHorizontal(propFp)) {
            return "outsidePlot";
        }
        if (!PlotFootprintUtil.hasSolidVoxels(prefabYaw, buf)) {
            return "prefabMissing";
        }
        if (!PropPrefabOps.canPlaceSolids(world, propAnchor, prefabYaw, buf)) {
            return "blocked";
        }
        return null;
    }
}
