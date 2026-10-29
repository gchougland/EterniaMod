package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.builtin.buildertools.prefabeditor.PrefabEditSessionManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.protocol.packets.player.SetGameMode;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shows a house or decoration in a page's {@code PrefabPreviewComponent #PrefabPreview}, the prefab browser's viewer.
 *
 * <p>The client only accepts {@code BuilderToolPrefabPreview} while it believes it is in Creative (its preview module
 * lives in the builder tools). Adventure players keep their real server game mode: only the client is told Creative
 * while a preview is shown, and is told its real mode again when the preview clears or the page closes. This is the
 * approach Aetherhaven ships. The client keeps one prefab preview, so a page shows one at a time.
 */
public final class PrefabCardPreview {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int TILT = 23, SPIN_SPEED = 27, MAX_SIZE = 100;
    private static final int BIOME_TINT = ColorParseUtil.colorToARGBInt(PrefabEditSessionManager.DEFAULT_TINT) & 0x00FFFFFF;
    private static final int WATER_TINT = ColorParseUtil.colorToARGBInt(Environment.getUnknownFor("").getWaterTint()) & 0x00FFFFFF;
    /**
     * The client's preview draws placed items (a breakfast nook's plates and food) a quarter block above the blocks they
     * rest on; in the world they sit right. Measured in game. The preview also ignores the size of block-type
     * decorations (plates and mugs show full size); nothing the server sends changes that, so it is left to the client.
     */
    private static final float ENTITY_DROP = 0.25f;
    /** The viewer element mounts a little after the page; sending again covers that. */
    private static final long[] RETRY_DELAYS_MS = {50L, 120L, 250L};

    private final PlayerRef player;
    private boolean spoofed;
    private long serial;
    @Nullable private String shown;

    public PrefabCardPreview(@Nonnull PlayerRef player) { this.player = player; }

    /** The prefab behind {@code eternia:house/<id>}, {@code eternia:prop/<id>} or {@code eternia:addition/<id>}, if it has one. */
    @Nullable
    public static String prefabPath(@Nullable String contentId) {
        var plugin = EterniaModPlugin.get();
        if (plugin == null || contentId == null || !contentId.startsWith("eternia:")) return null;
        String[] parts = contentId.substring(8).split("/", 2);
        if (parts.length < 2) return null;
        return switch (parts[0]) {
            case "house" -> {
                BuildingDefinition building = plugin.getBuildingCatalog() == null ? null : plugin.getBuildingCatalog().get(parts[1]);
                yield building == null ? null : building.getPrefabPath();
            }
            case "prop", "addition" -> {
                PropDefinition prop = plugin.getPropCatalog() == null ? null : plugin.getPropCatalog().get(parts[1]);
                yield prop == null ? null : prop.getPrefabPath();
            }
            default -> null;
        };
    }

    public static boolean supports(@Nullable String contentId) { return prefabPath(contentId) != null; }

    /** Shows the content after the page has been (re)built, or clears the viewer when it has no prefab. World thread. */
    public void show(@Nullable String contentId) {
        long mine = ++serial;
        if (!supports(contentId)) { clear(); return; }
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();
        var world = store.getExternalData().getWorld();
        for (long delay : RETRY_DELAYS_MS) {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                try {
                    world.execute(() -> {
                        if (mine != serial || !ref.isValid()) return;
                        spoof(store, ref);
                        send(contentId);
                        shown = contentId;
                    });
                } catch (RuntimeException stopping) { /* The world is shutting down; nothing to show. */ }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /** Empties the viewer and gives the client back its real game mode. World thread. */
    public void clear() {
        serial++;
        Ref<EntityStore> ref = player.getReference();
        if (shown != null) { player.getPacketHandler().write(new BuilderToolPrefabPreview()); shown = null; }
        if (ref != null && ref.isValid()) restore(ref.getStore(), ref);
    }

    private void spoof(Store<EntityStore> store, Ref<EntityStore> ref) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (spoofed || component == null || component.getGameMode() == GameMode.Creative) return;
        player.getPacketHandler().writeNoCache(new SetGameMode(GameMode.Creative));
        spoofed = true;
    }

    private void restore(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (!spoofed) return;
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component != null) player.getPacketHandler().writeNoCache(new SetGameMode(component.getGameMode()));
        spoofed = false;
    }

    /** Moves entities down onto the preview's blocks (see {@link #ENTITY_DROP}). Returns the same array for convenience. */
    @Nullable
    public static ClipboardEntityChange[] alignEntities(@Nullable ClipboardEntityChange[] entities) {
        if (entities == null) return null;
        for (ClipboardEntityChange entity : entities) entity.y -= ENTITY_DROP;
        return entities;
    }

    private void send(String contentId) {
        BuilderToolPrefabPreview packet = new BuilderToolPrefabPreview();
        Path resolved = PrefabResolveUtil.resolvePrefabPath(prefabPath(contentId));
        if (resolved != null) {
            try {
                BlockSelection selection = PrefabStore.get().getPrefab(resolved);
                if (selection != null) {
                    var blocks = selection.toPacket();
                    packet.blocksChange = blocks.blocksChange;
                    packet.fluidsChange = blocks.fluidsChange;
                    packet.entityChanges = alignEntities(blocks.entityChanges);
                    packet.tilt = TILT;
                    packet.spinSpeed = SPIN_SPEED;
                    packet.previewScale = MAX_SIZE;
                    packet.biomeTint = BIOME_TINT;
                    packet.waterTint = WATER_TINT;
                }
            } catch (RuntimeException failure) {
                LOGGER.atWarning().withCause(failure).log("Preview prefab could not load: %s", resolved);
            }
        }
        player.getPacketHandler().write(packet);
    }
}
