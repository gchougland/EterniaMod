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
        if(plot.isHex())return findValidHexPosition(world,plot,actor,session,def,plugin,relative);
        var lot=plot.getFootprint();int minX=lot.getMinX()+5-relative.getMinX(),maxX=lot.getMaxX()-5-relative.getMaxX(),minZ=lot.getMinZ()+5-relative.getMinZ(),maxZ=lot.getMaxZ()-5-relative.getMaxZ();
        if(minX>maxX||minZ>maxZ)return false;
        var candidates=new java.util.ArrayList<Vector3i>();int y=session.getAnchor().y;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)candidates.add(new Vector3i(x,y,z));
        double cx=(minX+maxX)/2.0,cz=(minZ+maxZ)/2.0;candidates.sort(java.util.Comparator.comparingDouble(v->Math.pow(v.x-cx,2)+Math.pow(v.z-cz,2)));
        var manager=com.hexvane.eterniamod.hub.EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        for(var candidate:candidates)if(preview(world,manager,plot,actor,candidate,session.getPrefabYaw(),def,plugin)==null){session.setAnchor(candidate);session.clearBirdsEyeSnapshot();return true;}
        return false;
    }

    /**
     * Hex plots: the spot {@link com.hexvane.eterniamod.hexplots.HexFit#nearestFit} picks (closest to the middle of the
     * plot with the setback from its outer edges), then any other fitting spot by distance when roads or loading
     * ground rule that one out.
     */
    private static boolean findValidHexPosition(World world,HubPlotRecord plot,UUID actor,BuildingPlacementSession session,BuildingDefinition def,EterniaModPlugin plugin,HubPlotFootprint relative){
        var cells=plot.getCells();var lattice=cells.lattice();int setback=com.hexvane.eterniamod.hexplots.HexPlotSettings.current().houseSetback();
        int width=relative.getMaxX()-relative.getMinX()+1,depth=relative.getMaxZ()-relative.getMinZ()+1;int y=session.getAnchor().y;
        var manager=com.hexvane.eterniamod.hub.EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        var best=com.hexvane.eterniamod.hexplots.HexFit.nearestFit(lattice,cells.cells(),width,depth,setback);
        if(best.isEmpty())return false;
        var first=new Vector3i(best.get().x()-relative.getMinX(),y,best.get().z()-relative.getMinZ());
        if(preview(world,manager,plot,actor,first,session.getPrefabYaw(),def,plugin)==null){session.setAnchor(first);session.clearBirdsEyeSnapshot();return true;}
        var box=cells.bounds();double cx=best.get().x()+width/2.0,cz=best.get().z()+depth/2.0;
        var spots=new java.util.ArrayList<com.hexvane.eterniamod.housing.PlotRect>();
        for(int z=box.z()+setback;z+depth+setback<=box.endZ();z++)for(int x=box.x()+setback;x+width+setback<=box.endX();x++){
            var spot=new com.hexvane.eterniamod.housing.PlotRect(x,z,width,depth);
            if(com.hexvane.eterniamod.hexplots.HexFit.fits(lattice,cells.cells(),spot,setback))spots.add(spot);
        }
        spots.sort(java.util.Comparator.comparingDouble(r->Math.pow(r.x()+width/2.0-cx,2)+Math.pow(r.z()+depth/2.0-cz,2)));
        for(var spot:spots){
            var candidate=new Vector3i(spot.x()-relative.getMinX(),y,spot.z()-relative.getMinZ());
            if(preview(world,manager,plot,actor,candidate,session.getPrefabYaw(),def,plugin)==null){session.setAnchor(candidate);session.clearBirdsEyeSnapshot();return true;}
        }
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
        return validateInternal(world,plotManager,plot,playerUuid,previewSignAnchor,prefabYaw,def,plugin,false);
    }
    public static String preview(World world,HubPlotManager plotManager,HubPlotRecord plot,UUID playerUuid,Vector3i previewSignAnchor,Rotation prefabYaw,BuildingDefinition def,EterniaModPlugin plugin){return validateInternal(world,plotManager,plot,playerUuid,previewSignAnchor,prefabYaw,def,plugin,true);}
    private static String validateInternal(World world,HubPlotManager plotManager,HubPlotRecord plot,UUID playerUuid,Vector3i previewSignAnchor,Rotation prefabYaw,BuildingDefinition def,EterniaModPlugin plugin,boolean preview){
        if (!(preview?com.hexvane.eterniamod.housing.HousingAccess.previewCan(plugin,plot,playerUuid,"housing.structure"):com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,playerUuid,"housing.structure"))) {
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
        if (plot.isHex()) {
            String hex = HexPlacement.house(plot.getCells(), HexPlacement.rect(buildingFp), plugin.getInfrastructure().structureRoads(world.getName()), com.hexvane.eterniamod.hexplots.HexPlotSettings.current().houseSetback());
            if (hex != null) return hex;
            if (!PlotFootprintUtil.hasSolidVoxels(prefabYaw, buf)) return "prefabMissing";
            return com.hexvane.eterniamod.housing.NativeHousingChecks.mutation(world, plot, buildingAnchor, prefabYaw, buf, true);
        }
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
