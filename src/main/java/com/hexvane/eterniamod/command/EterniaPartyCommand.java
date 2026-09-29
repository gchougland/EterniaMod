package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.*;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.*;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class EterniaPartyCommand extends AbstractCommandCollection {
    public EterniaPartyCommand(){super("party","Create and manage an activity party");setPermissionGroups("hytale:Adventurer");for(String action:new String[]{"create","list","invite","accept","leave"})addSubCommand(new Action(action));}
    private static final class Action extends AbstractPlayerCommand {
        private final String action;private final RequiredArg<String> name;
        Action(String action){super(action,"Party "+action);this.action=action;name=action.equals("invite")||action.equals("accept")?withRequiredArg("player","Player name",ArgTypes.STRING):null;}
        protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            try{var services=EterniaModPlugin.get().getServices();var parties=services.parties();var target=name==null?null:services.accounts().findByName(name.get(context)).orElseThrow(()->new IllegalArgumentException("Player has not joined Eternia"));
                switch(action){
                    case "create"->{parties.create(player.getUuid());player.sendMessage(Message.raw("Party created. Use /e party invite <player>."));}
                    case "invite"->{parties.invite(player.getUuid(),target.id());var online=Universe.get().getPlayer(target.id());if(online!=null)online.sendMessage(Message.raw(player.getUsername()+" invited you to a party. Use /e party accept "+player.getUsername()+" within 15 minutes."));player.sendMessage(Message.raw("Party invitation sent."));}
                    case "accept"->{parties.accept(player.getUuid(),target.id());player.sendMessage(Message.raw("You joined the party."));}
                    case "leave"->{parties.leave(player.getUuid());player.sendMessage(Message.raw("You left the party."));}
                    default->{var party=parties.find(player.getUuid()).orElseThrow(()->new IllegalArgumentException("You have no party. Use /e party create."));for(var id:party.members()){String label=services.accounts().find(id).map(a->a.displayName()).orElse(id.toString());player.sendMessage(Message.raw(label+(id.equals(party.leader())?" · Leader":"")+(Universe.get().getPlayer(id)==null?" · Offline":" · Online")));}}
                }
            }catch(RuntimeException failure){player.sendMessage(Message.raw(failure.getMessage()==null?"Party action could not finish.":failure.getMessage()));}
        }
    }
}

