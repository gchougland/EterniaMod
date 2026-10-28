package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.runtime.WorldDestinationsMenu;
import com.hexvane.eterniamod.socialui.SocialUiActions;
import com.hexvane.eterniamod.ui.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Housing dashboard. Actions use the same authoritative placement and travel services as physical tools. */
public final class ArchitectLedgerPage extends EterniaInteractiveCustomUIPage<ChoicePage.Data> {
    private final EterniaModPlugin plugin;
    private static final String[] ACTIONS={"Plots","Place","Customize","Claim","Guild","Waygate"};
    public ArchitectLedgerPage(EterniaModPlugin plugin,PlayerRef player){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ChoicePage.Data.CODEC);this.plugin=plugin;}
    public void reopen(Ref<EntityStore> ref,Store<EntityStore> store){open(plugin,ref,store,playerRef);}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        var component=store.getComponent(ref,Player.getComponentType());
        if(component!=null)component.getPageManager().openCustomPage(ref,store,new ArchitectLedgerPage(plugin,player));
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> store){
        c.append("EterniaMod/ArchitectLedgerPage.ui");bindHome(e);showWallet(c,ref,store);
        c.set("#Note.Text",plugin.getRuntimeConfig().local()?"Local playground: solo guilds can claim an estate. Roads, borders and guild permissions still apply.":"Make a place your own. The house button above opens your Journal.");
        for(String action:ACTIONS)e.addEventBinding(CustomUIEventBindingType.Activating,"#"+action+" #Open",new EventData().append("Action",action),false);
        e.addEventBinding(CustomUIEventBindingType.Activating,"#Close",new EventData().append("Action","Close"),false);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ChoicePage.Data data){
        if(data.action==null||isDismissed())return;
        store.getExternalData().getWorld().execute(()->{
            if(isDismissed()||!ref.isValid())return;
            try{
                switch(data.action){
                    case "Plots" -> MyPlotsPage.open(plugin,ref,store,playerRef);
                    case "Place" -> HousingInventory.open(plugin,ref,store,playerRef);
                    case "Customize" -> CustomizationMenus.open(plugin,ref,store,playerRef);
                    case "Claim" -> PlotClaimPage.open(ref,store,playerRef,plugin);
                    case "Guild" -> plugin.getMenuActions().perform(SocialUiActions.Action.GUILD_HOUSING,ref,store,playerRef);
                    case "Waygate" -> {
                        if(plugin.getTravel().canUseTravel(ref,store,playerRef.getUuid()))WorldDestinationsMenu.open(plugin,ref,store,playerRef);
                        else {var update=new UICommandBuilder();update.set("#Note.Text","Home Waygate is a travel unlock. Nothing needs placing. Stand inside your own plot after purchasing it, or use a public portal.");sendUpdate(update,null,false);}
                    }
                    case "Close" -> close();
                    default -> { }
                }
            }catch(RuntimeException error){var update=new UICommandBuilder();update.set("#Note.Text",error instanceof com.hexvane.eterniamod.domain.DomainException?error.getMessage():"This action could not complete. Please try again.");sendUpdate(update,null,false);}
        });
    }
}
