package com.hexvane.eterniamod.guildroads;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Static lifecycle/protection bridge. No registry entry is ever a plot-claim anchor. */
public final class GuildRoads {
    private GuildRoads(){}
    private static volatile GuildRoadService service;
    private static ScheduledExecutorService timer;
    private record Start(UUID guild,String world,GuildRoadPlanner.Cell cell,int ground,String style,Instant expires){}
    private static final Map<UUID,Start> starts=new ConcurrentHashMap<>();
    public static synchronized void startup(EterniaModPlugin plugin)throws IOException{
        if(service!=null)throw new IllegalStateException("Guild roads already started");
        service=new GuildRoadService(plugin);timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"Eternia-guild-road-preview");t.setDaemon(true);return t;});
    }
    public static synchronized void close(){if(timer!=null)timer.shutdownNow();timer=null;service=null;starts.clear();}
    public static boolean protectedColumn(String world,int x,int z){var current=service;return current!=null&&current.roads().stream().anyMatch(r->r.world().equals(world)&&r.rectangle().contains(x,z));}
    public static List<PlotRect> roadRects(String world){var current=service;return current==null?List.of():current.roads().stream().filter(r->r.world().equals(world)).map(GuildRoadRegistry.Road::rectangle).toList();}
    public static void beforePack(World world,HubPlotRecord plot)throws Exception{var current=service;if(current!=null)current.beforePack(world,plot);}
    static GuildRoadService service(){return Objects.requireNonNull(service,"Guild roads are unavailable");}
    static ScheduledFuture<?> previewTimer(Runnable action){return timer.scheduleWithFixedDelay(action,0,600,TimeUnit.MILLISECONDS);}
    public static void open(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        try{
            var current=service();UUID guild=current.membership(player.getUuid());var nativeWorld=store.getExternalData().getWorld();String world=nativeWorld.getName();var choices=new ArrayList<ChoicePage.Choice>();
            var feet=store.getComponent(ref,TransformComponent.getComponentType()).getPosition();var personal=current.personalPlot(nativeWorld,player.getUuid(),(int)Math.floor(feet.x),(int)Math.floor(feet.z));boolean manage=current.canManage(player.getUuid(),guild);
            if(personal!=null&&personal.getAttachedGuildUuid().equals(guild)){
                boolean allow=!current.easement(personal);
                choices.add(new ChoicePage.Choice("Road easement on your plot: "+(allow?"not granted":"granted"),allow?"Grant":"Revoke",(r,s)->ChoicePage.open(r,s,player,allow?"Allow guild roads here?":"Revoke road easement?",allow?"Guild road builders may create checked road segments on this plot. You can remove intersecting segments and revoke this permission later.":"Remove intersecting road segments first. Revoking blocks other guild members from building new roads here.",List.of(new ChoicePage.Choice(allow?"Grant this guild an easement on this property":"Revoke this property's road easement","Confirm",(rr,ss)->{
                    try{current.setEasement(ss.getExternalData().getWorld(),player.getUuid(),personal.getPlotId(),allow);player.sendMessage(Message.raw(allow?"Guild road easement granted for this plot.":"Road easement revoked."));open(rr,ss,player);}catch(Exception e){error(player,e);}
                })))));
            }
            if(manage)for(var style:current.styles(guild))choices.add(new ChoicePage.Choice(style.name()+" · mark your feet as the start","Set start",(r,s)->{
                try{UUID now=current.guild(player.getUuid());if(!now.equals(guild))throw new IllegalStateException("Your guild changed");var p=s.getComponent(r,TransformComponent.getComponentType()).getPosition();starts.put(player.getUuid(),new Start(guild,s.getExternalData().getWorld().getName(),new GuildRoadPlanner.Cell((int)Math.floor(p.x),(int)Math.floor(p.z)),(int)Math.floor(p.y)-1,style.id(),Instant.now().plusSeconds(600)));player.sendMessage(Message.raw("Road start saved. Close this menu, walk in a straight line to the endpoint, then reopen Guild roads and preview."));}catch(Exception e){error(player,e);}
            }));
            Start marked=starts.get(player.getUuid());
            if(manage&&marked!=null&&marked.guild().equals(guild)&&marked.world().equals(world)&&Instant.now().isBefore(marked.expires()))choices.add(new ChoicePage.Choice("From "+marked.cell().x()+", "+marked.cell().z()+" to your current feet","Preview road",(r,s)->{
                try{if(!s.getExternalData().getWorld().getName().equals(marked.world())||Instant.now().isAfter(marked.expires()))throw new IllegalStateException("Road start expired or belongs to another world");var p=s.getComponent(r,TransformComponent.getComponentType()).getPosition();if((int)Math.floor(p.y)-1!=marked.ground())throw new IllegalStateException("Both endpoints must be at the same ground height");var preview=current.preview(s.getExternalData().getWorld(),player.getUuid(),marked.cell(),new GuildRoadPlanner.Cell((int)Math.floor(p.x),(int)Math.floor(p.z)),marked.ground(),marked.style());s.getComponent(r,Player.getComponentType()).getPageManager().openCustomPage(r,s,new GuildRoadPreviewPage(player,preview));}catch(Exception e){error(player,e);}
            }));
            for(var road:current.roads())if(road.world().equals(world)&&(manage&&road.guild().equals(guild)||personal!=null&&road.guild().equals(personal.getAttachedGuildUuid())&&road.rectangle().overlaps(com.hexvane.eterniamod.housing.NativeHousingChecks.rect(personal.getFootprint()))))choices.add(new ChoicePage.Choice("Road at "+road.rectangle().x()+", "+road.rectangle().z()+" · "+road.rectangle().area()+" blocks · "+road.state(),road.state()==GuildRoadRegistry.State.ACTIVE?"Remove":"Recover",(r,s)->{
                try{var preview=current.previewRemoval(s.getExternalData().getWorld(),player.getUuid(),road.id());s.getComponent(r,Player.getComponentType()).getPageManager().openCustomPage(r,s,new GuildRoadPreviewPage(player,preview));}catch(Exception e){error(player,e);}
            }));
            ChoicePage.open(ref,store,player,"Guild community roads","Straight, level segments up to 128 blocks. Other members must grant an easement before you build on their plot. Roads bridge at most five unclaimed blocks and remain five blocks from houses. Remove a segment before claiming land across it.",choices);
        }catch(Exception e){error(player,e);}
    }
    static void error(PlayerRef player,Exception error){player.sendMessage(Message.raw(error.getMessage()==null?"Guild road action could not finish":error.getMessage()));}
}
