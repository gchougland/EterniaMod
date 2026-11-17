package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * {@code /hub}: back to the Eternia Hub from anywhere. It takes the name of the engine's Creative Hub command (a builder
 * command for creative servers), on Gibson's request: registering a command under a taken name replaces it.
 */
public final class EterniaHubCommand extends AbstractPlayerCommand {
    public EterniaHubCommand() {
        super("hub", "Return to the Eternia Hub");
        setPermissionGroups("hytale:Adventurer");
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
        var plugin = EterniaModPlugin.get();
        if (plugin == null) return;
        plugin.getTravel().goToHub(player).ifPresent(problem -> player.sendMessage(Message.raw(problem)));
    }
}
