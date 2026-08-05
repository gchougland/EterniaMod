package com.hexvane.eterniamod.prop;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prefab.PropPrefabOps;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class PropLookupUtil {
    public record PropMatch(@Nonnull HubPlotProp prop) {}

    private PropLookupUtil() {}

    @Nullable
    public static PropMatch findPropAtBlock(
        @Nonnull HubPlotRecord plot,
        @Nonnull World world,
        @Nonnull Vector3i blockPos,
        @Nonnull EterniaModPlugin plugin
    ) {
        for (HubPlotProp prop : plot.getProps()) {
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
            if (PropPrefabOps.blockBelongsToProp(
                world, origin, prop.resolveRotationYaw(), buffer, blockPos.x, blockPos.y, blockPos.z
            )) {
                return new PropMatch(prop);
            }
        }
        return null;
    }

    @Nullable
    public static HubPlotProp findOwnedPlotPropAtBlock(
        @Nonnull HubPlotRecord plot,
        @Nonnull World world,
        @Nonnull Vector3i blockPos,
        @Nonnull EterniaModPlugin plugin
    ) {
        PropMatch match = findPropAtBlock(plot, world, blockPos, plugin);
        return match != null ? match.prop() : null;
    }
}
