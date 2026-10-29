package com.hexvane.eterniamod.ui;

import com.google.gson.Gson;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.authoring.*;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Admin designer for season passes. Drafts change freely; going live registers the pass, after which levels and rewards are fixed. */
public final class SeasonDesignerPage extends EterniaInteractiveCustomUIPage<SeasonDesignerPage.Data> {
    private static final Gson GSON=new Gson();
    private List<AuthoringDrafts.Season> passes=List.of();
    private AuthoringDrafts.Season selected;private boolean unsaved,confirmLive,busy,loaded;
    private String rewardTrack="FREE",rewardContent="",rewardLevel="",rewardQty="1";
    private String message="Pick a pass on the left or start a new one.";

    public SeasonDesignerPage(PlayerRef player){super(player,CustomPageLifetime.CanDismiss,Data.CODEC);}

    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> store){
        c.append("EterniaMod/SeasonDesignerPage.ui");
        for(int i=0;i<passes.size();i++){var p=passes.get(i);String row="#PassList["+i+"]";c.append("#PassList","EterniaMod/AuthoringRow.ui");
            c.set(row+" #RowText.Text",p.name.isBlank()?p.id:p.name+(p.live?" (live)":" (draft)"));c.set(row+" #RowAction.Text",selected!=null&&p.id.equals(selected.id)?"Open":"Edit");bind(e,row+" #RowAction","Pick:"+i);}
        if(passes.isEmpty()&&selected==null&&!loaded)message="Loading passes...";
        boolean has=selected!=null,live=has&&selected.live;
        c.set("#PassId.Value",has?selected.id:"");c.set("#PassId.IsReadOnly",!has||!unsaved);
        c.set("#PassName.Value",has?selected.name:"");c.set("#PassName.IsReadOnly",!has||live);
        c.set("#PassDescription.Value",has?selected.description:"");c.set("#PassDescription.IsReadOnly",!has);
        c.set("#StartsOn.Value",has?selected.startsOn:"");c.set("#StartsOn.IsReadOnly",!has);c.set("#EndsOn.Value",has?selected.endsOn:"");c.set("#EndsOn.IsReadOnly",!has);
        c.set("#Levels.Value",has?Integer.toString(selected.thresholds.size()):"");c.set("#Levels.IsReadOnly",!has||live);
        c.set("#XpPerLevel.Value",has&&!selected.thresholds.isEmpty()?Long.toString(selected.thresholds.getFirst()):"");c.set("#XpPerLevel.IsReadOnly",!has||live);
        c.set("#PassState.Text",!has?"":live?"This pass is live. Its name, levels and rewards are fixed so players keep what they earned. You can still change the description and dates.":
            "Draft. Change anything, then save to upload it to the website. Go live when it is ready for players. Live passes use the same daily and weekly quests as Foundations.");
        if(has)for(int i=0;i<selected.rewards.size();i++){var r=selected.rewards.get(i);String row="#RewardList["+i+"]";c.append("#RewardList","EterniaMod/AuthoringRow.ui");
            c.set(row+" #RowText.Text","Level "+r.level+" "+(r.track.equals("PAID")?"paid":"free")+": "+(r.name==null||r.name.isBlank()?r.contentId:r.name)+(r.quantity>1?" x"+r.quantity:""));
            c.set(row+" #RowAction.Text","Remove");c.set(row+" #RowAction.Disabled",live);if(!live)bind(e,row+" #RowAction","Remove:"+i);}
        c.set("#RewardContent.Value",rewardContent);c.set("#RewardLevel.Value",rewardLevel);c.set("#RewardQty.Value",rewardQty);
        c.set("#RewardTrack.Text",rewardTrack.equals("PAID")?"Paid track":"Free track");
        for(String id:new String[]{"RewardContent","RewardLevel","RewardQty"})c.set("#"+id+".IsReadOnly",!has||live);
        for(String id:new String[]{"RewardTrack","AddReward"})c.set("#"+id+".Disabled",!has||live);
        c.set("#SaveDraft.Disabled",!has||busy);c.set("#GoLive.Disabled",!has||live||busy);c.set("#GoLive.Text",confirmLive?"Confirm go live":"Go live");
        c.set("#Status.Text",message);
        for(String action:new String[]{"NewPass","RewardTrack","AddReward","CloseDesigner","SaveDraft","GoLive"})bind(e,"#"+action,action);
        if(!loaded)load(ref,store,null);
    }
    private void bind(UIEventBuilder e,String selector,String action){
        e.addEventBinding(CustomUIEventBindingType.Activating,selector,new EventData().append("Action",action).append("@PassId","#PassId.Value").append("@PassName","#PassName.Value")
            .append("@PassDescription","#PassDescription.Value").append("@StartsOn","#StartsOn.Value").append("@EndsOn","#EndsOn.Value").append("@Levels","#Levels.Value")
            .append("@XpPerLevel","#XpPerLevel.Value").append("@RewardContent","#RewardContent.Value").append("@RewardLevel","#RewardLevel.Value").append("@RewardQty","#RewardQty.Value"),false);
    }
    /** Live passes come from the game; drafts and website details come from the authoring folder. */
    private void load(Ref<EntityStore> ref,Store<EntityStore> store,String select){
        loaded=true;var plugin=EterniaModPlugin.get();if(plugin==null)return;var services=plugin.getServices();var drafts=AuthoringDrafts.of(ContentEntryPage.dataDirectory(plugin));var world=store.getExternalData().getWorld();
        SocialUiBootstrap.supply(services,()->{
            var found=new TreeMap<String,AuthoringDrafts.Season>();
            for(var d:drafts.seasons())found.put(d.id,d);
            for(var live:services.seasons().definitions()){var merged=ContentAuthoring.draftFrom(live);var saved=found.get(live.id());
                if(saved!=null){merged.description=saved.description;merged.startsOn=saved.startsOn;merged.endsOn=saved.endsOn;merged.websiteRevision=saved.websiteRevision;
                    for(var r:merged.rewards)saved.rewards.stream().filter(s->s.contentId.equals(r.contentId)&&s.level==r.level&&s.track.equals(r.track)).findFirst().ifPresent(s->r.name=s.name);}
                found.put(live.id(),merged);}
            return List.copyOf(found.values());
        }).whenComplete((list,error)->{try{world.execute(()->{if(isDismissed()||!ref.isValid())return;
            if(error!=null)message="Passes could not be loaded. "+WebsiteSync.reason(error);else{passes=list;if(select!=null)list.stream().filter(p->p.id.equals(select)).findFirst().ifPresent(p->{selected=copy(p);unsaved=false;});}
            rebuild();});}catch(RuntimeException ignored){/* World is closing. */}});
    }

    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if(data.action==null||isDismissed())return;
        store.getExternalData().getWorld().execute(()->{if(!ref.isValid()||isDismissed())return;try{
            SetupAccess.require(playerRef.getUuid());read(data);String action=data.action;
            if(!action.equals("GoLive"))confirmLive=false;
            if(action.startsWith("Pick:")){int i=Integer.parseInt(action.substring(5));if(i<0||i>=passes.size())return;selected=copy(passes.get(i));unsaved=false;message=selected.live?"Live pass opened.":"Draft opened.";}
            else if(action.startsWith("Remove:")){int i=Integer.parseInt(action.substring(7));if(selected==null||selected.live||i<0||i>=selected.rewards.size())return;selected.rewards.remove(i);message="Reward removed. Save to keep the change.";}
            else switch(action){
                case "NewPass"->{selected=new AuthoringDrafts.Season();selected.id="eternia:";selected.thresholds=new ArrayList<>(ContentAuthoring.thresholds(30,2000));unsaved=true;message="Give the new pass an ID and a name, then save.";}
                case "RewardTrack"->rewardTrack=rewardTrack.equals("PAID")?"FREE":"PAID";
                case "AddReward"->addReward();
                case "CloseDesigner"->{close();return;}
                case "SaveDraft"->{if(!busy)save(ref,store,false);return;}
                case "GoLive"->{if(busy)return;if(!confirmLive){ContentAuthoring.validate(selected);confirmLive=true;message="Going live fixes the levels and rewards for good. Press Confirm go live to continue.";}else save(ref,store,true);}
                default->{return;}
            }
            rebuild();
        }catch(IllegalArgumentException|DomainException error){message=error.getMessage();rebuild();}});
    }
    private void read(Data d){
        rewardContent=d.rewardContent==null?rewardContent:d.rewardContent.strip();rewardLevel=d.rewardLevel==null?rewardLevel:d.rewardLevel.strip();rewardQty=d.rewardQty==null?rewardQty:d.rewardQty.strip();
        if(selected==null)return;
        if(unsaved&&d.passId!=null){String id=d.passId.strip().toLowerCase(Locale.ROOT);selected.id=id.contains(":")?id:"eternia:"+id;}
        if(d.description!=null)selected.description=d.description.strip();
        if(d.startsOn!=null)selected.startsOn=d.startsOn.strip();if(d.endsOn!=null)selected.endsOn=d.endsOn.strip();
        if(selected.live)return;
        if(d.name!=null)selected.name=d.name.strip();
        if(d.levels!=null&&d.xp!=null&&!d.levels.isBlank()&&!d.xp.isBlank()){
            int levels=(int)number(d.levels,"Write the number of levels as a whole number.");long xp=number(d.xp,"Write the XP per level as a whole number.");
            if(levels!=selected.thresholds.size()||selected.thresholds.isEmpty()||xp!=selected.thresholds.getFirst())selected.thresholds=new ArrayList<>(ContentAuthoring.thresholds(levels,xp));
        }
    }
    private void addReward(){
        if(selected==null||selected.live)return;
        if(!rewardContent.matches("[a-z0-9_.-]+:[a-z0-9_./-]{1,150}"))throw new IllegalArgumentException("Write the content ID, like eternia:prop/aqua_lamp.");
        int level=(int)number(rewardLevel,"Write the reward level as a whole number.");long qty=rewardQty.isBlank()?1:number(rewardQty,"Write the amount as a whole number.");
        if(level<1||level>selected.thresholds.size())throw new IllegalArgumentException("Choose a level from 1 to "+selected.thresholds.size()+".");
        if(qty<1)throw new IllegalArgumentException("The amount must be at least 1.");
        var r=new AuthoringDrafts.Reward();r.contentId=rewardContent;r.level=level;r.track=rewardTrack;r.quantity=qty;
        // Placeable things and move credits are counted; everything else is a one time unlock.
        r.kind=qty>1||rewardContent.startsWith("eternia:prop/")||rewardContent.startsWith("eternia:pet/")||rewardContent.equals("eternia:plot_move_credit")?"QUANTITY":"UNLOCK";
        r.index=(int)selected.rewards.stream().filter(x->x.track.equals(r.track)&&x.level==r.level).mapToInt(x->x.index+1).max().orElse(0);
        selected.rewards.add(r);rewardContent="";message="Reward added. Save to keep the change.";
    }
    private static long number(String value,String error){String v=value.strip().replace(",","");if(!v.matches("\\d{1,9}"))throw new IllegalArgumentException(error);return Long.parseLong(v);}

    private void save(Ref<EntityStore> ref,Store<EntityStore> store,boolean goLive){
        var plugin=EterniaModPlugin.get();if(plugin==null||selected==null)return;
        ContentAuthoring.validate(selected);
        if(unsaved&&passes.stream().anyMatch(p->p.id.equals(selected.id)))throw new IllegalArgumentException("A pass with that ID already exists.");
        var pass=copy(selected);var services=plugin.getServices();var drafts=AuthoringDrafts.of(ContentEntryPage.dataDirectory(plugin));var sync=WebsiteSync.from(plugin.getRuntimeConfig());
        var data=ContentEntryPage.dataDirectory(plugin);UUID actor=playerRef.getUuid();String actorName=playerRef.getUsername();var world=store.getExternalData().getWorld();
        busy=true;confirmLive=false;message=goLive?"Going live...":"Saving...";rebuild();
        SocialUiBootstrap.supply(services,()->{
            if(goLive){
                var definitions=services.seasons().definitions();
                if(definitions.stream().anyMatch(d->d.id().equals(pass.id)))throw new IllegalArgumentException("That pass is already live.");
                var quests=definitions.stream().filter(d->d.id().equals("eternia:foundations")).findFirst().or(()->definitions.stream().filter(d->!d.quests().isEmpty()).findFirst()).map(SeasonService.Definition::quests).orElse(List.of());
                var definition=ContentAuthoring.definition(pass,quests);
                services.seasons().register(definition);
                ContentEntryPage.writeJson(data.resolve("Seasons").resolve(ContentAuthoring.seasonSlug(pass.id)+".json"),definition);
                pass.live=true;
            }
            try{drafts.save(pass);}catch(java.io.IOException e){throw new IllegalStateException("The pass could not be saved on the server.");}
            return pass;
        }).thenCompose(saved->{
            String done=goLive?"The pass is live in game.":"Saved in game.";
            if(!sync.configured())return CompletableFuture.completedFuture(done+" The website is not connected on this server.");
            return sync.season(ContentAuthoring.seasonUpload(saved,actor,actorName)).thenApply(r->{
                saved.websiteRevision=r.revision();try{drafts.save(saved);}catch(java.io.IOException ignored){/* The upload already succeeded. */}
                return done+(r.unchanged()?" The website already had this version.":" Uploaded to the website as version "+r.revision()+".");
            }).exceptionally(error->done+" "+WebsiteSync.reason(error));
        }).whenComplete((text,error)->{try{world.execute(()->{if(isDismissed())return;busy=false;
            message=error==null?text:WebsiteSync.reason(error);
            if(ref.isValid())if(error==null)load(ref,store,pass.id);else rebuild();});}catch(RuntimeException ignored){/* World is closing. */}});
    }
    private static AuthoringDrafts.Season copy(AuthoringDrafts.Season s){return GSON.fromJson(GSON.toJson(s),AuthoringDrafts.Season.class);}

    public static final class Data{
        public String action,passId,name,description,startsOn,endsOn,levels,xp,rewardContent,rewardLevel,rewardQty;
        public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new)
            .append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add()
            .append(new KeyedCodec<>("@PassId",Codec.STRING),(d,v)->d.passId=v,d->d.passId).add()
            .append(new KeyedCodec<>("@PassName",Codec.STRING),(d,v)->d.name=v,d->d.name).add()
            .append(new KeyedCodec<>("@PassDescription",Codec.STRING),(d,v)->d.description=v,d->d.description).add()
            .append(new KeyedCodec<>("@StartsOn",Codec.STRING),(d,v)->d.startsOn=v,d->d.startsOn).add()
            .append(new KeyedCodec<>("@EndsOn",Codec.STRING),(d,v)->d.endsOn=v,d->d.endsOn).add()
            .append(new KeyedCodec<>("@Levels",Codec.STRING),(d,v)->d.levels=v,d->d.levels).add()
            .append(new KeyedCodec<>("@XpPerLevel",Codec.STRING),(d,v)->d.xp=v,d->d.xp).add()
            .append(new KeyedCodec<>("@RewardContent",Codec.STRING),(d,v)->d.rewardContent=v,d->d.rewardContent).add()
            .append(new KeyedCodec<>("@RewardLevel",Codec.STRING),(d,v)->d.rewardLevel=v,d->d.rewardLevel).add()
            .append(new KeyedCodec<>("@RewardQty",Codec.STRING),(d,v)->d.rewardQty=v,d->d.rewardQty).add().build();
    }
}
