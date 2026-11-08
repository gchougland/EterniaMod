package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hexvane.eterniamod.housing.PlotClaimPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Every way into claiming land: the deed, Bramble's "Claim a plot", the Architect's Ledger, the Journal and My Plots.
 * Hex worlds open the Claim Map; rectangle worlds (and worlds without housing, which explain themselves) keep the old
 * placement preview. World thread.
 */
public final class ClaimMapRoutes {
    private ClaimMapRoutes() {}

    /** True when the player stands in a world whose plots are hexagons. */
    public static boolean inHexWorld(Store<EntityStore> store) {
        return HexLand.isHexWorld(store.getExternalData().getWorld().getName());
    }

    /** Claim a personal plot (or, in hex worlds, grow the one they already have here). Resumes a rectangle draft. */
    public static void claim(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        if (inHexWorld(store) && ClaimMapPage.open(plugin, ref, store, player, ClaimMapLogic.Mode.CLAIM, HousingRules.Scope.PUBLIC, null)) return;
        PlotClaimPage.open(ref, store, player, plugin);
    }

    /** Start a fresh claim: personal, or the guild estate when {@code guild}. */
    public static void claimNew(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, boolean guild) {
        HousingRules.Scope scope = guild ? HousingRules.Scope.GUILD_ROOT : HousingRules.Scope.PUBLIC;
        if (inHexWorld(store) && ClaimMapPage.open(plugin, ref, store, player, ClaimMapLogic.Mode.CLAIM, scope, null)) return;
        PlotClaimPage.openNew(ref, store, player, plugin, guild);
    }

    /** "Add a hexagon" to a plot in the player's current world. Returns false when that is not possible here. */
    public static boolean expand(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, UUID plotId) {
        return inHexWorld(store) && ClaimMapPage.open(plugin, ref, store, player, ClaimMapLogic.Mode.EXPAND, HousingRules.Scope.PUBLIC, plotId);
    }
}
