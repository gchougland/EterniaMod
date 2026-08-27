package com.hexvane.eterniamod.placement;

import com.hexvane.eterniamod.building.PrefabLocalOffset;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.Axis;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.protocol.packets.interface_.BlockChange;
import com.hypixel.hytale.protocol.packets.interface_.EditorBlocksChange;
import com.hypixel.hytale.protocol.packets.interface_.FluidChange;
import com.hypixel.hytale.protocol.packets.player.HideTriggerVolumePastePrefabPreview;
import com.hypixel.hytale.protocol.packets.player.ShowTriggerVolumePastePrefabPreview;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.prefab.selection.standard.RotateBlockMode;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

public final class BuildingPlacementClientPrefabPreview {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int DEFAULT_BIOME_TINT =
        ColorParseUtil.colorToARGBInt(
            com.hypixel.hytale.builtin.buildertools.prefabeditor.PrefabEditSessionManager.DEFAULT_TINT
        )
            & 16777215;
    private static final int DEFAULT_WATER_TINT =
        ColorParseUtil.colorToARGBInt(Environment.getUnknownFor("").getWaterTint()) & 16777215;

    private BuildingPlacementClientPrefabPreview() {}

    public record Payload(
        @Nullable BlockChange[] blocksChange,
        @Nullable FluidChange[] fluidsChange,
        @Nullable ClipboardEntityChange[] entityChanges,
        int anchorX,
        int anchorY,
        int anchorZ
    ) {}

    public static void hide(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().write(new HideTriggerVolumePastePrefabPreview());
    }

    public static boolean sendFull(
        @Nonnull PlayerRef playerRef,
        @Nonnull String prefabPathKey,
        int rotationSteps,
        @Nonnull Vector3i prefabOriginWorld,
        @Nonnull Rotation placementYaw,
        @Nonnull PrefabPreviewCacheHolder session
    ) {
        Payload payload = resolvePayload(prefabPathKey, rotationSteps, session);
        if (payload == null) {
            return false;
        }
        writeShow(playerRef, flooredPosition(resolveClientPreviewPosition(prefabOriginWorld, payload, placementYaw)), payload);
        return true;
    }

    public static boolean sendPositionOnly(
        @Nonnull PlayerRef playerRef,
        @Nonnull Vector3i prefabOriginWorld,
        @Nonnull Payload payload,
        @Nonnull Rotation placementYaw
    ) {
        Vector3f pos = flooredPosition(resolveClientPreviewPosition(prefabOriginWorld, payload, placementYaw));
        ShowTriggerVolumePastePrefabPreview packet = new ShowTriggerVolumePastePrefabPreview();
        packet.position = pos;
        applyTintFromPlayerPosition(playerRef, packet);
        playerRef.getPacketHandler().write(packet);
        return true;
    }

    @Nonnull
    public static Vector3i resolveClientPreviewPosition(
        @Nonnull Vector3i prefabBufferOriginWorld,
        @Nonnull Payload payload,
        @Nonnull Rotation placementYaw
    ) {
        Vector3i anchorOffset = PrefabLocalOffset.rotate(placementYaw, payload.anchorX(), payload.anchorY(), payload.anchorZ());
        return new Vector3i(
            prefabBufferOriginWorld.x + anchorOffset.x,
            prefabBufferOriginWorld.y + anchorOffset.y,
            prefabBufferOriginWorld.z + anchorOffset.z
        );
    }

    @Nonnull
    public static Vector3i flooredClientPreviewOrigin(
        @Nonnull Vector3i prefabBufferOriginWorld,
        @Nonnull PrefabPreviewCacheHolder session,
        @Nonnull Rotation placementYaw
    ) {
        Payload payload = session.getClientPrefabPreviewPayload();
        if (payload == null) {
            return new Vector3i(
                MathUtil.floor(prefabBufferOriginWorld.x),
                MathUtil.floor(prefabBufferOriginWorld.y),
                MathUtil.floor(prefabBufferOriginWorld.z)
            );
        }
        Vector3i pos = resolveClientPreviewPosition(prefabBufferOriginWorld, payload, placementYaw);
        return new Vector3i(MathUtil.floor(pos.x), MathUtil.floor(pos.y), MathUtil.floor(pos.z));
    }

    public static void clearSessionCache(@Nonnull PrefabPreviewCacheHolder session) {
        session.clearClientPrefabPreviewCache();
    }

    @Nullable
    static Payload resolvePayload(
        @Nonnull String prefabPathKey,
        int rotationSteps,
        @Nonnull PrefabPreviewCacheHolder session
    ) {
        int steps = (rotationSteps % 4 + 4) % 4;
        Payload cached = session.getClientPrefabPreviewPayload();
        if (cached != null
            && steps == session.getClientPrefabPreviewRotationSteps()
            && prefabPathKey.equals(session.getClientPrefabPreviewPathKey())) {
            return cached;
        }
        Path resolved = PrefabResolveUtil.resolvePrefabPath(prefabPathKey);
        if (resolved == null) {
            LOGGER.atWarning().log("Building placement prefab not found: %s", prefabPathKey);
            return null;
        }
        BlockSelection selection;
        try {
            selection = PrefabStore.get().getPrefab(resolved);
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Building placement prefab failed to load: %s", resolved);
            return null;
        }
        if (selection == null) {
            LOGGER.atWarning().log("Building placement prefab resolved empty: %s", resolved);
            return null;
        }
        BlockSelection rotated = selection.cloneSelection();
        if (steps != 0) {
            rotated = rotated.rotate(Axis.Y, 90 * steps, RotateBlockMode.ALL);
        }
        EditorBlocksChange editor = rotated.toPacket();
        Payload payload =
            new Payload(
                editor.blocksChange,
                editor.fluidsChange,
                editor.entityChanges,
                selection.getAnchorX(),
                selection.getAnchorY(),
                selection.getAnchorZ()
            );
        session.setClientPrefabPreviewCache(prefabPathKey, steps, payload);
        int blockCount = payload.blocksChange() != null ? payload.blocksChange().length : 0;
        LOGGER.atInfo().log(
            "Building placement prefab payload ready: %s blocks=%d anchor=(%d,%d,%d)",
            prefabPathKey,
            blockCount,
            payload.anchorX(),
            payload.anchorY(),
            payload.anchorZ()
        );
        return payload;
    }

    private static void writeShow(@Nonnull PlayerRef playerRef, @Nonnull Vector3f position, @Nonnull Payload payload) {
        ShowTriggerVolumePastePrefabPreview packet = new ShowTriggerVolumePastePrefabPreview();
        packet.position = position;
        packet.blocksChange = payload.blocksChange();
        packet.fluidsChange = payload.fluidsChange();
        packet.entityChanges = payload.entityChanges();
        applyTintFromPlayerPosition(playerRef, packet);
        playerRef.getPacketHandler().write(packet);
    }

    @Nonnull
    private static Vector3f flooredPosition(@Nonnull Vector3i prefabOriginWorld) {
        return new Vector3f(
            (float) MathUtil.floor(prefabOriginWorld.x),
            (float) MathUtil.floor(prefabOriginWorld.y),
            (float) MathUtil.floor(prefabOriginWorld.z)
        );
    }

    private static void applyTintFromPlayerPosition(
        @Nonnull PlayerRef playerRef,
        @Nonnull ShowTriggerVolumePastePrefabPreview packet
    ) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            packet.biomeTint = DEFAULT_BIOME_TINT;
            packet.waterTint = DEFAULT_WATER_TINT;
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        Vector3d pos = playerRef.getTransform().getPosition();
        applyTintFromWorldPosition(world, MathUtil.floor(pos.x), MathUtil.floor(pos.y), MathUtil.floor(pos.z), packet);
    }

    private static void applyTintFromWorldPosition(
        @Nonnull World world,
        int x,
        int y,
        int z,
        @Nonnull ShowTriggerVolumePastePrefabPreview packet
    ) {
        BlockChunk blockChunk = ChunkSectionBlockUtil.blockChunkAt(world, x, z);
        if (blockChunk != null) {
            packet.biomeTint = blockChunk.getTint(x, z);
            int envId = blockChunk.getEnvironment(x, y, z);
            Environment environment = Environment.getAssetMap().getAsset(envId);
            if (environment != null) {
                com.hypixel.hytale.protocol.Color waterColor = environment.getWaterTint();
                if (waterColor != null) {
                    packet.waterTint =
                        (waterColor.red & 255) << 16 | (waterColor.green & 255) << 8 | waterColor.blue & 255;
                    return;
                }
            }
            packet.waterTint = DEFAULT_WATER_TINT;
        } else {
            packet.biomeTint = DEFAULT_BIOME_TINT;
            packet.waterTint = DEFAULT_WATER_TINT;
        }
    }
}
