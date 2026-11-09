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
        if (plot == null || !plot.hasBuilding() || !com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,uc.getUuid(),"housing.structure")) {
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
        // Decorations and additions placed inside or against the house are packed first, newest first, so nothing is
        // left floating or buried when the ground comes back. Each goes back to the inventory it came from.
        int packedFirst = 0;
        try {
            UUID house = NativePlacementTransactions.house(plugin, plot);
            for (var item : NativePlacementTransactions.inTheWay(plugin, plot, house)) {
                UUID op;
                try { op = NativePlacementTransactions.pickup(plugin, world, plot, uc.getUuid(), item.id()); }
                catch (Exception failure) {
                    plugin.getLogger().atWarning().withCause(failure).log("House pickup on %s: could not pack %s (%s) first", plot.getPlotId(), item.id(), item.contentId());
                    if (playerRef != null) playerRef.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                        "Could not pack " + NativePlacementTransactions.name(plugin, item) + " that stands in your house: " + failure.getMessage()));
                    return Result.FAILED;
                }
                try {
                    plot.removeProp(item.id());
                    plotManager.updatePlot(plot);
                    plotManager.saveIfDirty();
                    NativePlacementTransactions.complete(plugin, op);
                    packedFirst++;
                } catch (Exception failure) { NativePlacementTransactions.lock(plugin, op); return Result.FAILED; }
            }
        } catch (Exception failure) {
            plugin.getLogger().atWarning().withCause(failure).log("House pickup on %s: could not check what is inside", plot.getPlotId());
            if (playerRef != null) playerRef.sendMessage(com.hypixel.hytale.server.core.Message.raw(failure.getMessage()));
            return Result.FAILED;
        }
        if (packedFirst > 0 && playerRef != null) playerRef.sendMessage(com.hypixel.hytale.server.core.Message.raw(
            packedFirst == 1 ? "Packed 1 decoration from your house first." : "Packed " + packedFirst + " decorations from your house first."));
        UUID operation;
        try { operation=NativePlacementTransactions.pickup(plugin,world,plot,uc.getUuid(),plot.getPlotId()); }
        catch(Exception failure){ if(playerRef!=null)playerRef.sendMessage(com.hypixel.hytale.server.core.Message.raw(failure.getMessage()));return Result.FAILED; }
        try {
        plot.setBuilding(null);
        plotManager.updatePlot(plot);
        plotManager.saveIfDirty();
        plugin.getServices().housing().updateBuildingPresent(NativePlacementTransactions.owner(plot),plot.getPlotId(),false);
        NativePlacementTransactions.complete(plugin,operation);
        if (playerRef != null) {
            HubPlotVisibilityOverlay.refreshIfEnabled(playerRef, world, plugin);
        }
        return Result.SUCCESS;
        } catch(Exception failure){NativePlacementTransactions.lock(plugin,operation);return Result.FAILED;}
    }
}
