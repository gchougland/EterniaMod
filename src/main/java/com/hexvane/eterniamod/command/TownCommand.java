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
import java.util.List;

/** /e admin town place and undo: build the Eternia town on the land where you stand, or take it away (docs/implementation/town-placement.md). */
final class TownCommand extends AbstractCommandCollection {
    TownCommand(){super("town","Build the Eternia town on this land, or remove it");setPermissionGroups("hytale:WorldEditor");addSubCommand(new Place());addSubCommand(new Undo());}

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
    private static String message(Throwable e){while(e.getCause()!=null&&(e instanceof java.util.concurrent.CompletionException||e.getMessage()==null))e=e.getCause();return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
