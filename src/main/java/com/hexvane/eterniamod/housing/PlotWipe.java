package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.customization.CustomizationBootstrap;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.domain.ProvenanceService;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions;
import com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /e admin wipeplots}: clears every plot in a test world. For each plot, everything placed on it (house,
 * decorations, stations) is packed back to its owner's inventory, the garden path is removed, and the plot's ground is
 * put back the way it was saved when the plot was claimed (after levelling). Only then are the plot records removed, so the world's plot shape can
 * change. One object that will not pack never stops the rest: its ground is still restored.
 */
public final class PlotWipe {
    /** What the wipe did, for the admin's message. */
    public record Result(int plots, int packed, int grounds, List<String> problems) {}

    private PlotWipe() {}

    /** Clears the world's plots one at a time, loading each plot's ground first. Completes on the world thread. */
    public static CompletableFuture<Result> wipe(EterniaModPlugin plugin, World world) {
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        var plots = List.copyOf(manager.listPlots());
        var problems = new ArrayList<String>();
        int[] totals = new int[2];
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var plot : plots) {
            chain = chain.thenCompose(v -> plot.isHex() && plot.getCells() != null
                    ? NativeClaimCoordinator.loadGroundFor(world, plot.getCells().bounds())
                    : CompletableFuture.completedFuture(null))
                .thenRunAsync(() -> {
                    totals[0] += packAll(plugin, world, plot, problems);
                    try { if (NativeRelocationCoordinator.restoreGround(plugin, world, plot)) totals[1]++; }
                    catch (RuntimeException failure) {
                        problems.add("Plot " + plot.getPlotId() + ": its ground could not be restored (" + failure.getMessage() + ")");
                        plugin.getLogger().atWarning().withCause(failure).log("Wipe: ground of plot %s", plot.getPlotId());
                    }
                }, world);
        }
        return chain.thenApplyAsync(v -> {
            int removed = 0;
            for (var plot : manager.listPlots()) if (manager.removePlot(plot.getPlotId())) removed++;
            manager.saveIfDirty();
            int saved = plugin.getServices().housing().wipeWorld(world.getName());
            return new Result(Math.max(removed, saved), totals[0], totals[1], List.copyOf(problems));
        }, world);
    }

    /** Packs every placed object on the plot, newest first, so whatever stands on another goes first. World thread. */
    private static int packAll(EterniaModPlugin plugin, World world, HubPlotRecord plot, List<String> problems) {
        var services = plugin.getServices();
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        var placed = new ArrayList<ProvenanceService.Instance>();
        for (var item : services.provenance().instances(plot.getPlotId())) if (item.state().equals("PLACED")) placed.add(item);
        placed.sort(Comparator.comparingLong((ProvenanceService.Instance i) -> Long.parseLong(i.nativeData().getOrDefault("placedAt", "0"))).reversed()
            .thenComparing(i -> "HOUSE".equals(i.nativeData().get("kind")) ? 1 : 0));
        int packed = 0;
        for (var item : placed) {
            String kind = item.nativeData().getOrDefault("kind", "PROP");
            try {
                if (kind.equals("PATH")) removePath(plugin, world, plot);
                else {
                    UUID op = NativePlacementTransactions.pickup(plugin, world, plot, null, item.id());
                    try {
                        if (kind.equals("HOUSE")) plot.setBuilding(null); else plot.removeProp(item.id());
                        manager.updatePlot(plot);
                        manager.saveIfDirty();
                        NativePlacementTransactions.complete(plugin, op);
                    } catch (RuntimeException failure) {
                        NativePlacementTransactions.lock(plugin, op);
                        throw failure;
                    }
                }
                packed++;
            } catch (Exception failure) {
                problems.add(NativePlacementTransactions.name(plugin, item) + " on plot " + plot.getPlotId() + " could not be packed: " + failure.getMessage());
                plugin.getLogger().atWarning().withCause(failure).log("Wipe: could not pack %s (%s) on plot %s", item.id(), item.contentId(), plot.getPlotId());
            }
        }
        return packed;
    }

    /** Removes the garden path as its owner would, so the path can be laid again on a new plot. */
    private static void removePath(EterniaModPlugin plugin, World world, HubPlotRecord plot) throws java.io.IOException {
        var service = CustomizationBootstrap.service();
        if (service == null) throw new IllegalStateException("paths are not available on this server");
        service.confirm(world, service.removePath(world, plot.getPlotId(), actor(plugin, plot)));
    }

    /** Who may remove the path: the player who owns the plot, or the leader of the guild that owns the estate. */
    private static UUID actor(EterniaModPlugin plugin, HubPlotRecord plot) {
        Owner owner = HousingAccess.owner(plot);
        if (owner != null && owner.kind() == Owner.Kind.GUILD)
            return plugin.getServices().guilds().find(owner.id()).map(g -> g.leader()).orElse(plot.getOwnerUuid());
        return plot.getOwnerUuid();
    }
}
