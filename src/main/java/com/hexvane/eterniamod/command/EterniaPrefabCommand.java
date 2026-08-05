package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.ui.PrefabBrowserPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

public final class EterniaPrefabCommand extends AbstractCommandCollection {
    public EterniaPrefabCommand() {
        super("prefab", "eterniamod_commands.commands.eternia.prefab.desc");
        this.setPermissionGroups("hytale:WorldEditor");
        this.addSubCommand(new BrowseCommand());
    }

    private static final class BrowseCommand extends AbstractPlayerCommand {
        BrowseCommand() {
            super("browse", "eterniamod_commands.commands.eternia.prefab.browse.desc");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            player.getPageManager().openCustomPage(ref, store, new PrefabBrowserPage(playerRef));
        }
    }
}
