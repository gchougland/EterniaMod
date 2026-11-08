package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.hexplots.HexCoord;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Lets the hexagons picked on the Claim Map glow in the world while the page is open. The borders engine does the
 * drawing ({@code boundary.HexBorderFocus.show / clear}); it is wired in at plugin setup with one line:
 * <pre>{@code ClaimMapFocus.install(new ClaimMapFocus() {
 *     public void show(UUID p, String w, Set<HexCoord> c, long ms) { HexBorderFocus.show(p, w, c, ms); }
 *     public void clear(UUID p) { HexBorderFocus.clear(p); } });}</pre>
 * Until then nothing glows. Any thread.
 */
public interface ClaimMapFocus {
    /** Outline {@code cells} of {@code world} for {@code player} for {@code millis} (renewed while the page is open). */
    void show(UUID player, String world, Set<HexCoord> cells, long millis);

    /** Stop outlining (page closed). */
    void clear(UUID player);

    static ClaimMapFocus current() { return Holder.current; }

    static void install(ClaimMapFocus focus) { Holder.current = Objects.requireNonNull(focus); }

    final class Holder {
        private static volatile ClaimMapFocus current = new ClaimMapFocus() {
            @Override public void show(UUID player, String world, Set<HexCoord> cells, long millis) {}
            @Override public void clear(UUID player) {}
        };

        private Holder() {}
    }
}
