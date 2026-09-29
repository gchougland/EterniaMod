package com.hexvane.eterniamod.guildroads;

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
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.joml.Vector3f;

final class GuildRoadPreviewPage extends EterniaInteractiveCustomUIPage<GuildRoadPreviewPage.Data> {
    private final GuildRoadService.Preview preview;private ScheduledFuture<?> timer;private boolean submitted;
    GuildRoadPreviewPage(PlayerRef player,GuildRoadService.Preview preview){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.preview=preview;}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> store){
        var road=preview.road();var r=road.rectangle();commands.append("EterniaMod/CustomizationPreview.ui");bindHome(events);
        commands.set("#Description.Text",(preview.remove()?"Restore saved terrain beneath this road":"Build this guild-owned road")+": "+r.x()+", "+r.z()+" → "+(r.endX()-1)+", "+(r.endZ()-1)+" at Y "+road.groundY()+". "+r.area()+" blocks. "+(preview.remove()?"The entire marked segment will be removed; interrupted operations accept only recorded before/after blocks.":"This road creates no plot anchor. It will be removed before an intersecting plot moves.")+" Confirm within 90 seconds.");
        for(String action:new String[]{"Confirm","Cancel"})events.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,new EventData().append("Action",action),false);
        if(timer==null)timer=GuildRoads.previewTimer(()->store.getExternalData().getWorld().execute(()->{if(!ref.isValid()||Instant.now().isAfter(preview.expires())){close();return;}if(!isDismissed())draw();}));
    }
    private void draw(){var road=preview.road();var r=road.rectangle();for(int x=r.x();x<r.endX();x++)for(int z=r.z();z<r.endZ();z++){double y=road.groundY()+1.03;line(x+.15,y,z+.5,x+.85,y,z+.5);line(x+.5,y,z+.15,x+.5,y,z+.85);}}
    private void line(double x,double y,double z,double ex,double ey,double ez){var matrix=DebugLineCylinderUtil.segmentMatrix(x,y,z,ex,ey,ez,.018,Math.hypot(ex-x,ez-z));if(matrix!=null)playerRef.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder,Matrix4dUtil.asFloatData(matrix),preview.remove()?new Vector3f(.9f,.66f,.3f):new Vector3f(.57f,.79f,.55f),.8f,(byte)DebugUtils.FLAG_NO_WIREFRAME,null,.8f));}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){if(submitted||isDismissed()||data.action==null)return;if(data.action.equals("Cancel")){close();return;}if(!data.action.equals("Confirm"))return;submitted=true;store.getExternalData().getWorld().execute(()->{try{if(!ref.isValid())return;GuildRoads.service().confirm(store.getExternalData().getWorld(),preview);playerRef.sendMessage(Message.raw(preview.remove()?"Road removed; its saved terrain is restored.":"Guild road built and saved."));}catch(Exception e){GuildRoads.error(playerRef,e);}finally{close();}});}
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){if(timer!=null)timer.cancel(false);super.onDismiss(ref,store);}
    public static final class Data{public String action;public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new).append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add().build();}
}
