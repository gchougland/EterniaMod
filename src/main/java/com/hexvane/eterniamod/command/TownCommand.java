package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.town.TownBuilder;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hexvane.eterniamod.town.TownRoadNetwork;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import java.util.List;

/** /e admin town place and undo: build the Eternia town on the land where you stand, or take it away (docs/implementation/town-placement.md). */
final class TownCommand extends AbstractCommandCollection {
    TownCommand(){super("town","Build the Eternia town on this land, or remove it");setPermissionGroups("hytale:WorldEditor");addSubCommand(new Place());addSubCommand(new Undo());addSubCommand(new Roads());addSubCommand(new Portal());addSubCommand(new Rename());}

    private static final class Place extends AbstractPlayerCommand {
        Place(){super("place","Survey the land where you stand and build the town here after you confirm");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            try{SetupAccess.require(player.getUuid());}catch(RuntimeException e){player.sendMessage(Message.raw(e.getMessage()));return;}
            var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null){player.sendMessage(Message.raw("Your position is unavailable."));return;}
            var p=transform.getPosition();int x=(int)Math.floor(p.x),z=(int)Math.floor(p.z);var plugin=EterniaModPlugin.get();
            player.sendMessage(Message.raw("Planning the town around "+x+", "+z+". The square will be centred where you stand."));
            TownBuilder.preview(plugin,world,player.getUuid(),x,z,player).whenComplete((preview,failure)->world.execute(()->{
                if(failure!=null){player.sendMessage(Message.raw("The town cannot be built here: "+message(failure)));return;}
                if(!ref.isValid()){player.sendMessage(Message.raw(preview.summary()+" Run the command again to build it."));return;}
                ChoicePage.open(ref,store,player,"Build the town here?",preview.summary(),List.of(new ChoicePage.Choice("Build the Eternia town on this land","Build",(r,s)->{
                    try{TownBuilder.place(plugin,world,preview,player);player.sendMessage(Message.raw("Building the town. This takes a while; progress appears here."));}
                    catch(RuntimeException e){player.sendMessage(Message.raw(e.getMessage()));}
                })));
            }));
        }
    }
    private static final class Undo extends AbstractPlayerCommand {
        Undo(){super("undo","Remove the town placed in this world and put the land back");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            ChoicePage.open(ref,store,player,"Remove the town?","Removes the town's roads, NPCs, plaza, buildings and walls, and puts the land back as it was before the town was placed.",List.of(new ChoicePage.Choice("Remove the town and restore the land","Remove",(r,s)->{
                try{TownBuilder.undo(EterniaModPlugin.get(),world,player.getUuid(),player);player.sendMessage(Message.raw("Removing the town..."));}catch(RuntimeException e){player.sendMessage(Message.raw(e.getMessage()));}
            })));
        }
    }
    /** /e admin town roads grow and undo. */
    private static final class Roads extends AbstractCommandCollection {
        Roads(){super("roads","Grow a town's road network around the nearest plaza, or take one away");setPermissionGroups("hytale:WorldEditor");addSubCommand(new Grow());addSubCommand(new RoadsUndo());addSubCommand(new RoadsClear());addSubCommand(new RoadsRecover());}
    }
    private static final class Grow extends AbstractPlayerCommand {
        private final OptionalArg<Integer> radius=withOptionalArg("radius","How many hexagons out from the plaza the roads may reach (2 to 25, default 7)",ArgTypes.INTEGER);
        private final OptionalArg<Integer> lead=withOptionalArg("lead","When growing, how many free hexagons beside the roads to keep ready (default 12)",ArgTypes.INTEGER);
        Grow(){super("grow","Plan roads round the plaza you stand in or nearest, and build them after you confirm");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            plan(context,store,ref,player,world,TownRoadNetwork.Mode.PLAZA,radius.provided(context)?radius.get(context):TownRoadNetwork.DEFAULT_RADIUS,lead.provided(context)?lead.get(context):0);
        }
    }
    private static final class Portal extends AbstractPlayerCommand {
        private final OptionalArg<Integer> radius=withOptionalArg("radius","How many hexagons out from the platform the roads may reach (2 to 25, default 7)",ArgTypes.INTEGER);
        private final OptionalArg<Integer> lead=withOptionalArg("lead","When growing, how many free hexagons beside the roads to keep ready (default 12)",ArgTypes.INTEGER);
        Portal(){super("portal","Place a portal town where you stand: the spawn platform, a portal and roads round it");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            plan(context,store,ref,player,world,TownRoadNetwork.Mode.PORTAL,radius.provided(context)?radius.get(context):TownRoadNetwork.DEFAULT_RADIUS,lead.provided(context)?lead.get(context):0);
        }
    }
    /** /e admin town rename: names the portal town nearest to you, as its portal's travel menu shows it. */
    private static final class Rename extends AbstractPlayerCommand {
        private final com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg<String> name=withRequiredArg("name","The town's new name",ArgTypes.GREEDY_STRING);
        Rename(){super("rename","Rename the portal town nearest to you");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null){player.sendMessage(Message.raw("Your position is unavailable."));return;}
            var p=transform.getPosition();
            try{String old=TownRoadNetwork.rename(EterniaModPlugin.get(),world.getName(),player.getUuid(),(int)Math.floor(p.x),(int)Math.floor(p.z),name.get(context));
                player.sendMessage(Message.raw(old+" is now called "+name.get(context).strip()+"."));}
            catch(Exception e){player.sendMessage(Message.raw(message(e)));}
        }
    }
    private static void plan(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world,TownRoadNetwork.Mode mode,int radius,int lead){
        try{SetupAccess.require(player.getUuid());}catch(RuntimeException e){player.sendMessage(Message.raw(e.getMessage()));return;}
        var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null){player.sendMessage(Message.raw("Your position is unavailable."));return;}
        var p=transform.getPosition();int x=(int)Math.floor(p.x),z=(int)Math.floor(p.z);var plugin=EterniaModPlugin.get();
        player.sendMessage(Message.raw(mode==TownRoadNetwork.Mode.PORTAL?"Planning a portal town centred where you stand...":"Planning roads round the nearest plaza..."));
        TownRoadNetwork.preview(plugin,world,player.getUuid(),mode,x,z,radius,player).whenComplete((preview,failure)->world.execute(()->{
            if(failure!=null){player.sendMessage(Message.raw("The roads cannot be planned here: "+message(failure)));return;}
            if(!ref.isValid()){player.sendMessage(Message.raw(preview.summary()));return;}
            com.hexvane.eterniamod.town.TownRoadPlanPage.open(ref,store,player,world,preview,preview.picture(),lead);
        }));
    }
    private static final class RoadsClear extends AbstractPlayerCommand {
        RoadsClear(){super("clear","Take away the nearest town's roads but keep the town, so new roads can be grown");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null){player.sendMessage(Message.raw("Your position is unavailable."));return;}
            var p=transform.getPosition();int x=(int)Math.floor(p.x),z=(int)Math.floor(p.z);
            ChoicePage.open(ref,store,player,"Take away these roads?","Removes the roads and bridges of the town nearest to you and puts their land back. A portal town keeps its platform, portal and plaza. Grow new roads afterwards with /e admin town roads grow.",List.of(new ChoicePage.Choice("Take the roads away, keep the town","Clear",(r,s)->{
                try{TownRoadNetwork.undo(EterniaModPlugin.get(),world,player.getUuid(),x,z,player,true);player.sendMessage(Message.raw("Taking the roads away..."));}catch(RuntimeException e){player.sendMessage(Message.raw(e.getMessage()));}
            })));
        }
    }
    private static final class RoadsRecover extends AbstractPlayerCommand {
        RoadsRecover(){super("recover","Put back road work left unfinished in this world, which stops claims");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            player.sendMessage(Message.raw("Checking for unfinished road work..."));
            TownRoadNetwork.recoverUnfinished(EterniaModPlugin.get(),world,player.getUuid(),player);
        }
    }
    private static final class RoadsUndo extends AbstractPlayerCommand {
        RoadsUndo(){super("undo","Take away the town roads nearest to you, with their portal town if they had one");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null){player.sendMessage(Message.raw("Your position is unavailable."));return;}
            var p=transform.getPosition();int x=(int)Math.floor(p.x),z=(int)Math.floor(p.z);
            ChoicePage.open(ref,store,player,"Take away these town roads?","Removes the town roads built nearest to you and puts their ground back. A portal town loses its platform and portal too.",List.of(new ChoicePage.Choice("Take the roads away","Remove",(r,s)->{
                try{TownRoadNetwork.undo(EterniaModPlugin.get(),world,player.getUuid(),x,z,player);player.sendMessage(Message.raw("Taking the roads away..."));}catch(RuntimeException e){player.sendMessage(Message.raw(e.getMessage()));}
            })));
        }
    }
    private static String message(Throwable e){while(e.getCause()!=null&&(e instanceof java.util.concurrent.CompletionException||e.getMessage()==null))e=e.getCause();return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
