package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.ranks.RankPermissions;
import com.hexvane.eterniamod.runtime.TeleportRequests;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;

/** {@code /tpa}, {@code /tpask}, {@code /tpahere}, {@code /tpaccept} and {@code /tpdeny}: none of these names is a game command. */
public final class TeleportRequestCommands {
    private TeleportRequestCommands() {}

    public static List<AbstractPlayerCommand> all() {
        return List.of(new Ask("tpa", "Ask to teleport to a player", false), new Ask("tpask", "Ask to teleport to a player", false),
            new Ask("tpahere", "Ask a player to teleport to you", true), new Answer("tpaccept", "Accept a teleport request", true),
            new Answer("tpdeny", "Decline a teleport request", false));
    }

    private static final String[] EVERYONE = {"hytale:Adventurer", RankPermissions.MOD_GROUP, RankPermissions.BUILDER_GROUP};

    private static final class Ask extends AbstractPlayerCommand {
        private final boolean here;
        private final RequiredArg<String> player;
        Ask(String name, String description, boolean here) {
            super(name, description); this.here = here; setPermissionGroups(EVERYONE);
            player = withRequiredArg("player", "eterniamod_commands.commands.eternia.args.onlinePlayerName.usage", EterniaArgTypes.ONLINE_PLAYER_NAME);
        }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef me, World world) {
            var plugin = EterniaModPlugin.get(); if (plugin == null) return;
            TeleportRequests.ask(plugin, me, player.get(context), here);
        }
    }

    private static final class Answer extends AbstractPlayerCommand {
        private final boolean accept;
        private final OptionalArg<String> player;
        Answer(String name, String description, boolean accept) {
            super(name, description); this.accept = accept; setPermissionGroups(EVERYONE);
            player = withOptionalArg("player", "eterniamod_commands.commands.eternia.args.onlinePlayerName.usage", EterniaArgTypes.ONLINE_PLAYER_NAME);
        }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef me, World world) {
            var plugin = EterniaModPlugin.get(); if (plugin == null) return;
            TeleportRequests.answer(plugin, me, player.provided(context) ? player.get(context) : null, accept);
        }
    }
}
