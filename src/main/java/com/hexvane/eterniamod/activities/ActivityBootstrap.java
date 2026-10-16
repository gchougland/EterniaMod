package com.hexvane.eterniamod.activities;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3i;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** Verified native activity in adventure worlds, with explicit world and target overrides. */
public final class ActivityBootstrap implements AutoCloseable {
    private static final Logger LOG=Logger.getLogger(ActivityBootstrap.class.getName());
    private static volatile ActivityBootstrap active;
    public record Settings(Set<String> worlds,Map<String,Long> kills,Map<String,Long> mining,Map<String,Long> harvesting){
        public Settings{worlds=Set.copyOf(worlds);kills=Map.copyOf(kills);mining=Map.copyOf(mining);harvesting=Map.copyOf(harvesting);
            for(var map:List.of(kills,mining,harvesting))map.forEach((id,xp)->{if(id.isBlank()||id.length()>200||xp<0||xp>100000)throw new IllegalArgumentException("Invalid activity XP mapping");});}
    }
    private final EterniaServices services;
    private final EterniaModPlugin plugin;
    private final Settings settings;
    private final QuestToasts questToasts;
    private final ConcurrentMap<UUID,Settings> localTrials=new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"eternia-activity-outbox");t.setDaemon(true);return t;});
    private volatile boolean closed;
    public ActivityBootstrap(EterniaModPlugin plugin,EterniaServices services,Settings settings){
        this.plugin=plugin;this.services=Objects.requireNonNull(services);this.settings=Objects.requireNonNull(settings);active=this;
        questToasts=new QuestToasts(services);
        var registry=plugin.getEntityStoreRegistry();registry.registerSystem(new Placement());registry.registerSystem(new Mining());registry.registerSystem(new Kills());
        plugin.getCodecRegistry(Interaction.CODEC).register("EterniaHarvestCrop",EterniaHarvestCrop.class,EterniaHarvestCrop.CODEC);
        plugin.getCodecRegistry(com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData.CODEC).register("EterniaAwaitHarvest",EterniaAwaitHarvestStage.class,EterniaAwaitHarvestStage.CODEC);
        worker.scheduleWithFixedDelay(()->{
            if(closed)return;
            try{if(services.readView(ActivityBootstrap.class,java.time.Duration.ofSeconds(10),()->services.activitySources().hasPending()))for(int batch=0;batch<4;batch++){if(services.batch(()->services.activitySources().drain(25))<25)break;}}catch(RuntimeException e){LOG.warning("Activity outbox remains pending: "+e.getClass().getSimpleName());}
            try{questToasts.deliver();}catch(RuntimeException e){LOG.warning("Quest notification remains pending: "+e.getClass().getSimpleName());}
        },1,1,TimeUnit.SECONDS);
    }
    static ActivityBootstrap active(){return active;}
    /** Local fixture registration is tied to the saved playground world UUID, never an arbitrary name. */
    public static void registerLocalTrials(EterniaModPlugin plugin,World world,UUID actor){
        com.hexvane.eterniamod.localplayground.LocalPlayground.require(plugin,actor);
        var value=active;if(value==null||value.closed||value.plugin!=plugin)throw new IllegalStateException("Activity adapter is unavailable");
        if(!value.managedTrial(world))throw new IllegalStateException("Activity trials require the managed adventure test world");
        value.localTrials.put(world.getWorldConfig().getUuid(),com.hexvane.eterniamod.localplayground.PlaygroundActivities.settings(world));
    }
    private boolean managedTrial(World world){return plugin.getRuntimeConfig().local()&&world.getName().equals(com.hexvane.eterniamod.localplayground.LocalPlayground.TRIALS)&&plugin.getInfrastructure().world(world.getName()).map(p->p.role().equals("adventure")).orElse(false)&&com.hexvane.eterniamod.localplayground.LocalPlayground.managedWorld(plugin,world.getName());}
    private Settings settings(World world){
        // The durable playground marker authorizes re-registration after a restart.
        if(managedTrial(world))return localTrials.computeIfAbsent(world.getWorldConfig().getUuid(),id->com.hexvane.eterniamod.localplayground.PlaygroundActivities.settings(world));
        return settings;
    }
    boolean enabled(World world){return !closed&&(settings(world).worlds.contains(world.getName())||plugin.getInfrastructure().world(world.getName()).map(w->w.role().equals("adventure")).orElse(false));}
    private boolean killsEnabled(World world){return enabled(world)||!closed&&plugin.getRuntimeConfig().local()&&com.hexvane.eterniamod.localplayground.LocalPlayground.managedWorld(plugin,world.getName());}
    private Long xp(World world,SeasonService.ActivityKind kind,String target){var configured=switch(kind){case KILL->settings(world).kills;case MINE->settings(world).mining;case HARVEST->settings(world).harvesting;default->Map.<String,Long>of();};return configured.containsKey(target)?configured.get(target):QuestTargets.defaultXp(kind,target);}
    void harvested(World world,UUID actor,Vector3i position,String target,String cycle){
        Long xp=xp(world,SeasonService.ActivityKind.HARVEST,target);if(enabled(world)&&xp!=null)services.activitySources().verifiedHarvestCycle(actor,block(world,position),target,cycle,xp);
    }
    static ActivitySourceService.Block block(World w,Vector3i p){return new ActivitySourceService.Block(w.getWorldConfig().getUuid(),p.x,p.y,p.z);}
    static BlockType blockType(World world,Vector3i p){
        var sectionRef=world.getChunkStore().getChunkSectionReferenceAtBlock(p.x,p.y,p.z);if(sectionRef==null||!sectionRef.isValid())return null;
        var section=world.getChunkStore().getStore().getComponent(sectionRef,BlockSection.getComponentType());if(section==null)return null;
        return BlockType.getAssetMap().getAsset(section.get(ChunkUtil.indexBlock(p.x,p.y,p.z)));
    }
    private static boolean adventure(Store<EntityStore> store,Ref<EntityStore> ref){var player=store.getComponent(ref,Player.getComponentType());return player!=null&&player.getGameMode()==GameMode.Adventure;}
    private final class Placement extends EntityEventSystem<EntityStore,PlaceBlockEvent>{
        Placement(){super(PlaceBlockEvent.class);}public Query<EntityStore> getQuery(){return Query.any();}
        public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,PlaceBlockEvent event){
            World world=store.getExternalData().getWorld();if(!enabled(world)||event.isCancelled())return;
            // A synchronous durable deny marker is required before native mutation; DB failure cancels placement.
            try{services.activitySources().markPlayerPlacement(block(world,event.getTargetBlock()));}catch(RuntimeException e){event.setCancelled(true);LOG.warning("Placement cancelled because source provenance could not persist");}
        }
    }
    private final class Mining extends EntityEventSystem<EntityStore,BreakBlockEvent>{
        Mining(){super(BreakBlockEvent.class);}public Query<EntityStore> getQuery(){return PlayerRef.getComponentType();}
        public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,BreakBlockEvent event){
            World world=store.getExternalData().getWorld();Long xp=xp(world,SeasonService.ActivityKind.MINE,event.getBlockType().getId());if(!enabled(world)||xp==null||event.isCancelled()||!adventure(store,chunk.getReferenceTo(index)))return;
            var player=chunk.getComponent(index,PlayerRef.getComponentType());if(player==null)return;UUID actor=player.getUuid();var position=new Vector3i(event.getTargetBlock());String target=event.getBlockType().getId();
            world.execute(()->{
                if(closed||event.isCancelled()||!position.equals(event.getTargetBlock()))return;
                BlockType after=blockType(world,position);if(after==null||!after.getId().equals(BlockType.EMPTY.getId()))return;
                try{services.activitySources().verifiedNaturalBreak(actor,block(world,position),target,xp);}catch(RuntimeException e){LOG.warning("Mining source could not persist; no XP granted");}
            });
        }
    }
    private final class Kills extends DeathSystems.OnDeathSystem{
        public Query<EntityStore> getQuery(){return NPCEntity.getComponentType();}
        public void onComponentAdded(Ref<EntityStore> ref,DeathComponent death,Store<EntityStore> store,CommandBuffer<EntityStore> buffer){
            World world=store.getExternalData().getWorld();if(!killsEnabled(world)||death.getDeathInfo()==null||!(death.getDeathInfo().getSource() instanceof Damage.EntitySource source)||!source.getRef().isValid())return;
            var killer=store.getComponent(source.getRef(),PlayerRef.getComponentType());var npc=store.getComponent(ref,NPCEntity.getComponentType());var identity=store.getComponent(ref,UUIDComponent.getComponentType());
            if(killer==null||npc==null||identity==null||!adventure(store,source.getRef()))return;Long xp=xp(world,SeasonService.ActivityKind.KILL,npc.getRoleName());if(xp==null)return;
            try{services.activitySources().verifiedDeath(killer.getUuid(),world.getWorldConfig().getUuid(),identity.getUuid(),npc.getRoleName(),xp);}catch(RuntimeException e){LOG.warning("Death source could not persist; no XP granted");}
        }
    }
    @Override public void close(){closed=true;if(active==this)active=null;worker.shutdown();try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();worker.shutdownNow();}}
}
