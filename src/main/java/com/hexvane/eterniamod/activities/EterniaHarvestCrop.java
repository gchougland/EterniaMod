package com.hexvane.eterniamod.activities;

import com.hypixel.hytale.builtin.adventure.farming.FarmingUtil;
import com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/** Credits native crop and sickle harvests only after a successful, observable crop transition. */
public final class EterniaHarvestCrop extends HarvestCropInteraction {
    public static final BuilderCodec<EterniaHarvestCrop> CODEC=BuilderCodec.builder(EterniaHarvestCrop.class,EterniaHarvestCrop::new,HarvestCropInteraction.CODEC).build();
    public EterniaHarvestCrop(){}
    @Override protected void interactWithBlock(World world,CommandBuffer<EntityStore> buffer,InteractionType type,InteractionContext context,ItemStack item,Vector3i position,CooldownHandler cooldown){
        if(requireNotBroken&&item!=null&&item.isBroken()){context.getState().state=InteractionState.Failed;return;}
        var block=ActivityBootstrap.blockType(world,position);var before=farming(world,position);int generation=before==null?-1:before.getGeneration();
        String beforeId=block==null?"":block.getId();
        if(!FarmingUtil.harvest(world.getChunkStore().getStore(),buffer,context.getEntity(),position)){context.getState().state=InteractionState.Failed;return;}
        var bootstrap=ActivityBootstrap.active();var player=buffer.getComponent(context.getEntity(),PlayerRef.getComponentType());var entity=buffer.getComponent(context.getEntity(),Player.getComponentType());var after=farming(world,position);
        var afterBlock=ActivityBootstrap.blockType(world,position);
        boolean changed=afterBlock!=null&&!afterBlock.getId().equals(beforeId);
        boolean advanced=after!=null&&after.getGeneration()==Math.max(0,generation)+1&&after.getLastTickGameTime()!=null;
        String cycle=advanced?after.getGeneration()+":"+after.getLastTickGameTime():null;
        if(bootstrap!=null&&block!=null&&player!=null&&entity!=null&&entity.getGameMode()==GameMode.Adventure&&changed&&advanced)
            bootstrap.harvested(world,player.getUuid(),new Vector3i(position),block.getId(),cycle);
    }
    private static FarmingBlock farming(World world,Vector3i position){
        var chunks=world.getChunkStore();var section=chunks.getChunkSectionReferenceAtBlock(position.x,position.y,position.z);if(section==null||!section.isValid())return null;
        var ref=BlockModule.getBlockEntity(chunks.getStore(),section,position.x,position.y,position.z);return ref==null||!ref.isValid()?null:chunks.getStore().getComponent(ref,FarmingBlock.getComponentType());
    }
}
