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

    /** Initial decoration preview sits three blocks ahead, on the nearby surface. */
    @Nullable
    public static Vector3i anchorInFrontOfPlayer(Ref<EntityStore> ref,Store<EntityStore> store){
        var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)return null;
        var position=transform.getPosition();float yaw=BuildingPlacementNudgeUtil.getPlayerYawRadians(ref,store);
        int x=(int)Math.floor(position.x-Math.sin(yaw)*3),z=(int)Math.floor(position.z-Math.cos(yaw)*3);
        int feet=(int)Math.floor(position.y);var world=store.getExternalData().getWorld();
        for(int y=feet+1;y>=feet-3;y--){
            var ground=com.hexvane.eterniamod.world.ChunkSectionBlockUtil.blockType(world,x,y-1,z);
            if(ground!=null&&ground.isCubeDrawType()&&ground.getMaterial()==com.hypixel.hytale.protocol.BlockMaterial.Solid)
                return BuildingPlacementAnchorUtil.pickAnchor(world,new BlockPosition(x,y-1,z));
        }
        return new Vector3i(x,feet+com.hexvane.eterniamod.EterniaModConstants.PLOT_SIGN_BLOCK_Y_ABOVE_LOGICAL_ANCHOR,z);
    }

    public static void snapSessionToPlayer(@Nonnull BuildingPlacementSession session, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Vector3i anchor = anchorAtPlayerFeet(ref, store);
        if (anchor == null) {
            return;
        }
        session.setAnchor(anchor);
        session.clearBirdsEyeSnapshot();
    }

    public static void snapPropSessionToPlayer(@Nonnull PropPlacementSession session, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Vector3i anchor = anchorInFrontOfPlayer(ref, store);
        if (anchor == null) {
            return;
        }
        session.setAnchor(anchor);
    }
}
