package com.hexvane.eterniamod.loot;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.LootChestService;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenContainerInteraction;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * World loot chests give each player their own contents. The droplist is taken before the engine rolls it into the
 * shared container, and opening shows the player's stored copy in the engine's own chest window instead.
 * Every load and save runs on one worker in order, so reopening a chest always sees the last save.
 */
public final class LootChests implements AutoCloseable {
    private static final String OPEN_WINDOW="OpenWindow",CLOSE_WINDOW="CloseWindow";
    private final EterniaModPlugin plugin;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"eternia-loot-chests");t.setDaemon(true);return t;});
    private final Set<UUID> opening=ConcurrentHashMap.newKeySet();
    private LootChests(EterniaModPlugin plugin){this.plugin=plugin;}
    public static LootChests register(EterniaModPlugin plugin) {
        var chests=new LootChests(plugin);
        LootChestBlock.register(plugin.getChunkStoreRegistry());
        plugin.getChunkStoreRegistry().registerSystem(chests.new Mark());
        var registry=plugin.getEntityStoreRegistry();
        registry.registerSystem(chests.new Use());registry.registerSystem(chests.new Break());
        registry.registerSystem(chests.new Damage());registry.registerSystem(chests.new EnvironmentDamage());
        return chests;
    }
    private boolean housing(World world){return plugin.getInfrastructure().isHousing(world.getName());}
    static boolean opensAsContainer(BlockType block) {
        var interactions=block.getInteractions();String id=interactions==null?null:interactions.get(InteractionType.Use);
        var root=id==null?null:RootInteraction.getAssetMap().getAsset(id);
        if(root==null||root.getInteractionIds()==null)return false;
        for(String each:root.getInteractionIds())if(Interaction.getAssetMap().getAsset(each) instanceof OpenContainerInteraction)return true;
        return false;
    }
    /** Holder systems all run before any ref system, so this always sees the droplist before the engine's stash system does. */
    private final class Mark extends HolderSystem<ChunkStore> {
        private final Query<ChunkStore> query=Query.and(ItemContainerBlock.getComponentType(),BlockModule.BlockStateInfo.getComponentType());
        @Override public Query<ChunkStore> getQuery(){return query;}
        @Override public void onEntityAdd(Holder<ChunkStore> holder,AddReason reason,Store<ChunkStore> store) {
            var container=holder.getComponent(ItemContainerBlock.getComponentType());var info=holder.getComponent(BlockModule.BlockStateInfo.getComponentType());
            String droplist=container.getDroplist();if(droplist==null)return;
            var world=store.getExternalData().getWorld();
            if(!LootChestRules.becomesPersonal(droplist,world.getWorldConfig().isBlockSpawnersResolved(),housing(world),container.getItemContainer().isEmpty(),opensAsContainer(info,store)))return;
            container.setDroplist(null);container.setStayOpenWhenEmpty(false);
            holder.putComponent(LootChestBlock.getComponentType(),new LootChestBlock(droplist,UUID.randomUUID().toString()));
        }
        @Override public void onEntityRemoved(Holder<ChunkStore> holder,RemoveReason reason,Store<ChunkStore> store){}
        private boolean opensAsContainer(BlockModule.BlockStateInfo info,Store<ChunkStore> store) {
            var sectionRef=info.getSectionRef();if(sectionRef==null||!sectionRef.isValid())return false;
            var section=store.getComponent(sectionRef,BlockSection.getComponentType());if(section==null)return false;
            int index=info.getIndex();
            var block=BlockType.getAssetMap().getAsset(section.get(ChunkUtil.xFromIndex(index),ChunkUtil.yFromIndex(index),ChunkUtil.zFromIndex(index)));
            return block!=null&&LootChests.opensAsContainer(block);
        }
    }
    private static Vector3i base(World world,Vector3i position) {
        var resolved=BlockOperations.resolveBaseBlockPosition(world.getChunkStore(),new BlockPosition(position.x,position.y,position.z));
        return new Vector3i(resolved.x,resolved.y,resolved.z);
    }
    private static Ref<ChunkStore> blockEntity(World world,Vector3i position) {
        var sectionRef=world.getChunkStore().getChunkSectionReferenceAtBlock(position.x,position.y,position.z);
        if(sectionRef==null||!sectionRef.isValid())return null;
        return BlockModule.getBlockEntity(world.getChunkStore().getStore(),sectionRef,position.x,position.y,position.z);
    }
    private static LootChestBlock marker(World world,Vector3i position) {
        var ref=blockEntity(world,position);
        return ref==null?null:world.getChunkStore().getStore().getComponent(ref,LootChestBlock.getComponentType());
    }
    private final class Use extends EntityEventSystem<EntityStore,UseBlockEvent.Pre> {
        Use(){super(UseBlockEvent.Pre.class);}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,UseBlockEvent.Pre event) {
            if(event.isCancelled()||event.getInteractionType()!=InteractionType.Use)return;
            var world=store.getExternalData().getWorld();var position=base(world,event.getTargetBlock());
            var marker=marker(world,position);
            if(!LootChestRules.opensPersonally(marker!=null,housing(world)))return;
            event.setCancelled(true);
            var ref=chunk.getReferenceTo(index);var player=chunk.getComponent(index,PlayerRef.getComponentType());var uuid=chunk.getComponent(index,UUIDComponent.getComponentType());
            if(player==null||uuid==null)return;
            var chest=new LootChestService.Chest(world.getWorldConfig().getUuid(),position.x,position.y,position.z,marker.getInstance(),marker.getDroplist());
            short capacity=world.getChunkStore().getStore().getComponent(blockEntity(world,position),ItemContainerBlock.getComponentType()).getCapacity();
            load(world,ref,player,uuid.getUuid(),position,chest,capacity);
        }
    }
    private void load(World world,Ref<EntityStore> ref,PlayerRef player,UUID uuid,Vector3i position,LootChestService.Chest chest,short capacity) {
        if(!opening.add(uuid))return;
        try {
            worker.execute(()->{
                try {
                    var opened=plugin.getServices().lootChests().open(uuid,chest,()->LootChestContents.encode(LootChestContents.fill(ItemModule.get().getRandomItemDrops(chest.droplist()),capacity,new Random())));
                    world.execute(()->{
                        try {show(world,ref,uuid,position,chest,opened.contents());}
                        catch(RuntimeException failure){failed(player,failure);}
                        finally {opening.remove(uuid);}
                    });
                }catch(RuntimeException failure){opening.remove(uuid);failed(player,failure);}
            });
        }catch(RejectedExecutionException stopping){opening.remove(uuid);}
    }
    private void failed(PlayerRef player,RuntimeException failure) {
        plugin.getLogger().atSevere().withCause(failure).log("A loot chest could not be opened for %s",player.getUuid());
        player.sendMessage(Message.raw("This chest could not be opened. Please try again in a moment."));
    }
    private void show(World world,Ref<EntityStore> ref,UUID uuid,Vector3i position,LootChestService.Chest chest,String contents) {
        if(!ref.isValid())return;
        var marker=marker(world,position);
        if(marker==null||!marker.getInstance().equals(chest.instance()))return;
        var chunkStore=world.getChunkStore();var chunks=chunkStore.getStore();
        var sectionRef=chunkStore.getChunkSectionReferenceAtBlock(position.x,position.y,position.z);
        var section=chunks.getComponent(sectionRef,BlockSection.getComponentType());
        var block=BlockType.getAssetMap().getAsset(section.get(position.x,position.y,position.z));
        var store=ref.getStore();var player=store.getComponent(ref,Player.getComponentType());
        var shared=chunks.getComponent(blockEntity(world,position),ItemContainerBlock.getComponentType());
        if(block==null||player==null||shared==null)return;
        SimpleItemContainer personal=LootChestContents.decode(contents);
        int rotation=section.getRotationIndex(position.x,position.y,position.z);
        var window=new ContainerBlockWindow(position.x,position.y,position.z,rotation,block,personal);
        var windows=shared.getWindows();
        if(windows.putIfAbsent(uuid,window)!=null)return;
        if(!player.getPageManager().setPageWithWindows(ref,store,Page.Bench,true,window)){windows.remove(uuid,window);return;}
        personal.registerChangeEvent(change->save(uuid,chest,LootChestContents.encode(personal)));
        window.registerCloseEvent(close->closed(world,ref,uuid,position,block,shared,window));
        if(windows.size()==1)BlockOperations.setBlockInteractionState(chunkStore,sectionRef,position.x,position.y,position.z,block,OPEN_WINDOW,false);
        sound(ref,store,block.getBlockForState(OPEN_WINDOW),block,rotation,position);
    }
    private void save(UUID uuid,LootChestService.Chest chest,String contents) {
        Runnable write=()->{
            try {plugin.getServices().lootChests().save(uuid,chest,contents);}
            catch(RuntimeException failure){plugin.getLogger().atSevere().withCause(failure).log("A loot chest could not be saved for %s",uuid);}
        };
        try {worker.execute(write);}catch(RejectedExecutionException stopping){write.run();}
    }
    private void closed(World world,Ref<EntityStore> ref,UUID uuid,Vector3i position,BlockType opened,ItemContainerBlock shared,ContainerBlockWindow window) {
        var windows=shared.getWindows();windows.remove(uuid,window);
        // A close can arrive during a store update, such as the block being removed. The block is only touched afterwards.
        world.execute(()->{
            var chunkStore=world.getChunkStore();
            var sectionRef=chunkStore.getChunkSectionReferenceAtBlock(position.x,position.y,position.z);if(sectionRef==null||!sectionRef.isValid())return;
            var section=chunkStore.getStore().getComponent(sectionRef,BlockSection.getComponentType());if(section==null)return;
            var current=BlockType.getAssetMap().getAsset(section.get(position.x,position.y,position.z));if(current==null)return;
            if(windows.isEmpty()&&Objects.equals(Objects.requireNonNullElse(opened.getDefaultStateKey(),opened.getId()),Objects.requireNonNullElse(current.getDefaultStateKey(),current.getId())))
                BlockOperations.setBlockInteractionState(chunkStore,sectionRef,position.x,position.y,position.z,current,CLOSE_WINDOW,false);
            if(ref.isValid())sound(ref,ref.getStore(),current.getBlockForState(CLOSE_WINDOW),opened,section.getRotationIndex(position.x,position.y,position.z),position);
        });
    }
    private static void sound(Ref<EntityStore> ref,Store<EntityStore> store,BlockType state,BlockType block,int rotation,Vector3i position) {
        if(state==null||state.getInteractionSoundEventIndex()==SoundEvent.EMPTY_ID)return;
        var at=new Vector3d();block.getBlockCenter(rotation,at);at.add(position.x,position.y,position.z);
        SoundUtil.playSoundEvent3d(ref,state.getInteractionSoundEventIndex(),at,store);
    }
    private boolean protectedChest(World world,Vector3i position,boolean creative) {
        var base=base(world,position);
        return LootChestRules.breakDenied(marker(world,base)!=null,housing(world),creative);
    }
    private static boolean creative(ArchetypeChunk<EntityStore> chunk,int index){var player=chunk.getComponent(index,Player.getComponentType());return player!=null&&player.getGameMode()==GameMode.Creative;}
    private final class Break extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        Break(){super(BreakBlockEvent.class);}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,BreakBlockEvent event){if(protectedChest(store.getExternalData().getWorld(),event.getTargetBlock(),creative(chunk,index)))event.setCancelled(true);}
    }
    private final class Damage extends EntityEventSystem<EntityStore,DamageBlockEvent> {
        Damage(){super(DamageBlockEvent.class);}
        @Override public Query<EntityStore> getQuery(){return Query.any();}
        @Override public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,DamageBlockEvent event){if(protectedChest(store.getExternalData().getWorld(),event.getTargetBlock(),creative(chunk,index)))event.setCancelled(true);}
    }
    private final class EnvironmentDamage extends WorldEventSystem<EntityStore,DamageBlockEvent> {
        EnvironmentDamage(){super(DamageBlockEvent.class);}
        @Override public void handle(Store<EntityStore> store,CommandBuffer<EntityStore> buffer,DamageBlockEvent event){if(protectedChest(store.getExternalData().getWorld(),event.getTargetBlock(),false))event.setCancelled(true);}
    }
    /** Finishes queued saves. Called before the store closes. */
    @Override public void close() {
        worker.shutdown();
        try {if(!worker.awaitTermination(10,TimeUnit.SECONDS))plugin.getLogger().atSevere().log("Loot chest saves did not finish before shutdown");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
