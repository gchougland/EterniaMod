package com.hexvane.eterniamod.hexplots;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.GuildService;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one entry point to hex land for every feature: claim map, world map overlay, border glow, protection helpers
 * and the claim and expansion commits.
 *
 * <ul>
 *   <li>{@link #view(World)}: an immutable {@link HexLandView} of the world (world thread; cached, rebuilt only when
 *       roads, plots, reservations or road work change).</li>
 *   <li>{@link #context(World)}: the {@link HousingService.HexContext} to pass to {@code claimCells / expandPlot}.</li>
 *   <li>{@link #viewer} and {@link #loadNames}: account lookups, off the world thread.</li>
 * </ul>
 *
 * <p>Street cells are every cell a public road or plaza area of the world's infrastructure plan touches (spline roads
 * register their exact columns as road areas). Blocked cells are cells touching an unfinished road edit, unfinished
 * paving or a guild community road.
 */
public final class HexLand {
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();
    private static final int MAX_NAMES = 20_000;

    private record Cached(long infraRevision, HousingInfrastructure.WorldPlan plan, Set<HexCoord> street,
                          List<PlotRect> blockedInputs, Set<HexCoord> blocked,
                          long plotsRevision, Set<HexCoord> reserved, HexLandView view) {}

    private HexLand() {}

    /** True when {@code world} is a housing world whose plots are hexagons. Any thread. */
    public static boolean isHexWorld(String world) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        return plugin != null && plugin.getInfrastructure().world(world).map(HousingInfrastructure.WorldPlan::hexPlots).orElse(false);
    }

    /** The world's lattice when it has one (hex worlds always do). Any thread. */
    public static Optional<HexLattice> lattice(String world) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        return plugin == null ? Optional.empty() : plugin.getInfrastructure().world(world).flatMap(HousingInfrastructure.WorldPlan::hexLattice);
    }

    /** {@link #view(EterniaModPlugin, World)} with the running plugin. */
    public static Optional<HexLandView> view(World world) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        return plugin == null ? Optional.empty() : view(plugin, world);
    }

    /**
     * The land of {@code world}, or empty when the world has no lattice. World thread only (reads the plot manager).
     * Cheap when nothing changed: a few revision compares.
     */
    public static Optional<HexLandView> view(EterniaModPlugin plugin, World world) {
        String name = world.getName();
        HousingInfrastructure.Snapshot snapshot = plugin.getInfrastructure().snapshot();
        HousingInfrastructure.WorldPlan plan = snapshot.worlds().get(name);
        if (plan == null || plan.hexLattice().isEmpty()) {
            CACHE.remove(name);
            return Optional.empty();
        }
        HexLattice lattice = plan.hexLattice().get();
        Cached old = CACHE.get(name);

        Set<HexCoord> street;
        if (old != null && old.infraRevision == snapshot.revision() && Objects.equals(old.plan, plan)) street = old.street;
        else street = Set.copyOf(HexClaimRules.streetCells(lattice, plan.roads(), plan.portals()));

        List<PlotRect> blockedInputs = blockedInputs(name);
        Set<HexCoord> blocked;
        if (old != null && old.street == street && old.blockedInputs.equals(blockedInputs)) blocked = old.blocked;
        else {
            Set<HexCoord> cells = new HashSet<>(lattice.cellsTouching(blockedInputs));
            cells.removeAll(street);
            blocked = Set.copyOf(cells);
        }

        HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        Set<HexCoord> reserved = plugin.getServices().housing().reservedCells(name);
        long plotsRevision = manager.getRevision();
        if (old != null && old.street == street && old.blocked == blocked && old.reserved == reserved && old.plotsRevision == plotsRevision)
            return Optional.of(old.view);

        List<HexLandView.PlotInfo> plots = new ArrayList<>();
        for (HubPlotRecord plot : manager.listPlots()) {
            PlotCells cells = plot.getCells();
            if (cells == null || !cells.lattice().equals(lattice)) continue;
            plots.add(info(plot, cells));
        }
        HexLandView view = new HexLandView(name, lattice, street, blocked, reserved, plots);
        CACHE.put(name, new Cached(snapshot.revision(), plan, street, blockedInputs, blocked, plotsRevision, reserved, view));
        return Optional.of(view);
    }

    /**
     * World facts for a durable hex commit ({@code HousingService.claimCells / expandPlot}). World thread. Build it
     * right before the commit; the domain re-checks ownership itself, and the caller should re-read
     * {@code HousingInfrastructure.snapshot().revision()} after the commit if it must be sure roads did not move.
     */
    public static Optional<HousingService.HexContext> context(EterniaModPlugin plugin, World world) {
        return view(plugin, world).map(v -> new HousingService.HexContext(v.lattice(), v.streetCells(), v.blockedCells(), HexPlotSettings.current()));
    }

    public static Optional<HousingService.HexContext> context(World world) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        return plugin == null ? Optional.empty() : context(plugin, world);
    }

    /** Plot info for one native plot record. */
    public static HexLandView.PlotInfo info(HubPlotRecord plot, PlotCells cells) {
        UUID guildOwner = plot.getGuildOwnerUuid();
        if (guildOwner != null) return new HexLandView.PlotInfo(plot.getPlotId(), HexLandView.OwnerKind.GUILD, guildOwner, guildOwner, cells);
        return new HexLandView.PlotInfo(plot.getPlotId(), HexLandView.OwnerKind.PLAYER, plot.getOwnerUuid(), plot.getAttachedGuildUuid(), cells);
    }

    /**
     * A viewer for {@link HexLandView#cell}: the player's guild is looked up here. Off the world thread (domain read).
     * Pass {@code intent} = the kind of plot the player is about to claim, or null when just looking.
     */
    public static HexLandView.Viewer viewer(EterniaServices services, UUID player, HexClaimRules.Kind intent) {
        UUID guild = services.guilds().membership(player).map(GuildService.Membership::guildId).orElse(null);
        return new HexLandView.Viewer(player, guild, intent);
    }

    /** Display name of a player or guild if {@link #loadNames} has seen it, else null. Any thread. */
    public static String nameOf(UUID id) { return id == null ? null : NAMES.get(id); }

    /** Name function to pass to {@link HexLandView#cell} and {@link HexLandView#disk}. */
    public static java.util.function.Function<UUID, String> names() { return HexLand::nameOf; }

    /**
     * Loads display names for owner ids (players and guilds) into the shared cache. Off the world thread: one domain
     * batch. Call it when a map page opens, with the owner ids of the view's plots.
     */
    public static void loadNames(EterniaServices services, Collection<UUID> ids) {
        Set<UUID> missing = new HashSet<>();
        for (UUID id : ids) if (id != null && !NAMES.containsKey(id)) missing.add(id);
        if (missing.isEmpty()) return;
        Map<UUID, String> found = services.batch(() -> {
            Map<UUID, String> out = new HashMap<>();
            services.accounts().findAll(missing).forEach((id, account) -> out.put(id, account.displayName()));
            for (UUID id : missing) if (!out.containsKey(id)) services.guilds().find(id).ifPresent(g -> out.put(id, g.name()));
            return out;
        });
        if (NAMES.size() + found.size() > MAX_NAMES) NAMES.clear();
        NAMES.putAll(found);
    }

    /** Puts known names in the cache directly (fixtures, pictures and tests). */
    public static void rememberNames(Map<UUID, String> names) { NAMES.putAll(names); }

    /** Player text for a rule result: {@code eternia_hexplots.reason.*} with {@code {count}} filled in. */
    public static com.hypixel.hytale.server.core.Message message(HexClaimRules.Result result) {
        return com.hypixel.hytale.server.core.Message.translation(result.reason().langKey())
            .param("count", String.format(java.util.Locale.ROOT, "%,d", result.count()));
    }

    /** Short player name of a cell state ({@code eternia_hexplots.state.*}), for map legends and tooltips. */
    public static com.hypixel.hytale.server.core.Message stateName(HexLandView.CellState state) {
        return com.hypixel.hytale.server.core.Message.translation("eternia_hexplots.state." + state.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Drops cached views and names (plugin shutdown, tests). */
    public static void clearCaches() {
        CACHE.clear();
        NAMES.clear();
    }

    private static List<PlotRect> blockedInputs(String world) {
        List<PlotRect> out = new ArrayList<>(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world));
        out.addAll(com.hexvane.eterniamod.setup.paving.RoadPaving.protectedRects(world));
        out.addAll(com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world));
        return List.copyOf(out);
    }
}
