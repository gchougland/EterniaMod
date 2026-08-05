package com.hexvane.eterniamod.prefab;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.placement.PlotFootprintUtil;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.nio.file.Path;
import java.util.Random;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class PrefabDefinitionFactory {
    private static final String PREFAB_SUFFIX = ".prefab.json";

    private PrefabDefinitionFactory() {}

    @Nonnull
    public static String idFromPrefabPath(@Nonnull String virtualOrFilePath) {
        String fileName = lastPathSegment(virtualOrFilePath.replace('\\', '/'));
        String base = fileName;
        if (base.endsWith(PREFAB_SUFFIX)) {
            base = base.substring(0, base.length() - PREFAB_SUFFIX.length());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            } else if (c == ' ' || c == '-' || c == '_') {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
            }
        }
        String id = sb.toString();
        while (id.startsWith("_")) {
            id = id.substring(1);
        }
        while (id.endsWith("_")) {
            id = id.substring(0, id.length() - 1);
        }
        return id.isEmpty() ? "prefab" : id;
    }

    @Nonnull
    public static String displayNameFromPrefabPath(@Nonnull String virtualOrFilePath) {
        String fileName = lastPathSegment(virtualOrFilePath.replace('\\', '/'));
        String base = fileName;
        if (base.endsWith(PREFAB_SUFFIX)) {
            base = base.substring(0, base.length() - PREFAB_SUFFIX.length());
        }
        if (base.isEmpty()) {
            return "Prefab";
        }
        return base.replace('_', ' ');
    }

    @Nonnull
    public static String prefabPathKeyFromResolved(@Nonnull Path resolvedFile) {
        String fileName = resolvedFile.getFileName().toString();
        PrefabStore store = PrefabStore.get();
        if (store.findAssetPrefabPath(fileName) != null) {
            return fileName;
        }
        for (var packPath : store.getAllBrowsablePrefabPaths()) {
            Path prefabsRoot = packPath.prefabsPath();
            try {
                Path relative = prefabsRoot.relativize(resolvedFile);
                if (!relative.toString().startsWith("..")) {
                    String key = relative.toString().replace('\\', '/');
                    if (store.findAssetPrefabPath(key) != null) {
                        return key;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Not under this pack root.
            }
        }
        return fileName;
    }

    @Nullable
    public static int[] findManagementBlockLocalPos(@Nonnull IPrefabBuffer buffer) {
        BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
        PrefabRotation rotation = PrefabRotation.fromRotation(Rotation.None);
        PrefabBufferCall call = new PrefabBufferCall(new Random(0L), rotation);
        final int[] found = new int[3];
        final boolean[] any = {false};
        buffer.forEach(
            IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, supportValue, blockRotation, filler, t, fluidId, fluidLevel) -> {
                if (any[0] || filler != FillerBlockUtil.NO_FILLER) {
                    return;
                }
                BlockType blockType = blockTypeMap.getAsset(blockId);
                if (blockType == null || blockType.getId() == null) {
                    return;
                }
                if (!EterniaModConstants.MANAGEMENT_BLOCK_TYPE_ID.equals(blockType.getId())) {
                    return;
                }
                found[0] = x;
                found[1] = y;
                found[2] = z;
                any[0] = true;
            },
            null,
            null,
            call
        );
        return any[0] ? new int[] {found[0], found[1], found[2]} : null;
    }

    public static boolean validatePropPrefab(@Nonnull IPrefabBuffer buffer) {
        return PlotFootprintUtil.hasSolidVoxels(Rotation.None, buffer);
    }

    @Nonnull
    public static PropDefinition buildPropDefinition(@Nonnull String virtualPath, @Nonnull Path resolvedFile) {
        String id = idFromPrefabPath(virtualPath);
        String displayName = displayNameFromPrefabPath(virtualPath);
        String prefabPath = prefabPathKeyFromResolved(resolvedFile);
        return PropDefinition.create(id, displayName, prefabPath);
    }

    @Nullable
    public static BuildingDefinition buildBuildingDefinition(
        @Nonnull String virtualPath,
        @Nonnull Path resolvedFile,
        @Nonnull IPrefabBuffer buffer
    ) {
        int[] managementPos = findManagementBlockLocalPos(buffer);
        if (managementPos == null) {
            return null;
        }
        String id = idFromPrefabPath(virtualPath);
        String displayName = displayNameFromPrefabPath(virtualPath);
        String prefabPath = prefabPathKeyFromResolved(resolvedFile);
        return BuildingDefinition.create(id, displayName, prefabPath, managementPos);
    }

    @Nonnull
    private static String lastPathSegment(@Nonnull String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
