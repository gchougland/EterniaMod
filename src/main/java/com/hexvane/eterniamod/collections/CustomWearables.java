package com.hexvane.eterniamod.collections;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Cosmetic equipment projection only. Real inventory, armor and combat stats remain untouched. */
final class CustomWearables {
    private static ComponentType<EntityStore,Clothing> type;
    static final String ITEM="Eternia_Prowl_Shirt";
    static final class Clothing implements Component<EntityStore>{
        boolean enabled=true,dirty=true;
        @Override public Component<EntityStore> clone(){var copy=new Clothing();copy.enabled=enabled;return copy;}
    }
    static void register(EterniaModPlugin plugin){
        type=plugin.getEntityStoreRegistry().registerComponent(Clothing.class,Clothing::new);
        plugin.getEntityStoreRegistry().registerSystem(new EntityTickingSystem<EntityStore>(){
            @Override public Query<EntityStore> getQuery(){return Query.and(type,EntityTrackerSystems.Visible.getComponentType());}
            @Override public SystemGroup<EntityStore> getGroup(){return EntityTrackerSystems.QUEUE_UPDATE_GROUP;}
            @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.AFTER,InventorySystems.SyncEquipmentSystem.class));}
            @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
                var ref=chunk.getReferenceTo(index);var clothing=chunk.getComponent(index,type);
                var visible=chunk.getComponent(index,EntityTrackerSystems.Visible.getComponentType());
                for(var viewer:visible.visibleTo.values()){
                    // Amend native changes in the same update batch, including new observers and held item changes.
                    var queued=viewer.updates.get(ref);var updates=queued==null?null:queued.toUpdatesArray();
                    if(clothing.enabled&&updates!=null)for(var update:updates)if(update instanceof EquipmentUpdate equipment)project(equipment);
                    if(clothing.dirty){
                        var equipment=InventoryUtils.createEquipmentUpdate(ref,store,store.getComponent(ref,PlayerSettings.getComponentType()),store.getComponent(ref,InventoryComponent.Armor.getComponentType()),store.getComponent(ref,InventoryComponent.Utility.getComponentType()));
                        if(clothing.enabled)project(equipment);
                        viewer.queueUpdate(ref,equipment);
                    }
                }
                clothing.dirty=false;
                if(!clothing.enabled)commands.removeComponent(ref,type);
            }
        });
    }
    static void project(EquipmentUpdate equipment){
        String[] armor=equipment.armorIds==null?new String[ItemArmorSlot.values().length]:Arrays.copyOf(equipment.armorIds,ItemArmorSlot.values().length);
        for(int i=0;i<armor.length;i++)if(armor[i]==null)armor[i]="";
        armor[ItemArmorSlot.Chest.ordinal()]=ITEM;equipment.armorIds=armor;
    }
    static void apply(Ref<EntityStore> ref,Store<EntityStore> store,boolean enabled){
        var clothing=store.getComponent(ref,type);
        if(enabled&&clothing==null)store.putComponent(ref,type,new Clothing());
        else if(clothing!=null&&clothing.enabled!=enabled){clothing.enabled=enabled;clothing.dirty=true;}
    }
    static void departed(Holder<EntityStore> holder){holder.tryRemoveComponent(type);}
}
