package com.hexvane.eterniamod.placement;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Snaps plot placement previews to the player's standing position. */
public final class BuildingPlacementSnapUtil {
    private BuildingPlacementSnapUtil() {}

    @Nullable
    public static Vector3i anchorAtPlayerFeet(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
        if (tc == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        Vector3d pos = tc.getPosition();
        int groundY = (int) Math.floor(pos.y - 0.01);
        return BuildingPlacementAnchorUtil.pickAnchor(
            world,
            new BlockPosition((int) Math.floor(pos.x), groundY, (int) Math.floor(pos.z))
        );
    }

    public static void snapSessionToPlayer(@Nonnull BuildingPlacementSession session, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Vector3i anchor = anchorAtPlayerFeet(ref, store);
        if (anchor == null) {
            return;
        }
        session.setAnchor(anchor);
        session.clearBirdsEyeSnapshot();
    }
}
