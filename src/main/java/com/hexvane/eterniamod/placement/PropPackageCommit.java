package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.prefab.PrefabEntityOps;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prefab.PropPrefabOps;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hexvane.eterniamod.prop.PropItemMetadata;
import com.hexvane.eterniamod.prop.PropBoundsUtil;
import com.hexvane.eterniamod.prop.PropLookupUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

public final class PropPackageCommit {
    private PropPackageCommit() {}

    public static boolean packageProp(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull EterniaModPlugin plugin
    ) {
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (uc == null || player == null || playerRef == null) {
            return false;
        }
        CombinedItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING);
        if (inv == null) {
            return false;
        }
        ItemStack held = InventoryComponent.getItemInHand(store, ref);
        if (ItemStack.isEmpty(held) || !EterniaModConstants.PACKAGING_WAND_ID.equals(held.getItemId())) {
            return false;
        }
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = PropBoundsUtil.findAimedPlot(plotManager.listPlots(), ref, store, plugin, 64.0);
        if (plot == null) plot = plotManager.findPlotAtPlayerOrTarget(ref, store);
        if (plot == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.notInPlot"));
            return false;
        }
        UUID playerUuid = uc.getUuid();
        // A pickup that stopped part way is finished or cancelled first, so it never blocks packing.
        if (com.hexvane.eterniamod.housing.HousingAccess.locked(plugin,plot)) {
            try { com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions.healPickups(plugin,world,plot); }
            catch (RuntimeException failure) { plugin.getLogger().atWarning().withCause(failure).log("Could not resolve pickups before packing"); }
        }
        if (com.hexvane.eterniamod.housing.HousingAccess.locked(plugin,plot)) {
            try {
                if(com.hexvane.eterniamod.housing.relocation.FailedPropRecovery.recover(plugin,world,plot,playerUuid)>0) {
                    playerRef.sendMessage(Message.raw("The interrupted decoration has been returned to your build inventory. Your plot is unlocked; you can place it again or package another decoration."));
                    return true;
                }
                playerRef.sendMessage(Message.raw("Something on this plot did not finish. Open My Plots and press Fix my plot. Nothing has been lost."));
            }catch(Exception failure){playerRef.sendMessage(Message.raw(failure.getMessage()));}
            return false;
        }
        if (!com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,playerUuid,com.hexvane.eterniamod.housing.HousingCustody.PACK)) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.notYourPlot"));
            return false;
        }
        PropLookupUtil.PropMatch match = PropBoundsUtil.findPropAlongLookRay(plot, ref, store, plugin, 64.0);
        if (match == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.noPropHere"));
            return false;
        }
        PropDefinition def = plugin.getPropCatalog().get(match.prop().getPropId());
        if (def == null) {
            return false;
        }
        // The highlight copies stand where the decoration is; take them away before packing it.
        PropPackHighlight.clear(world, playerUuid);
        var packedProp = match.prop();
        var vanishAt = packedProp;
        UUID operation;
        try {operation=NativePlacementTransactions.pickup(plugin,world,plot,playerUuid,match.prop().getInstanceId());}
        catch(Exception failure){playerRef.sendMessage(Message.raw(failure.getMessage()));return false;}
        try {
        plot.removeProp(match.prop().getInstanceId());
        plotManager.updatePlot(plot);
        plotManager.saveIfDirty();
        NativePlacementTransactions.complete(plugin,operation);
        var custody=plugin.getServices().provenance().find(match.prop().getInstanceId()).orElseThrow().owner();
        vanish(store, plugin, vanishAt);
        playerRef.sendMessage(custody.kind()==com.hexvane.eterniamod.domain.Owner.Kind.GUILD?Message.raw("Decoration returned to the guild build inventory."):Message.translation("eterniamod_common.eterniamod.common.propPackaged"));
        return true;
        }catch(Exception failure){NativePlacementTransactions.lock(plugin,operation);return false;}
    }

    /** A swirl of sparkles where the packed decoration stood. */
    private static void vanish(Store<EntityStore> store, EterniaModPlugin plugin, com.hexvane.eterniamod.hub.HubPlotProp prop) {
        PropDefinition def = plugin.getPropCatalog().get(prop.getPropId());
        var buffer = def == null ? null : com.hexvane.eterniamod.prefab.PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());
        double x = prop.getAnchorX() + 0.5, y = prop.getAnchorY(), z = prop.getAnchorZ() + 0.5;
        if (buffer != null) {
            var fp = PlotFootprintUtil.computePropFootprint(new org.joml.Vector3i(prop.getAnchorX(), prop.getAnchorY(), prop.getAnchorZ()), prop.resolveRotationYaw(), buffer);
            x = (fp.getMinX() + fp.getMaxX() + 1) * 0.5; y = fp.getMinY(); z = (fp.getMinZ() + fp.getMaxZ() + 1) * 0.5;
        }
        PropPackHighlight.vanish(store, x, y, z);
    }
}
