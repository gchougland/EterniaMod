package com.hexvane.eterniamod.memories;

import com.hexvane.eterniamod.domain.MemoryService;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesGameplayConfig;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.builtin.adventure.memories.memories.npc.NPCMemory;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.entities.SpawnModelParticles;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.protocol.packets.player.UpdateMemoriesCount;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PickupItemComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Replaces vanilla's memory collection, which skips anything on the shared list, and applies levels per player. */
final class PersonalMemorySystems {
    private static final String ICON="NotificationIcons/MemoriesIcon.png";
    private static final String WINDOW_LEVEL="worldMemoriesLevel";
    private PersonalMemorySystems() {}

    private static boolean award(NPCMemory memory,Map<String,MemoryService.Entry> recorded,Ref<EntityStore> playerRef,PlayerRef player,PlayerMemories carried,Ref<EntityStore> npcRef,CommandBuffer<EntityStore> buffer) {
        if(!carried.recordMemory(memory))return false;
        var packets=player.getPacketHandler();
        NotificationUtil.sendNotification(packets,Message.translation("server.memories.general.collected").param("memoryTitle",Message.translation(memory.getTitle())),null,ICON);
        packets.writeNoCache(new UpdateMemoriesCount(carried.getRecordedMemories().size()));
        var config=MemoriesGameplayConfig.get(buffer.getExternalData().getWorld().getGameplayConfig());var position=effectPosition(npcRef,buffer);
        if(config!=null&&position!=null) {
            var mote=ItemComponent.generatePickedUpItem(new ItemStack(config.getMemoriesCatchItemId()),position,buffer,playerRef);
            var pickup=mote.getComponent(PickupItemComponent.getComponentType());
            if(pickup!=null)pickup.setInitialLifeTime(0.62f);
            buffer.addEntity(mote,AddReason.SPAWN);
        }
        return true;
    }
    private static boolean wanted(String id,Map<String,MemoryService.Entry> recorded,PlayerMemories carried) {
        return MemoryService.collectable(id,recorded.keySet(),carried.getRecordedMemories().stream().map(Memory::getId).toList());
    }
    private static String entryId(Role role,NPCEntity npc) {
        String override=role.getMemoriesNameOverride();
        return override!=null&&!override.isEmpty()?override:npc.getRoleName();
    }
    @Nullable private static Vector3d effectPosition(Ref<EntityStore> npcRef,CommandBuffer<EntityStore> buffer) {
        var transform=buffer.getComponent(npcRef,TransformComponent.getComponentType());if(transform==null)return null;
        var position=new Vector3d(transform.getPosition());
        var box=buffer.getComponent(npcRef,BoundingBox.getComponentType());
        if(box!=null)position.y+=box.getBoundingBox().middleY();
        return position;
    }
    private static void worldEffects(Ref<EntityStore> npcRef,CommandBuffer<EntityStore> buffer) {
        var transform=buffer.getComponent(npcRef,TransformComponent.getComponentType());if(transform==null)return;
        var config=MemoriesGameplayConfig.get(buffer.getExternalData().getWorld().getGameplayConfig());if(config==null)return;
        var position=effectPosition(npcRef,buffer);var particle=config.getMemoriesCatchEntityParticle();var networkId=buffer.getComponent(npcRef,NetworkId.getComponentType());
        if(position!=null&&particle!=null&&networkId!=null) {
            var packet=new SpawnModelParticles(networkId.getId(),new com.hypixel.hytale.protocol.ModelParticle[]{particle.toPacket()});
            var players=SpatialResource.<EntityStore>getThreadLocalReferenceList();
            buffer.getResource(EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure().ordered(position,config.getMemoriesCatchParticleViewDistance(),players);
            for(var ref:players){var player=buffer.getComponent(ref,PlayerRef.getComponentType());if(player!=null)player.getPacketHandler().write(packet);}
        }
        String sound=config.getMemoriesCatchSoundEventId();
        if(sound!=null){int index=SoundEvent.getAssetMap().getIndex(sound);if(index!=SoundEvent.EMPTY_ID)SoundUtil.playSoundEvent3d(index,SoundCategory.SFX,transform.getPosition(),buffer);}
    }
    private static String locationKey(World world,Vector3d position) {
        if(world.getChunkStore().getGenerator() instanceof ChunkGenerator generator)
            return "server.map.region."+generator.getZoneBiomeResultAt((int)world.getWorldConfig().getSeed(),MathUtil.floor(position.x),MathUtil.floor(position.z)).getZoneResult().getZone().name();
        var instance=world.getWorldConfig().getPluginConfig().get(InstanceWorldConfig.class);
        if(instance!=null&&instance.getDiscovery()!=null&&instance.getDiscovery().getTitleKey()!=null)return instance.getDiscovery().getTitleKey();
        return NPCMemory.ZONE_NAME_UNKNOWN;
    }

    static final class Gather extends EntityTickingSystem<EntityStore> {
        private final PersonalMemories memories;private final double radius;
        Gather(PersonalMemories memories,double radius){this.memories=memories;this.radius=radius;}
        @Override public Query<EntityStore> getQuery(){return Query.and(TransformComponent.getComponentType(),Player.getComponentType(),PlayerRef.getComponentType(),PlayerMemories.getComponentType());}
        @Override public void tick(float dt,int index,@Nonnull ArchetypeChunk<EntityStore> chunk,@Nonnull Store<EntityStore> store,@Nonnull CommandBuffer<EntityStore> buffer) {
            if(chunk.getComponent(index,Player.getComponentType()).getGameMode()!=GameMode.Adventure)return;
            var carried=chunk.getComponent(index,PlayerMemories.getComponentType());if(carried.getMemoriesCapacity()<=0)return;
            var position=chunk.getComponent(index,TransformComponent.getComponentType()).getPosition();
            var nearby=SpatialResource.<EntityStore>getThreadLocalReferenceList();
            store.getResource(NPCPlugin.get().getNpcSpatialResource()).getSpatialStructure().collect(position,radius,nearby);
            if(nearby.isEmpty())return;
            var player=chunk.getComponent(index,PlayerRef.getComponentType());
            var recorded=memories.recorded(player.getUuid());if(recorded==null)return;
            for(var npcRef:nearby) {
                var npc=buffer.getComponent(npcRef,NPCEntity.getComponentType());if(npc==null)continue;
                var role=npc.getRole();if(role==null||!role.isMemory()||role.getMemoriesUnlockedBy()!=Role.MemoriesUnlockMethod.Proximity)continue;
                String id=entryId(role,npc);if(!wanted(id,recorded,carried))continue;
                var memory=PersonalMemories.npcMemory(id,role.getNameTranslationKey(),locationKey(buffer.getExternalData().getWorld(),new Vector3d(position)),System.currentTimeMillis());
                if(award(memory,recorded,chunk.getReferenceTo(index),player,carried,npcRef,buffer)) {
                    worldEffects(npcRef,buffer);
                    // Awarding reuses the shared nearby list, so any further memories wait for a later tick.
                    break;
                }
            }
        }
    }

    static final class Kill extends DeathSystems.OnDeathSystem {
        private final PersonalMemories memories;private final double defaultRange;
        Kill(PersonalMemories memories,double defaultRange){this.memories=memories;this.defaultRange=defaultRange;}
        @Override public Query<EntityStore> getQuery(){return Query.and(NPCEntity.getComponentType(),TransformComponent.getComponentType());}
        @Override public void onComponentAdded(@Nonnull Ref<EntityStore> ref,@Nonnull DeathComponent component,@Nonnull Store<EntityStore> store,@Nonnull CommandBuffer<EntityStore> buffer) {
            var npc=store.getComponent(ref,NPCEntity.getComponentType());if(npc==null)return;
            var role=npc.getRole();if(role==null||!role.isMemory()||role.getMemoriesUnlockedBy()!=Role.MemoriesUnlockMethod.Kill)return;
            var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)return;
            double range=role.getMemoriesKillRange()>0?role.getMemoriesKillRange():defaultRange;
            var found=SpatialResource.<EntityStore>getThreadLocalReferenceList();
            store.getResource(EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure().collect(transform.getPosition(),range,found);
            if(found.isEmpty())return;
            List<Ref<EntityStore>> nearby=new ArrayList<>(found);
            String id=entryId(role,npc);String location=locationKey(buffer.getExternalData().getWorld(),new Vector3d(transform.getPosition()));long now=System.currentTimeMillis();boolean awarded=false;
            for(var playerRef:nearby) {
                var body=store.getComponent(playerRef,Player.getComponentType());if(body==null||body.getGameMode()!=GameMode.Adventure)continue;
                var carried=store.getComponent(playerRef,PlayerMemories.getComponentType());var player=store.getComponent(playerRef,PlayerRef.getComponentType());
                if(carried==null||player==null||carried.getMemoriesCapacity()<=0)continue;
                var recorded=memories.recorded(player.getUuid());if(recorded==null||!wanted(id,recorded,carried))continue;
                if(award(PersonalMemories.npcMemory(id,role.getNameTranslationKey(),location,now),recorded,playerRef,player,carried,ref,buffer))awarded=true;
                else NotificationUtil.sendNotification(player.getPacketHandler(),Message.translation("server.memories.general.full"),Message.translation("server.memories.general.full.detail"),ICON,null,NotificationStyle.Warning,"memories.full");
            }
            if(awarded)worldEffects(ref,buffer);
        }
    }

    /** The server's own craft check, since the shared list now clears every level. */
    static final class CraftGate extends EntityEventSystem<EntityStore,CraftRecipeEvent.Pre> {
        private final PersonalMemories memories;
        CraftGate(PersonalMemories memories){super(CraftRecipeEvent.Pre.class);this.memories=memories;}
        @Override public Query<EntityStore> getQuery(){return PlayerRef.getComponentType();}
        @Override public void handle(int index,@Nonnull ArchetypeChunk<EntityStore> chunk,@Nonnull Store<EntityStore> store,@Nonnull CommandBuffer<EntityStore> buffer,@Nonnull CraftRecipeEvent.Pre event) {
            int required=event.getCraftedRecipe().getRequiredMemoriesLevel();if(required<=1)return;
            var player=chunk.getComponent(index,PlayerRef.getComponentType());
            if(!MemoryService.canCraft(required,memories.level(player.getUuid(),store.getExternalData().getWorld().getGameplayConfig())))event.setCancelled(true);
        }
    }

    /** Bench windows announce the shared level when they open, so each one is corrected to the player's own level. */
    static final class WindowLevels extends EntityTickingSystem<EntityStore> {
        private final PersonalMemories memories;
        WindowLevels(PersonalMemories memories){this.memories=memories;}
        @Override public Query<EntityStore> getQuery(){return Query.and(Player.getComponentType(),PlayerRef.getComponentType());}
        @Override public void tick(float dt,int index,@Nonnull ArchetypeChunk<EntityStore> chunk,@Nonnull Store<EntityStore> store,@Nonnull CommandBuffer<EntityStore> buffer) {
            var uuid=chunk.getComponent(index,PlayerRef.getComponentType()).getUuid();
            int level=memories.level(uuid,store.getExternalData().getWorld().getGameplayConfig());
            var windows=chunk.getComponent(index,Player.getComponentType()).getWindowManager();
            for(var window:windows.getWindows()) {
                var data=window.getData();
                if(!data.has(WINDOW_LEVEL)||data.get(WINDOW_LEVEL).getAsInt()==level)continue;
                data.addProperty(WINDOW_LEVEL,level);
                windows.updateWindow(window);
            }
        }
    }
}
