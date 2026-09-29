package com.hexvane.eterniamod.ui;

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
import java.util.function.BiConsumer;

/** A paged server-owned choice list. UI values never become arbitrary command strings. */
public final class ChoicePage extends EterniaInteractiveCustomUIPage<ChoicePage.Data> {
    public record Choice(String label,String button,BiConsumer<Ref<EntityStore>,Store<EntityStore>> action,String contentId) {public Choice(String label,String button,BiConsumer<Ref<EntityStore>,Store<EntityStore>> action){this(label,button,action,"");}}
    private final String title,description;private final List<Choice> choices;private int page;private BiConsumer<Ref<EntityStore>,Store<EntityStore>> back;
    public ChoicePage(PlayerRef player,String title,String description,List<Choice> choices){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.title=title;this.description=description;this.choices=List.copyOf(choices);}
    public static void open(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,String title,String description,List<Choice> choices){var component=store.getComponent(ref,Player.getComponentType());if(component!=null){var next=new ChoicePage(player,title,description,choices);next.back=returnAction(component.getPageManager().getCustomPage());component.getPageManager().openCustomPage(ref,store,next);}}
    public static BiConsumer<Ref<EntityStore>,Store<EntityStore>> returnAction(com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage current){
        if(!(current instanceof ChoicePage parent))return null;
        return (ref,store)->{var component=store.getComponent(ref,Player.getComponentType());if(component==null)return;var fresh=new ChoicePage(parent.playerRef,parent.title,parent.description,parent.choices);fresh.page=parent.page;fresh.back=parent.back;component.getPageManager().openCustomPage(ref,store,fresh);};
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> store){
        c.append("EterniaMod/ChoicePage.ui");bindHome(e);c.set("#Back.Visible",back!=null);c.set("#Title.Text",title);c.set("#Description.Text",description);c.set("#Pagination.Text","Page "+(page+1)+" / "+Math.max(1,(choices.size()+5)/6));
        c.set("#SinglePageSpacer.Visible",choices.size() <= 6);com.hexvane.eterniamod.ui.MenuPagination.show(c, choices.size(), 6);c.set("#Previous.Disabled",page==0);c.set("#Next.Disabled",(page+1)*6>=choices.size());
        int start=page*6,end=Math.min(start+6,choices.size());
        for(int i=start;i<end;i++){String selector="#Rows["+(i-start)+"]";c.append("#Rows","EterniaMod/ServiceRow.ui");ContentImages.show(c,selector+" #RowImage",choices.get(i).contentId,false);c.set(selector+" #RowText.Text",choices.get(i).label);c.set(selector+" #RowAction.Text",choices.get(i).button);int width=UiPresentation.buttonWidth(choices.get(i).button);c.setObject(selector+" #RowAction.Anchor",UiAnchors.serviceAction(width));c.setObject(selector+".Anchor",UiAnchors.heightWithBottom(Math.max(76,UiPresentation.wrappedHeight(choices.get(i).label,810-width-(choices.get(i).contentId.isEmpty()?0:78),23)+24),8));e.addEventBinding(CustomUIEventBindingType.Activating,selector+" #RowAction",new EventData().append("Action","Choose:"+i),false);}
        if(choices.isEmpty())c.set("#Description.Text",description+"\nNo entries are available yet.");
        for(String action:new String[]{"Previous","Next","Back","Close"})e.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,new EventData().append("Action",action),false);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){if(data.action==null||isDismissed())return;store.getExternalData().getWorld().execute(()->{
        if(isDismissed()||!ref.isValid())return;
        try {if(data.action.equals("Back")){if(back!=null)back.accept(ref,store);return;}if(data.action.equals("Close")){close();return;}if(data.action.equals("Next")){if((page+1)*6<choices.size())page++;rebuild();return;}if(data.action.equals("Previous")){page=Math.max(0,page-1);rebuild();return;}
            if(data.action.startsWith("Choose:")){int i=Integer.parseInt(data.action.substring(7));if(i>=page*6&&i<Math.min(choices.size(),page*6+6))choices.get(i).action.accept(ref,store);}
        }catch(RuntimeException error){playerRef.sendMessage(Message.raw(error instanceof com.hexvane.eterniamod.domain.DomainException?error.getMessage():"This action could not complete. Please try again after the issue is resolved."));}
    });}
    public static final class Data{public String action;public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new).append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add().build();}
}
