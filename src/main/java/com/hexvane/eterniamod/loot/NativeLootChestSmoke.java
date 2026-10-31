package com.hexvane.eterniamod.loot;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.LootChestService;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockEntity;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Opt-in acceptance on the real engine: its stash system never fills a marked chest, and leaves other chests alone. */
public final class NativeLootChestSmoke {
    private static final String CHEST="Furniture_Goblin_Chest_Small",DROPLIST="Portals_Goblin_Chest_Tier1";
    private NativeLootChestSmoke() {}
    public static void run(EterniaModPlugin plugin) {
        World world=com.hexvane.eterniamod.localplayground.LocalPlayground.createFlatWorld("eternia_loot_smoke").join();
        world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(0,0),GetChunkFlags.SET_TICKING).join();
        world.getChunkStore().getChunkSectionReferenceAsync(0,0,0,GetChunkFlags.SET_TICKING).join();
        var done=new CompletableFuture<Void>();
        world.execute(()->{try{placeAndCheck(world);done.complete(null);}catch(Throwable failure){done.completeExceptionally(failure);}});
        done.join();
        rollAndCheck(plugin,world);
    }
    private static ItemContainerBlock place(World world,int x,String droplist,boolean filled) {
        var chunkStore=world.getChunkStore();var store=chunkStore.getStore();var sectionRef=chunkStore.getChunkSectionReferenceAtBlock(x,2,1);
        var type=BlockType.getAssetMap().getAsset(CHEST);if(type==null||type.getBlockEntity()==null)throw new IllegalStateException("Smoke chest block is missing");
        BlockOperations.setBlock(chunkStore,sectionRef,x,2,1,BlockType.getAssetMap().getIndex(CHEST),type,0,FillerBlockUtil.NO_FILLER,0);
        var holder=type.getBlockEntity().clone();var prototype=holder.getComponent(ItemContainerBlock.getComponentType());
        prototype.setDroplist(droplist);if(filled)prototype.getItemContainer().addItemStackToSlot((short)0,new ItemStack("Ingredient_Stick",1));
        BlockEntity.setBlockEntity(store,sectionRef,store.getComponent(sectionRef,BlockComponentSection.getComponentType()),x,2,1,type,0,holder);
        var ref=BlockModule.getBlockEntity(store,sectionRef,x,2,1);if(ref==null)throw new IllegalStateException("Smoke chest was not added to the world");
        return store.getComponent(ref,ItemContainerBlock.getComponentType());
    }
    private static boolean marked(World world,int x) {
        var chunkStore=world.getChunkStore();var store=chunkStore.getStore();
        return store.getComponent(BlockModule.getBlockEntity(store,chunkStore.getChunkSectionReferenceAtBlock(x,2,1),x,2,1),LootChestBlock.getComponentType())!=null;
    }
    private static void placeAndCheck(World world) {
        var loot=place(world,1,DROPLIST,false);
        if(!marked(world,1)||loot.getDroplist()!=null||!loot.getItemContainer().isEmpty())throw new IllegalStateException("A loot chest was still rolled into its shared container");
        var plain=place(world,3,null,false);
        if(marked(world,3)||!plain.getItemContainer().isEmpty())throw new IllegalStateException("A chest with no loot table changed");
        var stocked=place(world,5,DROPLIST,true);
        if(marked(world,5)||stocked.getItemContainer().isEmpty())throw new IllegalStateException("A chest holding items must keep working as a shared chest");
    }
    private static void rollAndCheck(EterniaModPlugin plugin,World world) {
        var chest=new LootChestService.Chest(world.getWorldConfig().getUuid(),1,2,1,"smoke",DROPLIST);var chests=plugin.getServices().lootChests();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();short capacity=18;
        var random=new Random(1);var stacks=new ArrayList<ItemStack>();
        for(int i=0;i<50&&stacks.isEmpty();i++)stacks.addAll(ItemModule.get().getRandomItemDrops(DROPLIST));
        if(stacks.isEmpty())throw new IllegalStateException("Smoke droplist rolled nothing");
        String rolled=LootChestContents.encode(LootChestContents.fill(stacks,capacity,random));
        chests.open(a,chest,()->rolled);chests.open(b,chest,()->rolled);
        var copyA=LootChestContents.decode(chests.open(a,chest,()->{throw new IllegalStateException("Second roll");}).contents());
        if(copyA.isEmpty()||copyA.getCapacity()!=capacity)throw new IllegalStateException("A stored chest did not come back with its items");
        copyA.dropAllItemStacks();chests.save(a,chest,LootChestContents.encode(copyA));
        if(!LootChestContents.decode(chests.open(a,chest,()->{throw new IllegalStateException("Second roll");}).contents()).isEmpty())throw new IllegalStateException("An emptied chest refilled");
        if(LootChestContents.decode(chests.open(b,chest,()->{throw new IllegalStateException("Second roll");}).contents()).isEmpty())throw new IllegalStateException("One player emptied another player's chest");
    }
}
