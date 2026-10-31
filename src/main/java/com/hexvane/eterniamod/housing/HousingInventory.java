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
    private record InventoryKey(Owner owner,String kind,List<String> ids) {}
    private record Stock(List<ProvenanceService.Instance> instances,Map<String,Long> available) {}
    private HousingInventory(){}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        if(resume(plugin,ref,store,player))return;
        var cards=new ArrayList<IllustratedMenuPage.Card>();
        Owner personal=Owner.player(player.getUuid());
        cards.add(new IllustratedMenuPage.Card("YOUR HOUSE STYLES","Browse your homes. Stand on your personal plot to place one.",ContentImages.path("eternia:house/hub_house",false),"House styles",(r,s)->houses(plugin,r,s,player,personal)));
        cards.add(new IllustratedMenuPage.Card("YOUR DECORATIONS","Furniture, garden treasures and packed belongings for your own plot.",ContentImages.path("eternia:prop/potion_shelf",false),"Decorations",(r,s)->props(plugin,r,s,player,personal)));
        var membership=plugin.getServices().guilds().membership(player.getUuid()).orElse(null);
        if(membership!=null){
            Owner guild=Owner.guild(membership.guildId());
            plugin.getServices().guildBenefits().ensureWaygateProp(guild.id());
            cards.add(new IllustratedMenuPage.Card("GUILD HOUSE STYLES","Homes owned by your guild. Your role controls building on the estate.",ContentImages.path("eternia:house/founders_hall",false),"Guild houses",(r,s)->houses(plugin,r,s,player,guild)));
            cards.add(new IllustratedMenuPage.Card("GUILD DECORATIONS","A separate collection shared by your guild. Personal items stay in your collection.","EterniaMod/Icons/guild.png","Guild decorations",(r,s)->props(plugin,r,s,player,guild)));
        }
        cards.add(new IllustratedMenuPage.Card("PALETTES & PATHS","Choose your materials, add a room or lay a welcoming garden path.","EterniaMod/Icons/palette.png","Customize home",(r,s)->com.hexvane.eterniamod.customization.CustomizationMenus.open(plugin,r,s,player)));
        cards.add(new IllustratedMenuPage.Card("MANAGE YOUR HOME","Find your land, pack a house style or move your whole furnished plot.","EterniaMod/Icons/plot.png","Manage plots",(r,s)->manage(plugin,r,s,player)));
        IllustratedMenuPage.open(ref,store,player,"Housing inventory","BUILD SOMETHING THAT FEELS LIKE HOME","Browse from anywhere. To place a house or decoration, stand on the plot that will hold it.",cards);
    }
    private static void manage(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        var choices=new ArrayList<ChoicePage.Choice>();
        choices.add(new ChoicePage.Choice("Find your plot, restore a packed home or move your whole furnished plot","My plots",(r,s)->MyPlotsPage.open(plugin,r,s,player),"eternia:plot/free"));
        choices.add(new ChoicePage.Choice("Stand on your plot to pack its house style. Pack decorations and additions first.","Pack house",(r,s)->{
            var current=EterniaWorldRegistries.getOrCreateHubPlotManager(s.getExternalData().getWorld(),plugin).findPlotAtPlayerOrTarget(r,s);
            if(current==null||!HousingAccess.can(plugin,current,player.getUuid(),"housing.structure")){player.sendMessage(Message.raw("Stand on a plot where you can change the house first."));return;}
            if(!current.hasBuilding()){player.sendMessage(Message.raw("No house is placed on this plot."));return;}
            s.getComponent(r,Player.getComponentType()).getPageManager().openCustomPage(r,s,new BuildingPickupPage(player,current.getPlotId()).withReturnFrom(s.getComponent(r,Player.getComponentType()).getPageManager().getCustomPage()));
        },"eternia:move/house"));
        ChoicePage.open(ref,store,player,"Manage your home","Keep your belongings when changing your home or moving to a new neighborhood.",choices);
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
    private static void houses(EterniaModPlugin p,Ref<EntityStore> r,Store<EntityStore>s,PlayerRef player,Owner owner) {
        var choices=new ArrayList<ChoicePage.Choice>();
        var definitions=List.copyOf(p.getBuildingCatalog().asMap().values());
        var owned=p.getServices().readView(new InventoryKey(owner,"houses",definitions.stream().map(d->d.getId()).toList()),java.time.Duration.ofSeconds(2),()->definitions.stream().filter(d->p.getServices().ownership().owns(owner,"eternia:house/"+d.getId())).map(d->d.getId()).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        for(var def:definitions)if(owned.contains(def.getId()))choices.add(new ChoicePage.Choice(def.getDisplayName()==null?com.hexvane.eterniamod.ui.UiPresentation.friendlyId(def.getId()):def.getDisplayName(),"Place",(ref,store)->{
            var current=placementPlot(p,ref,store,player,owner,true);if(current==null)return;
            if(!p.getServices().ownership().owns(owner,"eternia:house/"+def.getId()))return;
            if(current.hasBuilding()){player.sendMessage(Message.raw("Pack the existing house before placing another style."));return;}
            Vector3i anchor=BuildingPlacementSnapUtil.anchorAtPlayerFeet(ref,store);if(anchor==null)return;
            var session=new BuildingPlacementSession(store.getExternalData().getWorld(),current.getPlotId(),anchor,0,def.getId());
            BuildingPlacementValidator.findValidPosition(store.getExternalData().getWorld(),current,player.getUuid(),session,def,p);BuildingPlacementSessions.put(player.getUuid(),session);store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new BuildingPlacementPage(player,session).withReturnFrom(store.getComponent(ref,Player.getComponentType()).getPageManager().getCustomPage()));
        },"eternia:house/"+def.getId()));
        ChoicePage.open(r,s,player,owner.kind()==Owner.Kind.GUILD?"Guild house styles":"Your house styles","Browse from anywhere. Stand on your "+(owner.kind()==Owner.Kind.GUILD?"guild estate":"personal plot")+" to place a house. Leave five blocks inside borders and away from roads.",choices);
    }
    private static void props(EterniaModPlugin p,Ref<EntityStore>r,Store<EntityStore>s,PlayerRef player,Owner owner) {
        var choices=new ArrayList<ChoicePage.Choice>();
        var ids=p.getPropCatalog().asMap().values().stream().map(d->d.getId()).toList();
        var stock=p.getServices().readView(new InventoryKey(owner,"props",ids),java.time.Duration.ofSeconds(2),()->{var amounts=new HashMap<String,Long>();for(var id:ids)amounts.put("eternia:prop/"+id,p.getServices().ownership().available(owner,"eternia:prop/"+id));return new Stock(p.getServices().provenance().ownedInstances(owner),Map.copyOf(amounts));});
        var instances=stock.instances();var packed=instances.stream().filter(i->i.state().equals("PACKED")).toList();
        for(var def:p.getPropCatalog().asMap().values()) {
            String content="eternia:prop/"+def.getId();long available=stock.available().getOrDefault(content,0L);long stored=packed.stream().filter(i->i.contentId().equals(content)).count();
            long placed=instances.stream().filter(i->i.contentId().equals(content)&&i.state().equals("PLACED")).count();
            if(available+stored==0){
                if(placed>0)choices.add(new ChoicePage.Choice((def.getDisplayName()==null?UiPresentation.friendlyId(def.getId()):def.getDisplayName())+" · "+placed+" placed. Use the Packaging Wand to recover it.","My plots",(rr,ss)->MyPlotsPage.open(p,rr,ss,player),content));
                continue;
            }
            choices.add(new ChoicePage.Choice((def.getDisplayName()==null?com.hexvane.eterniamod.ui.UiPresentation.friendlyId(def.getId()):def.getDisplayName())+"  ·  "+available+" available, "+stored+" packed","Place",(ref,store)->{
                var current=placementPlot(p,ref,store,player,owner,false);if(current==null)return;
                if(def.getId().equals("guild_waygate")&&!com.hexvane.eterniamod.runtime.GuildWaygate.canPlace(p,current,owner)){player.sendMessage(Message.raw("Place one Guild Waygate on your guild estate after unlocking guild travel."));return;}
                Vector3i anchor=BuildingPlacementSnapUtil.anchorInFrontOfPlayer(ref,store);if(anchor==null)return;
                var session=new PropPlacementSession(store.getExternalData().getWorld(),current.getPlotId(),anchor,0,def.getId(),owner);
                PropPlacementSessions.put(player.getUuid(),session);store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new PropPlacementPage(player,session).withReturnFrom(store.getComponent(ref,Player.getComponentType()).getPageManager().getCustomPage()));
            },content));
        }
        boolean guild=owner.kind()==Owner.Kind.GUILD;
        ChoicePage.open(r,s,player,guild?"Guild decorations":"Decorations",guild?"Browse from anywhere. Stand on your guild estate or an attached member plot to place these, with permission from your guild.":"Browse from anywhere. Stand on your personal plot to place these decorations. Packed decorations keep their contents.",choices);
    }
    private static HubPlotRecord placementPlot(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Owner owner,boolean house){
        var transform=store.getComponent(ref,com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        var position=transform==null?null:transform.getPosition();
        var plot=position==null?null:EterniaWorldRegistries.getOrCreateHubPlotManager(store.getExternalData().getWorld(),plugin).findPlotContainingHorizontal((int)Math.floor(position.x),(int)Math.floor(position.z));
        boolean allowed=plot!=null&&(house?HousingAccess.owner(plot).equals(owner)&&HousingAccess.can(plugin,plot,player.getUuid(),"housing.structure"):HousingCustody.can(plugin,plot,player.getUuid(),owner,HousingCustody.PLACE));
        if(!allowed){player.sendMessage(Message.raw(owner.kind()==Owner.Kind.PLAYER?"Stand on your personal plot to place this item.":"Stand on a guild plot where your role allows this item to be placed."));return null;}
        return plot;
    }

}
