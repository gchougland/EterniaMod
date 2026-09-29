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
        var plugin = com.hexvane.eterniamod.EterniaModPlugin.get();
        if (plugin == null || !com.hexvane.eterniamod.housing.HousingAccess.can(plugin, plot, playerUuid, "housing.prop.place")) {
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
        if (def.getCategory().equals("addition")) {
            if (!plot.hasBuilding()) return "needsHouse";
            if (!com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,playerUuid,"housing.addition")) return "notYourPlot";
            var result = com.hexvane.eterniamod.housing.HousingRules.structure(
                com.hexvane.eterniamod.housing.NativeHousingChecks.rect(plotFp),com.hexvane.eterniamod.housing.NativeHousingChecks.rect(propFp),
                plugin.getInfrastructure().structureRoads(world.getName()));
            if (!result.valid()) return result.code();
            var house = plot.getBuilding();var houseDef = plugin.getBuildingCatalog().get(house.getBuildingId());
            var houseBuffer = houseDef == null ? null : PrefabResolveUtil.resolvePrefabBuffer(houseDef.getPrefabPath());
            if (houseBuffer == null) return "prefabMissing";
            var houseFp = PlotFootprintUtil.computeFootprint(new Vector3i(house.getAnchorX(),house.getAnchorY(),house.getAnchorZ()),house.resolveRotationYaw(),houseBuffer);
            if (com.hexvane.eterniamod.housing.NativeHousingChecks.rect(houseFp).gap(com.hexvane.eterniamod.housing.NativeHousingChecks.rect(propFp)) > 0) return "additionDetached";
        }
        if (!plotFp.containsFootprintHorizontal(propFp)) {
            return "outsidePlot";
        }
        if (!PlotFootprintUtil.hasSolidVoxels(prefabYaw, buf)) {
            return "prefabMissing";
        }
        if (!PropPrefabOps.canPlaceSolids(world, propAnchor, prefabYaw, buf)) {
            return "blocked";
        }
        String mutation = com.hexvane.eterniamod.housing.NativeHousingChecks.mutation(world, plot, propAnchor, prefabYaw, buf, false);
        if (mutation != null) return mutation;
        return null;
    }
}
