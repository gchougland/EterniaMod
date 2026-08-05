package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.BuildingItemMetadata;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotBuilding;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
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
import com.hypixel.hytale.server.core.util.PrefabUtil;
import java.nio.file.Path;
import java.util.Random;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

public final class BuildingPlacementCommit {
    private BuildingPlacementCommit() {}

    public static boolean place(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull BuildingPlacementSession session,
        @Nonnull EterniaModPlugin plugin
    ) {
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.getPlot(session.getPlotId());
        if (plot == null) {
            return false;
        }
        BuildingDefinition def = plugin.getBuildingCatalog().get(session.getBuildingId());
        if (def == null) {
            return false;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        CombinedItemContainer inv =
            player != null ? InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING) : null;
        if (inv == null || !consumeBuildingItem(inv, session.getBuildingId())) {
            return false;
        }
        Vector3i buildingAnchor = def.resolvePrefabAnchorWorld(session.getAnchor(), session.getPrefabYaw());
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            return false;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
        PrefabUtil.paste(buffer, world, buildingAnchor, session.getPrefabYaw(), true, new Random(), store);
        plot.setBuilding(
            new HubPlotBuilding(
                session.getBuildingId(),
                buildingAnchor.x,
                buildingAnchor.y,
                buildingAnchor.z,
                session.getPrefabYaw()
            )
        );
        plotManager.updatePlot(plot);
        plotManager.saveIfDirty();
        return true;
    }

    private static boolean consumeBuildingItem(@Nonnull CombinedItemContainer inv, @Nonnull String buildingId) {
        for (short slot = 0; slot < inv.getCapacity(); slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            if (!EterniaModConstants.BUILDING_ITEM_ID.equals(stack.getItemId())) {
                continue;
            }
            if (!BuildingItemMetadata.matchesBuilding(stack, buildingId)) {
                continue;
            }
            if (stack.getQuantity() <= 1) {
                return inv.removeItemStack(stack).succeeded();
            }
            return inv.removeItemStackFromSlot(slot, stack, 1).succeeded();
        }
        return false;
    }
}
