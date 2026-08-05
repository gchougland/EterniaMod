package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.hub.EterniaPlacedInstance;
import com.hexvane.eterniamod.prefab.PrefabEntityOps;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prefab.PropPrefabOps;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hexvane.eterniamod.prop.PropItemMetadata;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

public final class PropPlacementCommit {
    private PropPlacementCommit() {}

    public static boolean place(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull PropPlacementSession session,
        @Nonnull EterniaModPlugin plugin
    ) {
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.getPlot(session.getPlotId());
        if (plot == null) {
            return false;
        }
        PropDefinition def = plugin.getPropCatalog().get(session.getPropId());
        if (def == null) {
            return false;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        CombinedItemContainer inv =
            player != null ? InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING) : null;
        if (inv == null || !consumePropItem(store, ref, inv, session.getPropId())) {
            return false;
        }
        Vector3i propAnchor = def.resolvePrefabAnchorWorld(session.getAnchor(), session.getPrefabYaw());
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            return false;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
        UUID instanceId = UUID.randomUUID();
        PropPrefabOps.pasteSolidsOnly(world, propAnchor, session.getPrefabYaw(), buffer);
        PrefabEntityOps.pasteEntities(
            prefabPath,
            world,
            propAnchor,
            session.getPrefabYaw(),
            store,
            EterniaPlacedInstance.Kind.PROP,
            instanceId,
            session.getPropId()
        );
        plot.addProp(
            new HubPlotProp(
                instanceId,
                session.getPropId(),
                propAnchor.x,
                propAnchor.y,
                propAnchor.z,
                session.getPrefabYaw()
            )
        );
        plotManager.updatePlot(plot);
        plotManager.saveIfDirty();
        return true;
    }

    private static boolean consumePropItem(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull CombinedItemContainer inv,
        @Nonnull String propId
    ) {
        ItemStack held = InventoryComponent.getItemInHand(store, ref);
        if (
            !ItemStack.isEmpty(held)
                && EterniaModConstants.PROP_ITEM_ID.equals(held.getItemId())
                && PropItemMetadata.matchesProp(held, propId)
                && consumeOneFromHand(store, ref, held)
        ) {
            return true;
        }
        for (short slot = 0; slot < inv.getCapacity(); slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            if (!EterniaModConstants.PROP_ITEM_ID.equals(stack.getItemId())) {
                continue;
            }
            if (!PropItemMetadata.matchesProp(stack, propId)) {
                continue;
            }
            if (stack.getQuantity() <= 1) {
                return inv.removeItemStack(stack).succeeded();
            }
            return inv.removeItemStackFromSlot(slot, stack, 1).succeeded();
        }
        return false;
    }

    private static boolean consumeOneFromHand(
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull ItemStack held
    ) {
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null) {
            return false;
        }
        short slot = hotbar.getActiveSlot();
        if (held.getQuantity() <= 1) {
            return hotbar.getInventory().removeItemStackFromSlot(slot).succeeded();
        }
        return hotbar.getInventory().removeItemStackFromSlot(slot, held, 1).succeeded();
    }
}
