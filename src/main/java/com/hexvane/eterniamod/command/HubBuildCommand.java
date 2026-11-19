package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.customization.HubBuildMode;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

final class HubBuildCommand extends AbstractPlayerCommand {
    private final Boolean mode;
    /** Under {@code /e admin} for world editors, and at {@code /e build} for the Builder rank too. */
    HubBuildCommand(String... groups) {
        super("build","Turn building on or off in the Hub and housing worlds"); mode=null;setPermissionGroups(groups);
        addSubCommand(new HubBuildCommand("on",true,groups));addSubCommand(new HubBuildCommand("off",false,groups));
    }
    private HubBuildCommand(String name,boolean mode,String... groups) {
        super(name,"Turn your building mode "+name);this.mode=mode;setPermissionGroups(groups);
    }
    @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world) {
        try {
            var plugin=EterniaModPlugin.get();
            boolean enabled=mode!=null?mode:!HubBuildMode.enabled(plugin,player.getUuid(),world);
            HubBuildMode.set(plugin,player,world,enabled);
            context.sendMessage(Message.raw(enabled
                ?"Building is on for you. Place and break blocks on unclaimed ground and in public plazas. Homes, roads and service areas stay protected. Leaving the world or signing out turns it off."
                :"Building is off for you."));
        } catch (RuntimeException failure) { context.sendMessage(Message.raw(failure.getMessage())); }
    }
}
