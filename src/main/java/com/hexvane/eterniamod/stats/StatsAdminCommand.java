package com.hexvane.eterniamod.stats;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.command.EterniaArgTypes;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** {@code /eternia admin stats <player>}: a text summary. With no player: the server firsts and the leaders of each stat. */
public final class StatsAdminCommand extends AbstractAsyncCommand {
    private final RequiredArg<String> player = withRequiredArg("player", "eternia_stats.command.player", EterniaArgTypes.ONLINE_PLAYER_NAME);

    public StatsAdminCommand() {
        super("stats", "eternia_stats.admin.desc");
        addUsageVariant(new AbstractAsyncCommand("eternia_stats.admin.desc") {
            @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
                return run(context, runtime -> {
                    var stats = runtime.servicesStats();
                    var leaders = new LinkedHashMap<String, List<StatsService.Ranked>>();
                    for (String stat : StatsFields.RANKED) leaders.put(stat, stats.top(stat, 3));
                    return StatsSummary.server(stats.firsts(), leaders, runtime.bosses());
                });
            }
        });
    }

    @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
        String name = player.get(context).strip();
        return run(context, runtime -> {
            var plugin = EterniaModPlugin.get();
            UUID id = null; try { id = UUID.fromString(name); } catch (IllegalArgumentException ignored) {}
            var account = id == null ? plugin.getServices().accounts().findByName(name) : plugin.getServices().accounts().find(id);
            if (account.isEmpty()) return List.of("No player named " + name + " has visited Eternia yet.");
            var stats = runtime.servicesStats().player(account.get().id());
            if (stats.isEmpty()) return List.of(account.get().displayName() + " has no stats yet.");
            var firsts = runtime.servicesStats().firsts().stream().filter(f -> f.player().equals(stats.get().id())).toList();
            return StatsSummary.player(stats.get(), firsts, runtime.bosses());
        });
    }

    private static CompletableFuture<Void> run(CommandContext context, java.util.function.Function<StatsRuntime, List<String>> work) {
        var runtime = StatsRuntime.active();
        if (runtime == null) { context.sendMessage(Message.translation("eternia_stats.command.notReady")); return CompletableFuture.completedFuture(null); }
        return runtime.read(() -> work.apply(runtime)).handle((lines, failure) -> {
            if (failure != null) context.sendMessage(Message.raw("Stats could not be read: " + rootMessage(failure)));
            else lines.forEach(line -> context.sendMessage(Message.raw(line)));
            return null;
        });
    }

    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
