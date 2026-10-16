package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ephemeral guild-only chat. Never falls back to public chat when membership or permissions change. */
public final class EterniaGuildChatCommand extends AbstractPlayerCommand {
    private final RequiredArg<String> message=withRequiredArg("message","Message for your online guild members",ArgTypes.GREEDY_STRING);
    private final ConcurrentHashMap<UUID,Long> lastSent=new ConcurrentHashMap<>();
    public EterniaGuildChatCommand(){super("guildchat","Talk privately to online members of your guild");setPermissionGroups("hytale:Adventurer");}
    @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef sender,World world){
        var plugin=EterniaModPlugin.get();if(plugin==null)return;
        final String text;
        try{text=GuildChatText.normalize(message.get(context));}catch(IllegalArgumentException invalid){sender.sendMessage(Message.raw(invalid.getMessage()));return;}
        var services=plugin.getServices();UUID actor=sender.getUuid();
        SocialUiBootstrap.supply(services,()->{
            if(Universe.get().getPlayer(actor)!=sender)return null;
            long now=System.nanoTime();if(lastSent.size()>=4096)lastSent.entrySet().removeIf(entry->now-entry.getValue()>60_000_000_000L);
            if(lastSent.size()>=4096&&!lastSent.containsKey(actor))throw new IllegalArgumentException("Guild chat is busy. Try again shortly.");
            lastSent.compute(actor,(id,previous)->{if(previous!=null&&now-previous<2_000_000_000L)throw new IllegalArgumentException("Wait two seconds between guild messages.");return now;});
            var delivery=services.batch(()->{
            var guild=services.guilds().membership(actor).orElseThrow(()->new IllegalArgumentException("Join a guild before using guild chat.")).guildId();
            if(!services.guilds().can(actor,guild,"guild.chat"))throw new IllegalArgumentException("Your guild role does not allow guild chat.");
                var members=services.guilds().roster(actor).stream().map(m->m.playerId()).filter(id->services.guilds().can(id,guild,"guild.chat")).toList();
                return new Delivery(services.guilds().find(guild).map(g->inline(g.name())).orElse("Guild"),members);
            });
            var formatted=Message.raw("["+delivery.name()+"] "+inline(sender.getUsername())+": "+text);
            for(var id:delivery.recipients()){
                if(!SocialUiBootstrap.isOpen(services)||Universe.get().getPlayer(actor)!=sender)break;
                var recipient=Universe.get().getPlayer(id);if(recipient!=null)recipient.sendMessage(formatted);
            }
            return null;
        }).exceptionally(error->{
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            if(Universe.get().getPlayer(actor)==sender)sender.sendMessage(Message.raw(cause instanceof DomainException||cause instanceof IllegalArgumentException?cause.getMessage():"Guild chat is unavailable. Try again shortly."));
            return null;
        });
    }
    private record Delivery(String name,java.util.List<UUID> recipients) {}
    private static String inline(String text){return text.replaceAll("[\\p{Cntrl}\\r\\n]"," ");}
}
