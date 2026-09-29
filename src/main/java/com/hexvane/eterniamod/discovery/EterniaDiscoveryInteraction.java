package com.hexvane.eterniamod.discovery;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/** The interaction codec has no reward, discovery-id or account fields to copy or forge. */
public final class EterniaDiscoveryInteraction extends SimpleBlockInteraction {
    public static final BuilderCodec<EterniaDiscoveryInteraction> CODEC=BuilderCodec.builder(EterniaDiscoveryInteraction.class,EterniaDiscoveryInteraction::new,SimpleBlockInteraction.CODEC).build();
    public EterniaDiscoveryInteraction(){super("EterniaDiscovery");}
    @Override protected void interactWithBlock(World world,CommandBuffer<EntityStore> buffer,InteractionType type,InteractionContext context,ItemStack held,Vector3i position,CooldownHandler cooldown){
        if(type!=InteractionType.Use){context.getState().state=InteractionState.Failed;return;}
        try{var bootstrap=DiscoveryBootstrap.active();if(bootstrap==null)throw new IllegalStateException("Discoveries are not available");bootstrap.use(world,buffer,context.getEntity(),new Vector3i(position));}
        catch(RuntimeException failure){context.getState().state=InteractionState.Failed;var player=buffer.getComponent(context.getEntity(),PlayerRef.getComponentType());if(player!=null)player.sendMessage(Message.raw(failure instanceof com.hexvane.eterniamod.domain.DomainException||failure instanceof IllegalArgumentException?failure.getMessage():"This discovery could not finish. Try again; collected rewards are saved on your account."));}
    }
    @Override protected void simulateInteractWithBlock(InteractionType type,InteractionContext context,ItemStack held,World world,Vector3i position){}
}
