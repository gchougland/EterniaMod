package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.placement.HexPlacement;
import com.hexvane.eterniamod.placement.PlotFootprintUtil;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.production.PlotRoles.Role;
import java.util.HashSet;
import java.util.Set;
import org.joml.Vector3i;

/**
 * The roles of a real plot's hexagons: {@link PlotRoles} fed with what stands on the plot (its house, its stations)
 * and kept beside the plot's production record. World thread.
 */
public final class HexRoles {
    private HexRoles() {}

    /** What players chose for hexagons that are being added right now, by plot. Read once the hexagon has joined. */
    private static final java.util.Map<java.util.UUID, java.util.Map<HexCoord, Role>> WANTED = new java.util.concurrent.ConcurrentHashMap<>();

    /** The player picked what the hexagon they are adding should be. Call before the hexagon is added. */
    public static void want(java.util.UUID plotId, HexCoord cell, Role role) {
        WANTED.computeIfAbsent(plotId, id -> new java.util.concurrent.ConcurrentHashMap<>()).put(cell, role);
    }

    /** The hexagon was not added after all. */
    public static void forget(java.util.UUID plotId, HexCoord cell) {
        var wanted = WANTED.get(plotId);
        if (wanted != null) wanted.remove(cell);
    }

    /** The plot's roles as they stand now, saved if they changed. Null for a plot that is not made of hexagons. */
    public static PlotRoles of(EterniaModPlugin plugin, HubPlotRecord plot) {
        ProductionRuntime runtime = ProductionRuntime.get();
        if (runtime == null || plot == null || !plot.isHex()) return null;
        var state = runtime.plot(plot.getPlotId());
        var house = houseCells(plugin, plot);
        var wanted = WANTED.get(plot.getPlotId());
        PlotRoles roles;
        boolean changed;
        synchronized (state) {
            roles = PlotRoles.resolve(plot.getCells(), state.plot().roles, house, wanted, maxProduction(plot));
            changed = !roles.text().equals(state.plot().roles);
            if (changed) state.plot().roles = roles.text();
        }
        if (changed) runtime.save(state);
        // A choice is used up once its hexagon is part of the plot and saved with its role.
        if (wanted != null) wanted.keySet().removeIf(plot.getCells()::contains);
        return roles;
    }

    /** The most housing hexagons this plot may have (4 on a player plot, 10 on a guild plot). */
    public static int maxHouse(HubPlotRecord plot) {
        return com.hexvane.eterniamod.hexplots.PlotRules.current().tier(plot.getGuildOwnerUuid() != null).houseMax();
    }

    /** The most production hexagons this plot may have (6 on a player plot, 12 on a guild plot). */
    public static int maxProduction(HubPlotRecord plot) {
        return com.hexvane.eterniamod.hexplots.PlotRules.current().tier(plot.getGuildOwnerUuid() != null).productionMax();
    }

    /** What the player chose (or was suggested) for a hexagon being added, once {@link #addRefusal} has passed it. */
    public static Role chosen(java.util.UUID plotId, HexCoord cell) {
        var wanted = WANTED.get(plotId);
        return wanted == null ? null : wanted.get(cell);
    }

    /** Null when {@code cell} may join the plot as what the player chose (or as what is suggested), else why not. */
    public static String addRefusal(EterniaModPlugin plugin, HubPlotRecord plot, HexCoord cell) {
        PlotRoles roles = of(plugin, plot);
        if (roles == null) return null;
        int max = maxHouse(plot);
        var wanted = WANTED.get(plot.getPlotId());
        Role role = wanted == null ? null : wanted.get(cell);
        if (role == null) {
            role = roles.suggested(cell, max);
            // Whatever is checked is what the hexagon becomes.
            want(plot.getPlotId(), cell, role);
        }
        String refused = roles.canAddAs(cell, role, max);
        if (refused != null) forget(plot.getPlotId(), cell);
        return refused;
    }

    /** The hexagons the plot's house stands on. Empty when there is no house. */
    public static Set<HexCoord> houseCells(EterniaModPlugin plugin, HubPlotRecord plot) {
        var rect = houseRect(plugin, plot);
        return rect == null ? Set.of() : new HashSet<>(plot.getCells().lattice().cellsTouching(rect));
    }

    private static com.hexvane.eterniamod.housing.PlotRect houseRect(EterniaModPlugin plugin, HubPlotRecord plot) {
        if (!plot.hasBuilding()) return null;
        var house = plot.getBuilding();
        var def = plugin.getBuildingCatalog().get(house.getBuildingId());
        var buffer = def == null ? null : PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());
        if (buffer == null) return null;
        return HexPlacement.rect(PlotFootprintUtil.computeFootprint(
            new Vector3i(house.getAnchorX(), house.getAnchorY(), house.getAnchorZ()), house.resolveRotationYaw(), buffer));
    }

    /** The hexagons that hold at least one station. */
    public static Set<HexCoord> stationCells(EterniaModPlugin plugin, HubPlotRecord plot) {
        var out = new HashSet<HexCoord>();
        for (HubPlotProp prop : plot.getProps()) {
            var def = plugin.getPropCatalog().get(prop.getPropId());
            if (def != null && HexPlacement.isGenerator(def)) out.add(HexPlacement.standingCell(plot.getCells().lattice(), prop, def));
        }
        return out;
    }
}
