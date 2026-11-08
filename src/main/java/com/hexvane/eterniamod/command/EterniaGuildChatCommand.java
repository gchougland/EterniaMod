package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.chat.GuildChannel;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Ephemeral guild-only chat. Never falls back to public chat when membership or permissions change.
 * Delivery, cooldown and the shared chat look live in {@link GuildChannel}, which the Guild chat channel also uses. */
public final class EterniaGuildChatCommand extends AbstractPlayerCommand {
    private final RequiredArg<String> message=withRequiredArg("message","Message for your online guild members",ArgTypes.GREEDY_STRING);
    public EterniaGuildChatCommand(){super("guildchat","Talk privately to online members of your guild");setPermissionGroups("hytale:Adventurer");}
    @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef sender,World world){
        var plugin=EterniaModPlugin.get();if(plugin==null)return;
        final String text;
        try{text=GuildChatText.normalize(message.get(context));}catch(IllegalArgumentException invalid){sender.sendMessage(Message.raw(invalid.getMessage()));return;}
        GuildChannel.send(plugin.getServices(),sender,text);
    }
}
