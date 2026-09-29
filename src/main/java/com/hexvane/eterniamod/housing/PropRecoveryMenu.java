package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.housing.relocation.FailedPropRecovery;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;

/** Keeps results on screen, loads saved cells before checking them, and logs the actual failure. */
public final class PropRecoveryMenu {
    private PropRecoveryMenu() {}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Owner owner) {
        try {
            var slot=plugin.getServices().housing().find(owner).orElseThrow();
            var location=plugin.getServices().housing().location(owner).orElseThrow();
            var world=store.getExternalData().getWorld();
            if(!location.worldId().equals(world.getName()))throw new IllegalStateException("Visit "+location.worldId()+" first, then retry recovery. Plot: X "+location.minX()+", Z "+location.minZ()+".");
            var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(slot.propertyId());
            if(plot==null)throw new IllegalStateException("The saved plot cannot be found. Its operation has been retained for administrator review.");
            ChoicePage.open(ref,store,player,"Recover decoration","Loading the saved plot cells and checking the interrupted placement...",List.of(new ChoicePage.Choice("You may leave this screen while recovery finishes.","My plots",(r,s)->MyPlotsPage.open(plugin,r,s,player))));
            var progress=store.getComponent(ref,Player.getComponentType()).getPageManager().getCustomPage();
            FailedPropRecovery.loadPending(plugin,world,plot,player.getUuid()).whenComplete((v,failure)->world.execute(()->{
                String message;boolean success=false;
                try {
                    if(failure!=null)throw new IllegalStateException("The plot could not be loaded. Please retry.",failure);
                    int count=FailedPropRecovery.recover(plugin,world,plot,player.getUuid());
                    success=count>0;
                    message=success?"The interrupted decoration is back in your housing build inventory. Your plot is unlocked. It is a catalog quantity, not an item in your hotbar.":"This operation cannot be automatically returned. No blocks or inventory were changed. Its ownership and snapshots remain saved for administrator review.";
                }catch(Exception error){plugin.getLogger().atWarning().withCause(error).log("Prop recovery did not complete for property %s",plot.getPlotId());message=error.getMessage()==null?"Recovery could not finish. See the server log for details.":error.getMessage();}
                player.sendMessage(Message.raw(message));
                if(ref.isValid()&&ref.equals(player.getReference())&&store.getComponent(ref,Player.getComponentType()).getPageManager().getCustomPage()==progress)result(plugin,ref,store,player,owner,message,success);
            }));
        }catch(Exception error){plugin.getLogger().atWarning().withCause(error).log("Could not start prop recovery");result(plugin,ref,store,player,owner,error.getMessage()==null?"Could not start recovery.":error.getMessage(),false);}
    }
    private static void result(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Owner owner,String message,boolean success) {
        var actions=new java.util.ArrayList<ChoicePage.Choice>();
        if(success)actions.add(new ChoicePage.Choice("Browse your houses and returned decorations","Housing inventory",(r,s)->HousingInventory.open(plugin,r,s,player)));
        else actions.add(new ChoicePage.Choice("Load the plot cells and check again","Retry recovery",(r,s)->open(plugin,r,s,player,owner)));
        actions.add(new ChoicePage.Choice("See this property's current state","My plots",(r,s)->MyPlotsPage.open(plugin,r,s,player)));
        ChoicePage.open(ref,store,player,success?"Decoration returned":"Recovery needs attention",message,actions);
    }
}
