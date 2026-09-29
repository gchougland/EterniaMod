package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

final class SplineRoadSettingsPage extends EterniaInteractiveCustomUIPage<SplineRoadSettingsPage.Data> {
    private final SplineRoadTool.Session session;
    SplineRoadSettingsPage(PlayerRef player,SplineRoadTool.Session session){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.session=session;}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> store){c.append("EterniaMod/SplineRoadSettings.ui");bindHome(e);c.set("#Width.Text",session.width+" blocks");var styles=SplineRoadTool.service().styles();c.set("#Style.Text",styles.get(Math.floorMod(session.style,styles.size())).name());for(String action:new String[]{"Narrower","Wider","Previous","Next","Done"})e.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,new EventData().append("Action",action),false);}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){if(data.action==null)return;store.getExternalData().getWorld().execute(()->{if(!ref.isValid())return;try{SetupAccess.require(playerRef.getUuid());if(!SplineRoadTool.holding(ref,store)||!session.world.equals(store.getExternalData().getWorld().getName())){close();return;}switch(data.action){case "Narrower"->session.width=Math.max(1,session.width-1);case "Wider"->session.width=Math.min(9,session.width+1);case "Previous"->session.style--;case "Next"->session.style++;case "Done"->{close();return;}default->{return;}}session.invalidate();session.message="Width or style changed · review the updated ground preview";rebuild();}catch(Exception error){SplineRoadTool.error(playerRef,error);close();}});}
    public static final class Data{public String action;public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new).append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add().build();}
}
