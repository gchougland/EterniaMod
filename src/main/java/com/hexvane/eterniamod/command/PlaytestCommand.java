package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.PlaytestService;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.time.Instant;

/**
 * {@code /eternia admin playtest}: playtest settings, and the wipe between the alpha and the beta.
 * The wipe runs from the server console only, with nobody online, and the server must be stopped right after.
 */
final class PlaytestCommand extends AbstractCommandCollection {
    /** Typed in full to run the wipe, so it cannot happen by accident. */
    static final String CONFIRM = "WIPE-ALPHA";

    PlaytestCommand() {
        super("playtest", "Alpha and beta playtest settings and the beta wipe");
        addSubCommand(new Status());
        addSubCommand(new Preview());
        addSubCommand(new Wipe());
    }

    private static final class Status extends CommandBase {
        Status() { super("status", "Show the alpha setting and the starting allowance"); }
        @Override protected void executeSync(CommandContext context) {
            var plugin = EterniaModPlugin.get();
            var settings = plugin.getRuntime().playtest();
            context.sendMessage(Message.raw("Alpha: " + (settings.alpha() ? "on, everyone who joins is remembered as an alpha tester" : "off")));
            context.sendMessage(Message.raw("Starting allowance: " + settings.startingCrowns() + " Crowns and " + settings.startingCoins() + " Coins, given once per player"));
        }
    }

    private static final class Preview extends CommandBase {
        Preview() { super("preview", "Show what the beta wipe would remove, without changing anything"); }
        @Override protected void executeSync(CommandContext context) {
            var report = PlaytestService.preview(EterniaModPlugin.get().getRuntime().rawStore());
            context.sendMessage(Message.raw("The beta wipe would remove " + report.total() + " records and keep " + report.alphaTesters() + " alpha testers."));
            report.records().forEach((namespace, count) -> context.sendMessage(Message.raw("  " + namespace + ": " + count)));
            context.sendMessage(Message.raw("To run it: stop everyone joining, back up the database, then type in the console: eternia admin playtest wipe " + CONFIRM));
        }
    }

    private static final class Wipe extends CommandBase {
        private final RequiredArg<String> confirm = withRequiredArg("confirm", "Type " + CONFIRM + " to confirm", ArgTypes.STRING);
        Wipe() { super("wipe", "Wipe every player record for the beta, keeping only the alpha tester list (console only)"); }
        @Override protected void executeSync(CommandContext context) {
            if (context.sender() instanceof PlayerRef) { context.sendMessage(Message.raw("Run the beta wipe from the server console, not in game.")); return; }
            if (!CONFIRM.equals(confirm.get(context))) { context.sendMessage(Message.raw("Nothing was changed. Type exactly: eternia admin playtest wipe " + CONFIRM)); return; }
            int online = Universe.get().getPlayers().size();
            if (online > 0) { context.sendMessage(Message.raw("Nothing was changed: " + online + " player(s) are online. Everyone must be off the server first.")); return; }
            var plugin = EterniaModPlugin.get();
            // Joining is closed first, so nobody can write new records while the wipe runs or before the restart.
            plugin.getRuntime().closeForReset();
            final PlaytestService.Report report;
            try {
                report = PlaytestService.wipeForBeta(plugin.getRuntime().rawStore(), Instant.now());
            } catch (RuntimeException failure) {
                plugin.getLogger().atSevere().withCause(failure).log("Beta wipe failed; nothing was removed");
                context.sendMessage(Message.raw("The wipe failed and nothing was removed. The server stays closed to players; restart it. See the log for details."));
                return;
            }
            plugin.getLogger().atWarning().log("Beta wipe removed %d records; %d alpha testers kept", report.total(), report.alphaTesters());
            context.sendMessage(Message.raw("Done: removed " + report.total() + " records and kept " + report.alphaTesters() + " alpha testers."));
            context.sendMessage(Message.raw("Players cannot join until the server restarts. Stop the server now and carry on with the alpha to beta guide."));
        }
    }
}
