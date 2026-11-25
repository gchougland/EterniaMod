package com.hexvane.eterniamod.portals;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.worldmap.CreateUserMarker;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.worldstore.WorldMarkersResource;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import org.joml.Vector3d;

/**
 * Keeps the world map and compass personal.
 * <ul>
 *   <li>{@code "eterniaPortals"}: each player sees only the portals on their own list.</li>
 *   <li>{@code "shared"} (replaced): markers made with "share with other players" show to their maker only. New
 *       markers are always saved as personal ({@link #personalOnly}); this covers ones made before.</li>
 * </ul>
 * The share checkbox on the map screen is the client's own and cannot be hidden from the server; it simply does nothing.
 * Pads no longer make vanilla warps, so vanilla's {@code "warps"} provider is left alone (its {@code Warp} type is
 * deprecated with no replacement). Pads placed before this keep their old warp marker until they are broken.
 */
final class PersonalMapMarkers {
    static final String PORTALS = "eterniaPortals";

    private PersonalMapMarkers() {}

    static void install(World world, PortalIndex index) {
        var map = world.getWorldMapManager();
        map.addMarkerProvider(PORTALS, new Portals(index));
        map.addMarkerProvider("shared", OwnSharedMarkers.INSTANCE);
    }

    /** Inbound filter: a marker the player asked to share is saved as their own personal marker instead. */
    static boolean personalOnly(PlayerRef player, Packet packet) {
        if (packet instanceof CreateUserMarker marker) marker.shared = false;
        return false;
    }

    /** Whether a shared marker made by {@code createdBy} shows for {@code viewer}. Markers with no maker show to nobody. */
    static boolean sharedVisibleTo(UUID viewer, UUID createdBy) { return createdBy != null && createdBy.equals(viewer); }

    /**
     * Markers are gathered on each world's map thread, not the world thread, so no store is read here: the player's id
     * is remembered on the world thread when they enter a world ({@link #remember}).
     */
    private static final Map<Player, UUID> VIEWERS = Collections.synchronizedMap(new IdentityHashMap<>());

    static void remember(Player player, UUID id) { if (player != null && id != null) VIEWERS.put(player, id); }
    static void forget(UUID id) { synchronized (VIEWERS) { VIEWERS.values().removeIf(id::equals); } }
    private static UUID viewer(Player player) { return VIEWERS.get(player); }

    private record Portals(PortalIndex index) implements WorldMapManager.MarkerProvider {
        @Override public void update(World world, Player player, MarkersCollector collector) {
            UUID id = viewer(player);
            if (id == null) return;
            for (var p : index.inWorld(id, world.getName())) {
                var at = new Transform(new Vector3d(p.x() + 0.5, p.y() + 0.65, p.z() + 0.5), new Rotation3f());
                if (!collector.isInViewDistance(at)) continue;
                collector.add(new MapMarkerBuilder("EterniaPortal-" + p.id(), "Warp.png", at).withName(Message.raw(p.name())).build());
            }
        }
    }

    private static final class OwnSharedMarkers implements WorldMapManager.MarkerProvider {
        static final OwnSharedMarkers INSTANCE = new OwnSharedMarkers();
        @Override public void update(World world, Player player, MarkersCollector collector) {
            UUID id = viewer(player);
            if (id == null) return;
            var shared = world.getChunkStore().getStore().getResource(WorldMarkersResource.getResourceType());
            for (var marker : shared.getUserMapMarkers()) {
                if (sharedVisibleTo(id, marker.getCreatedByUuid())) collector.addIgnoreViewDistance(marker.toProtocolMarker());
            }
        }
    }
}
