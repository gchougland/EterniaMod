package com.hexvane.eterniamod.boundary;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandEvents;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per world land and border index for the glow and the plot outline overlay, shared by every viewer of the world.
 * {@link HexLand#view} is asked again at most every {@link #REFRESH_NANOS} unless {@link HexLandEvents} says the
 * land changed, so ten scans a second per player stay a map lookup. World thread for {@link #get}.
 */
public final class HexBorderWorlds {
    static final long REFRESH_NANOS = 1_000_000_000L;
    private final EterniaModPlugin plugin;
    private final Map<String, State> worlds = new ConcurrentHashMap<>();
    private final Runnable stopListening;

    /** A world's land and its border index, both immutable. */
    public record Snapshot(HexLandView view, HexBorderIndex index) {}

    private static final class State {
        volatile boolean dirty = true;
        long fetchedAt;
        Snapshot snapshot;
    }

    public HexBorderWorlds(EterniaModPlugin plugin) {
        this.plugin = plugin;
        this.stopListening = HexLandEvents.listen(change -> {
            State state = worlds.get(change.world());
            if (state != null) state.dirty = true;
        });
    }

    /** The hex land of {@code world}, or null when it is not a hex plot world. World thread. */
    public Snapshot get(World world) {
        String name = world.getName();
        if (!HexLand.isHexWorld(name)) { worlds.remove(name); return null; }
        State state = worlds.computeIfAbsent(name, ignored -> new State());
        long now = System.nanoTime();
        if (state.snapshot != null && !state.dirty && now - state.fetchedAt < REFRESH_NANOS) return state.snapshot;
        state.dirty = false;
        state.fetchedAt = now;
        HexLandView view = HexLand.view(plugin, world).orElse(null);
        if (view == null) { state.snapshot = null; return null; }
        if (state.snapshot != null && state.snapshot.view() == view) return state.snapshot;
        List<HexBorderIndex.PlotEntry> entries = new ArrayList<>();
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        for (HexLandView.PlotInfo plot : view.plots().values()) {
            HubPlotRecord record = manager.getPlot(plot.plotId());
            int ground = record == null || record.getFootprint() == null ? 64 : record.getFootprint().resolveVisualCenterY();
            entries.add(new HexBorderIndex.PlotEntry(plot.plotId(), plot.cells(), ground));
        }
        state.snapshot = new Snapshot(view, new HexBorderIndex(entries));
        return state.snapshot;
    }

    public void forget(World world) { worlds.remove(world.getName()); }

    public void close() { stopListening.run(); worlds.clear(); }
}
