package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hexvane.eterniamod.ui.UiAnchors;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Small persistent guild board. Server-owned routes and immutable confirmations never grant authority. */
public final class GuildBoardPage extends EterniaInteractiveCustomUIPage<GuildBoardPage.Data> {
    private static final int PAGE_SIZE=6;
    private final SocialUiBootstrap.Registration registration;
    private final EterniaServices services;
    private final UUID actor;
    private final Map<String,String> routes=new HashMap<>();
    private final String receiptPrefix="native:board:"+UUID.randomUUID()+":";
    private Snapshot snapshot=new Snapshot(null,"Guild notice board",List.of(),Map.of(),false,false);
    private UUID selected,editingId,editingGuild;
    private long editingRevision;
    private String title="",body="",editingGuildName="",status="Loading guild notices…";
    private boolean editing,busy,initial=true;
    private int page;
    private long generation,operation;
    private Pending pending;

    GuildBoardPage(PlayerRef player,SocialUiBootstrap.Registration registration){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);
        this.registration=registration;services=registration.services;actor=player.getUuid();
    }
    @Override public void build(@Nonnull Ref<EntityStore> ref,@Nonnull UICommandBuilder ui,@Nonnull UIEventBuilder events,@Nonnull Store<EntityStore> store){
        ui.append("EterniaMod/GuildBoardPage.ui");bindHome(events);routes.clear();
        ui.set("#BoardTitle.TextSpans",Message.raw(snapshot.guildName+" / Notice board"));
        ui.set("#Status.TextSpans",Message.raw(busy?"Working…":status));
        int start=Math.min(page*PAGE_SIZE,snapshot.posts.size());
        for(int i=start;i<Math.min(start+PAGE_SIZE,snapshot.posts.size());i++){
            var post=snapshot.posts.get(i);ui.append("#PostList","EterniaMod/GuildPostNav.ui");String selector="#PostList["+(i-start)+"]";
            String caption=(post.pinned()?"Pinned · ":"")+post.title();
            ui.set(selector+".TextSpans",Message.raw(caption));ui.setObject(selector+".Anchor",UiAnchors.heightWithBottom(Math.max(64,UiPresentation.wrappedHeight(caption,221,24)+20),8));
            ui.set(selector+".Disabled",busy||pending!=null);bind(events,selector,"select:"+post.id());
        }
        ui.set("#PageControls.Visible", pending == null && snapshot.posts.size() > PAGE_SIZE);
        button(ui,events,"#Previous","Earlier","previous",page>0);
        button(ui,events,"#Next","More","next",(page+1)*PAGE_SIZE<snapshot.posts.size());
        button(ui,events,"#Refresh","Refresh","refresh",true);
        button(ui,events,"#Back","Guild menu","back",true);
        var post=current();boolean confirming=pending!=null;
        ui.set("#Editor.Visible",editing&&!confirming);ui.set("#Reader.Visible",!editing||confirming);
        ui.set("#DraftTitle.Value",title);ui.set("#DraftBody.Value",body);
        String heading,byline,text;
        if(confirming){heading=switch(pending.action){case "POST"->"Publish notice";case "EDIT"->"Save changes";case "REMOVE"->"Remove notice";default->pending.pinned?"Pin notice":"Unpin notice";};byline=pending.guildName+" • “"+pending.title+"”";text=switch(pending.action){
            case "POST"->"Publish this notice for members of “"+pending.guildName+"”?\n\n"+pending.body;
            case "EDIT"->"Save these changes to your notice?\n\n"+pending.body;
            case "REMOVE"->"Remove this notice from “"+pending.guildName+"”'s board? Members will no longer see it.";
            default->pending.pinned?"Keep this notice at the top of “"+pending.guildName+"”'s board?":"Return this notice to its usual place among recent posts?";
        };}
        else if(post!=null){heading=post.title();byline=(post.pinned()?"Pinned • ":"")+snapshot.authors.getOrDefault(post.author(),"Former member");text=post.body();}
        else{heading="Your guild's shared notices";byline="Use /e guildchat <message> for a live conversation.";text=snapshot.posts.isEmpty()?"No notices have been posted. Members with board posting permission can write the first one.":"Select a notice to read it, or write a new one.";}
        ui.set("#PostTitle.TextSpans",Message.raw(heading));ui.set("#PostAuthor.TextSpans",Message.raw(byline));ui.set("#ReadBody.TextSpans",Message.raw(text));
        int lines=Arrays.stream(text.split("\\R",-1)).mapToInt(line->Math.max(1,(line.length()+49)/50)).sum();ui.setObject("#ReadBody.Anchor",UiAnchors.heightWithRight(Math.max(80,lines*24),12));
        button(ui,events,"#New","New notice","new",snapshot.canPost&&!editing&&!confirming);
        button(ui,events,"#Edit","Edit own notice","edit",snapshot.canPost&&post!=null&&post.author().equals(actor)&&!editing&&!confirming);
        button(ui,events,"#Save","Review notice","save",editing&&!confirming);
        button(ui,events,"#CancelEdit","Cancel edit","cancel_edit",editing&&!confirming);
        button(ui,events,"#Pin",post!=null&&post.pinned()?"Unpin":"Pin","pin",snapshot.canModerate&&post!=null&&!editing&&!confirming);
        button(ui,events,"#Remove","Remove","remove",snapshot.canModerate&&post!=null&&!editing&&!confirming);
        button(ui,events,"#Confirm","Confirm","confirm",confirming);
        button(ui,events,"#Cancel","Cancel","cancel",confirming);
        if(initial){initial=false;refresh(ref,store);}
    }
    private void button(UICommandBuilder ui,UIEventBuilder events,String selector,String label,String route,boolean visible){
        ui.set(selector+".TextSpans",Message.raw(label));ui.set(selector+".Visible",visible);ui.set(selector+".Disabled",busy);if(visible)bind(events,selector,route);
    }
    private void bind(UIEventBuilder events,String selector,String route){String key=generation+":"+routes.size();routes.put(key,route);
        events.addEventBinding(CustomUIEventBindingType.Activating,selector,new EventData().append("Action",key).append("@Title","#DraftTitle.Value").append("@Body","#DraftBody.Value"),false);}
    @Override public void handleDataEvent(@Nonnull Ref<EntityStore> ref,@Nonnull Store<EntityStore> store,@Nonnull Data data){
        if(isDismissed()||registration.closed||busy)return;String route=routes.get(data.action);if(route==null)return;
        if(editing&&pending==null){if(data.title!=null)title=shorten(data.title,80);if(data.body!=null)body=shorten(data.body,2000);}
        if(route.equals("back")){registration.open(ref,store,playerRef,EterniaServicesPage.Section.GUILD);return;}
        if(route.equals("refresh")){refresh(ref,store);return;}
        if(route.equals("previous")||route.equals("next")){page=Math.max(0,page+(route.equals("next")?1:-1));generation++;rebuild();return;}
        if(route.startsWith("select:")){selected=UUID.fromString(route.substring(7));editing=false;pending=null;generation++;rebuild();return;}
        var post=current();
        switch(route){
            case "new"->{if(!snapshot.canPost)return;selected=null;editingId=null;editingGuild=snapshot.guild;editingGuildName=snapshot.guildName;editingRevision=0;editing=true;title="";body="";}
            case "edit"->{if(post==null||!post.author().equals(actor)||!snapshot.canPost)return;editingId=post.id();editingGuild=post.guild();editingGuildName=snapshot.guildName;editingRevision=post.revision();editing=true;title=post.title();body=post.body();}
            case "cancel_edit"->editing=false;
            case "save"->{if(!editing||!snapshot.canPost)return;pending=new Pending(editingId==null?"POST":"EDIT",editingGuild,editingGuildName,editingId,editingRevision,title,body,post!=null&&post.pinned());}
            case "pin","remove"->{if(post==null||!snapshot.canModerate)return;pending=new Pending(route.equals("pin")?"PIN":"REMOVE",post.guild(),snapshot.guildName,post.id(),post.revision(),post.title(),post.body(),route.equals("pin")?!post.pinned():post.pinned());}
            case "cancel"->pending=null;
            case "confirm"->{Pending request=pending;if(request==null)return;pending=null;String receipt=receiptPrefix+operation;
                work(ref,store,()->{
                    UUID id=request.id;
                    switch(request.action){
                        case "POST"->id=services.guildCommunications().post(actor,request.guild,request.title,request.body,receipt).id();
                        case "EDIT"->services.guildCommunications().edit(actor,id,request.revision,request.title,request.body);
                        case "PIN"->services.guildCommunications().moderate(actor,id,request.revision,request.pinned,false);
                        case "REMOVE"->services.guildCommunications().moderate(actor,id,request.revision,request.pinned,true);
                        default->throw new IllegalArgumentException("Unknown notice action");
                    }
                    return new Loaded(load(),id,true,"Guild notice updated.");
                });return;
            }
            default->{return;}
        }
        generation++;rebuild();
    }
    private GuildCommunicationService.Post current(){return selected==null?null:snapshot.posts.stream().filter(p->p.id().equals(selected)).findFirst().orElse(null);}
    private Snapshot load(){
        var membership=services.guilds().membership(actor).orElseThrow(()->new IllegalArgumentException("Join a guild to use its notice board."));UUID guild=membership.guildId();
        var posts=services.guildCommunications().board(actor).stream().filter(p->!p.removed()).toList();var authors=new HashMap<UUID,String>();
        if(posts.stream().anyMatch(post->!post.guild().equals(guild))||services.guilds().membership(actor).filter(member->member.guildId().equals(guild)).isEmpty())throw new IllegalArgumentException("Your guild membership changed. Reopen the board.");
        for(var post:posts)authors.computeIfAbsent(post.author(),id->services.accounts().find(id).map(AccountService.Account::displayName).orElse("Former member"));
        return new Snapshot(guild,services.guilds().find(guild).map(GuildService.Guild::name).orElse("Guild"),posts,Map.copyOf(authors),services.guilds().can(actor,guild,"board.post"),services.guilds().can(actor,guild,"board.moderate"));
    }
    private void refresh(Ref<EntityStore> ref,Store<EntityStore> store){work(ref,store,()->new Loaded(load(),selected,false,"Current guild notices. Use Refresh to retrieve new posts."));}
    private void work(Ref<EntityStore> ref,Store<EntityStore> store,Supplier<Loaded> task){
        busy=true;long version=++generation;var world=store.getExternalData().getWorld();
        CompletableFuture.supplyAsync(task,registration.executor).whenComplete((result,error)->world.execute(()->{
            if(isDismissed()||registration.closed||!ref.isValid()||version!=generation)return;busy=false;
            if(error==null){snapshot=result.snapshot;selected=result.selected;if(current()==null)selected=null;status=result.notice;if(result.mutated){operation++;editing=false;title=body="";}}
            else{Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();status=cause instanceof DomainException||cause instanceof IllegalArgumentException?cause.getMessage():"The board action could not finish. Refresh or contact a steward.";}
            page=Math.min(page,Math.max(0,(snapshot.posts.size()-1)/PAGE_SIZE));generation++;rebuild();
        }));
    }
    private static String shorten(String value,int limit){return value.length()<=limit?value:value.substring(0,limit);}
    private record Snapshot(UUID guild,String guildName,List<GuildCommunicationService.Post> posts,Map<UUID,String> authors,boolean canPost,boolean canModerate){}
    private record Loaded(Snapshot snapshot,UUID selected,boolean mutated,String notice){}
    private record Pending(String action,UUID guild,String guildName,UUID id,long revision,String title,String body,boolean pinned){}
    public static final class Data{
        public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new)
            .append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add()
            .append(new KeyedCodec<>("@Title",Codec.STRING),(d,v)->d.title=v,d->d.title).add()
            .append(new KeyedCodec<>("@Body",Codec.STRING),(d,v)->d.body=v,d->d.body).add().build();
        public String action,title,body;
    }
}
