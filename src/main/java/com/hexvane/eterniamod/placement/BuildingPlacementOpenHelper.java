package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.BuildingItemMetadata;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.hub.HubPlotVisibilityOverlay;
import com.hexvane.eterniamod.ui.BuildingPlacementPage;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class BuildingPlacementOpenHelper {
    private BuildingPlacementOpenHelper() {}

    @Nullable
    public static CustomUIPage tryOpen(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull ComponentAccessor<EntityStore> componentAccessor,
        @Nonnull PlayerRef playerRef,
        @Nonnull InteractionContext context
    ) {
        BlockPosition tb = context.getTargetBlock();
        Store<EntityStore> store = ref.getStore();
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uc == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        UUID playerUuid = uc.getUuid();
        BuildingPlacementSession existing = BuildingPlacementSessions.get(playerUuid);
        if (existing != null && existing.getWorld().getName().equals(world.getName())) {
            BuildingPlacementDebugLog.openAttempt(playerRef.getUsername(), existing.getBuildingId(), true);
            return new BuildingPlacementPage(playerRef, existing);
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        Player player = store.getComponent(ref, Player.getComponentType());
        CombinedItemContainer inv =
            player != null ? InventoryComponent.getCombined(store, ref, InventoryComponent.EVERYTHING) : null;
        if (plugin == null || inv == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.needBuildingItem"));
            return null;
        }
        ItemStack held = findBuildingItem(inv);
        if (ItemStack.isEmpty(held)) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.needBuildingItem"));
            return null;
        }
        String buildingId = BuildingItemMetadata.readBuildingId(held);
        if (buildingId == null || buildingId.isBlank()) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.invalidBuildingItem"));
            return null;
        }
        BuildingDefinition def = plugin.getBuildingCatalog().get(buildingId);
        if (def == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.unknownBuilding"));
            return null;
        }
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.findPlotAtPlayerOrTarget(ref, store);
        if (plot == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.notInPlot"));
            return null;
        }
        if (!plot.isOwnedBy(playerUuid)) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.notYourPlot"));
            return null;
        }
        if (plot.hasBuilding()) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.plotAlreadyHasBuilding"));
            return null;
        }
        Vector3i anchor = null;
        if (tb != null && plot.getFootprint().containsHorizontal(tb.x, tb.z)) {
            anchor = BuildingPlacementAnchorUtil.pickAnchor(world, tb);
        }
        if (anchor == null) {
            anchor = BuildingPlacementSnapUtil.anchorAtPlayerFeet(ref, store);
        }
        if (anchor == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.lookAtBlock"));
            return null;
        }
        BuildingPlacementClientPrefabPreview.hide(playerRef);
        BuildingPlacementSession session =
            new BuildingPlacementSession(world, plot.getPlotId(), anchor, 0, buildingId);
        BuildingPlacementSessions.put(playerUuid, session);
        BuildingPlacementDebugLog.openAttempt(playerRef.getUsername(), buildingId, true);
        return new BuildingPlacementPage(playerRef, session);
    }

    /** Clears client preview overlays and drops the server placement session. */
    public static void cancelActive(@Nonnull PlayerRef playerRef, @Nonnull UUID playerUuid) {
        BuildingPlacementSession session = BuildingPlacementSessions.get(playerUuid);
        BuildingPlacementSessions.remove(playerUuid);
        BuildingPlacementClientPrefabPreview.hide(playerRef);
        BuildingPlacementWireframeOverlay.clearFor(playerRef);
        if (session != null) {
            BuildingPlacementClientPrefabPreview.clearSessionCache(session);
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        World world = session != null ? session.getWorld() : resolveWorld(playerRef);
        if (plugin != null && world != null) {
            HubPlotVisibilityOverlay.refreshIfEnabled(playerRef, world, plugin);
        }
    }

    @Nullable
    private static World resolveWorld(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return ref.getStore().getExternalData().getWorld();
    }

    @Nullable
    private static ItemStack findBuildingItem(@Nonnull CombinedItemContainer inv) {
        for (short slot = 0; slot < inv.getCapacity(); slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            if (!EterniaModConstants.BUILDING_ITEM_ID.equals(stack.getItemId())) {
                continue;
            }
            if (BuildingItemMetadata.readBuildingId(stack) != null) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
