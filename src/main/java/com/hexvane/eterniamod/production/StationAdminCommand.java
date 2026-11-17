package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.placement.PropPlacementCommit;
import com.hexvane.eterniamod.placement.PropPlacementSession;
import com.hexvane.eterniamod.placement.PropPlacementValidator;
import com.hexvane.eterniamod.production.ProductionRuntime.Station;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/**
 * Admin tools for trying production without waiting: {@code /e admin station place|fill|rich|golden|collect|work|open|max|info|menu},
 * plus {@code addhex} (add a free hexagon beside the plot, through the same steps as the Claim Map) and {@code roles}
 * (say what each hexagon is for).
 * Each acts on the station of the plot the admin stands on. They go through the same code a player's key press does.
 */
public final class StationAdminCommand extends AbstractCommandCollection {
    public StationAdminCommand() {
        super("station", "Try production stations without waiting");
        setPermissionGroups("hytale:WorldEditor");
        for (String action : new String[] {"place", "fill", "rich", "golden", "collect", "work", "open", "max", "info", "addhex", "roles", "menu"}) addSubCommand(new Do(action));
    }

    /** Hexagons that touch the plot and are not part of it, nearest the plot's first hexagon first. */
    private static java.util.List<com.hexvane.eterniamod.hexplots.HexCoord> candidates(com.hexvane.eterniamod.hub.HubPlotRecord plot) {
        var out = new java.util.LinkedHashSet<com.hexvane.eterniamod.hexplots.HexCoord>();
        for (var cell : plot.getCells().cells()) for (var n : cell.neighbors()) if (!plot.getCells().contains(n)) out.add(n);
        return java.util.List.copyOf(out);
    }

    /** Tries each neighbouring hexagon in turn until one is accepted, exactly as the Claim Map's add button would. */
    private static void addHexagon(EterniaModPlugin plugin, World world, PlayerRef player, com.hexvane.eterniamod.hub.HubPlotRecord plot,
                                   java.util.List<com.hexvane.eterniamod.hexplots.HexCoord> left) {
        if (left.isEmpty()) { player.sendMessage(Message.raw("No hexagon beside this plot could be added.")); return; }
        var cell = left.removeFirst();
        com.hexvane.eterniamod.claimmap.HexClaimCommits.current().expandPlot(player, world, plot.getPlotId().toString(), cell).whenComplete((done, failure) -> world.execute(() -> {
            if (failure != null || done == null || !done.ok()) { addHexagon(plugin, world, player, plot, left); return; }
            var fresh = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).getPlot(plot.getPlotId());
            var box = fresh.getCells().lattice().bounds(java.util.List.of(cell));
            player.sendMessage(Message.raw("Added hexagon " + cell + ", middle at " + (box.x() + box.width() / 2) + " " + (box.z() + box.depth() / 2) + "."));
            sayRoles(plugin, player, fresh);
        }));
    }

    private static void sayRoles(EterniaModPlugin plugin, PlayerRef player, com.hexvane.eterniamod.hub.HubPlotRecord plot) {
        var roles = HexRoles.of(plugin, plot);
        var text = new StringBuilder("Hexagons:");
        if (roles != null) for (var entry : roles.all().entrySet()) text.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        player.sendMessage(Message.raw(text.toString()));
    }

    private static final class Do extends AbstractPlayerCommand {
        private final String action;

        Do(String action) {
            super(action, "Production station: " + action);
            setPermissionGroups("hytale:WorldEditor");
            this.action = action;
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            var plugin = EterniaModPlugin.get();
            ProductionRuntime runtime = ProductionRuntime.get();
            if (plugin == null || runtime == null) return;
            if (action.equals("menu")) { ProductionJournalPage.open(ref, store, player); return; }
            var at = player.getTransform().getPosition();
            int x = (int) Math.floor(at.x), y = (int) Math.floor(at.y), z = (int) Math.floor(at.z);
            var plot = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).findPlotContainingHorizontal(x, z);
            if (plot == null) { player.sendMessage(Message.raw("Stand on a plot first.")); return; }
            if (action.equals("place")) {
                var def = plugin.getPropCatalog().get("station_miner");
                var session = new PropPlacementSession(world, plot.getPlotId(),
                    new Vector3i(x, y + com.hexvane.eterniamod.EterniaModConstants.PLOT_SIGN_BLOCK_Y_ABOVE_LOGICAL_ANCHOR, z), 0, "station_miner");
                String refused = def == null ? "prefabMissing" : PropPlacementValidator.validate(world, plot, player.getUuid(), session.getAnchor(), session.getPrefabYaw(), def);
                if (refused != null) { player.sendMessage(Message.raw("Cannot place here: " + refused)); return; }
                player.sendMessage(Message.raw(PropPlacementCommit.place(ref, store, session, plugin) ? "Mine Shaft placed." : "Placing did not finish."));
                return;
            }
            if (action.equals("roles") || action.equals("addhex")) {
                if (!plot.isHex()) { player.sendMessage(Message.raw("This plot is not made of hexagons.")); return; }
                if (action.equals("addhex")) { addHexagon(plugin, world, player, plot, new java.util.ArrayList<>(candidates(plot))); return; }
                sayRoles(plugin, player, plot);
                return;
            }
            var stations = runtime.stationsOf(plot.getPlotId());
            if (stations.isEmpty()) { player.sendMessage(Message.raw("This plot has no station. Use /e admin station place.")); return; }
            Station s = stations.getFirst();
            Profession p = runtime.profession(s);
            switch (action) {
                case "fill" -> { synchronized (s) { s.row.progress.stockpile = ProductionMath.cap(runtime.rules, p, s.row.progress, runtime.mods(s)); s.row.progress.full = true; } }
                case "rich" -> { synchronized (s) { s.row.progress.purse += Math.max(1000, s.row.progress.purse * 9); } }
                case "golden" -> s.golden = runtime.rules.goldenWindowSeconds();
                case "collect" -> ProductionActions.use(runtime, world, ref, store, player, s, "COLLECT");
                case "work" -> ProductionActions.use(runtime, world, ref, store, player, s, "WORK");
                case "open" -> ProductionActions.use(runtime, world, ref, store, player, s, "OPEN");
                case "max" -> {
                    int[] bought = runtime.upgrade(s, 10_000);
                    if (bought[1] > 0) runtime.visuals.showTier(world, s, player.getUuid());
                }
                default -> {}
            }
            synchronized (s) {
                var g = s.row.progress;
                player.sendMessage(Message.raw(p.station() + ": level " + g.level + ", tier shown " + s.row.tierShown + ", purse " + ProductionMath.format(g.purse)
                    + ", stockpile " + ProductionMath.format(g.stockpile) + ", running " + s.running + ", at " + s.x + " " + s.y + " " + s.z));
            }
        }
    }
}
