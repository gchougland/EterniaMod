package com.hexvane.eterniamod.chat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * {@code /eternia chat} (alias {@code /e c}).
 * <ul>
 *   <li>{@code /e c}: your channel and the list of channels</li>
 *   <li>{@code /e c party} or {@code /e c p}: switch plain chat to Party</li>
 *   <li>{@code /e c p see you at the gate}: one message to Party without switching</li>
 * </ul>
 */
public final class EterniaChatCommand extends AbstractPlayerCommand {
    public EterniaChatCommand() {
        super("chat", "eternia_chat.command.desc");
        addAliases("c");
        setPermissionGroups("hytale:Adventurer");
        for (ChatChannel channel : ChatChannel.values()) addSubCommand(new ChannelCommand(channel));
        addSubCommand(new HelpCommand());
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
        status(player);
    }

    static void status(PlayerRef player) {
        var service = ChatService.get();
        if (service == null) player.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.unavailable")));
        else service.showStatus(player);
    }

    /** One channel: with a message it sends once, without one it switches. */
    private static final class ChannelCommand extends AbstractPlayerCommand {
        private final ChatChannel channel;
        private final RequiredArg<String> message;

        ChannelCommand(ChatChannel channel) {
            super(channel.id(), channel.langKey("command"));
            this.channel = channel;
            setPermissionGroups("hytale:Adventurer");
            addAliases(channel.aliases().toArray(String[]::new));
            message = withRequiredArg("message", "eternia_chat.command.message", ArgTypes.GREEDY_STRING);
            addUsageVariant(new AbstractPlayerCommand(channel.langKey("switch")) {
                @Override
                protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
                    var service = ChatService.get();
                    if (service == null) player.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.unavailable")));
                    else service.switchTo(player, channel);
                }
            });
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            var service = ChatService.get();
            if (service == null) player.sendMessage(ChatFormat.warn(Message.translation("eternia_chat.fail.unavailable")));
            else service.sendOnce(player, channel, message.get(context));
        }
    }

    private static final class HelpCommand extends AbstractPlayerCommand {
        HelpCommand() {
            super("help", "eternia_chat.command.help");
            setPermissionGroups("hytale:Adventurer");
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            status(player);
        }
    }
}
