package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions;
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
        var actor = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        if (inv == null || actor == null || session.getWorld() != world
            || PropPlacementValidator.validate(world, plot, actor.getUuid(), session.getAnchor(), session.getPrefabYaw(), def) != null) {
            return false;
        }
        Vector3i propAnchor = def.resolvePrefabAnchorWorld(session.getAnchor(), session.getPrefabYaw());
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            return false;
        }
        IPrefabBuffer buffer = PrefabBufferUtil.getCached(prefabPath);
        if (buffer == null) return false;
        NativePlacementTransactions.Placed placed;
        try {
            var source=session.getCustodyOwner()!=null?session.getCustodyOwner():NativePlacementTransactions.owner(plot);
            com.hexvane.eterniamod.housing.HousingCustody.require(plugin,plot,actor.getUuid(),source,com.hexvane.eterniamod.housing.HousingCustody.PLACE);
            placed=NativePlacementTransactions.place(plugin,world,plot,actor.getUuid(),session.getPropId(),propAnchor,session.getPrefabYaw(),buffer,false,source);
        }
        catch (Exception failure) { plugin.getLogger().atWarning().withCause(failure).log("Prop placement did not complete"); actor.sendMessage(com.hypixel.hytale.server.core.Message.raw(failure.getMessage())); return false; }
        try {
        UUID instanceId=placed.instanceId();
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
        NativePlacementTransactions.complete(plugin,placed.operation());
        return true;
        } catch (Exception failure) { plugin.getLogger().atWarning().withCause(failure).log("Prop placement metadata did not complete"); NativePlacementTransactions.lock(plugin,placed.operation()); actor.sendMessage(com.hypixel.hytale.server.core.Message.raw("Placement metadata needs recovery; its snapshot is retained.")); return false; }
    }
}
