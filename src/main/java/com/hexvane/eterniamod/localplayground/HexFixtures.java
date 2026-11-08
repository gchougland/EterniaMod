package com.hexvane.eterniamod.localplayground;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.JournalService;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.hexplots.PlotShape;
import com.hexvane.eterniamod.housing.HexClaimCheck;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.joml.Vector3i;

/**
 * Hex plot helpers for fixtures (local playground, native smoke, town builder): a world plan on the data default grid,
 * and a claim that runs the real rules ({@link HexClaimCheck#reserve}) and the native tail from {@code hex-plots-api.md}
 * without a connected player. World thread. Never used by player facing flows, which go through the claim map.
 */
public final class HexFixtures {
    private HexFixtures() {}

    /** The plan with hex plots on the data default grid when it has housing; other plans unchanged. */
    public static HousingInfrastructure.WorldPlan hex(HousingInfrastructure.WorldPlan plan) {
        return plan.supportsHousing() ? plan.withPlots(PlotShape.HEX, lattice()) : plan;
    }

    public static HexLattice lattice() { return HexPlotSettings.current().defaultLattice(); }

    /** A flower of seven cells around {@code center} (a guild estate start). */
    public static List<HexCoord> flower(HexCoord center) { return HexCoord.disk(center, 1); }

    /**
     * Claims {@code cells} for {@code actor} with the real rules and finishes the native side: plot projection, masked
     * terrain baseline, journal and activation. Returns the property id. Throws with the rule's message on refusal.
     */
    public static UUID claim(EterniaModPlugin plugin, World world, UUID actor, Collection<HexCoord> cells, HousingRules.Scope scope, int groundY) {
        world.getEntityStore().getStore().assertThread();
        boolean paid = scope != HousingRules.Scope.GUILD_ROOT && cells.size() == 3;
        UUID property = UUID.randomUUID(), operation = UUID.randomUUID();
        var out = HexClaimCheck.reserve(plugin, world, actor, cells, scope, paid, property, operation);
        if (!out.ok()) throw new IllegalStateException("Fixture claim refused: " + out.result().message());
        var owner = out.slot().owner();
        UUID guild = scope == HousingRules.Scope.PUBLIC ? null : plugin.getServices().guilds().membership(actor).orElseThrow().guildId();
        var plot = new HubPlotRecord(out.slot().propertyId(), world.getName(), HubPlotFootprint.forCells(out.cells(), groundY), scope == HousingRules.Scope.GUILD_ROOT ? null : actor);
        plot.setGuildOwnerUuid(scope == HousingRules.Scope.GUILD_ROOT ? guild : null);
        plot.setAttachedGuildUuid(scope == HousingRules.Scope.GUILD_MEMBER ? guild : null);
        NativeRelocationCoordinator.captureBaseline(plugin, world, plot);
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        manager.addPlot(plot);
        manager.saveIfDirty();
        var services = plugin.getServices();
        services.batch(() -> {
            var op = services.journal().find(out.slot().operationId()).orElseThrow();
            if (op.state() != JournalService.State.WORLD_APPLIED) services.journal().advance(op.id(), op.revision(), JournalService.State.WORLD_APPLIED);
            services.housing().activate(owner, out.slot().operationId());
            return null;
        });
        // Same starter grants as a real estate claim (claimCellsNow): the guild's house, path and mailbox unlocks.
        if (scope == HousingRules.Scope.GUILD_ROOT) plugin.getRuntime().welcomeGuild(guild);
        return out.slot().propertyId();
    }

    /** Block position of a cell's centre at height {@code y} (a house anchor that fits any shape holding the cell). */
    public static Vector3i centre(HexLattice lattice, HexCoord cell, int y) {
        return new Vector3i((int) Math.floor(lattice.centerX(cell)), y, (int) Math.floor(lattice.centerZ(cell)));
    }

    /** The cells of {@code world}'s plot {@code property}, for checks. */
    public static PlotCells cells(EterniaModPlugin plugin, World world, UUID property) {
        return EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).getPlot(property).getCells();
    }

    /** True when {@code world} has hex plots. */
    public static boolean isHex(World world) { return HexLand.isHexWorld(world.getName()); }
}
