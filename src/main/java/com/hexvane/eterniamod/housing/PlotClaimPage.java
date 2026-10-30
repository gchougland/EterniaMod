package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.placement.BuildingPlacementCameraUtil;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.*;

public final class PlotClaimPage extends EterniaInteractiveCustomUIPage<PlotClaimPage.Data> {
    /** A draft only resumes near where it was left, so the deed always plans land where the player stands. */
    private record Draft(String world,int x,int y,int z,boolean paid,boolean rectangle,HousingRules.Scope scope){
        private static final int RESUME_RANGE=48;
        boolean nearby(String worldName,double px,double pz){return world.equals(worldName)&&Math.abs(px-x)<=RESUME_RANGE&&Math.abs(pz-z)<=RESUME_RANGE;}
    }
    private static final ConcurrentHashMap<UUID,Draft> DRAFTS=new ConcurrentHashMap<>();
    private boolean discarded;
    public static boolean resumeDraft(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,EterniaModPlugin plugin){
        var draft=DRAFTS.get(player.getUuid());var t=store.getComponent(ref,TransformComponent.getComponentType());
        if(draft==null||t==null||!draft.nearby(store.getExternalData().getWorld().getName(),t.getPosition().x,t.getPosition().z))return false;
        open(ref,store,player,plugin);return true;
    }
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("eternia-claim-preview").factory());
    private final EterniaModPlugin plugin;private final NativeClaimCoordinator claims;private final UUID operation=UUID.randomUUID();
    private PlotRect rect;private int centerX,centerZ,groundY;private boolean paid,rectangle,bird,review,submitted;
    private HousingRules.Scope scope=HousingRules.Scope.PUBLIC;
    private HousingRules.Result status=new HousingRules.Result(false,"loading","Checking your plot access. You can position the grid while this loads.");private ScheduledFuture<?> preview;
    private ClaimEligibility eligibility;private boolean loading;private long lastRefresh,revision;private Long reviewRequest;
    private HousingRules.Scope requestedScope;private boolean requestedPaid;
    private final java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
    private record GridView(PlotRect rect,int groundY,Boolean valid,boolean bird){}
    private GridView drawn;
    private long lastDraw;
    public PlotClaimPage(PlayerRef player,EterniaModPlugin plugin,int x,int y,int z) {
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);
        this.plugin=plugin;claims=plugin.getClaims();centerX=x;centerZ=z;groundY=y;resize();
    }
    public static void open(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,EterniaModPlugin plugin) {
        var transform=store.getComponent(ref,TransformComponent.getComponentType());var p=store.getComponent(ref,Player.getComponentType());
        if(transform==null||p==null)return;var pos=transform.getPosition();
        var next=new PlotClaimPage(player,plugin,(int)Math.floor(pos.x),(int)Math.floor(pos.y),(int)Math.floor(pos.z));
        var draft=DRAFTS.get(player.getUuid());
        if(draft!=null&&draft.nearby(store.getExternalData().getWorld().getName(),pos.x,pos.z)){next.centerX=draft.x;next.groundY=draft.y;next.centerZ=draft.z;next.paid=draft.paid;next.rectangle=draft.rectangle;next.scope=draft.scope;next.resize();}
        p.getPageManager().openCustomPage(ref,store,next);
    }
    public static void openNew(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,EterniaModPlugin plugin,boolean guild){
        var t=store.getComponent(ref,TransformComponent.getComponentType());if(t==null)return;var pos=t.getPosition();
        var next=new PlotClaimPage(player,plugin,(int)Math.floor(pos.x),(int)Math.floor(pos.y),(int)Math.floor(pos.z));next.scope=guild?HousingRules.Scope.GUILD_ROOT:HousingRules.Scope.PUBLIC;next.resize();
        DRAFTS.remove(player.getUuid());store.getComponent(ref,Player.getComponentType()).getPageManager().openCustomPage(ref,store,next);
    }
    private void resize(){int size=scope==HousingRules.Scope.GUILD_ROOT?(paid?64:48):(paid?32:24);rect=scope==HousingRules.Scope.GUILD_ROOT&&rectangle?PlotRect.centered(centerX,centerZ,paid?32:36,paid?128:64):PlotRect.centered(centerX,centerZ,size,size);review=false;reviewRequest=null;revision++;}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> store) {
        commands.append("EterniaMod/PlotClaimPage.ui");bindHome(events);
        refreshStatus(store);
        updateText(commands);
        for(String action:new String[]{"Public","Member","Guild","Size","Shape","North","South","West","East","Center","Camera","Confirm","Cancel","MyPlots"})
            events.addEventBinding(CustomUIEventBindingType.Activating,"#"+action,new EventData().append("Action",action),false);
        requestEligibility(ref,store,false);
        if(preview==null)preview=TIMER.scheduleAtFixedRate(()->{
            if(isDismissed()||!ref.isValid()||!queued.compareAndSet(false,true))return;
            store.getExternalData().getWorld().execute(()->{try{if(!isDismissed()&&ref.isValid()&&!submitted){
                requestEligibility(ref,store,false);
                drawGrid(false);
            }}finally{queued.set(false);}});
        },0,250,TimeUnit.MILLISECONDS);
    }
    private void refreshStatus(Store<EntityStore> store){
        status=eligibility!=null&&eligibility.matches(scope,paid)?claims.validate(store.getExternalData().getWorld(),rect,eligibility):new HousingRules.Result(false,"loading","Checking your plot access. You can position the grid while this loads.");
        if(!status.valid())review=false;
    }
    private void drawGrid(boolean force){
        var view=new GridView(rect,groundY,status.code().equals("loading")?null:status.valid(),bird);long now=System.nanoTime();
        if(force||!view.equals(drawn)||now-lastDraw>=TimeUnit.SECONDS.toNanos(1)){ClaimGrid.draw(playerRef,rect,groundY,view.valid(),bird);drawn=view;lastDraw=now;}
    }
    private void requestEligibility(Ref<EntityStore> ref,Store<EntityStore> store,boolean force){
        if(loading||submitted||isDismissed())return;
        long now=System.nanoTime();if(!force&&requestedScope==scope&&requestedPaid==paid&&now-lastRefresh<TimeUnit.SECONDS.toNanos(10))return;
        var targetScope=scope;boolean targetPaid=paid;requestedScope=targetScope;requestedPaid=targetPaid;lastRefresh=now;loading=true;
        var world=store.getExternalData().getWorld();
        com.hexvane.eterniamod.socialui.SocialUiBootstrap.supply(plugin.getServices(),()->ClaimEligibility.load(plugin.getServices(),playerRef.getUuid(),targetScope,targetPaid)).whenComplete((value,error)->world.execute(()->{
            loading=false;lastRefresh=System.nanoTime();if(isDismissed()||submitted||!ref.isValid())return;
            if(scope!=targetScope||paid!=targetPaid){requestEligibility(ref,store,false);return;}
            if(error!=null){eligibility=null;review=false;reviewRequest=null;status=new HousingRules.Result(false,"unavailable","Your plot access could not be checked. Try Review claim again in a moment.");plugin.getLogger().atWarning().withCause(error).log("Plot preview account check failed");}
            else{eligibility=value;refreshStatus(store);if(reviewRequest!=null&&reviewRequest==revision){review=status.valid();reviewRequest=null;}}
            var commands=new UICommandBuilder();updateText(commands);sendUpdate(commands,null,false);drawGrid(true);
        }));
    }

    private void updateText(UICommandBuilder c) {
        c.set("#Dimensions.Text",rect.width()+" × "+rect.depth()+"\n"+(scope==HousingRules.Scope.PUBLIC?"Personal plot":scope==HousingRules.Scope.GUILD_MEMBER?"Guild neighborhood":"Guild estate"));
        c.set("#Coordinates.Text","X "+rect.x()+" – "+(rect.endX()-1)+"   Z "+rect.z()+" – "+(rect.endZ()-1));
        c.set("#Status.Text",submitted?"Claiming your land. Please wait.":reviewRequest!=null?"Checking this location before you confirm.":review?"Confirm this location? Your free plot slot will be assigned here. Moving later uses a move credit.":status.message());
        c.set("#Confirm.Text",submitted?"Claiming...":reviewRequest!=null?"Checking...":review?"Confirm claim":"Review claim");
        c.set("#Confirm.Disabled",submitted||reviewRequest!=null||status==null||!status.valid()&&!status.code().equals("unavailable"));
        c.set("#Size.Text",paid?"Larger size":"Free size");
        c.set("#Camera.Text",bird?"Return to player":"Bird’s-eye view");
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data) {
        if(data.action==null||submitted||isDismissed())return;
        var world=store.getExternalData().getWorld();world.execute(()->{
            if(!ref.isValid()||submitted||isDismissed())return;
            try {
                switch(data.action) {
                    case "Cancel" -> {discarded=true;DRAFTS.remove(playerRef.getUuid());playerRef.sendMessage(Message.raw("Plot preview cancelled. No new land was claimed."));MyPlotsPage.open(plugin,ref,store,playerRef);return;}
                    case "MyPlots" -> {MyPlotsPage.open(plugin,ref,store,playerRef);return;}
                    case "Public" -> {scope=HousingRules.Scope.PUBLIC;resize();}
                    case "Member" -> {scope=HousingRules.Scope.GUILD_MEMBER;resize();}
                    case "Guild" -> {scope=HousingRules.Scope.GUILD_ROOT;resize();}
                    case "Size" -> {paid=!paid;resize();}
                    case "Shape" -> {rectangle=!rectangle;resize();}
                    case "North" -> {centerZ--;resize();} case "South" -> {centerZ++;resize();}
                    case "West" -> {centerX--;resize();} case "East" -> {centerX++;resize();}
                    case "Center" -> {var t=store.getComponent(ref,TransformComponent.getComponentType());if(t!=null){centerX=(int)Math.floor(t.getPosition().x);centerZ=(int)Math.floor(t.getPosition().z);groundY=(int)Math.floor(t.getPosition().y);resize();}}
                    case "Camera" -> bird=!bird;
                    case "Confirm" -> {
                        if(!review){reviewRequest=revision;requestEligibility(ref,store,true);var c=new UICommandBuilder();updateText(c);sendUpdate(c,null,false);return;}
                        submitted=true;var c=new UICommandBuilder();updateText(c);sendUpdate(c,null,false);
                        world.execute(()->finishClaim(ref,store));return;
                    }
                    default -> {return;}
                }
                refreshStatus(store);requestEligibility(ref,store,false);
                if(bird){var t=store.getComponent(ref,TransformComponent.getComponentType());if(t!=null){var p=t.getPosition();BuildingPlacementCameraUtil.applyBirdsEye(playerRef,Math.max(rect.width(),rect.depth())*1.25f,p.x,p.y,p.z,rect.x()+rect.width()/2.0,groundY,rect.z()+rect.depth()/2.0);}}
                else BuildingPlacementCameraUtil.resetToPlayerCamera(playerRef);
                var c=new UICommandBuilder();updateText(c);sendUpdate(c,null,false);drawGrid(true);
            } catch(RuntimeException e) {
                submitted=false;review=false;status=new HousingRules.Result(false,"failed",e instanceof com.hexvane.eterniamod.domain.DomainException?e.getMessage():"The claim could not finish. Your slot and recovery record have been retained if work began. Ask an administrator to inspect it.");
                var c=new UICommandBuilder();updateText(c);sendUpdate(c,null,false);plugin.getLogger().atWarning().withCause(e).log("Plot claim did not finish");
            }
        });
    }
    private void finishClaim(Ref<EntityStore> ref,Store<EntityStore> store){
        if(!ref.isValid()){submitted=false;return;}
        try{
            claims.claim(store.getExternalData().getWorld(),playerRef.getUuid(),rect,groundY,scope,paid,operation);
            DRAFTS.remove(playerRef.getUuid());playerRef.sendMessage(Message.raw("Land claimed at X "+rect.x()+", Z "+rect.z()+". Open /e housing to place a house. My plots shows its location and lets you move it."));if(!isDismissed())close();
        }catch(RuntimeException error){
            submitted=false;review=false;reviewRequest=null;eligibility=null;status=new HousingRules.Result(false,"failed",error instanceof com.hexvane.eterniamod.domain.DomainException||error instanceof IllegalStateException?error.getMessage():"The claim could not finish. Open My plots to check its status before trying again.");
            playerRef.sendMessage(Message.raw(status.message()));if(!isDismissed()){var c=new UICommandBuilder();updateText(c);sendUpdate(c,null,false);drawGrid(true);}plugin.getLogger().atWarning().withCause(error).log("Plot claim did not finish");
        }
    }
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){if(!discarded&&!submitted)DRAFTS.put(playerRef.getUuid(),new Draft(store.getExternalData().getWorld().getName(),centerX,groundY,centerZ,paid,rectangle,scope));if(preview!=null)preview.cancel(false);playerRef.getPacketHandler().write(new com.hypixel.hytale.protocol.packets.player.ClearDebugShapes());BuildingPlacementCameraUtil.resetToPlayerCamera(playerRef);super.onDismiss(ref,store);}
    public static final class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new).append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add().build();
    }
}
