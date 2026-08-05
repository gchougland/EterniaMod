package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hexvane.eterniamod.prop.PropItemMetadata;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class EterniaPropCommand extends AbstractCommandCollection {
    public EterniaPropCommand() {
        super("prop", "eterniamod_commands.commands.eternia.prop.desc");
        this.setPermissionGroups("hytale:WorldEditor");
        this.addSubCommand(new GiveCommand());
        this.addSubCommand(new WandCommand());
    }

    private static final class GiveCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> propIdArg =
            this.withRequiredArg("propId", "eterniamod_commands.commands.eternia.prop.give.propId", EterniaArgTypes.PROP_ID);
        private final OptionalArg<String> playerArg =
            this.withOptionalArg("player", "eterniamod_commands.commands.eternia.prop.give.player", EterniaArgTypes.ONLINE_PLAYER_NAME);
        private final OptionalArg<Integer> amountArg =
            this.withOptionalArg("amount", "eterniamod_commands.commands.eternia.prop.give.amount", ArgTypes.INTEGER);

        GiveCommand() {
            super("give", "eterniamod_commands.commands.eternia.prop.give.desc");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) {
                return;
            }
            String propId = propIdArg.get(context).trim();
            PropDefinition def = plugin.getPropCatalog().get(propId);
            if (def == null) {
                playerRef.sendMessage(
                    Message.translation("eterniamod_commands.commands.eternia.prop.give.unknownProp").param("id", propId)
                );
                return;
            }
            PlayerRef target = playerRef;
            if (playerArg.provided(context)) {
                PlayerRef found = findPlayer(world, playerArg.get(context));
                if (found == null) {
                    playerRef.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.assign.playerNotFound"));
                    return;
                }
                target = found;
            }
            int amount = amountArg.provided(context) ? Math.max(1, Math.min(64, amountArg.get(context))) : 1;
            Ref<EntityStore> targetRef = target.getReference();
            if (targetRef == null) {
                return;
            }
            Store<EntityStore> targetStore = targetRef.getStore();
            Player targetPlayer = targetStore.getComponent(targetRef, Player.getComponentType());
            if (targetPlayer == null) {
                return;
            }
            ItemStack base = new ItemStack(EterniaModConstants.PROP_ITEM_ID, 1);
            for (int i = 0; i < amount; i++) {
                ItemStack stack = PropItemMetadata.withProp(base, propId, def.getDisplayName(), target.getLanguage());
                targetPlayer.giveItem(stack, targetRef, targetStore);
            }
            playerRef.sendMessage(
                Message.translation("eterniamod_commands.commands.eternia.prop.give.success")
                    .param("amount", amount)
                    .param("prop", def.getDisplayName() != null ? def.getDisplayName() : propId)
                    .param("player", target.getUsername())
            );
        }
    }

    private static final class WandCommand extends AbstractPlayerCommand {
        private final OptionalArg<String> playerArg =
            this.withOptionalArg("player", "eterniamod_commands.commands.eternia.prop.wand.player", EterniaArgTypes.ONLINE_PLAYER_NAME);

        WandCommand() {
            super("wand", "eterniamod_commands.commands.eternia.prop.wand.desc");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            PlayerRef target = playerRef;
            if (playerArg.provided(context)) {
                PlayerRef found = findPlayer(world, playerArg.get(context));
                if (found == null) {
                    playerRef.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.assign.playerNotFound"));
                    return;
                }
                target = found;
            }
            Ref<EntityStore> targetRef = target.getReference();
            if (targetRef == null) {
                return;
            }
            Store<EntityStore> targetStore = targetRef.getStore();
            Player targetPlayer = targetStore.getComponent(targetRef, Player.getComponentType());
            if (targetPlayer == null) {
                return;
            }
            targetPlayer.giveItem(new ItemStack(EterniaModConstants.PACKAGING_WAND_ID, 1), targetRef, targetStore);
            playerRef.sendMessage(
                Message.translation("eterniamod_commands.commands.eternia.prop.wand.success").param("player", target.getUsername())
            );
        }
    }

    @Nullable
    private static PlayerRef findPlayer(@Nonnull World world, @Nonnull String username) {
        String wanted = username.trim();
        for (PlayerRef player : world.getPlayerRefs()) {
            if (wanted.equalsIgnoreCase(player.getUsername())) {
                return player;
            }
        }
        return null;
    }
}
