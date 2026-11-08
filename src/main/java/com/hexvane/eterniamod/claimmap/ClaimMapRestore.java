package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Where a packed home may be set down, from the move engine ({@code NativeRelocationCoordinator.restoreCheck /
 * restoreDestinations}, same shape, same turn). Wired at plugin setup with one line:
 * <pre>{@code ClaimMapRestore.install(new ClaimMapRestore() {
 *     public HexClaimRules.Result check(World w, Owner o, UUID a, HousingRules.Scope s, Collection<HexCoord> c) { return getRelocation().restoreCheck(w, o, a, s, c); }
 *     public List<PlotCells> destinations(World w, Owner o, UUID a, HousingRules.Scope s, int x, int z, int r, int n) { return getRelocation().restoreDestinations(w, o, a, s, x, z, r, n); } });}</pre>
 * Until then the map previews with its own same shape, same turn check ({@code ClaimMapLogic.packedCheck}).
 * World thread.
 */
public interface ClaimMapRestore {
    HexClaimRules.Result check(World world, Owner owner, java.util.UUID actor, HousingRules.Scope scope, Collection<HexCoord> cells);

    List<PlotCells> destinations(World world, Owner owner, java.util.UUID actor, HousingRules.Scope scope, int x, int z, int radius, int limit);

    /** The installed move engine, or null when none is wired yet. */
    static ClaimMapRestore current() { return Holder.current; }

    static void install(ClaimMapRestore restore) { Holder.current = Objects.requireNonNull(restore); }

    final class Holder {
        private static volatile ClaimMapRestore current;

        private Holder() {}
    }
}
