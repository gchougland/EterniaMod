package com.hexvane.eterniamod.hexplots;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One hook for "the land changed here": fired after a claim, expansion, unclaim or move ({@code HubPlotManager}),
 * after public roads, plazas or the lattice change ({@code HousingInfrastructure.saveWorld}), and by the domain claim
 * and expansion commits when cells become reserved. The map overlay, border glow and open claim map pages listen so
 * they can redraw just those cells.
 *
 * <p>Threading: listeners run synchronously on the thread that made the change (often a world thread, sometimes a
 * command or setup thread). Keep them tiny: mark state dirty or hand off with {@code world.execute(...)}. A listener
 * that throws is logged and skipped; it never breaks the change itself.
 */
public final class HexLandEvents {
    private static final Logger LOG = Logger.getLogger(HexLandEvents.class.getName());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** What kind of change touched the cells. */
    public enum Cause { CLAIM, EXPAND, UNCLAIM, RESERVATION, ROADS, LATTICE }

    /**
     * {@code cells} are the cells whose state changed (empty for {@link Cause#LATTICE}: redraw the whole world).
     * {@code world} is the world name.
     */
    public record Change(String world, Set<HexCoord> cells, Cause cause) {
        public Change {
            Objects.requireNonNull(world);
            Objects.requireNonNull(cause);
            cells = Set.copyOf(cells);
        }

        /** True when listeners should forget everything they know about the world. */
        public boolean wholeWorld() { return cause == Cause.LATTICE || cells.isEmpty(); }
    }

    @FunctionalInterface
    public interface Listener {
        void onCellsChanged(Change change);
    }

    private HexLandEvents() {}

    /** Registers a listener; run the returned handle to remove it (on plugin shutdown or page close). */
    public static Runnable listen(Listener listener) {
        Objects.requireNonNull(listener);
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** Tells every listener the cells changed. Call after the change is saved. No effect for an empty cell set unless the cause is {@link Cause#LATTICE}. */
    public static void fire(String world, Collection<HexCoord> cells, Cause cause) {
        if (cells.isEmpty() && cause != Cause.LATTICE) return;
        if (LISTENERS.isEmpty()) return;
        Change change = new Change(world, Set.copyOf(cells), cause);
        for (Listener listener : LISTENERS) {
            try {
                listener.onCellsChanged(change);
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "Hex land listener failed for " + world, failure);
            }
        }
    }

    /** Test and shutdown helper. */
    public static void clearListeners() { LISTENERS.clear(); }
}
