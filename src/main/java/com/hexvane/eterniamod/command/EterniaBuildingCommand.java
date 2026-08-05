package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.BuildingItemMetadata;
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

public final class EterniaBuildingCommand extends AbstractCommandCollection {
    public EterniaBuildingCommand() {
        super("building", "eterniamod_commands.commands.eternia.building.desc");
        this.setPermissionGroups("hytale:WorldEditor");
        this.addSubCommand(new GiveCommand());
    }

    private static final class GiveCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> buildingIdArg =
            this.withRequiredArg("buildingId", "eterniamod_commands.commands.eternia.building.give.buildingId", EterniaArgTypes.BUILDING_ID);
        private final OptionalArg<String> playerArg =
            this.withOptionalArg("player", "eterniamod_commands.commands.eternia.building.give.player", EterniaArgTypes.ONLINE_PLAYER_NAME);
        private final OptionalArg<Integer> amountArg =
            this.withOptionalArg("amount", "eterniamod_commands.commands.eternia.building.give.amount", ArgTypes.INTEGER);

        GiveCommand() {
            super("give", "eterniamod_commands.commands.eternia.building.give.desc");
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
            String buildingId = buildingIdArg.get(context).trim();
            BuildingDefinition def = plugin.getBuildingCatalog().get(buildingId);
            if (def == null) {
                playerRef.sendMessage(
                    Message.translation("eterniamod_commands.commands.eternia.building.give.unknownBuilding").param("id", buildingId)
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
            Player targetPlayer = targetRef.getStore().getComponent(targetRef, Player.getComponentType());
            if (targetPlayer == null) {
                return;
            }
            ItemStack base = new ItemStack(EterniaModConstants.BUILDING_ITEM_ID, 1);
            for (int i = 0; i < amount; i++) {
                ItemStack stack = BuildingItemMetadata.withBuilding(base, buildingId, def.getDisplayName(), target.getLanguage());
                targetPlayer.giveItem(stack, null, null);
            }
            playerRef.sendMessage(
                Message.translation("eterniamod_commands.commands.eternia.building.give.success")
                    .param("amount", amount)
                    .param("building", def.getDisplayName() != null ? def.getDisplayName() : buildingId)
                    .param("player", target.getUsername())
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
