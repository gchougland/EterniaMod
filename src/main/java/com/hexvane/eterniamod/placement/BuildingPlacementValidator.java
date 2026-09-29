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

    /** Prefer the plot center, including legacy catalog anchor offsets; never move or place real blocks. */
    public static boolean findValidPosition(World world,HubPlotRecord plot,UUID actor,BuildingPlacementSession session,BuildingDefinition def,EterniaModPlugin plugin){
        var buffer=PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());if(buffer==null)return false;
        var relative=PlotFootprintUtil.computeFootprint(def.resolvePrefabAnchorWorld(new Vector3i(),session.getPrefabYaw()),session.getPrefabYaw(),buffer);
        var lot=plot.getFootprint();int minX=lot.getMinX()+5-relative.getMinX(),maxX=lot.getMaxX()-5-relative.getMaxX(),minZ=lot.getMinZ()+5-relative.getMinZ(),maxZ=lot.getMaxZ()-5-relative.getMaxZ();
        if(minX>maxX||minZ>maxZ)return false;
        var candidates=new java.util.ArrayList<Vector3i>();int y=session.getAnchor().y;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)candidates.add(new Vector3i(x,y,z));
        double cx=(minX+maxX)/2.0,cz=(minZ+maxZ)/2.0;candidates.sort(java.util.Comparator.comparingDouble(v->Math.pow(v.x-cx,2)+Math.pow(v.z-cz,2)));
        var manager=com.hexvane.eterniamod.hub.EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        for(var candidate:candidates)if(validate(world,manager,plot,actor,candidate,session.getPrefabYaw(),def,plugin)==null){session.setAnchor(candidate);session.clearBirdsEyeSnapshot();return true;}
        return false;
    }

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
        if (!com.hexvane.eterniamod.housing.HousingAccess.can(plugin, plot, playerUuid, "housing.structure")) {
            return "notYourPlot";
        }
        if (plot.hasBuilding()) {
            return "plotAlreadyHasBuilding";
        }
        if (!def.getHousingKind().equals(plot.getGuildOwnerUuid() == null ? "personal" : "guild")) return "housingKind";
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
        var structure = com.hexvane.eterniamod.housing.HousingRules.structure(
            com.hexvane.eterniamod.housing.NativeHousingChecks.rect(plotFp),
            com.hexvane.eterniamod.housing.NativeHousingChecks.rect(buildingFp),
            plugin.getInfrastructure().structureRoads(world.getName()));
        if (!structure.valid()) return structure.code();
        String mutation = com.hexvane.eterniamod.housing.NativeHousingChecks.mutation(world, plot, buildingAnchor, prefabYaw, buf, true);
        if (mutation != null) return mutation;
        return null;
    }
}
