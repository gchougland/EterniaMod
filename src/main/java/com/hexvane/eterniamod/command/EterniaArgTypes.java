package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class EterniaArgTypes {
    private EterniaArgTypes() {}

    public static final SingleArgumentType<String> ONLINE_PLAYER_NAME =
        new SingleArgumentType<>(
            "eterniamod_commands.commands.eternia.args.onlinePlayerName.name",
            "eterniamod_commands.commands.eternia.args.onlinePlayerName.usage",
            "PlayerName"
        ) {
            @Override
            public String parse(@Nonnull String input, @Nonnull ParseResult parseResult) {
                return input;
            }

            @Override
            public void suggest(
                @Nonnull CommandSender sender,
                @Nonnull String textAlreadyEntered,
                int numParametersTyped,
                @Nonnull SuggestionResult result
            ) {
                World world = EterniaCommandSuggest.playerWorld(sender);
                if (world != null) {
                    suggestPlayersInWorld(result, textAlreadyEntered, world);
                    return;
                }
                for (World loaded : Universe.get().getWorlds().values()) {
                    suggestPlayersInWorld(result, textAlreadyEntered, loaded);
                }
            }
        };

    public static final SingleArgumentType<String> BUILDING_ID =
        new SingleArgumentType<>(
            "eterniamod_commands.commands.eternia.args.buildingId.name",
            "eterniamod_commands.commands.eternia.args.buildingId.usage",
            "hub_house"
        ) {
            @Override
            public String parse(@Nonnull String input, @Nonnull ParseResult parseResult) {
                return input;
            }

            @Override
            public void suggest(
                @Nonnull CommandSender sender,
                @Nonnull String textAlreadyEntered,
                int numParametersTyped,
                @Nonnull SuggestionResult result
            ) {
                EterniaModPlugin plugin = EterniaModPlugin.get();
                if (plugin == null) {
                    return;
                }
                EterniaCommandSuggest.suggestPrefix(result, textAlreadyEntered, plugin.getBuildingCatalog().ids());
            }
        };

    public static final SingleArgumentType<String> PLOT_ID =
        new SingleArgumentType<>(
            "eterniamod_commands.commands.eternia.args.plotId.name",
            "eterniamod_commands.commands.eternia.args.plotId.usage",
            "00000000-0000-0000-0000-000000000001"
        ) {
            @Override
            public String parse(@Nonnull String input, @Nonnull ParseResult parseResult) {
                return input;
            }

            @Override
            public void suggest(
                @Nonnull CommandSender sender,
                @Nonnull String textAlreadyEntered,
                int numParametersTyped,
                @Nonnull SuggestionResult result
            ) {
                EterniaModPlugin plugin = EterniaModPlugin.get();
                World world = EterniaCommandSuggest.playerWorld(sender);
                if (plugin == null || world == null) {
                    return;
                }
                HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
                List<String> ids = new ArrayList<>();
                for (HubPlotRecord plot : manager.listPlots()) {
                    ids.add(plot.getPlotId().toString());
                }
                EterniaCommandSuggest.suggestPrefix(result, textAlreadyEntered, ids);
            }
        };

    public static final SingleArgumentType<Integer> PLOT_SIZE =
        new SingleArgumentType<>(
            "eterniamod_commands.commands.eternia.args.plotSize.name",
            "eterniamod_commands.commands.eternia.args.plotSize.usage",
            "24"
        ) {
            @Override
            public Integer parse(@Nonnull String input, @Nonnull ParseResult parseResult) {
                try {
                    return Integer.parseInt(input.trim());
                } catch (NumberFormatException e) {
                    parseResult.fail(Message.translation("eterniamod_commands.commands.eternia.args.plotSize.invalid"));
                    return 0;
                }
            }

            @Override
            public void suggest(
                @Nonnull CommandSender sender,
                @Nonnull String textAlreadyEntered,
                int numParametersTyped,
                @Nonnull SuggestionResult result
            ) {
                EterniaCommandSuggest.suggestPrefix(result, textAlreadyEntered, java.util.List.of("16", "24", "32"));
            }
        };

    private static void suggestPlayersInWorld(
        @Nonnull SuggestionResult result,
        @Nullable String partial,
        @Nonnull World world
    ) {
        for (PlayerRef player : world.getPlayerRefs()) {
            EterniaCommandSuggest.suggestPrefix(result, partial, java.util.List.of(player.getUsername()));
        }
    }
}
