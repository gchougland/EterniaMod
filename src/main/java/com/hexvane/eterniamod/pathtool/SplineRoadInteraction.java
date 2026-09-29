package com.hexvane.eterniamod.pathtool;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class SplineRoadInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<SplineRoadInteraction> CODEC=BuilderCodec.builder(SplineRoadInteraction.class,SplineRoadInteraction::new,SimpleInstantInteraction.CODEC).append(new KeyedCodec<>("Action",Codec.STRING),(i,v)->i.action=v,i->i.action).add().build();
    private String action="Add";
    @Override protected void firstRun(InteractionType type,InteractionContext context,CooldownHandler cooldown){var commands=context.getCommandBuffer();if(commands==null)return;var ref=context.getEntity();var store=commands.getStore();var player=commands.getComponent(ref,PlayerRef.getComponentType());if(player==null)return;String requested=action;store.getExternalData().getWorld().execute(()->{if(ref.isValid())SplineRoadTool.action(requested,ref,store,player);});}
}
