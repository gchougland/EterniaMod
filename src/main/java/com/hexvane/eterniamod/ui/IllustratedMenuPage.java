package com.hexvane.eterniamod.ui;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.*;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.function.BiConsumer;

/** Illustrated destinations and housing categories with server-owned actions. */
public final class IllustratedMenuPage extends EterniaInteractiveCustomUIPage<ChoicePage.Data> {
    public record Card(String title,String detail,String image,String button,BiConsumer<Ref<EntityStore>,Store<EntityStore>> action) {}
    private final String title,subtitle,description;
    private final List<Card> cards;
    private BiConsumer<Ref<EntityStore>,Store<EntityStore>> back;
    private int page;
    private IllustratedMenuPage(PlayerRef player,String title,String subtitle,String description,List<Card> cards){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ChoicePage.Data.CODEC);
        this.title=title;this.subtitle=subtitle;this.description=description;this.cards=List.copyOf(cards);
    }
    public static void open(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,String title,String subtitle,String description,List<Card> cards){
        var component=store.getComponent(ref,Player.getComponentType());if(component==null)return;
        var next=new IllustratedMenuPage(player,title,subtitle,description,cards);
        next.back=ChoicePage.returnAction(component.getPageManager().getCustomPage());
        component.getPageManager().openCustomPage(ref,store,next);
    }
    public void reopen(Ref<EntityStore> ref,Store<EntityStore> store){
        var component=store.getComponent(ref,Player.getComponentType());if(component==null)return;
        var next=new IllustratedMenuPage(playerRef,title,subtitle,description,cards);next.back=back;next.page=page;
        component.getPageManager().openCustomPage(ref,store,next);
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> store){
        c.append("EterniaMod/IllustratedMenuPage.ui");bindHome(e);showWallet(c,ref,store);
        c.set("#Title.Text",title);JournalChrome.icon(c,"#PageIcon",title.equals("Travel")?"worlds":"storage");c.set("#Subtitle.Text",subtitle);c.set("#Description.Text",description);
        c.set("#Notice.Text",cards.isEmpty()?"No destinations or items are available yet.":"");
        c.set("#Back.Visible",back!=null);MenuPagination.show(c,cards.size(),6);
        c.set("#Previous.Disabled",page==0);c.set("#Next.Disabled",(page+1)*6>=cards.size());
        c.set("#Pagination.Text",(page+1)+" / "+Math.max(1,(cards.size()+5)/6));
        for(int slot=0;slot<6;slot++){
            String selector="#Card"+slot;int index=page*6+slot;boolean visible=index<cards.size();c.set(selector+".Visible",visible);
            if(!visible)continue;var card=cards.get(index);
            c.set(selector+" #Name.Text",card.title());c.set(selector+" #Detail.Text",card.detail());
            c.setObject(selector+" #Art.Background",new PatchStyle(Value.of(card.image())));
            c.set(selector+" #Open.Text",card.button());
            e.addEventBinding(CustomUIEventBindingType.Activating,selector+" #Open",new EventData().append("Action","Choose:"+index),false);
        }
        for(String action:List.of("Back","Previous","Next","Close"))e.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,new EventData().append("Action",action),false);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ChoicePage.Data data){
        if(data.action==null||isDismissed())return;
        store.getExternalData().getWorld().execute(()->{
            if(isDismissed()||!ref.isValid())return;
            try{
                switch(data.action){
                    case "Close" -> close();
                    case "Back" -> {if(back!=null)back.accept(ref,store);}
                    case "Previous" -> {page=Math.max(0,page-1);rebuild();}
                    case "Next" -> {if((page+1)*6<cards.size())page++;rebuild();}
                    default -> {if(data.action.startsWith("Choose:")){int index=Integer.parseInt(data.action.substring(7));if(index>=page*6&&index<Math.min(cards.size(),page*6+6))cards.get(index).action().accept(ref,store);}}
                }
            }catch(RuntimeException error){
                var update=new UICommandBuilder();update.set("#Notice.Text",error instanceof com.hexvane.eterniamod.domain.DomainException||error instanceof IllegalStateException?error.getMessage():"This action could not complete. Please try again.");sendUpdate(update,null,false);
            }
        });
    }
}
