package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.PlotClaimPage;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class EterniaHousingTool extends SimpleInstantInteraction {
    public static final BuilderCodec<EterniaHousingTool> CODEC=BuilderCodec.builder(EterniaHousingTool.class,EterniaHousingTool::new,SimpleInstantInteraction.CODEC)
        .append(new KeyedCodec<>("Route",Codec.STRING),(tool,value)->tool.route=value,tool->tool.route).add().build();
    private String route="CLAIM";
    @Override protected void firstRun(InteractionType type,InteractionContext context,CooldownHandler cooldown){
        var commands=context.getCommandBuffer();if(commands==null)return;var ref=context.getEntity();var player=commands.getComponent(ref,PlayerRef.getComponentType());if(player==null)return;
        var store=commands.getStore();String destination=route;store.getExternalData().getWorld().execute(()->{if(!ref.isValid())return;var plugin=EterniaModPlugin.get();if(destination.equals("CLAIM"))PlotClaimPage.open(ref,store,player,plugin);else if(destination.equals("LEDGER"))CustomizationTools.ledger(plugin,ref,store,player);else if(destination.equals("WORLDS"))plugin.getMenuActions().perform(com.hexvane.eterniamod.socialui.SocialUiActions.Action.WORLD_SELECT,ref,store,player);});
    }
}
