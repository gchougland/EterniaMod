package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.server.core.universe.world.World;

/** The extra rules for putting a station down, beside the ones every decoration follows. */
public final class StationPlacement {
    private StationPlacement() {}

    /** True when a station anchored at this height would reach below the bottom of the world with its pit. */
    public static boolean shallow(Profession profession, int anchorY) { return anchorY - profession.layout().depth() - 1 < 0; }

    /**
     * The prefab a station's first tier is placed from at this height: its catalog prefab, or the twin without a pit
     * where the ground is too thin to dig. Decorations that are not stations get their own path back.
     */
    public static String prefab(PropDefinition def, int anchorY) {
        ProductionRuntime runtime = ProductionRuntime.get();
        String id = ProductionRuntime.professionOf(def.getId());
        Profession profession = runtime == null || id == null ? null : runtime.professions.get(id);
        return profession == null ? def.getPrefabPath() : profession.prefabFile(1, shallow(profession, anchorY));
    }

    /**
     * Null when the station may stand there, else a placement code. One station of each profession per plot, and only
     * on a production hexagon ({@link PlotRoles}).
     */
    public static String check(EterniaModPlugin plugin, World world, HubPlotRecord plot, PropDefinition def, HubPlotFootprint footprint) {
        String profession = ProductionRuntime.professionOf(def.getId());
        if (profession == null) return "prefabMissing";
        for (HubPlotProp prop : plot.getProps()) if (profession.equals(ProductionRuntime.professionOf(prop.getPropId()))) return "stationAlready";
        PlotRoles roles = HexRoles.of(plugin, plot);
        if (roles != null) {
            var cell = com.hexvane.eterniamod.placement.HexPlacement.standingCell(plot.getCells().lattice(), com.hexvane.eterniamod.placement.HexPlacement.rect(footprint));
            if (!roles.cells().contains(cell) || roles.of(cell) != PlotRoles.Role.PRODUCTION) return "stationNeedsProduction";
        }
        return null;
    }
}
