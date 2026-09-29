package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/** Housing ownership is enforced on all heights; the preview's visual Y bounds grant no permissions. */
public final class HousingProtection {
    private HousingProtection() {}
    public static void register(EterniaModPlugin plugin) {
        var registry=plugin.getEntityStoreRegistry();
        registry.registerSystem(new BreakProtection(plugin));registry.registerSystem(new PlaceProtection(plugin));
        registry.registerSystem(new DamageProtection(plugin));registry.registerSystem(new UseProtection(plugin));
    }
    private static boolean denied(EterniaModPlugin plugin,ArchetypeChunk<EntityStore> chunk,int index,Store<EntityStore> store,Vector3i pos,String capability) {
        boolean use=!capability.startsWith("housing.block.");
        var world=store.getExternalData().getWorld();
        var infrastructure=plugin.getInfrastructure();
        if (!use&&infrastructure.protectedColumn(world.getName(),pos.x,pos.z)) return true;
        if (!infrastructure.isHousing(world.getName())) return false;
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).findPlotContainingHorizontal(pos.x,pos.z);
        if (plot==null) return !use;
        var player=chunk.getComponent(index,PlayerRef.getComponentType());
        // A disconnected visitor must still be able to leave a personal house.
        // Door use grants no storage/build rights; guild estates keep role checks.
        if(player!=null&&capability.equals("housing.door.use")&&plot.getGuildOwnerUuid()==null&&plot.hasBuilding()&&!HousingAccess.locked(plugin,plot)){
            var visitor=chunk.getComponent(index,com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if(visitor!=null&&plot.getFootprint().containsHorizontal((int)Math.floor(visitor.getPosition().x),(int)Math.floor(visitor.getPosition().z)))return false;
        }
        // A ledger admits visitors; every mutation inside it independently checks its own permission.
        return player==null||!HousingAccess.can(plugin,plot,player.getUuid(),capability);
    }
    private static final class BreakProtection extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        private final EterniaModPlugin plugin;BreakProtection(EterniaModPlugin p){super(BreakBlockEvent.class);plugin=p;}
        public Query<EntityStore> getQuery(){return Query.any();}
        public void handle(int i,ArchetypeChunk<EntityStore> c,Store<EntityStore>s,CommandBuffer<EntityStore>b,BreakBlockEvent e){if(denied(plugin,c,i,s,e.getTargetBlock(),"housing.block.break"))e.setCancelled(true);}
    }
    private static final class PlaceProtection extends EntityEventSystem<EntityStore,PlaceBlockEvent> {
        private final EterniaModPlugin plugin;PlaceProtection(EterniaModPlugin p){super(PlaceBlockEvent.class);plugin=p;}
        public Query<EntityStore> getQuery(){return Query.any();}
        public void handle(int i,ArchetypeChunk<EntityStore> c,Store<EntityStore>s,CommandBuffer<EntityStore>b,PlaceBlockEvent e){if(denied(plugin,c,i,s,e.getTargetBlock(),"housing.block.build"))e.setCancelled(true);}
    }
    private static final class DamageProtection extends EntityEventSystem<EntityStore,DamageBlockEvent> {
        private final EterniaModPlugin plugin;DamageProtection(EterniaModPlugin p){super(DamageBlockEvent.class);plugin=p;}
        public Query<EntityStore> getQuery(){return Query.any();}
        public void handle(int i,ArchetypeChunk<EntityStore> c,Store<EntityStore>s,CommandBuffer<EntityStore>b,DamageBlockEvent e){if(denied(plugin,c,i,s,e.getTargetBlock(),"housing.block.break"))e.setCancelled(true);}
    }
    private static final class UseProtection extends EntityEventSystem<EntityStore,UseBlockEvent.Pre> {
        private final EterniaModPlugin plugin;UseProtection(EterniaModPlugin p){super(UseBlockEvent.Pre.class);plugin=p;}
        public Query<EntityStore> getQuery(){return Query.any();}
        public void handle(int i,ArchetypeChunk<EntityStore> c,Store<EntityStore>s,CommandBuffer<EntityStore>b,UseBlockEvent.Pre e){
            if(!plugin.getInfrastructure().isHousing(s.getExternalData().getWorld().getName()))return;
            for(String capability:HousingUseCapabilities.required(s,e)){
                if(HousingUseCapabilities.PORTAL_SERVICE.equals(capability)){
                    var pos=e.getTargetBlock();
                    if(plugin.getInfrastructure().publicPortalColumn(s.getExternalData().getWorld().getName(),pos.x,pos.z))continue;
                    // An unregistered prop can open a visitor menu; it cannot grant travel rights.
                    // The selected journey independently checks public proximity or a current home/guild entitlement.
                    capability="housing.visit";
                }
                if(denied(plugin,c,i,s,e.getTargetBlock(),capability)){e.setCancelled(true);return;}
            }
        }
    }
}
