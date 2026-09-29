package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.setup.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Every authoring command opens a review; only confirm commits the server-owned draft. */
public final class EterniaSetupCommand extends AbstractPlayerCommand {
    public EterniaSetupCommand() {
        super("setup","Set up this world's housing, roads, portals and Hub services in game");setPermissionGroups("hytale:WorldEditor");
        for(String name:new String[]{"world","arrival","corner","road","portal","remove","list","show","confirm"})addSubCommand(new Action(name));
    }
    @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world) {
        try{InfrastructureSetup.open(EterniaModPlugin.get(),ref,store,player);}catch(RuntimeException failure){context.sendMessage(Message.raw(failure.getMessage()));}
    }
    private static final class Action extends AbstractPlayerCommand {
        private final String action;private final RequiredArg<String> value;
        Action(String name){super(name,"Eternia setup "+name);action=name;setPermissionGroups("hytale:WorldEditor");value=switch(name){case "world"->withRequiredArg("role","hub-housing, hub, housing or adventure",ArgTypes.STRING);case "corner"->withRequiredArg("number","1 or 2 at the current player position",ArgTypes.STRING);case "road","portal","remove"->withRequiredArg("name","Stable lowercase area name",ArgTypes.STRING);default->null;};}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world) {
            try{
                SetupAccess.require(player.getUuid());var plugin=EterniaModPlugin.get();
                switch(action){
                    case "world"->InfrastructureSetup.role(plugin,ref,store,player,value.get(context));
                    case "arrival"->InfrastructureSetup.arrival(plugin,ref,store,player);
                    case "corner"->InfrastructureSetup.corner(ref,store,player,Integer.parseInt(value.get(context)));
                    case "road"->InfrastructureSetup.area(plugin,ref,store,player,HousingInfrastructure.AreaKind.ROAD,value.get(context));
                    case "portal"->InfrastructureSetup.area(plugin,ref,store,player,HousingInfrastructure.AreaKind.PORTAL,value.get(context));
                    case "remove"->InfrastructureSetup.remove(plugin,ref,store,player,value.get(context));
                    case "list"->InfrastructureSetup.list(plugin,ref,store,player);
                    case "show"->InfrastructureSetup.show(ref,store,player);
                    case "confirm"->InfrastructureSetup.confirm(plugin,ref,store,player);
                    default->throw new IllegalArgumentException("Unknown setup command");
                }
            }catch(RuntimeException failure){context.sendMessage(Message.raw(failure.getMessage()));}
        }
    }
}
