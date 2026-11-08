package com.hexvane.eterniamod.stats;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.command.EterniaArgTypes;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** {@code /eternia stats} opens your own records; {@code /eternia stats <player>} anyone's who has visited Eternia. */
public final class StatsCommand extends AbstractPlayerCommand {
    private final RequiredArg<String> player = withRequiredArg("player", "eternia_stats.command.player", EterniaArgTypes.ONLINE_PLAYER_NAME);

    public StatsCommand() {
        super("stats", "eternia_stats.command.desc");
        setPermissionGroups("hytale:Adventurer");
        addUsageVariant(new AbstractPlayerCommand("eternia_stats.command.desc") {
            @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef self, World world) {
                StatsPage.open(ref, store, self, self.getUuid(), self.getUsername());
            }
        });
    }

    @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef self, World world) {
        String name = player.get(context).strip();
        if (name.equalsIgnoreCase(self.getUsername()) || name.equalsIgnoreCase("me")) { StatsPage.open(ref, store, self, self.getUuid(), self.getUsername()); return; }
        var runtime = StatsRuntime.active(); var plugin = EterniaModPlugin.get();
        if (runtime == null || plugin == null) { self.sendMessage(Message.translation("eternia_stats.command.notReady")); return; }
        runtime.read(() -> plugin.getServices().accounts().findByName(name)).whenComplete((account, failure) -> {
            try {
                world.execute(() -> {
                    if (!ref.isValid()) return;
                    if (failure != null) self.sendMessage(Message.translation("eternia_stats.command.failed"));
                    else if (account.isEmpty()) self.sendMessage(Message.translation("eternia_stats.command.notFound").param("player", name));
                    else StatsPage.open(ref, store, self, account.get().id(), account.get().displayName());
                });
            } catch (RuntimeException closing) { /* The world is stopping. */ }
        });
    }
}
