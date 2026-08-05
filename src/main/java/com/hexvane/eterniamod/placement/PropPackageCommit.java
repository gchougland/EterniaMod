package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
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
        HubPlotRecord plot = plotManager.findPlotAtPlayerOrTarget(ref, store);
        if (plot == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.notInPlot"));
            return false;
        }
        UUID playerUuid = uc.getUuid();
        if (!plot.isOwnedBy(playerUuid)) {
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
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            return false;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
        Vector3i origin =
            new Vector3i(match.prop().getAnchorX(), match.prop().getAnchorY(), match.prop().getAnchorZ());
        if (!PropPrefabOps.isIntact(world, origin, match.prop().resolveRotationYaw(), buffer)) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.propChanged"));
            return false;
        }
        ItemStack reward =
            PropItemMetadata.withProp(
                new ItemStack(EterniaModConstants.PROP_ITEM_ID, 1),
                def.getId(),
                def.getDisplayName(),
                playerRef.getLanguage()
            );
        if (!inv.canAddItemStack(reward)) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.inventoryFull"));
            return false;
        }
        PropPrefabOps.removeSolidsOnly(world, origin, match.prop().resolveRotationYaw(), buffer);
        PrefabEntityOps.removeLinkedEntities(world, match.prop().getInstanceId());
        plot.removeProp(match.prop().getInstanceId());
        plotManager.updatePlot(plot);
        plotManager.saveIfDirty();
        player.giveItem(reward, ref, store);
        playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.propPackaged"));
        return true;
    }
}
