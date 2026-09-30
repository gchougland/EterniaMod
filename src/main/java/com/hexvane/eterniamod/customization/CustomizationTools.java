package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.runtime.NativeMenuActions;
import com.hexvane.eterniamod.socialui.*;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Physical tools contain no claim IDs, entitlement proof, currency, recipes, fuel or consumable rights. */
public final class CustomizationTools {
    public static final String DEED="Eternia_Plot_Deed",LEDGER="Eternia_Architect_Ledger";
    private CustomizationTools(){}
    public static String reissue(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,boolean includeServices) {
        store.assertThread();var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.EVERYTHING);if(inventory==null)return "Your inventory is unavailable.";
        int given=0;boolean full=false;
        for(String id:includeServices?List.of(DEED,LEDGER,com.hexvane.eterniamod.EterniaModConstants.PACKAGING_WAND_ID):List.of(DEED)) {
            boolean exists=false;for(short slot=0;slot<inventory.getCapacity();slot++){var item=inventory.getItemStack(slot);if(!ItemStack.isEmpty(item)&&id.equals(item.getItemId())){exists=true;break;}}
            if(exists)continue;var item=new ItemStack(id,1);if(!inventory.canAddItemStack(item)||!inventory.addItemStack(item,true,false,true).succeeded()){full=true;continue;}given++;
        }
        if(!includeServices)return full?"Free an inventory slot for your plot deed.":given>0?"Your plot deed is ready. Use it to choose a plot.":"You already have a plot deed.";
        return full?"Free some inventory space, then ask for your remaining tools.":given>0?"Your housing toolkit is ready. Use the deed to choose a plot, the ledger to manage your home, and the wand to pack decorations.":"You already have your housing toolkit. Come back if you lose a tool.";
    }
    public static void ledger(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        if(HousingInventory.resume(plugin,ref,store,player))return;
        ChoicePage.open(ref,store,player,"Architect’s ledger","Your homes, current quests and Eternia services.",List.of(
            new ChoicePage.Choice("All Eternia services","Main menu",(r,s)->SocialUiBootstrap.open(r,s,player,plugin.getServices(),plugin.getMenuActions(),EterniaServicesPage.Section.GREETER)),
            new ChoicePage.Choice("Your current goals and progress","Quests",(r,s)->SocialUiBootstrap.open(r,s,player,plugin.getServices(),plugin.getMenuActions(),EterniaServicesPage.Section.QUESTS)),
            new ChoicePage.Choice("House styles, decorations and whole-plot moves","Housing",(r,s)->HousingInventory.open(plugin,r,s,player)),
            new ChoicePage.Choice("House palettes and a path toward the road","Customize",(r,s)->CustomizationMenus.open(plugin,r,s,player)),
            new ChoicePage.Choice("Send offline messages and receive items","Mailbox",(r,s)->SocialUiBootstrap.open(r,s,player,plugin.getServices(),new NativeMenuActions(plugin),EterniaServicesPage.Section.MAIL)),
            new ChoicePage.Choice("Browse player stores","Player shops",(r,s)->SocialUiBootstrap.open(r,s,player,plugin.getServices(),new NativeMenuActions(plugin),EterniaServicesPage.Section.SHOP))));
    }
}
