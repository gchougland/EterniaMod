package com.hexvane.eterniamod.ranks;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * {@code /eternia rank give|take|show}. Admins (and the console) give and take any rank; moderators give and take
 * Streamer. Staff ranks change the player's permission group; every rank gives or revokes its title.
 */
public final class RankCommand extends AbstractCommandCollection {
    private static final String TEAL = "#85c5b3", CORAL = "#e0907a", SAGE = "#b5c3b8";

    public RankCommand() {
        super("rank", "Give, take or show a player's ranks");
        setPermissionGroups(RankPermissions.MOD_GROUP);
        addSubCommand(new Change(true));
        addSubCommand(new Change(false));
        addSubCommand(new Show());
    }

    private static final class Change extends AbstractAsyncCommand {
        private final boolean give;
        private final RequiredArg<String> player = withRequiredArg("player", "Player name or ID", ArgTypes.STRING);
        private final RequiredArg<String> rank = withRequiredArg("rank", "admin, mod, builder, streamer, founder, wanderer, settler, steward, elder or eternal", ArgTypes.STRING);

        Change(boolean give) {
            super(give ? "give" : "take", give ? "Give a player a rank and its title" : "Take a rank and its title from a player");
            this.give = give;
            setPermissionGroups(RankPermissions.MOD_GROUP);
        }

        @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
            var chosen = Rank.find(rank.get(context)).orElse(null);
            if (chosen == null) {
                context.sendMessage(Message.raw("There is no rank called \"" + rank.get(context) + "\". Ranks: "
                    + Arrays.stream(Rank.values()).map(Rank::id).collect(Collectors.joining(", ")) + ".").color(CORAL));
                return CompletableFuture.completedFuture(null);
            }
            if (!allowed(context, chosen)) {
                context.sendMessage(Message.raw(chosen == Rank.STREAMER ? "Only moderators and admins can change the Streamer rank."
                    : "Only admins can change the " + chosen.displayName() + " rank.").color(CORAL));
                return CompletableFuture.completedFuture(null);
            }
            var plugin = EterniaModPlugin.get();
            if (plugin == null) return CompletableFuture.completedFuture(null);
            var services = plugin.getServices();
            String input = player.get(context);
            return SocialUiBootstrap.supply(services, () -> {
                var target = target(plugin, input);
                if (target == null) return List.of(Message.raw("No player called \"" + input + "\" has joined Eternia.").color(CORAL));
                boolean changed;
                if (chosen.staff()) {
                    boolean had = RankPermissions.staffRanks(target.id()).contains(chosen);
                    if (had != give) RankPermissions.setStaff(target.id(), chosen, give);
                    RankPermissions.syncTitles(services, target.id());
                    changed = had != give;
                } else {
                    changed = give ? services.ranks().give(target.id(), chosen.id(), chosen.titleId()) : services.ranks().take(target.id(), chosen.id());
                }
                plugin.getLogger().atInfo().log("Rank %s %s %s (%s) by %s", give ? "given:" : "taken:", chosen.id(), target.name(), target.id(), who(context));
                if (!changed) return List.of(Message.raw(target.name() + (give ? " already has " : " does not have ") + "the " + chosen.displayName() + " rank.").color(SAGE));
                return List.of(Message.raw(give
                    ? target.name() + " now has the " + chosen.displayName() + " rank. They can wear its title from their Collection."
                    : target.name() + " no longer has the " + chosen.displayName() + " rank.").color(TEAL));
            }).handle((messages, failure) -> {
                if (failure != null) {
                    plugin.getLogger().atWarning().withCause(failure).log("Rank change failed");
                    context.sendMessage(Message.raw("The rank could not be changed. Try again in a moment.").color(CORAL));
                } else messages.forEach(context::sendMessage);
                return null;
            });
        }
    }

    private static final class Show extends AbstractAsyncCommand {
        private final RequiredArg<String> player = withRequiredArg("player", "Player name or ID", ArgTypes.STRING);
        Show() { super("show", "Show a player's ranks"); setPermissionGroups(RankPermissions.MOD_GROUP); }

        @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
            var plugin = EterniaModPlugin.get();
            if (plugin == null) return CompletableFuture.completedFuture(null);
            String input = player.get(context);
            return SocialUiBootstrap.supply(plugin.getServices(), () -> {
                var target = target(plugin, input);
                if (target == null) return Message.raw("No player called \"" + input + "\" has joined Eternia.").color(CORAL);
                var names = new ArrayList<String>();
                for (Rank rank : RankPermissions.staffRanks(target.id())) names.add(rank.displayName());
                var held = plugin.getServices().ranks().ranks(target.id());
                for (Rank rank : Rank.values()) if (!rank.staff() && held.contains(rank.id())) names.add(rank.displayName());
                return Message.raw(target.name() + ": " + (names.isEmpty() ? "no ranks" : String.join(", ", names)) + ".").color(TEAL);
            }).thenAccept(context::sendMessage);
        }
    }

    /** The console and admins may change any rank; moderators may change Streamer. */
    static boolean allowed(CommandContext context, Rank rank) {
        if (!(context.sender() instanceof PlayerRef player)) return true;
        if (RankPermissions.has(player.getUuid(), RankPermissions.GRANT_ANY) || RankPermissions.staffRanks(player.getUuid()).contains(Rank.ADMIN)) return true;
        return rank == Rank.STREAMER && RankPermissions.has(player.getUuid(), RankPermissions.GRANT_STREAMER);
    }

    private static String who(CommandContext context) {
        return context.sender() instanceof PlayerRef player ? player.getUsername() : "Console";
    }

    private record Target(UUID id, String name) {}

    /** An online player by name, then any player who has joined, by name or ID. */
    private static Target target(EterniaModPlugin plugin, String input) {
        String text = input.trim();
        for (PlayerRef online : Universe.get().getPlayers()) if (online.getUsername().equalsIgnoreCase(text)) return new Target(online.getUuid(), online.getUsername());
        var accounts = plugin.getServices().accounts();
        try {
            UUID id = UUID.fromString(text);
            return accounts.find(id).map(account -> new Target(id, account.displayName())).orElse(null);
        } catch (IllegalArgumentException notAnId) {
            return accounts.findByName(text).map(account -> new Target(account.id(), account.displayName())).orElse(null);
        }
    }
}
