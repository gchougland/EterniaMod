package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.*;

/** Complements origin-cell protection with multiblock extents and unattributed explosion damage. */
final class HousingEnvironmentalProtection {
    // Native edits are already denied throughout owned/reserved plots, including their catalog
    // objects. Avoid a second provenance scan and snapshot load for every hit on those plots.
    static void register(EterniaModPlugin plugin){var registry=plugin.getEntityStoreRegistry();registry.registerSystem(new ExtentPlace(plugin));registry.registerSystem(new EnvironmentDamage(plugin));}
    private static final class EnvironmentDamage extends WorldEventSystem<EntityStore,DamageBlockEvent> {
        private final EterniaModPlugin plugin;EnvironmentDamage(EterniaModPlugin plugin){super(DamageBlockEvent.class);this.plugin=plugin;}
        @Override public void handle(Store<EntityStore> store,CommandBuffer<EntityStore> commands,DamageBlockEvent event){var world=store.getExternalData().getWorld();var p=event.getTargetBlock();if(plugin.getInfrastructure().isHousing(world.getName())||plugin.getInfrastructure().protectedColumn(world.getName(),p.x,p.z))event.setCancelled(true);}
    }
    private static final class ExtentPlace extends EntityEventSystem<EntityStore,PlaceBlockEvent> {
        private final EterniaModPlugin plugin;ExtentPlace(EterniaModPlugin plugin){super(PlaceBlockEvent.class);this.plugin=plugin;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,PlaceBlockEvent event){
            var world=store.getExternalData().getWorld();if(!plugin.getInfrastructure().world(world.getName()).isPresent())return;
            var item=event.getItemInHand();var block=item==null?null:BlockType.getAssetMap().getAsset(item.getBlockKey());
            if(block==null){if(plugin.getInfrastructure().isHousing(world.getName()))event.setCancelled(true);return;}
            var hitbox=BlockBoundingBoxes.getAssetMap().getAsset(block.getHitboxTypeIndex());if(hitbox==null){if(plugin.getInfrastructure().isHousing(world.getName()))event.setCancelled(true);return;}
            var actor=chunk.getComponent(index,PlayerRef.getComponentType());var origin=event.getTargetBlock();
            FillerBlockUtil.forEachFillerBlock(hitbox.get(event.getRotation().index()),(dx,dy,dz)->{
                int x=origin.x+dx,z=origin.z+dz;if(actor!=null&&HubBuildMode.canEdit(plugin,actor.getUuid(),world,x,z))return;if(plugin.getInfrastructure().protectedColumn(world.getName(),x,z)){event.setCancelled(true);return;}
                if(!plugin.getInfrastructure().isHousing(world.getName()))return;
                event.setCancelled(true);
            });
        }
    }
    private HousingEnvironmentalProtection(){}
}
