package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.placement.*;
import com.hexvane.eterniamod.ui.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import org.joml.Vector3i;

public final class HousingInventory {
    private HousingInventory(){}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        if(resume(plugin,ref,store,player))return;
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(store.getExternalData().getWorld(),plugin);
        var plot=manager.findPlotAtPlayerOrTarget(ref,store);
        if(plot==null||!HousingAccess.can(plugin,plot,player.getUuid(),"housing.prop.place")){MyPlotsPage.open(plugin,ref,store,player);return;}
        if(!HousingAccess.can(plugin,plot,player.getUuid(),"housing.prop.place")){player.sendMessage(Message.raw("Your plot or guild build permissions do not allow this action."));return;}
        Owner propertyOwner=HousingAccess.owner(plot);
        var choices=new ArrayList<ChoicePage.Choice>();
        choices.add(new ChoicePage.Choice("Locate personal and guild land; review packing or restoring a plot","My plots",(r,s)->MyPlotsPage.open(plugin,r,s,player)));
        choices.add(new ChoicePage.Choice("House styles","Browse",(r,s)->houses(plugin,r,s,player,plot.getPlotId())));
        if(HousingCustody.can(plugin,plot,player.getUuid(),propertyOwner,HousingCustody.PLACE))
            choices.add(new ChoicePage.Choice(propertyOwner.kind()==Owner.Kind.GUILD?"Guild decorations":"Decorations","Browse",(r,s)->props(plugin,r,s,player,plot.getPlotId(),propertyOwner)));
        if(propertyOwner.kind()==Owner.Kind.PLAYER&&plot.getAttachedGuildUuid()!=null){
            Owner guildOwner=Owner.guild(plot.getAttachedGuildUuid());
            if(HousingCustody.can(plugin,plot,player.getUuid(),guildOwner,HousingCustody.PLACE))
                choices.add(new ChoicePage.Choice("Guild decorations","Browse",(r,s)->props(plugin,r,s,player,plot.getPlotId(),guildOwner)));
        }
        choices.addAll(List.of(
            new ChoicePage.Choice("Palettes and paths","Customize",(r,s)->com.hexvane.eterniamod.customization.CustomizationMenus.open(plugin,r,s,player)),
            new ChoicePage.Choice("Pack house style","Review",(r,s)->{if(!plot.hasBuilding()){player.sendMessage(Message.raw("No house is placed."));return;}s.getComponent(r,Player.getComponentType()).getPageManager().openCustomPage(r,s,new BuildingPickupPage(player,plot.getPlotId()).withReturnFrom(s.getComponent(r,Player.getComponentType()).getPageManager().getCustomPage()));}),
            new ChoicePage.Choice("Move the entire furnished plot","Review",(r,s)->ChoicePage.open(r,s,player,"Move this plot?","This reserves one move credit and saves your house and belongings. Use your deed to restore the same or a larger owned plot size in a new location.",List.of(new ChoicePage.Choice("Save and pack this plot","Confirm",(rr,ss)->{
                var current=plot(plugin,ss,plot.getPlotId());if(current==null)return;
                Owner owner=HousingAccess.owner(current);
                if(owner.kind()==Owner.Kind.PLAYER&&!owner.id().equals(player.getUuid())||owner.kind()==Owner.Kind.GUILD&&!plugin.getServices().guilds().can(player.getUuid(),owner.id(),"housing.move"))return;
                plugin.getPlotMover().request(ss.getExternalData().getWorld(),owner,player);
            }))))
        ));
        ChoicePage.open(ref,store,player,"Housing inventory","Choose a house style or decoration from an inventory you can use.",choices);
    }
    public static boolean resume(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        var building=BuildingPlacementSessions.get(player.getUuid());
        if(building!=null&&building.getWorld()==store.getExternalData().getWorld()) {
            var plot=plot(plugin,store,building.getPlotId());
            if(plot!=null&&!plot.hasBuilding()&&HousingAccess.can(plugin,plot,player.getUuid(),"housing.structure")) {
                store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new BuildingPlacementPage(player,building));return true;
            }
            BuildingPlacementOpenHelper.cancelActive(player,player.getUuid());
        }
        var prop=PropPlacementSessions.get(player.getUuid());
        if(prop!=null&&prop.getWorld()==store.getExternalData().getWorld()) {
            var plot=plot(plugin,store,prop.getPlotId());
            if(plot!=null&&HousingCustody.can(plugin,plot,player.getUuid(),prop.getCustodyOwner()==null?HousingAccess.owner(plot):prop.getCustodyOwner(),HousingCustody.PLACE)) {
                store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new PropPlacementPage(player,prop));return true;
            }
            PropPlacementOpenHelper.cancelActive(player,player.getUuid());
        }
        return PlotClaimPage.resumeDraft(ref,store,player,plugin);
    }
    private static HubPlotRecord plot(EterniaModPlugin p,Store<EntityStore>s,UUID id){return EterniaWorldRegistries.getOrCreateHubPlotManager(s.getExternalData().getWorld(),p).getPlot(id);}
    private static void houses(EterniaModPlugin p,Ref<EntityStore> r,Store<EntityStore>s,PlayerRef player,UUID id) {
        var plot=plot(p,s,id);if(plot==null||!HousingAccess.can(p,plot,player.getUuid(),"housing.structure"))return;
        Owner owner=HousingAccess.owner(plot);var choices=new ArrayList<ChoicePage.Choice>();
        for(var def:p.getBuildingCatalog().asMap().values())if(p.getServices().ownership().owns(owner,"eternia:house/"+def.getId()))choices.add(new ChoicePage.Choice(def.getDisplayName()==null?com.hexvane.eterniamod.ui.UiPresentation.friendlyId(def.getId()):def.getDisplayName(),"Place",(ref,store)->{
            var current=plot(p,store,id);if(current==null||!HousingAccess.can(p,current,player.getUuid(),"housing.structure")||!p.getServices().ownership().owns(HousingAccess.owner(current),"eternia:house/"+def.getId()))return;
            if(current.hasBuilding()){player.sendMessage(Message.raw("Pack the existing house before placing another style."));return;}
            Vector3i anchor=BuildingPlacementSnapUtil.anchorAtPlayerFeet(ref,store);if(anchor==null)return;
            var session=new BuildingPlacementSession(store.getExternalData().getWorld(),id,anchor,0,def.getId());
            BuildingPlacementValidator.findValidPosition(store.getExternalData().getWorld(),current,player.getUuid(),session,def,p);BuildingPlacementSessions.put(player.getUuid(),session);store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new BuildingPlacementPage(player,session).withReturnFrom(store.getComponent(ref,Player.getComponentType()).getPageManager().getCustomPage()));
        },"eternia:house/"+def.getId()));
        ChoicePage.open(r,s,player,"House styles","Houses and additions must remain five blocks from plot borders and roads.",choices);
    }
    private static void props(EterniaModPlugin p,Ref<EntityStore>r,Store<EntityStore>s,PlayerRef player,UUID id,Owner owner) {
        var plot=plot(p,s,id);if(plot==null||!HousingCustody.can(p,plot,player.getUuid(),owner,HousingCustody.PLACE)){player.sendMessage(Message.raw("You no longer have permission to use this build inventory here."));return;}
        var choices=new ArrayList<ChoicePage.Choice>();var packed=p.getServices().provenance().ownedInstances(owner).stream().filter(i->i.state().equals("PACKED")).toList();
        for(var def:p.getPropCatalog().asMap().values()) {
            String content="eternia:prop/"+def.getId();long available=p.getServices().ownership().available(owner,content);long stored=packed.stream().filter(i->i.contentId().equals(content)).count();
            if(available+stored==0)continue;
            choices.add(new ChoicePage.Choice((def.getDisplayName()==null?com.hexvane.eterniamod.ui.UiPresentation.friendlyId(def.getId()):def.getDisplayName())+"  ·  "+available+" available, "+stored+" packed","Place",(ref,store)->{
                var current=plot(p,store,id);if(current==null||!HousingCustody.can(p,current,player.getUuid(),owner,HousingCustody.PLACE)){player.sendMessage(Message.raw("You no longer have permission to use this build inventory here."));return;}
                Vector3i anchor=BuildingPlacementSnapUtil.anchorAtPlayerFeet(ref,store);if(anchor==null)return;
                var session=new PropPlacementSession(store.getExternalData().getWorld(),id,anchor,0,def.getId(),owner);
                PropPlacementSessions.put(player.getUuid(),session);store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new PropPlacementPage(player,session).withReturnFrom(store.getComponent(ref,Player.getComponentType()).getPageManager().getCustomPage()));
            },content));
        }
        boolean guild=owner.kind()==Owner.Kind.GUILD;
        ChoicePage.open(r,s,player,guild?"Guild decorations":"Decorations",guild?"These decorations remain guild-owned. Packaging returns them and their saved contents to the guild build inventory.":"Packed props keep their contents. Placing one reuses that saved instance before taking another from your collection.",choices);
    }
}
