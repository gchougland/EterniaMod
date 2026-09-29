package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.ScheduledFuture;
import org.joml.Vector3f;

final class CustomizationPreviewPage extends EterniaInteractiveCustomUIPage<CustomizationPreviewPage.Data> {
    private final CustomizationService.Preview preview;private ScheduledFuture<?> timer;private boolean submitted;
    CustomizationPreviewPage(PlayerRef player,CustomizationService.Preview preview){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.preview=preview;}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> s){
        c.append("EterniaMod/CustomizationPreview.ui");bindHome(e);c.set("#Description.Text",preview.description()+". Green marks preview up to 128 affected cells. Confirm within 90 seconds to apply.");
        for(String action:new String[]{"Confirm","Cancel"})e.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,new EventData().append("Action",action),false);
        if(timer==null)timer=CustomizationBootstrap.previewTimer(()->s.getExternalData().getWorld().execute(()->{if(java.time.Instant.now().isAfter(preview.expires())||!ref.isValid()){close();return;}if(!isDismissed())draw();}));
    }
    private void draw(){var b=preview.after().bounds();int count=0;for(var value:preview.after().document().getArray("blocks")){if(count++>=128)break;var cell=value.asDocument();double x=b.minX()+cell.getInt32("x").getValue()+.5,y=b.minY()+cell.getInt32("y").getValue()+1.03,z=b.minZ()+cell.getInt32("z").getValue()+.5;line(x-.35,y,z,x+.35,y,z);line(x,y,z-.35,x,y,z+.35);}}
    private void line(double x,double y,double z,double ex,double ey,double ez){var matrix=DebugLineCylinderUtil.segmentMatrix(x,y,z,ex,ey,ez,.018,Math.hypot(ex-x,ez-z));if(matrix!=null)playerRef.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder,Matrix4dUtil.asFloatData(matrix),new Vector3f(.57f,.79f,.55f),.8f,(byte)DebugUtils.FLAG_NO_WIREFRAME,null,.8f));}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){if(submitted||isDismissed()||data.action==null)return;if(data.action.equals("Cancel")){returnOrClose(ref,store);return;}if(!data.action.equals("Confirm"))return;submitted=true;store.getExternalData().getWorld().execute(()->{try{if(!ref.isValid())return;CustomizationBootstrap.service().confirm(store.getExternalData().getWorld(),preview);playerRef.sendMessage(Message.raw("Customization applied and saved."));}catch(Exception error){playerRef.sendMessage(Message.raw(error.getMessage()==null?"Customization could not finish":error.getMessage()));}finally{close();}});}
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){if(timer!=null)timer.cancel(false);super.onDismiss(ref,store);}
    public static final class Data {public String action;public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new).append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add().build();}
}
