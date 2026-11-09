package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.housing.HousingAccess;
import com.hexvane.eterniamod.housing.HousingCustody;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.components.CarriedBlock;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Carry mode for placing a decoration: the player holds a moving box in both hands (the game's own block carrying,
 * which locks the hotbar), a hologram of the prop follows the crosshair, and three keys place, rotate or put it away.
 * The box is never an inventory item: it exists only as the carried block while a placement session is open.
 */
public final class PropCarry {
    /** Block type (and item) id of the carried moving box. */
    public static final String BOX = "Eternia_Prop_Carry";
    static final String HUD = "eternia:prop-carry";
    /** How far the hologram follows the crosshair. */
    private static final double REACH = 12.0;
    private static final long FOLLOW_MILLIS = 80;

    /** Per player preview state; touched on the world thread only, read by the tick for throttling. */
    static final class View {
        volatile boolean pending;
        volatile long last;
        boolean shown;
        @Nullable Vector3i shownOrigin;
        int shownSteps = -1;
        @Nullable String shownProp;
        @Nullable String status = "";
    }

    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private PropCarry() {}

    public static boolean carrying(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        CarriedBlock carried = store.getComponent(ref, CarriedBlock.getComponentType());
        return carried != null && BOX.equals(carried.getBlockTypeKey());
    }

    /** Starts carrying the session's prop. Call on the world thread, outside a system tick. */
    public static boolean start(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull PlayerRef player,
        @Nonnull PropPlacementSession session
    ) {
        CarriedBlock carried = store.getComponent(ref, CarriedBlock.getComponentType());
        if (carried != null && !BOX.equals(carried.getBlockTypeKey())) {
            player.sendMessage(Message.translation("eterniamod_common.eterniamod.common.carryHandsFull"));
            return false;
        }
        PropPlacementSession old = PropPlacementSessions.get(player.getUuid());
        if (old != null) {
            BuildingPlacementClientPrefabPreview.clearSessionCache(old);
        }
        BuildingPlacementOpenHelper.cancelActive(player, player.getUuid());
        BuildingPlacementClientPrefabPreview.hide(player);
        PropPlacementSessions.put(player.getUuid(), session);
        VIEWS.put(player.getUuid(), new View());
        if (carried == null) {
            store.addComponent(ref, CarriedBlock.getComponentType(), new CarriedBlock(BOX, null, null));
        }
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p != null && p.getPageManager().getCustomPage() != null) {
            p.getPageManager().setPage(ref, store, Page.None);
        }
        follow(ref, store, player);
        return true;
    }

    /** Called by the tick: queue one follow step on the world thread at most every {@link #FOLLOW_MILLIS}. */
    static void requestFollow(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PlayerRef player) {
        View view = VIEWS.computeIfAbsent(player.getUuid(), id -> new View());
        long now = System.currentTimeMillis();
        if (view.pending || now - view.last < FOLLOW_MILLIS) return;
        view.pending = true;
        view.last = now;
        store.getExternalData().getWorld().execute(() -> {
            view.pending = false;
            if (ref.isValid()) follow(ref, store, player);
        });
    }

    /** Moves the hologram under the crosshair and refreshes the outline and hints. */
    static void follow(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PlayerRef player) {
        PropPlacementSession session = PropPlacementSessions.get(player.getUuid());
        if (session == null || session.getWorld() != store.getExternalData().getWorld()) {
            stop(ref, store, player);
            return;
        }
        if (!carrying(ref, store)) {
            end(ref, store, player);
            return;
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        PropDefinition def = plugin == null ? null : plugin.getPropCatalog().get(session.getPropId());
        IPrefabBuffer buffer = def == null ? null : PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());
        if (def == null || buffer == null) {
            player.sendMessage(Message.translation("eterniamod_common.eterniamod.common.unknownProp"));
            stop(ref, store, player);
            return;
        }
        Vector3i target = TargetUtil.getTargetBlock(ref, REACH, store);
        if (target != null) {
            Vector3i sign = BuildingPlacementAnchorUtil.pickAnchor(
                session.getWorld(), new BlockPosition(target.x, target.y, target.z));
            session.setAnchor(centred(sign, session, def, buffer));
        }
        refresh(ref, store, player, session, def, buffer);
    }

    /** Anchor that puts the middle of the prop's footprint under the crosshair. */
    @Nonnull
    private static Vector3i centred(Vector3i sign, PropPlacementSession session, PropDefinition def, IPrefabBuffer buffer) {
        var footprint = PlotFootprintUtil.computePropFootprint(def.resolvePrefabAnchorWorld(sign, session.getPrefabYaw()), session.getPrefabYaw(), buffer);
        int midX = Math.floorDiv(footprint.getMinX() + footprint.getMaxX(), 2);
        int midZ = Math.floorDiv(footprint.getMinZ() + footprint.getMaxZ(), 2);
        return new Vector3i(sign.x + (sign.x - midX), sign.y, sign.z + (sign.z - midZ));
    }

    private static void refresh(
        Ref<EntityStore> ref,
        Store<EntityStore> store,
        PlayerRef player,
        PropPlacementSession session,
        PropDefinition def,
        IPrefabBuffer buffer
    ) {
        View view = VIEWS.computeIfAbsent(player.getUuid(), id -> new View());
        String error = validate(session, def, player.getUuid());
        Vector3i origin = def.resolvePrefabAnchorWorld(session.getAnchor(), session.getPrefabYaw());
        boolean moved = !view.shown || !origin.equals(view.shownOrigin) || view.shownSteps != session.getRotationSteps()
            || !session.getPropId().equals(view.shownProp);
        if (moved) {
            if (view.shown) BuildingPlacementClientPrefabPreview.hide(player);
            view.shown = BuildingPlacementClientPrefabPreview.sendFull(
                player, def.getPrefabPath(), session.getRotationSteps(), origin, session.getPrefabYaw(), session);
            view.shownOrigin = origin;
            view.shownSteps = session.getRotationSteps();
            view.shownProp = session.getPropId();
            HubPlotRecord plot = plot(session);
            if (plot != null) {
                PropPlacementWireframeOverlay.send(
                    player, plot.getFootprint(), PlotFootprintUtil.computePropFootprint(origin, session.getPrefabYaw(), buffer), error == null);
            }
        }
        if (moved || !java.util.Objects.equals(error, view.status)) {
            view.status = error;
            PropCarryHud.show(ref, store, player, def, session, error);
        }
    }

    @Nullable
    private static HubPlotRecord plot(PropPlacementSession session) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        return plugin == null ? null : EterniaWorldRegistries.getOrCreateHubPlotManager(session.getWorld(), plugin).getPlot(session.getPlotId());
    }

    /** The reason the prop cannot go here, or null when it can. */
    @Nullable
    private static String validate(PropPlacementSession session, PropDefinition def, UUID actor) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        HubPlotRecord plot = plot(session);
        if (plugin == null || plot == null) return "notInPlot";
        var custody = session.getCustodyOwner() != null ? session.getCustodyOwner() : HousingAccess.owner(plot);
        if (!HousingCustody.previewCan(plugin, plot, actor, custody, HousingCustody.PLACE)) return "notYourPlot";
        return PropPlacementValidator.preview(session.getWorld(), plot, actor, session.getAnchor(), session.getPrefabYaw(), def);
    }

    /** Rotate key: a quarter turn, keeping the prop under the crosshair. */
    public static void rotate(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PlayerRef player) {
        PropPlacementSession session = PropPlacementSessions.get(player.getUuid());
        if (session == null) {
            stop(ref, store, player);
            return;
        }
        session.rotateClockwise90();
        follow(ref, store, player);
    }

    /** Place key: places the prop when the spot is allowed, otherwise says why and keeps carrying. */
    public static void place(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PlayerRef player) {
        PropPlacementSession session = PropPlacementSessions.get(player.getUuid());
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (session == null || plugin == null) {
            stop(ref, store, player);
            return;
        }
        follow(ref, store, player);
        PropDefinition def = plugin.getPropCatalog().get(session.getPropId());
        if (def == null) return;
        String error = validate(session, def, player.getUuid());
        if (error != null) {
            Message why = PlacementText.message(error);
            if (why != null) player.sendMessage(why);
            return;
        }
        if (!PropPlacementCommit.place(ref, store, session, plugin)) {
            player.sendMessage(Message.translation("eterniamod_common.eterniamod.common.propPlaceFailed"));
            return;
        }
        player.sendMessage(Message.translation("eterniamod_common.eterniamod.common.propPlaced"));
        end(ref, store, player);
    }

    /** Put away key: ends carry mode without placing anything. */
    public static void putAway(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PlayerRef player) {
        end(ref, store, player);
        player.sendMessage(Message.translation("eterniamod_common.eterniamod.common.carryPutAway"));
    }

    /** Ends carry mode: removes the box, the hologram, the outline and the hints. World thread, outside a tick. */
    public static void end(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PlayerRef player) {
        if (ref.isValid() && carrying(ref, store)) {
            store.removeComponent(ref, CarriedBlock.getComponentType());
        }
        stop(ref, store, player);
    }

    /** Clears the session and everything drawn for it, leaving any carried block alone. */
    static void stop(@Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store, @Nonnull PlayerRef player) {
        VIEWS.remove(player.getUuid());
        PropPlacementOpenHelper.cancelActive(player, player.getUuid());
        if (ref != null && store != null && ref.isValid()) PropCarryHud.hide(ref, store, player);
    }

    /** Session cleanup when the player leaves or dies; the carried box itself is handled by the game. */
    static void forget(@Nonnull UUID player) {
        VIEWS.remove(player);
    }
}
