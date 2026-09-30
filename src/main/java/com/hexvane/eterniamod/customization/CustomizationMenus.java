package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.HousingAccess;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

public final class CustomizationMenus {
    private CustomizationMenus(){}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        var service=CustomizationBootstrap.service();if(service==null){player.sendMessage(Message.raw("Customization is unavailable."));return;}
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(store.getExternalData().getWorld(),plugin).findPlotAtPlayerOrTarget(ref,store);
        if(plot==null){player.sendMessage(Message.raw("Stand in your plot to customize it."));return;}
        var owner=HousingAccess.owner(plot);List<ChoicePage.Choice> choices=new ArrayList<>();
        for(var palette:service.catalog().palettes())if(plugin.getServices().ownership().owns(owner,"eternia:palette/"+palette.id()))
            choices.add(new ChoicePage.Choice(palette.name(),"Preview palette",(r,s)->preview(player,r,s,()->service.palette(s.getExternalData().getWorld(),plot.getPlotId(),player.getUuid(),palette))));
        for(var path:service.catalog().paths())if(plugin.getServices().ownership().owns(owner,"eternia:path/"+path.id()))
            choices.add(new ChoicePage.Choice(path.name()+" · choose a width and review your route","Choose path",(r,s)->pathWidth(service,plot.getPlotId(),path,r,s,player)));
        choices.add(new ChoicePage.Choice("Restore the ground beneath your existing path","Remove path",(r,s)->preview(player,r,s,()->service.removePath(s.getExternalData().getWorld(),plot.getPlotId(),player.getUuid()))));
        choices.add(new ChoicePage.Choice("Replace missing deed and service tools","Reissue tools",(r,s)->player.sendMessage(Message.raw(CustomizationTools.reissue(r,s,player,true)))));
        ChoicePage.open(ref,store,player,"Home customization","Palettes affect authored house surfaces. Paths use level, clear ground inside your lot and stop before public roads. Every change requires confirmation.",choices);
    }
    private static void pathWidth(CustomizationService service,UUID plot,CustomizationCatalog.PathStyle style,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        List<ChoicePage.Choice> choices=new ArrayList<>();
        for(int value=1;value<=3;value++){final int width=value;choices.add(new ChoicePage.Choice(width==1?"A narrow garden path":width==2?"Room for a comfortable walkway":"A broad entrance path",width+" block"+(width==1?"":"s")+" wide",(r,s)->preview(player,r,s,()->{
            var t=s.getComponent(r,TransformComponent.getComponentType()).getPosition();return service.path(s.getExternalData().getWorld(),plot,player.getUuid(),style,(int)Math.floor(t.x),(int)Math.floor(t.y),(int)Math.floor(t.z),width);
        })));}
        ChoicePage.open(ref,store,player,"Path width","Stand where your path should begin. Leave space for its full width and one empty block beside houses and decorations. Bends are softened where there is room.",choices);
    }
    private interface PreviewFactory {CustomizationService.Preview get()throws Exception;}
    private static void preview(PlayerRef player,Ref<EntityStore> ref,Store<EntityStore> store,PreviewFactory factory){try{var preview=factory.get();store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,new CustomizationPreviewPage(player,preview).withReturnFrom(store.getComponent(ref,Player.getComponentType()).getPageManager().getCustomPage()));}catch(Exception error){player.sendMessage(Message.raw(error.getMessage()==null?"Could not prepare customization preview":error.getMessage()));}}
}
