package com.hexvane.eterniamod.localplayground;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.activities.ActivityBootstrap;
import com.hexvane.eterniamod.discovery.DiscoveryBootstrap;
import com.hexvane.eterniamod.domain.DiscoveryService;
import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.*;
import org.joml.Vector3d;

/** Real native activities in the locally owned fixture; no direct quest or XP awards. */
public final class PlaygroundActivities {
    public static final String CROP="Eternia_Trial_Wheat",MOB="Skeleton_Fighter";
    public static final DiscoveryService.Definition DISCOVERY=new DiscoveryService.Definition("local-playground-first-light",LocalPlayground.TRIALS,52,1,20,"Eternia_Discovery_Cache",new DiscoveryService.Reward("eternia:prop/aqua_lamp",OwnershipService.Kind.QUANTITY,1));
    public static final List<String> MINING=List.of("Ore_Copper_Stone","Ore_Copper_Sandstone","Ore_Copper_Shale","Ore_Iron_Stone","Ore_Gold_Stone","Ore_Silver_Stone","Ore_Cobalt_Slate","Ore_Thorium_Mud","Rock_Stone","Rock_Basalt","Rock_Marble","Rock_Slate");
    private PlaygroundActivities(){}
    public static ActivityBootstrap.Settings settings(World world){
        var mining=new LinkedHashMap<String,Long>();MINING.forEach(id->mining.put(id,150L));
        return new ActivityBootstrap.Settings(Set.of(world.getName()),Map.of(MOB,250L),mining,Map.of(grown().getId(),100L));
    }
    private static void require(EterniaModPlugin plugin,World world,UUID actor){
        LocalPlayground.require(plugin,actor);world.getEntityStore().getStore().assertThread();
        if(!LocalPlayground.TRIALS.equals(world.getName())||!LocalPlayground.managedWorld(plugin,world.getName())||!plugin.getInfrastructure().world(world.getName()).map(p->p.role().equals("adventure")).orElse(false))throw new IllegalStateException("Use the managed adventure trials world");
    }
    public static void register(EterniaModPlugin plugin,World world,UUID actor){require(plugin,world,actor);ActivityBootstrap.registerLocalTrials(plugin,world,actor);}
    public static void prepare(EterniaModPlugin plugin,World world,UUID actor){
        require(plugin,world,actor);register(plugin,world,actor);
        for(int x=46;x<49;x++)for(int z=20;z<23;z++)validateSoil(world,x,z);
        // Initial additive setup only. A second visit never replenishes mined cells,
        // resets crop generations, or overwrites a builder's later changes.
        for(int i=0;i<MINING.size();i++){
            int x=20+(i%6)*4,z=20+(i/6)*6;var type=asset(MINING.get(i));
            for(int dz=0;dz<2;dz++)for(int y=1;y<=2;y++)placeEmpty(world,x,y,z+dz,type);
        }
        for(int x=46;x<49;x++)for(int z=20;z<23;z++){
            if(ChunkSectionBlockUtil.blockId(world,x,1,z)!=BlockType.EMPTY_ID)continue;
            if(ChunkSectionBlockUtil.blockId(world,x,0,z)!=BlockType.getAssetMap().getIndex("Soil_Dirt")&&!ChunkSectionBlockUtil.setBlock(world,x,0,z,asset("Soil_Dirt"),0))throw new IllegalStateException("Could not prepare crop soil");
            placeEmpty(world,x,1,z,grown());
            var section=world.getChunkStore().getChunkSectionReferenceAtBlock(x,1,z);var chunks=world.getChunkStore().getStore();var ref=BlockModule.getBlockEntity(chunks,section,x,1,z);
            var components=ChunkSectionBlockUtil.blockComponentSectionAt(world,x,1,z);int index=com.hypixel.hytale.math.util.ChunkUtil.indexBlock(x,1,z);
            // Setup can run before any player reaches the section. Native block
            // entities then live as parked holders, not EntityStore references.
            var holder=components==null?null:components.getBlockHolder(index);
            var farming=ref!=null&&ref.isValid()?chunks.getComponent(ref,FarmingBlock.getComponentType()):holder==null?null:holder.getComponent(FarmingBlock.getComponentType());
            if(farming==null)throw new IllegalStateException("Trial crop did not create its native farming state");
            farming.setCurrentStageSet("Default");farming.setGrowthProgress(1);farming.setLastTickGameTime(world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType()).getGameTime());components.markBlockNeedsSaving(index);
        }
        prepareDiscovery(plugin,world,actor);
        world.getWorldConfig().setSpawningNPC(false);world.getWorldConfig().markChanged();
        try{NativePlacementTransactions.flush(world,new NativeSnapshotStore.Bounds(20,0,20,54,4,29));}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    static boolean supportedTrialSoil(String id){return "Soil_Grass".equals(id)||"Soil_Dirt".equals(id);}
    private static void validateSoil(World world,int x,int z){
        var type=ChunkSectionBlockUtil.blockType(world,x,0,z);var section=ChunkSectionBlockUtil.blockSectionAt(world,x,0,z);
        var sectionRef=world.getChunkStore().getChunkSectionReferenceAtBlock(x,0,z);
        var fluids=sectionRef==null?null:world.getChunkStore().getStore().getComponent(sectionRef,com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType());
        // The native engine can retain an empty block holder/location cache on plain
        // terrain. Inspect a COPY of both parked and live state; the cache is not a build.
        var holder=ChunkSectionBlockUtil.blockEntityHolderAt(world,x,0,z);
        if(holder!=null)holder.tryRemoveComponent(BlockModule.BlockStateInfo.getComponentType());
        String problem=section==null?"block section is not loaded; retry setup":type==null?"ground is not loaded":!supportedTrialSoil(type.getId())?"found "+type.getId():section==null?"section is not loaded":section.getFiller(x,0,z)!=0?"multiblock ground":holder!=null&&!holder.getArchetype().isEmpty()?"ground contains block data "+holder.getArchetype():fluids!=null&&fluids.getFluidId(x,0,z)!=0?"ground contains fluid":null;
        // Rotation on a plain grass/dirt cube does not change its suitability as soil.
        if(problem!=null)throw new IllegalStateException("Crop setup needs plain grass or dirt at ("+x+", 0, "+z+"): "+problem+". Your existing builds were kept");
    }
    private static void prepareDiscovery(EterniaModPlugin plugin,World world,UUID actor){
        require(plugin,world,actor);var d=DISCOVERY;var floor=ChunkSectionBlockUtil.blockType(world,d.x(),d.y()-1,d.z());
        if(floor==null||floor.getMaterial()!=com.hypixel.hytale.protocol.BlockMaterial.Solid||ChunkSectionBlockUtil.blockId(world,d.x(),d.y()+1,d.z())!=BlockType.EMPTY_ID)throw new IllegalStateException("Clear the discovery shelf and its headroom near (52, 1, 20) before retrying setup");
        int existing=ChunkSectionBlockUtil.blockId(world,d.x(),d.y(),d.z());if(existing!=BlockType.EMPTY_ID&&existing!=BlockType.getAssetMap().getIndex(d.markerBlockId()))throw new IllegalStateException("The discovery shelf location at (52, 1, 20) is occupied");
        try{DiscoveryBootstrap.registerLocalExample(plugin,world,actor,d);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        placeEmpty(world,d.x(),d.y(),d.z(),asset(d.markerBlockId()));
    }
    private static BlockType asset(String id){return Objects.requireNonNull(BlockType.getAssetMap().getAsset(id),"Missing trial asset "+id);}
    private static BlockType grown(){
        var type=Objects.requireNonNull(asset(CROP).getBlockForState("StageFinal"),"Trial wheat has no harvest stage");
        if(!type.getId().contains(CROP)||type.getFarming()==null)throw new IllegalStateException("Trial wheat resolved to an inherited vanilla state: "+type.getId());
        for(String name:List.of("Default","Harvested")){var stages=type.getFarming().getStages().get(name);if(stages==null||stages.length!=3||!(stages[1] instanceof com.hypixel.hytale.builtin.adventure.farming.config.stages.BlockStateFarmingStageData)||stages[1].getDuration()==null||!(stages[2] instanceof com.hexvane.eterniamod.activities.EterniaAwaitHarvestStage))throw new IllegalStateException("Trial wheat did not retain its own mature-stage guard: "+type.getId()+" / "+name);}
        return type;
    }
    private static void placeEmpty(World world,int x,int y,int z,BlockType type){
        int current=ChunkSectionBlockUtil.blockId(world,x,y,z);if(current==BlockType.getAssetMap().getIndex(type.getId()))return;
        if(current!=BlockType.EMPTY_ID)throw new IllegalStateException("A trial sample location is occupied at "+x+", "+y+", "+z);
        if(!ChunkSectionBlockUtil.setBlock(world,x,y,z,type,0))throw new IllegalStateException("Could not place trial sample "+type.getId());
    }
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        LocalPlayground.require(plugin,player.getUuid());
        var choices=new ArrayList<ChoicePage.Choice>();
        choices.add(new ChoicePage.Choice("Twelve mineral types at (20–40, 20–26); each natural position rewards once","Mining samples",(r,s)->LocalPlayground.travel(plugin,player,LocalPlayground.TRIALS,24,1,17)));
        choices.add(new ChoicePage.Choice("Harvest nine wheat plants at (46–48, 20–22); their native regrowth takes about 20 game seconds","Harvest crops",(r,s)->LocalPlayground.travel(plugin,player,LocalPlayground.TRIALS,46,1,18)));
        choices.add(new ChoicePage.Choice("Find the discovery bookshelf at (52, 1, 20), then use it in Adventure mode for one Aqua Lamp token","Housing discovery",(r,s)->LocalPlayground.travel(plugin,player,LocalPlayground.TRIALS,52,1,18)));
        choices.add(new ChoicePage.Choice("Spawn one Skeleton Fighter at (60, 1, 30); defeat it before spawning another","Training battle",(r,s)->{
            try{spawn(plugin,s.getExternalData().getWorld(),player.getUuid());player.sendMessage(Message.raw("A Skeleton Fighter awaits near (60, 1, 30). Use Adventure mode for real kill XP."));}catch(RuntimeException e){player.sendMessage(Message.raw(e.getMessage()));}
        }));
        ChoicePage.open(ref,store,player,"Activity trials","Use Adventure mode and a pickaxe or sword. Only successful native mining, crop harvests and player kills award XP. Creative breaks, repeated placed blocks and environmental deaths do not count. Collect your tools from the playground supplies. Battle controls require you to be in the trials world.",choices);
    }
    public static void spawn(EterniaModPlugin plugin,World world,UUID actor){
        require(plugin,world,actor);register(plugin,world,actor);var store=world.getEntityStore().getStore();boolean[] alive={false};
        store.forEachChunk(NPCEntity.getComponentType(),(chunk,commands)->{for(int i=0;i<chunk.size();i++)if(MOB.equals(chunk.getComponent(i,NPCEntity.getComponentType()).getRoleName())&&chunk.getComponent(i,DeathComponent.getComponentType())==null)alive[0]=true;});
        if(alive[0])throw new IllegalStateException("Defeat the existing training skeleton first");
        for(int x=59;x<=61;x++)for(int z=29;z<=31;z++)for(int y=1;y<=3;y++)if(ChunkSectionBlockUtil.blockId(world,x,y,z)!=BlockType.EMPTY_ID)throw new IllegalStateException("Clear the training area near (60, 1, 30) first");
        int role=NPCPlugin.get().getIndex(MOB);if(role<0)throw new IllegalStateException("Native training role is unavailable");
        var spawned=NPCPlugin.get().spawnEntity(store,role,new Vector3d(60.5,1,30.5),new Rotation3f(0,0,0),null,(npc,holder,accessor)->{},null);
        if(spawned==null)throw new IllegalStateException("The training skeleton could not spawn");
    }
    /** Native crop lifecycle check. Uses real harvest/growth, without manufacturing player XP evidence. */
    public static java.util.concurrent.CompletableFuture<Void> nativeSmoke(EterniaModPlugin plugin,World world,UUID actor){
        var done=new java.util.concurrent.CompletableFuture<Void>();
        world.execute(()->{try{
            require(plugin,world,actor);if(!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))||plugin.getRuntimeConfig().postgres())throw new IllegalStateException("Crop acceptance requires isolated native smoke");
            var chunks=world.getChunkStore().getStore();var entities=world.getEntityStore().getStore();var pos=new org.joml.Vector3i(46,1,20);var section=world.getChunkStore().getChunkSectionReferenceAtBlock(pos.x,pos.y,pos.z);
            // This disposable headless fixture has no nearby player. Activate the
            // section through the same native holder-to-reference transition.
            chunks.tryRemoveComponent(section,com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.REGISTRY.getNonTickingComponentType());
            var before=cropState(world,pos);int first=before.getGeneration();
            if(before.getGrowthProgress()<1||!grown().getId().equals(ChunkSectionBlockUtil.blockType(world,pos).getId()))throw new IllegalStateException("Trial wheat did not retain a mature native state");
            if(!com.hypixel.hytale.builtin.adventure.farming.FarmingUtil.harvest(chunks,entities,null,pos)||cropState(world,pos).getGeneration()!=first+1)throw new IllegalStateException("Native crop harvest did not advance its generation");
            world.execute(()->{try{
                var crop=cropState(world,pos);if(crop.getGrowthProgress()!=0||grown().getId().equals(ChunkSectionBlockUtil.blockType(world,pos).getId()))throw new IllegalStateException("Native harvest did not reset the crop");
                crop.setLastTickGameTime(entities.getResource(WorldTimeResource.getResourceType()).getGameTime().minusSeconds(21));
                tickCrop(world,pos);
                world.execute(()->{try{
                    var mature=cropState(world,pos);int regrown=mature.getGeneration();if(regrown<=first+1||mature.getGrowthProgress()<1||!grown().getId().equals(ChunkSectionBlockUtil.blockType(world,pos).getId()))throw new IllegalStateException("Native regrowth lost the crop generation");
                    mature.setLastTickGameTime(entities.getResource(WorldTimeResource.getResourceType()).getGameTime().minusSeconds(86401));tickCrop(world,pos);
                    if(cropState(world,pos).getGeneration()!=regrown||cropState(world,pos).getGrowthProgress()!=1)throw new IllegalStateException("Mature crop was discarded or advanced past its harvest guard");
                    // The native chunk loader must read the same generation from disk.
                    NativePlacementTransactions.flush(world,new NativeSnapshotStore.Bounds(pos.x,0,pos.z,pos.x+1,3,pos.z+1));
                    var column=world.getChunkStore().getLoader().loadHolder(com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(pos.x),com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(pos.z)).join();
                    var holder=column.getComponent(com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn.getComponentType()).getSectionHolders()[0].getComponent(com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection.getComponentType()).getBlockHolder(com.hypixel.hytale.math.util.ChunkUtil.indexBlock(pos.x,pos.y,pos.z));
                    var saved=holder==null?null:holder.getComponent(FarmingBlock.getComponentType());if(saved==null||saved.getGeneration()!=regrown||saved.getGrowthProgress()<1)throw new IllegalStateException("Mature crop generation did not survive native disk reload");
                    if(!com.hypixel.hytale.builtin.adventure.farming.FarmingUtil.harvest(chunks,entities,null,pos)||cropState(world,pos).getGeneration()!=regrown+1)throw new IllegalStateException("Second native harvest reused or lost its generation");
                    plugin.getLogger().atInfo().log("ETERNIA_NATIVE_CROP_PASS: retained mature state, real harvest reset, native timed regrowth, disk-reloaded generation and second harvest; no synthetic XP awarded");done.complete(null);
                }catch(Throwable failure){done.completeExceptionally(failure);}});
            }catch(Throwable failure){done.completeExceptionally(failure);}});
        }catch(Throwable failure){done.completeExceptionally(failure);}});return done;
    }
    private static void tickCrop(World world,org.joml.Vector3i pos){
        var chunks=world.getChunkStore().getStore();var section=world.getChunkStore().getChunkSectionReferenceAtBlock(pos.x,pos.y,pos.z);var cropRef=BlockModule.getBlockEntity(chunks,section,pos.x,pos.y,pos.z);var crop=cropState(world,pos);
        chunks.forEachChunk(FarmingBlock.getComponentType(),(chunk,commands)->{for(int i=0;i<chunk.size();i++)if(chunk.getReferenceTo(i).equals(cropRef))com.hypixel.hytale.builtin.adventure.farming.FarmingUtil.tickFarming(commands,chunks.getComponent(cropRef,BlockModule.BlockStateInfo.getComponentType()),ChunkSectionBlockUtil.blockSectionAt(world,pos.x,pos.y,pos.z),section,cropRef,crop,com.hypixel.hytale.math.util.ChunkUtil.localCoordinate(pos.x),com.hypixel.hytale.math.util.ChunkUtil.localCoordinate(pos.y),com.hypixel.hytale.math.util.ChunkUtil.localCoordinate(pos.z),false);});
    }
    private static FarmingBlock cropState(World world,org.joml.Vector3i position){var ref=BlockModule.getBlockEntity(world,position.x,position.y,position.z);if(ref==null||!ref.isValid())throw new IllegalStateException("Native farming state is missing");return Objects.requireNonNull(world.getChunkStore().getStore().getComponent(ref,FarmingBlock.getComponentType()),"Native crop state is missing");}
}
