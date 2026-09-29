package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.hub.EterniaPlacedInstance;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.Axis;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.Dirty;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemPhysicsComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkSaver;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection;
import java.util.*;
import org.bson.*;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Reviewed static item-prop adapter. NPCs, inventories, timers and unknown gameplay state are not accepted. */
public final class NativeDecorativeEntities {
    private NativeDecorativeEntities() {}
    private static final Set<String> REQUIRED=Set.of("PreventItemMerging","Prop","BlockEntity","Transform","UUID","PreventPickup","EntityScale","Item","PrefabCopyable");
    private static final Set<String> PERSISTENT;
    static {var keys=new HashSet<>(REQUIRED);keys.add("EterniaPlacedInstance");PERSISTENT=Set.copyOf(keys);}

    /** Validate the persisted shape independently of a live ECS registry; no unsupported field is silently discarded. */
    static void validateDocument(BsonDocument document,boolean linked) {
        var components=document.getDocument("Components");
        if(!components.keySet().containsAll(REQUIRED)||!PERSISTENT.containsAll(components.keySet())||linked!=components.containsKey("EterniaPlacedInstance"))throw new IllegalStateException("Only reviewed decorative item entity components are supported");
        var item=components.getDocument("Item");var stack=item.getDocument("Item");
        if(stack.getNumber("Quantity").intValue()!=1||item.getBoolean("RemovedByPlayerPickup",BsonBoolean.FALSE).getValue()||!stack.getString("Id").getValue().equals(components.getDocument("BlockEntity").getString("BlockTypeKey").getValue()))throw new IllegalStateException("Decorative entity must contain one matching non-pickup item");
        double scale=components.getDocument("EntityScale").getNumber("Scale").doubleValue();
        if(!Double.isFinite(scale)||scale<=0||scale>4)throw new IllegalStateException("Decorative entity scale must be within (0,4]");
        var transform=components.getDocument("Transform");
        for(var key:List.of("X","Y","Z"))if(!Double.isFinite(transform.getDocument("Position").getNumber(key).doubleValue()))throw new IllegalStateException("Invalid decorative entity position");
        for(var key:List.of("Pitch","Yaw","Roll"))if(!Double.isFinite(transform.getDocument("Rotation").getNumber(key).doubleValue()))throw new IllegalStateException("Invalid decorative entity rotation");
    }

    /** Prepare fresh UUIDs and ownership links before writing the immutable placement after-image. */
    public static List<Holder<EntityStore>> prepare(IPrefabBuffer buffer,Rotation yaw,UUID instance,String catalog,boolean house) {
        if(buffer.getChildPrefabs()!=null&&buffer.getChildPrefabs().length>0)throw new IllegalStateException("Nested prefab children require an explicit placement adapter");
        BlockSelection entities=new BlockSelection();entities.setAnchor(0,0,0);
        buffer.forEachEntity((x,z,holders,unused)->{
            if(holders==null)return;
            for(var source:holders){
                var holder=source.clone();NativeSnapshotStore.assertLossless(holder,EntityStore.REGISTRY);
                validateDocument(EntityStore.REGISTRY.serialize(holder),false);
                String itemId=holder.getComponent(ItemComponent.getComponentType()).getItemStack().getItemId();
                if(Item.getAssetMap().getAsset(itemId)==null)throw new IllegalStateException("Unknown decorative item asset");
                // Exactly the native v0->v1 BlockEntitySystems.PositionMigrationSystem transform, performed before hashing.
                var block=holder.getComponent(BlockEntity.getComponentType());var transform=holder.getComponent(TransformComponent.getComponentType());
                if(block.consumeOutdatedAnchor()){
                    var scale=holder.getComponent(EntityScaleComponent.getComponentType());float oldScale=scale.getScale();scale.setScale(oldScale/2f);
                    transform.getPosition().add(transform.getRotation().transform(new Vector3d(0,(oldScale/4f)-0.5,0))).add(0,0.5,0);
                }
                holder.putComponent(UUIDComponent.getComponentType(),UUIDComponent.generateVersion3UUID());
                holder.addComponent(EterniaPlacedInstance.getComponentType(),new EterniaPlacedInstance(house?EterniaPlacedInstance.Kind.BUILDING:EterniaPlacedInstance.Kind.PROP,instance,catalog));
                entities.addEntityHolderRaw(holder);
            }
        },null);
        var rotated=yaw==Rotation.None?entities:entities.rotate(Axis.Y,yaw.getDegrees());
        var result=new ArrayList<Holder<EntityStore>>();rotated.forEachEntity(holder->{NativeSnapshotStore.assertLossless(holder,EntityStore.REGISTRY);validateDocument(EntityStore.REGISTRY.serialize(holder),true);result.add(holder);});return result;
    }

    /** Only documented native caches are stripped. New persistent or transient components fail before destruction. */
    public static Holder<EntityStore> capture(Holder<EntityStore> source) {
        var holder=source.clone();
        holder.tryRemoveComponent(NetworkId.getComponentType()); // regenerated by ItemSystems / BlockEntitySetupSystem
        holder.tryRemoveComponent(BoundingBox.getComponentType()); // rebuilt from native item/block asset and rotation
        holder.tryRemoveComponent(ItemPhysicsComponent.getComponentType()); // collision scratch cache, no velocity is accepted
        holder.tryRemoveComponent(DynamicLight.getComponentType()); // recomputed from the item asset
        holder.tryRemoveComponent(SnapshotBuffer.getComponentType()); // latency history is rebuilt by SnapshotSystems.Add
        holder.tryRemoveComponent(EntityTrackerSystems.Visible.getComponentType()); // per-client visibility, rebuilt by tracker
        holder.tryRemoveComponent(Dirty.getComponentType()); // save bookkeeping rebuilt by native EnsureUUID
        NativeSnapshotStore.assertLossless(holder,EntityStore.REGISTRY);
        validateDocument(EntityStore.REGISTRY.serialize(holder),true);
        return holder;
    }

    /** Reject duplicate persisted UUIDs before any block or entity world write. Restores retain their identity. */
    public static void preflightRestore(World world,BsonDocument prefab,Vector3i destination) {
        var ids=new HashSet<UUID>();
        for(var value:prefab.getArray("entities",new BsonArray())){
            validateDocument(value.asDocument(),true);var holder=EntityStore.REGISTRY.deserialize(value.asDocument());NativeSnapshotStore.assertLossless(holder,EntityStore.REGISTRY);
            UUID id=holder.getComponent(UUIDComponent.getComponentType()).getUuid();
            if(!ids.add(id)||world.getEntityStore().getRefFromUUID(id)!=null)throw new IllegalStateException("A decorative entity UUID is already present; recovery must inspect existing custody");
            var p=holder.getComponent(TransformComponent.getComponentType()).getPosition();int x=(int)Math.floor(p.x+destination.x),y=(int)Math.floor(p.y+destination.y),z=(int)Math.floor(p.z+destination.z);
            if(EterniaModPlugin.get().getInfrastructure().protectedColumn(world.getName(),x,z)||com.hexvane.eterniamod.world.ChunkSectionBlockUtil.sectionRefAt(world,x,y,z)==null)throw new IllegalStateException("Decorative entity destination became protected or unavailable");
        }
    }
    static UUID instance(BsonValue value){var holder=EntityStore.REGISTRY.deserialize(value.asDocument());var link=holder.getComponent(EterniaPlacedInstance.getComponentType());if(link==null)throw new IllegalStateException("Decorative entity has no placement provenance");return link.getInstanceId();}
    static void filterInstances(BsonDocument document,UUID instance,boolean retain){var kept=new BsonArray();for(var entity:document.getArray("entities",new BsonArray()))if(instance(entity).equals(instance)==retain)kept.add(entity);if(kept.isEmpty())document.remove("entities");else document.put("entities",kept);}
    static void requireIdentity(BsonDocument expected,BsonDocument captured,UUID instance){
        var before=new HashSet<BsonValue>();var after=new HashSet<BsonValue>();
        for(var value:expected.getArray("entities",new BsonArray()))if(instance(value).equals(instance))before.add(value.asDocument().getDocument("Components").getDocument("UUID").get("UUID"));
        for(var value:captured.getArray("entities",new BsonArray()))if(instance(value).equals(instance))if(!after.add(value.asDocument().getDocument("Components").getDocument("UUID").get("UUID")))throw new IllegalStateException("Duplicate decorative entity identity");
        if(!before.equals(after))throw new IllegalStateException("Decorative entities left their recorded property or changed identity; inspect before packing");
    }
    /** Native UNLOAD alone is not deletion: explicitly dirty membership and await the persisted body tombstone. */
    public static void remove(World world,Ref<EntityStore> ref){
        var store=world.getEntityStore().getStore();var dirty=store.getComponent(ref,Dirty.getComponentType());
        if(dirty!=null&&dirty.isSaving())throw new IllegalStateException("Decorative entity has in-flight storage work; retry after it completes");
        UUID uuid=store.getComponent(ref,UUIDComponent.getComponentType()).getUuid();
        var sectionRef=store.getComponent(ref,TransformComponent.getComponentType()).getSectionRef();
        if(sectionRef==null||!sectionRef.isValid())throw new IllegalStateException("Decorative entity is not attached to a durable section");
        var section=world.getChunkStore().getStore().getComponent(sectionRef,EntitySection.getComponentType());
        if(section==null||section.isSaving())throw new IllegalStateException("Decorative entity section is unavailable or saving");
        section.removeEntityReference(ref);
        // No second asynchronous native REMOVE tombstone may race a same-UUID restoration after this method returns.
        store.removeEntity(ref,RemoveReason.UNLOAD);
        if(world.getChunkStore().getSaver() instanceof IChunkSaver.Cubic cubic)cubic.removeEntity(uuid).join();
    }
    /** Cubic stores keep a separate UUID body row; legacy stores embed these holders in ChunkColumn/EntitySection. */
    public static void flushBodies(World world,NativeSnapshotStore.Bounds bounds){
        var store=world.getEntityStore().getStore();
        for(var ref:NativeSnapshotStore.entities(world,bounds))if(store.getComponent(ref,EterniaPlacedInstance.getComponentType())!=null){
            var dirty=store.getComponent(ref,Dirty.getComponentType());
            if(dirty!=null&&dirty.isSaving())throw new IllegalStateException("Decorative entity storage is still in flight");
            if(dirty!=null)dirty.forceMarkDirty();
            if(world.getChunkStore().getSaver() instanceof IChunkSaver.Cubic cubic)cubic.saveEntity(store.getComponent(ref,UUIDComponent.getComponentType()).getUuid(),store,ref).join();
        }
    }
}
