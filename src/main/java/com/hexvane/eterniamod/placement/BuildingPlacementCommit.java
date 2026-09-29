package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.BuildingItemMetadata;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotBuilding;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.hub.HubPlotVisibilityOverlay;
import com.hexvane.eterniamod.hub.ManagementBlockLinker;
import com.hexvane.eterniamod.hub.ReplacedBlockCell;
import com.hexvane.eterniamod.hub.EterniaPlacedInstance;
import com.hexvane.eterniamod.prefab.BuildingPrefabOps;
import com.hexvane.eterniamod.prefab.PrefabEntityOps;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
import java.util.List;
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
        PlayerRef actor = store.getComponent(ref, PlayerRef.getComponentType());
        if (inv == null || actor == null || session.getWorld() != world
            || BuildingPlacementValidator.validate(world, plotManager, plot, actor.getUuid(), session.getAnchor(), session.getPrefabYaw(), def, plugin) != null) {
            return false;
        }
        Vector3i buildingAnchor = def.resolvePrefabAnchorWorld(session.getAnchor(), session.getPrefabYaw());
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            return false;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
        if (buffer == null) return false;
        NativePlacementTransactions.Placed placed;
        try { placed=NativePlacementTransactions.place(plugin,world,plot,actor.getUuid(),session.getBuildingId(),buildingAnchor,session.getPrefabYaw(),buffer,true); }
        catch (Exception failure) { actor.sendMessage(com.hypixel.hytale.server.core.Message.raw(failure.getMessage())); return false; }
        try {
        plot.setBuilding(
            new HubPlotBuilding(
                session.getBuildingId(),
                buildingAnchor.x,
                buildingAnchor.y,
                buildingAnchor.z,
                session.getPrefabYaw(),
                List.of()
            )
        );
        // Management interactions resolve the authoritative plot; no unjournalled helper blocks are inserted.
        plotManager.updatePlot(plot);
        plotManager.saveIfDirty();
        plugin.getServices().housing().updateBuildingPresent(placed.contentOwner(),plot.getPlotId(),true);
        NativePlacementTransactions.complete(plugin,placed.operation());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null) {
            HubPlotVisibilityOverlay.refreshIfEnabled(playerRef, world, plugin);
        }
        return true;
        } catch (Exception failure) { NativePlacementTransactions.lock(plugin,placed.operation()); actor.sendMessage(com.hypixel.hytale.server.core.Message.raw("Placement metadata needs recovery; its snapshot is retained.")); return false; }
    }
}
