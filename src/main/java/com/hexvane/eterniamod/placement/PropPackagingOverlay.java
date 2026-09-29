package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.debug.DebugFootprintCubeUtil;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.prop.PropBoundsUtil;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hexvane.eterniamod.prop.PropLookupUtil;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.placement.PlotFootprintUtil;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3i;

public final class PropPackagingOverlay {
    private static final float DISPLAY_SECONDS = 2f;
    private static final Vector3f COLOR_NORMAL = new Vector3f(0.25f, 0.45f, 1.0f);
    private static final Vector3f COLOR_HIGHLIGHT = new Vector3f(0.55f, 0.75f, 1.0f);
    private static final float OPACITY_NORMAL = 0.35f;
    private static final float OPACITY_HIGHLIGHT = 0.5f;

    private PropPackagingOverlay() {}

    public static void clearFor(@Nullable PlayerRef player) {
        if (player == null) {
            return;
        }
        player.getPacketHandler().write(new ClearDebugShapes());
    }

    public static void sendForOwnedPlot(
        @Nonnull PlayerRef playerRef,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull EterniaModPlugin plugin
    ) {
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uc == null) {
            clearFor(playerRef);
            return;
        }
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = PropBoundsUtil.findAimedPlot(plotManager.listPlots(), ref, store, plugin, 64.0);
        if (plot == null) plot = plotManager.findPlotAtPlayerOrTarget(ref, store);
        if (plot == null || !com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,uc.getUuid(),com.hexvane.eterniamod.housing.HousingCustody.PACK)) {
            clearFor(playerRef);
            return;
        }
        PropLookupUtil.PropMatch highlightMatch = PropBoundsUtil.findPropAlongLookRay(plot, ref, store, plugin, 64.0);
        HubPlotProp highlighted = highlightMatch != null ? highlightMatch.prop() : null;
        clearFor(playerRef);
        for (HubPlotProp prop : plot.getProps()) {
            var instance=plugin.getServices().provenance().find(prop.getInstanceId()).orElse(null);
            if(instance==null||!com.hexvane.eterniamod.housing.HousingCustody.can(plugin,plot,uc.getUuid(),instance.owner(),com.hexvane.eterniamod.housing.HousingCustody.PACK))continue;
            PropDefinition def = plugin.getPropCatalog().get(prop.getPropId());
            if (def == null) {
                continue;
            }
            Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
            if (prefabPath == null) {
                continue;
            }
            IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
            Vector3i origin = new Vector3i(prop.getAnchorX(), prop.getAnchorY(), prop.getAnchorZ());
            HubPlotFootprint fp = PlotFootprintUtil.computeFootprint(origin, prop.resolveRotationYaw(), buffer);
            boolean isHighlight = highlighted != null && highlighted.getInstanceId().equals(prop.getInstanceId());
            sendCube(
                playerRef,
                fp,
                isHighlight ? COLOR_HIGHLIGHT : COLOR_NORMAL,
                isHighlight ? OPACITY_HIGHLIGHT : OPACITY_NORMAL
            );
        }
    }

    private static void sendCube(
        @Nonnull PlayerRef player,
        @Nonnull HubPlotFootprint fp,
        @Nonnull Vector3f color,
        float opacity
    ) {
        DebugFootprintCubeUtil.sendFootprintCube(
            player,
            fp,
            color,
            opacity,
            DISPLAY_SECONDS,
            EterniaModConstants.PROP_BOUNDS_PADDING
        );
    }
}
