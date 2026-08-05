package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Path;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BuildingPlacementValidator {
    private BuildingPlacementValidator() {}

    @Nullable
    public static String validate(
        @Nonnull World world,
        @Nonnull HubPlotManager plotManager,
        @Nonnull HubPlotRecord plot,
        @Nonnull UUID playerUuid,
        @Nonnull Vector3i previewSignAnchor,
        @Nonnull Rotation prefabYaw,
        @Nonnull BuildingDefinition def,
        @Nonnull EterniaModPlugin plugin
    ) {
        if (!plot.isOwnedBy(playerUuid)) {
            return "notYourPlot";
        }
        if (plot.hasBuilding()) {
            return "plotAlreadyHasBuilding";
        }
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            return "prefabMissing";
        }
        IPrefabBuffer buf = PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());
        if (buf == null) {
            return "prefabMissing";
        }
        Vector3i buildingAnchor = def.resolvePrefabAnchorWorld(previewSignAnchor, prefabYaw);
        HubPlotFootprint buildingFp = PlotFootprintUtil.computeFootprint(buildingAnchor, prefabYaw, buf);
        HubPlotFootprint plotFp = plot.getFootprint();
        if (!plotFp.containsFootprintHorizontal(buildingFp)) {
            return "outsidePlot";
        }
        if (!PlotFootprintUtil.hasSolidVoxels(prefabYaw, buf)) {
            return "prefabMissing";
        }
        return null;
    }
}
