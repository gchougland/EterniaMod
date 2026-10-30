package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.socialui.*;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hexvane.eterniamod.inventory.ui.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

public final class NativeMenuActions implements SocialUiActions {
    private final EterniaModPlugin plugin;
    public NativeMenuActions(EterniaModPlugin plugin){this.plugin=plugin;}
    @Override public Result perform(Action action,Ref<EntityStore>ref,Store<EntityStore>store,PlayerRef player) {
        switch(action) {
            case ARCHITECT_LEDGER -> com.hexvane.eterniamod.customization.CustomizationTools.ledger(plugin,ref,store,player);
            case HOUSING_INVENTORY -> HousingInventory.open(plugin,ref,store,player);
            case HOUSING_CLAIM -> {var notice=com.hexvane.eterniamod.customization.CustomizationTools.reissue(ref,store,player,true)+" Walk to where you want your home, then use the Housing Deed to mark your plot there.";player.sendMessage(Message.raw(notice));return Result.notice(notice);}
            case HOUSING_MANAGE -> {player.sendMessage(Message.raw(com.hexvane.eterniamod.customization.CustomizationTools.reissue(ref,store,player,true)));HousingInventory.open(plugin,ref,store,player);}
            case GUILD_HOUSING -> ChoicePage.open(ref,store,player,"Guild estate",plugin.getRuntimeConfig().local()?"Local testing: a solo guild can claim an estate. Normal placement rules still apply.":"Guild leaders claim estates with at least five members. Authorized builders furnish them.",List.of(
                new ChoicePage.Choice("Claim an estate","Choose plot",(r,s)->PlotClaimPage.openNew(r,s,player,plugin,true)),
                new ChoicePage.Choice("Furnish the estate","Inventory",(r,s)->HousingInventory.open(plugin,r,s,player)),
                new ChoicePage.Choice("Stand on the estate, then choose Guild decorations to place your unlocked Waygate portal","Place Waygate",(r,s)->HousingInventory.open(plugin,r,s,player),"eternia:prop/guild_waygate"),
                new ChoicePage.Choice("Plan or remove a community road","Roads",(r,s)->com.hexvane.eterniamod.guildroads.GuildRoads.open(r,s,player)),
                new ChoicePage.Choice("Redeem a purchased guild upgrade","Review vouchers",(r,s)->GuildBenefitsMenu.open(plugin,r,s,player))));
            case WORLD_SELECT -> {
                WorldDestinationsMenu.open(plugin,ref,store,player);
            }
            case HUB_TRAVEL -> {
                var hub=plugin.getInfrastructure().worlds().entrySet().stream().filter(e->e.getValue().role().equals("hub")).findFirst().orElse(null);
                if(hub==null)return Result.notice("A hub destination has not been configured yet.");
                plugin.getTravel().selectWorld(player,hub.getKey());return Result.notice("Travel requested. The destination is checked before teleporting.");
            }
            case STORE_OPEN -> {
                com.hexvane.eterniamod.premium.PremiumShopPage.open(plugin,ref,store,player);
            }
            case SHOP_BROWSE -> SocialUiBootstrap.open(ref,store,player,plugin.getServices(),this,EterniaServicesPage.Section.SHOP);
            case SHOP_MANAGE -> CommerceDeskPage.open(plugin,ref,store,player,CommerceDeskPage.Mode.MY_SHOP);
            case ITEM_DESK -> CommerceDeskPage.open(plugin,ref,store,player,CommerceDeskPage.Mode.DELIVERIES);
            case TRADE_DESK -> CommerceDeskPage.open(plugin,ref,store,player,CommerceDeskPage.Mode.TRADES);
        }
        return Result.openedPage();
    }
    @Override public Result composeMail(String recipient,String subject,String body,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        CommerceDeskPage.open(plugin,ref,store,player,CommerceDeskPage.Mode.MAIL,"",recipient,subject,body);return Result.openedPage();
    }
    @Override public Result visitShop(String listingId,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        try{NativeShopTravel.requireAtShop(plugin,ref,store,player,listingId);CommerceDeskPage.open(plugin,ref,store,player,CommerceDeskPage.Mode.BUY,listingId,"1","","");return Result.openedPage();}catch(RuntimeException ignored){}
        var component=store.getComponent(ref,Player.getComponentType());var origin=component==null?null:component.getPageManager().getCustomPage();
        try{NativeShopTravel.visit(plugin,player,listingId).whenComplete((arrived,failure)->{
            var current=player.getReference();if(current==null||!current.isValid())return;var currentStore=current.getStore();
            try{currentStore.getExternalData().getWorld().execute(()->{
                if(!current.isValid())return;var playerComponent=currentStore.getComponent(current,Player.getComponentType());
                if(failure!=null||!Boolean.TRUE.equals(arrived)){player.sendMessage(Message.raw("That shop is unavailable or its entrance is obstructed. Refresh the directory and try again."));return;}
                player.sendMessage(Message.raw("You can use the shop doors. Choose Return from visit in the market menu, or /e shopreturn, to go back."));
                if(playerComponent!=null&&playerComponent.getPageManager().getCustomPage()==origin&&origin!=null)CommerceDeskPage.open(plugin,current,currentStore,player,CommerceDeskPage.Mode.BUY,listingId,"1","","");
                else player.sendMessage(Message.raw("You arrived at the shop. Open Player shops and select this listing to purchase here."));
            });}catch(RuntimeException ignored){}
        });return Result.notice("Travelling to the seller's checked shop entrance…");}
        catch(RuntimeException failure){return Result.notice(failure.getMessage());}
    }
}
