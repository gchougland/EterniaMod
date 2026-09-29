package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.socialui.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.command.system.*;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class EterniaPlayCommand extends AbstractPlayerCommand {
    private final String route;
    public EterniaPlayCommand(String route){super(route,"Open Eternia "+route);this.route=route;setPermissionGroups("hytale:Adventurer");}
    @Override protected void execute(CommandContext context,Store<EntityStore>store,Ref<EntityStore>ref,PlayerRef player,World world){
        var plugin=EterniaModPlugin.get();if(plugin==null)return;
        switch(route){case "myplots"->MyPlotsPage.open(plugin,ref,store,player);case "shopreturn"->{try{com.hexvane.eterniamod.inventory.ui.NativeShopTravel.returnFromVisit(plugin,player);}catch(IllegalStateException failure){player.sendMessage(com.hypixel.hytale.server.core.Message.raw(failure.getMessage()));}}case "quests"->SocialUiBootstrap.open(ref,store,player,plugin.getServices(),plugin.getMenuActions(),EterniaServicesPage.Section.QUESTS);case "claim"->PlotClaimPage.open(ref,store,player,plugin);case "housing"->HousingInventory.open(plugin,ref,store,player);case "worlds"->plugin.getMenuActions().perform(SocialUiActions.Action.WORLD_SELECT,ref,store,player);default->SocialUiBootstrap.open(ref,store,player,plugin.getServices(),plugin.getMenuActions(),EterniaServicesPage.Section.GREETER);}
    }
}
