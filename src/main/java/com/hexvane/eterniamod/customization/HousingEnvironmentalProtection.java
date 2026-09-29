package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.HousingAccess;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
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
    static void register(EterniaModPlugin plugin){var authored=new AuthoredBlockProtection(plugin);var registry=plugin.getEntityStoreRegistry();registry.registerSystem(new ExtentPlace(plugin,authored));registry.registerSystem(new EnvironmentDamage(plugin));registry.registerSystem(new AuthoredBreak(authored));registry.registerSystem(new AuthoredDamage(authored));}
    private static final class AuthoredBreak extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        private final AuthoredBlockProtection authored;AuthoredBreak(AuthoredBlockProtection authored){super(BreakBlockEvent.class);this.authored=authored;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,BreakBlockEvent event){var p=event.getTargetBlock();if(authored.protectedCell(store.getExternalData().getWorld(),p.x,p.y,p.z)){event.setCancelled(true);var player=chunk.getComponent(index,PlayerRef.getComponentType());if(player!=null)player.sendMessage(com.hypixel.hytale.server.core.Message.raw("Use the housing ledger to pack or customize this catalog object."));}}
    }
    private static final class AuthoredDamage extends EntityEventSystem<EntityStore,DamageBlockEvent> {
        private final AuthoredBlockProtection authored;AuthoredDamage(AuthoredBlockProtection authored){super(DamageBlockEvent.class);this.authored=authored;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,DamageBlockEvent event){var p=event.getTargetBlock();if(authored.protectedCell(store.getExternalData().getWorld(),p.x,p.y,p.z))event.setCancelled(true);}
    }
    private static final class EnvironmentDamage extends WorldEventSystem<EntityStore,DamageBlockEvent> {
        private final EterniaModPlugin plugin;EnvironmentDamage(EterniaModPlugin plugin){super(DamageBlockEvent.class);this.plugin=plugin;}
        @Override public void handle(Store<EntityStore> store,CommandBuffer<EntityStore> commands,DamageBlockEvent event){var world=store.getExternalData().getWorld();var p=event.getTargetBlock();if(plugin.getInfrastructure().isHousing(world.getName())||plugin.getInfrastructure().protectedColumn(world.getName(),p.x,p.z))event.setCancelled(true);}
    }
    private static final class ExtentPlace extends EntityEventSystem<EntityStore,PlaceBlockEvent> {
        private final EterniaModPlugin plugin;private final AuthoredBlockProtection authored;ExtentPlace(EterniaModPlugin plugin,AuthoredBlockProtection authored){super(PlaceBlockEvent.class);this.plugin=plugin;this.authored=authored;}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands,PlaceBlockEvent event){
            var world=store.getExternalData().getWorld();if(!plugin.getInfrastructure().world(world.getName()).isPresent())return;
            var item=event.getItemInHand();var block=item==null?null:BlockType.getAssetMap().getAsset(item.getBlockKey());
            if(block==null){if(plugin.getInfrastructure().isHousing(world.getName()))event.setCancelled(true);return;}
            var position=event.getTargetBlock();if(authored.protectedCell(world,position.x,position.y,position.z)){event.setCancelled(true);return;}
            var hitbox=BlockBoundingBoxes.getAssetMap().getAsset(block.getHitboxTypeIndex());if(hitbox==null){if(plugin.getInfrastructure().isHousing(world.getName()))event.setCancelled(true);return;}
            var actor=chunk.getComponent(index,PlayerRef.getComponentType());var origin=event.getTargetBlock();Map<UUID,Boolean> permissions=new HashMap<>();
            FillerBlockUtil.forEachFillerBlock(hitbox.get(event.getRotation().index()),(dx,dy,dz)->{
                int x=origin.x+dx,z=origin.z+dz;if(plugin.getInfrastructure().protectedColumn(world.getName(),x,z)||authored.protectedCell(world,x,origin.y+dy,z)){event.setCancelled(true);return;}
                if(!plugin.getInfrastructure().isHousing(world.getName()))return;
                var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).findPlotContainingHorizontal(x,z);
                if(plot==null||actor==null||!permissions.computeIfAbsent(plot.getPlotId(),id->HousingAccess.can(plugin,plot,actor.getUuid(),"housing.block.build")))event.setCancelled(true);
            });
        }
    }
    private HousingEnvironmentalProtection(){}
}
