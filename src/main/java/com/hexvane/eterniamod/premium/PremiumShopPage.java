package com.hexvane.eterniamod.premium;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hexvane.eterniamod.ui.UiAnchors;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.function.Supplier;

/** The world thread renders detached snapshots; database work uses the shared service-menu executor. */
public final class PremiumShopPage extends EterniaInteractiveCustomUIPage<PremiumShopPage.Data> {
    private final EterniaModPlugin plugin;
    private final UUID actor;
    private final Map<String,String> routes=new HashMap<>();
    private PremiumShopSnapshot snapshot=PremiumShopSnapshot.empty();
    private List<PremiumService.Item> visible=List.of();
    private List<String> categories=List.of();
    private String category="All",notice="Loading the Crown Store…",requestId;
    private int page;
    private long generation;
    private boolean busy,initial=true;
    private PremiumService.Item selected;

    private static final int PAGE_SIZE=6,CARDS_PER_ROW=3;
    private static String categoryIcon(String category){return switch(category){case "All"->"crown";case "Companions"->"pet";case "Furnishings"->"furnishing";case "Guild charters"->"charter";case "Housing"->"housing";case "Season passes"->"season";case "Titles"->"title";case "Wardrobe"->"wardrobe";default->"collection";};}
    private PremiumShopPage(EterniaModPlugin plugin,PlayerRef player){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.plugin=plugin;actor=player.getUuid();}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        if(!SocialUiBootstrap.isOpen(plugin.getServices())||!ref.isValid())return;
        var component=store.getComponent(ref,Player.getComponentType());
        if(component!=null)component.getPageManager().openCustomPage(ref,store,new PremiumShopPage(plugin,player));
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> store){
        c.append("EterniaMod/PremiumShopPage.ui");bindHome(e);showWallet(c,ref,store);routes.clear();var balance=snapshot.balance();

        c.set("#Notice.Text",busy?"Working…":balance.owed()>0?"A refunded Crown purchase left "+balance.owed()+" Crowns to restore before further purchases.":notice);
        var cats=new ArrayList<String>();cats.add("All");snapshot.items().stream().map(PremiumService.Item::category).filter(name->!name.equals("All")).distinct().sorted().forEach(cats::add);categories=List.copyOf(cats);
        for(int n=0;n<categories.size();n++){
            String name=categories.get(n);
            bind(e,com.hexvane.eterniamod.ui.JournalChrome.sideTab(c,"#Categories",n,name,categoryIcon(name),category.equals(name),busy),"Category:"+n);
        }
        c.set("#Catalog.Visible",selected==null);c.set("#ConfirmView.Visible",selected!=null);
        if(selected==null){
            visible=snapshot.items().stream().filter(item->category.equals("All")||item.category().equals(category)).toList();page=Math.min(page,Math.max(0,(visible.size()-1)/PAGE_SIZE));
            int first=page*PAGE_SIZE,last=Math.min(visible.size(),first+PAGE_SIZE);
            for(int n=first;n<last;n++){
                var item=visible.get(n);int slot=n-first;String row="#Cards["+(slot/CARDS_PER_ROW)+"]";
                if(slot%CARDS_PER_ROW==0){c.append("#Cards","EterniaMod/PremiumCard.ui");for(int card=0;card<CARDS_PER_ROW;card++)c.set(row+" #Card"+card+".Visible",n+card<last);}
                String s=row+" #Card"+(slot%CARDS_PER_ROW);
                com.hexvane.eterniamod.ui.ContentImages.show(c,s+" #ItemImage",item.benefits().getFirst().contentId(),false);
                c.set(s+" #ItemName.Text",item.name());c.set(s+" #ItemDescription.Text",item.description());c.set(s+" #ItemCategory.Text",item.category());
                c.set(s+" #Price.Text",String.format(Locale.US,"%,d",item.price()));boolean owned=snapshot.owned().contains(item.id());
                c.set(s+" #Review.Text",owned?"Owned":"View");c.set(s+" #Review.Disabled",busy);bind(e,s+" #Review","Select:"+n);
            }
            c.set("#PageControls.Visible",visible.size() > PAGE_SIZE);com.hexvane.eterniamod.ui.MenuPagination.show(c, visible.size(), PAGE_SIZE);c.set("#Pagination.Text",(page+1)+" / "+Math.max(1,(visible.size()+PAGE_SIZE-1)/PAGE_SIZE));c.set("#Previous.Disabled",busy||page==0);c.set("#Next.Disabled",busy||(page+1)*PAGE_SIZE>=visible.size());
        }else{
            com.hexvane.eterniamod.ui.ContentImages.show(c,"#SelectedImage",selected.benefits().getFirst().contentId(),true);
            boolean screenshot=com.hexvane.eterniamod.ui.ContentImages.path(selected.benefits().getFirst().contentId(),true).endsWith("screenshot.png");c.setObject("#SelectedImage.Anchor",UiAnchors.size(screenshot?560:320,screenshot?350:320));
            c.set("#SelectedName.Text",selected.name());c.set("#SelectedDescription.Text",selected.description());
            c.setObject("#SelectedDescription.Anchor",UiAnchors.height(Math.max(100,UiPresentation.wrappedHeight(selected.description(),540,25))));c.setObject("#SelectedName.Anchor",UiAnchors.height(Math.max(58,UiPresentation.wrappedHeight(selected.name(),540,36))));
            c.set("#SelectedPrice.Text",snapshot.owned().contains(selected.id())?String.format(Locale.US,"Already yours.\nYour balance: %,d Crowns",balance.available()):String.format(Locale.US,"Price: %,d Crowns\nYour balance: %,d Crowns\nAfter purchase: %,d Crowns",selected.price(),balance.available(),Math.max(0,balance.available()-selected.price())));
            c.set("#Purchase.Text",snapshot.owned().contains(selected.id())?"Owned":"Confirm purchase");
            c.set("#Purchase.Disabled",busy||balance.available()<selected.price()||snapshot.owned().contains(selected.id()));
        }
        for(String action:List.of("Close","GetCrowns","Refresh","Previous","Next","Back","Purchase"))bind(e,"#"+action,action);
        for(String action:List.of("GetCrowns","Refresh","Back"))c.set("#"+action+".Disabled",busy);
        if(initial){initial=false;store.getExternalData().getWorld().execute(()->refresh(ref,store));}
    }
    private void bind(UIEventBuilder events,String selector,String action){String token=generation+":"+routes.size();routes.put(token,action);events.addEventBinding(CustomUIEventBindingType.Activating,selector,new EventData().append("Action",token),false);}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if(data.action==null||isDismissed())return;
        try{store.getExternalData().getWorld().execute(()->handle(ref,store,data.action));}catch(RuntimeException ignored){/* The old world is stopping. */}
    }
    private void handle(Ref<EntityStore> ref,Store<EntityStore> store,String token){
        if(!current(ref,store))return;String action=routes.get(token);if(action==null)return;
        if(action.equals("Close")){close();return;}if(busy)return;
        try{
            if(action.equals("Refresh")){refresh(ref,store);return;}
            if(action.equals("GetCrowns")){
                String url=plugin.getRuntimeConfig().websiteUrl();notice=url.isBlank()?"Website top-ups are not configured. Local testers can collect example Crowns from /e playground.":"Your secure Crown top-up link is in chat.";
                if(!url.isBlank()){
                    String storeUrl=url.replaceAll("/+$","")+"/store";
                    close();
                    playerRef.sendMessage(Message.raw("Click to get Crowns: ").insert(Message.raw(storeUrl).link(storeUrl)));
                    return;
                }
            }else if(action.startsWith("Category:")){
                int index=Integer.parseInt(action.substring(9));if(index>=0&&index<categories.size()){category=categories.get(index);selected=null;requestId=null;page=0;}
            }else if(action.startsWith("Select:")&&selected==null){
                int index=Integer.parseInt(action.substring(7));if(index>=page*PAGE_SIZE&&index<Math.min(visible.size(),page*PAGE_SIZE+PAGE_SIZE)){selected=visible.get(index);requestId=snapshot.owned().contains(selected.id())?null:UUID.randomUUID().toString();}
            }else if(action.equals("Back")){selected=null;requestId=null;}
            else if(action.equals("Next")&&selected==null&&(page+1)*PAGE_SIZE<visible.size())page++;
            else if(action.equals("Previous")&&selected==null)page=Math.max(0,page-1);
            else if(action.equals("Purchase")&&selected!=null&&requestId!=null){
                var reviewed=selected;String receipt=requestId;
                work(ref,store,()->{var purchase=PremiumShopSnapshot.checkout(plugin.getServices(),actor,reviewed,receipt);return new Loaded(purchase.snapshot(),purchase.order().name()+" is yours. Find it in housing inventory, Collection, a season pass, or guild upgrades.",true);});return;
            }
            generation++;rebuild();
        }catch(RuntimeException failure){failure(failure);generation++;rebuild();}
    }
    private void refresh(Ref<EntityStore> ref,Store<EntityStore> store){work(ref,store,()->new Loaded(PremiumShopSnapshot.load(plugin.getServices(),actor),"Choose something for your home or your next adventure.",false));}
    private void work(Ref<EntityStore> ref,Store<EntityStore> store,Supplier<Loaded> operation){
        if(busy||!current(ref,store))return;busy=true;long expected=++generation;rebuild();var world=store.getExternalData().getWorld();
        SocialUiBootstrap.supply(plugin.getServices(),operation).whenComplete((loaded,error)->{
            try{world.execute(()->{
                if(!current(ref,store)||generation!=expected)return;busy=false;
                if(error!=null)failure(error);
                else{snapshot=loaded.snapshot;notice=loaded.notice;if(loaded.purchased){selected=null;requestId=null;}}
                generation++;rebuild();
            });}catch(RuntimeException ignored){/* Detached pages cannot push updates; any committed order remains durable. */}
        });
    }
    private boolean current(Ref<EntityStore> ref,Store<EntityStore> store){
        if(isDismissed()||!ref.isValid()||!SocialUiBootstrap.isOpen(plugin.getServices()))return false;
        var component=store.getComponent(ref,Player.getComponentType());return component!=null&&component.getPageManager().getCustomPage()==this;
    }
    private void failure(Throwable error){
        while(error.getCause()!=null)error=error.getCause();
        notice=error instanceof DomainException?error.getMessage():"The store could not refresh. Retry this purchase or use Refresh to check your balance.";
        if(!(error instanceof DomainException))plugin.getLogger().atWarning().withCause(error).log("Crown store request remains available for safe retry");
    }
    private record Loaded(PremiumShopSnapshot snapshot,String notice,boolean purchased) {}
    public static final class Data {public String action;public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new).append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add().build();}
}
