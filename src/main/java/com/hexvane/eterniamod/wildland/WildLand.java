package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.domain.WildLandService;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.housing.PlotRect;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Land in adventure worlds as the rest of the plugin sees it: starting it up, changing land off the world thread, and
 * telling open maps when land changed.
 */
public final class WildLand {
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private WildLand() {}

    /** Reads the settings and every claim, and starts the block guards. Plugin setup. */
    public static void start(EterniaModPlugin plugin, java.nio.file.Path dataDirectory) {
        WildLandSettings.install(EterniaModPlugin.class.getClassLoader(), dataDirectory);
        try { WildLandIndex.reload(plugin.getServices()); }
        catch (RuntimeException failure) { plugin.getLogger().atWarning().withCause(failure).log("Land claims could not be read; they are read again on the next change"); }
        WildProtection.register(plugin);
    }

    /** Reads the settings file again (staff command). */
    public static WildLandSettings reloadSettings(EterniaModPlugin plugin) {
        var settings = WildLandSettings.install(EterniaModPlugin.class.getClassLoader(), plugin.getDataDirectory());
        WildLandIndex.reload(plugin.getServices());
        LISTENERS.forEach(l -> l.accept(null));
        return settings;
    }

    /** Land kept free for everyone in {@code world}: portals and roads, the arrival point, and anything past the border. */
    public static Predicate<HexCoord> blocked(EterniaModPlugin plugin, String world) {
        HexLattice lattice = WildLandSettings.current().lattice();
        var plan = plugin.getInfrastructure().world(world).orElse(null);
        List<PlotRect> kept = new ArrayList<>();
        if (plan != null) { kept.addAll(plan.roads()); kept.addAll(plan.portals()); }
        var arrival = plan == null ? null : plan.arrival();
        var border = plugin.getWorldBorders() == null ? null : plugin.getWorldBorders().get(world).orElse(null);
        return cell -> {
            PlotRect b = lattice.bounds(cell);
            for (PlotRect r : kept) if (overlaps(r, b)) return true;
            if (arrival != null && lattice.cellAt((int) Math.floor(arrival.x()), (int) Math.floor(arrival.z())).equals(cell)) return true;
            return border != null && !(border.contains(b.x(), b.z()) && border.contains(b.endX() - 1, b.endZ() - 1));
        };
    }

    private static boolean overlaps(PlotRect a, PlotRect b) {
        return a.x() < b.endX() && b.x() < a.endX() && a.z() < b.endZ() && b.z() < a.endZ();
    }

    /** Adds a hexagon to the owner's land here (or starts it), off the world thread, then refreshes the guards. */
    public static CompletableFuture<WildLandService.Outcome> add(EterniaModPlugin plugin, String world, Owner owner, UUID actor, HexCoord cell) {
        var settings = WildLandSettings.current();
        Predicate<HexCoord> blocked = blocked(plugin, world);
        return change(plugin, world, () -> plugin.getServices().wildLand().add(world, owner, actor, cell, settings, blocked));
    }

    public static CompletableFuture<WildLandService.Outcome> release(EterniaModPlugin plugin, String world, UUID claim, UUID actor, HexCoord cell) {
        var settings = WildLandSettings.current();
        return change(plugin, world, () -> plugin.getServices().wildLand().release(claim, actor, cell, settings));
    }

    public static CompletableFuture<WildLandService.Outcome> abandon(EterniaModPlugin plugin, String world, UUID claim, UUID actor) {
        var settings = WildLandSettings.current();
        return change(plugin, world, () -> plugin.getServices().wildLand().abandon(claim, actor, settings));
    }

    /** Shares a claim with someone (or stops sharing, with no permissions), then refreshes the guards. */
    public static CompletableFuture<Void> trust(EterniaModPlugin plugin, UUID claim, UUID actor, String who, Set<WildPerm> perms) {
        var land = WildLandIndex.byId(claim);
        return change(plugin, land == null ? null : land.world(), () -> { plugin.getServices().wildLand().trust(claim, actor, who, perms); return null; });
    }

    private static <T> CompletableFuture<T> change(EterniaModPlugin plugin, String world, java.util.function.Supplier<T> work) {
        return com.hexvane.eterniamod.socialui.SocialUiBootstrap.supply(plugin.getServices(), () -> {
            T done = work.get();
            WildLandIndex.reload(plugin.getServices());
            return done;
        }).whenComplete((done, failure) -> LISTENERS.forEach(l -> l.accept(world)));
    }

    /** Called with a world name (or null for every world) whenever land changes. Returns a way to stop listening. */
    public static Runnable listen(Consumer<String> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }
}
