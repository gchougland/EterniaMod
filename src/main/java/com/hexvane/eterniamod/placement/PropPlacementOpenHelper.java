package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.hub.HubPlotVisibilityOverlay;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hexvane.eterniamod.prop.PropItemMetadata;
import com.hexvane.eterniamod.ui.PropPlacementPage;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class PropPlacementOpenHelper {
    private PropPlacementOpenHelper() {}

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
        PropPlacementSession existing = PropPlacementSessions.get(playerUuid);
        if (existing != null && existing.getWorld().getName().equals(world.getName())) {
            ItemStack heldForSession = InventoryComponent.getItemInHand(store, ref);
            String heldPropId =
                !ItemStack.isEmpty(heldForSession) ? PropItemMetadata.readPropId(heldForSession) : null;
            if (heldPropId == null || heldPropId.equals(existing.getPropId())) {
                return new PropPlacementPage(playerRef, existing);
            }
            PropPlacementSessions.remove(playerUuid);
            BuildingPlacementClientPrefabPreview.clearSessionCache(existing);
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.needPropItem"));
            return null;
        }
        ItemStack held = InventoryComponent.getItemInHand(store, ref);
        if (ItemStack.isEmpty(held) || !EterniaModConstants.PROP_ITEM_ID.equals(held.getItemId())) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.needPropItem"));
            return null;
        }
        String propId = PropItemMetadata.readPropId(held);
        if (propId == null || propId.isBlank()) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.invalidPropItem"));
            return null;
        }
        PropDefinition def = plugin.getPropCatalog().get(propId);
        if (def == null) {
            playerRef.sendMessage(Message.translation("eterniamod_common.eterniamod.common.unknownProp"));
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
        PropPlacementSession session = new PropPlacementSession(world, plot.getPlotId(), anchor, 0, propId);
        PropPlacementSessions.put(playerUuid, session);
        return new PropPlacementPage(playerRef, session);
    }

    public static void cancelActive(@Nonnull PlayerRef playerRef, @Nonnull UUID playerUuid) {
        PropPlacementSession session = PropPlacementSessions.get(playerUuid);
        PropPlacementSessions.remove(playerUuid);
        BuildingPlacementClientPrefabPreview.hide(playerRef);
        PropPlacementWireframeOverlay.clearFor(playerRef);
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
}
