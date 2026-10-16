package com.hexvane.eterniamod.collections;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.builtin.mounts.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.model.config.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.*;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;

/** Summoned mounts use native horse controls and never become persistent livestock. */
public final class MountRuntime {
    private static ComponentType<EntityStore,Tag> type;
    private static final Set<UUID> pending=ConcurrentHashMap.newKeySet();
    private MountRuntime(){}
    private static final class Unavailable extends RuntimeException {Unavailable(String message){super(message);}}
    public static final class Tag implements Component<EntityStore>{
        final PlayerRef owner;
        Tag(PlayerRef owner){this.owner=owner;}
        @Override public Component<EntityStore> clone(){return new Tag(owner);}
    }
    public static void register(EterniaModPlugin plugin){
        type=plugin.getEntityStoreRegistry().registerComponent(Tag.class,()->new Tag(null));
        plugin.getEntityStoreRegistry().registerSystem(new EntityTickingSystem<EntityStore>(){
            @Override public Query<EntityStore> getQuery(){return type;}
            @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
                var ref=chunk.getReferenceTo(index);var tag=chunk.getComponent(index,type);
                var rider=tag.owner==null?null:tag.owner.getReference();
                var player=rider!=null&&rider.isValid()&&rider.getStore()==store?store.getComponent(rider,Player.getComponentType()):null;
                var network=store.getComponent(ref,NetworkId.getComponentType());
                boolean riding=player!=null&&network!=null&&player.getMountEntityId()==network.getId();
                if(riding&&(store.getComponent(rider,DeathComponent.getComponentType())!=null||store.getComponent(rider,Teleport.getComponentType())!=null)){
                    player.setMountEntityId(0);MountPlugin.resetOriginalPlayerMovementSettings(rider,commands);riding=false;
                }
                if(!riding)commands.removeEntity(ref,RemoveReason.REMOVE);
            }
        });
    }
    public static void summon(EterniaServices services,PlayerRef player,Ref<EntityStore> ref,Store<EntityStore> store,String content){
        if(!pending.add(player.getUuid()))return;
        var world=store.getExternalData().getWorld();
        CompletableFuture.supplyAsync(()->services.batch(()->{
            var definition=services.collection().definitions().stream().filter(d->d.id().equals(content)&&d.kind()==CollectionService.Kind.MOUNT).findFirst().orElseThrow(()->new Unavailable("This mount is unavailable."));
            if(!services.ownership().owns(Owner.player(player.getUuid()),content))throw new Unavailable("You do not own this mount.");
            return definition;
        })).whenComplete((definition,failure)->world.execute(()->{
            try{
                if(!ref.isValid()||ref.getStore()!=store)return;
                if(failure!=null)throw new Unavailable("This mount is unavailable or no longer owned.");
                var rider=store.getComponent(ref,Player.getComponentType());
                if(rider.getMountEntityId()!=0||store.getComponent(ref,MountedComponent.getComponentType())!=null)throw new Unavailable("Dismount before summoning another mount.");
                if(store.getComponent(ref,DeathComponent.getComponentType())!=null||store.getComponent(ref,Teleport.getComponentType())!=null)throw new Unavailable("You cannot summon a mount right now.");
                var pose=store.getComponent(ref,TransformComponent.getComponentType());var position=new Vector3d(pose.getPosition());
                // Leave room for both horse and rider. Never load or alter terrain to summon.
                for(double x:new double[]{-.8,0,.8})for(double z:new double[]{-1.3,0,1.3})for(double y:new double[]{.1,1,2,3}){
                    int bx=(int)Math.floor(position.x+x),by=(int)Math.floor(position.y+y),bz=(int)Math.floor(position.z+z);
                    if(!ChunkSectionBlockUtil.isChunkInMemory(world,bx,bz)||ChunkSectionBlockUtil.sectionRefAt(world,bx,by,bz)==null)throw new Unavailable("Find an open space to summon your mount.");
                    var block=ChunkSectionBlockUtil.blockType(world,bx,by,bz);
                    if(block!=null&&block!=BlockType.EMPTY&&block.getMaterial()!=BlockMaterial.Empty)throw new Unavailable("Find an open space to summon your mount.");
                }
                var asset=ModelAsset.getAssetMap().getAsset(definition.assetId());int role=NPCPlugin.get().getIndex("Empty_Role");
                if(asset==null||role<0)throw new Unavailable("This mount is not ready yet.");
                var result=NPCPlugin.get().spawnEntity(store,role,position,pose.getRotation(),Model.createScaledModel(asset,1),
                    (npc,holder,access)->{var mount=new NPCMountComponent();mount.setOriginalRoleIndex(role);mount.setOwnerPlayerRef(player);mount.setAnchor(0,1.6f,0);
                        holder.ensureComponent(Interactable.getComponentType());holder.putComponent(NPCMountComponent.getComponentType(),mount);holder.putComponent(type,new Tag(player));
                        holder.putComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());
                        holder.putComponent(Invulnerable.getComponentType(),Invulnerable.INSTANCE);},null);
                if(result==null)throw new Unavailable("Your mount could not be summoned. Try again.");
                var movement=store.getComponent(ref,MovementManager.getComponentType());var config=MovementConfig.getAssetMap().getAsset("Mount");
                if(config!=null){movement.setDefaultSettings(config,store.getComponent(ref,PhysicsValues.getComponentType()),rider.getGameMode());movement.applyDefaultSettings();movement.update(player.getPacketHandler());}
                player.sendMessage(Message.raw("Your mount is ready. Dismount to send it away."));
            }catch(RuntimeException error){if(!(error instanceof Unavailable))EterniaModPlugin.get().getLogger().atWarning().withCause(error).log("Could not summon collection mount");player.sendMessage(Message.raw(error instanceof Unavailable?error.getMessage():"Your mount could not be summoned. Try again."));}
            finally{pending.remove(player.getUuid());}
        }));
    }
}
