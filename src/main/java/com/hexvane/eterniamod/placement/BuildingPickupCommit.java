package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.BuildingItemMetadata;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotBuilding;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.hub.HubPlotVisibilityOverlay;
import com.hexvane.eterniamod.hub.ManagementBlockLinker;
import com.hexvane.eterniamod.prefab.BuildingPrefabOps;
import com.hexvane.eterniamod.prefab.PrefabEntityOps;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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

public final class BuildingPickupCommit {
    public enum Result {
        SUCCESS,
        INVENTORY_FULL,
        FAILED
    }

    private BuildingPickupCommit() {}

    @Nonnull
    public static Result pickup(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull UUID plotId,
        @Nonnull EterniaModPlugin plugin
    ) {
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (uc == null || player == null) {
            return Result.FAILED;
        }
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.getPlot(plotId);
        if (plot == null || !plot.hasBuilding() || !plot.isOwnedBy(uc.getUuid())) {
            return Result.FAILED;
        }
        HubPlotBuilding building = plot.getBuilding();
        if (building == null) {
            return Result.FAILED;
        }
        BuildingDefinition def = plugin.getBuildingCatalog().get(building.getBuildingId());
        if (def == null) {
            return Result.FAILED;
        }
        CombinedItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING);
        if (inv == null) {
            return Result.FAILED;
        }
        ItemStack reward =
            BuildingItemMetadata.withBuilding(
                new ItemStack(EterniaModConstants.BUILDING_ITEM_ID, 1),
                building.getBuildingId(),
                def.getDisplayName(),
                playerRef != null ? playerRef.getLanguage() : null
            );
        if (!inv.canAddItemStack(reward)) {
            return Result.INVENTORY_FULL;
        }
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            return Result.FAILED;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
        Vector3i anchor = new Vector3i(building.getAnchorX(), building.getAnchorY(), building.getAnchorZ());
        ManagementBlockLinker.removeLinked(
            world,
            def,
            anchor,
            building.resolveRotationYaw(),
            building.getReplacedBlocks()
        );
        PrefabEntityOps.removeLinkedEntities(world, plot.getPlotId());
        BuildingPrefabOps.removeAndRestore(
            world,
            anchor,
            building.resolveRotationYaw(),
            buffer,
            building.getReplacedBlocks()
        );
        plot.setBuilding(null);
        plotManager.updatePlot(plot);
        plotManager.saveIfDirty();
        player.giveItem(reward, ref, store);
        if (playerRef != null) {
            HubPlotVisibilityOverlay.refreshIfEnabled(playerRef, world, plugin);
        }
        return Result.SUCCESS;
    }
}
