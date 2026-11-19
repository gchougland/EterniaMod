package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.domain.GuildCrownService;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The guild's own Crowns: how many it has, the latest gifts and spending, and giving some of your own. Members whose
 * role may spend guild Crowns use them on more guild plots and hexagons from the Claim Map.
 */
final class GuildCrownsMenu {
    private static final long[] AMOUNTS = {100, 500, 1000, 2500};

    private GuildCrownsMenu() {}

    private record Snapshot(String guildName, long guildCrowns, long myCrowns, boolean maySpend, List<GuildCrownService.Entry> history) {}

    static void open(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        var services = plugin.getServices();
        UUID actor = player.getUuid();
        Snapshot snap;
        try {
            snap = services.batch(() -> {
                var membership = services.guilds().membership(actor).orElseThrow(() -> new IllegalStateException("Join a guild first."));
                var guild = services.guilds().find(membership.guildId()).orElseThrow();
                return new Snapshot(guild.name(), services.guildCrowns().balance(guild.id()), services.premium().balance(actor).available(),
                    services.guilds().can(actor, guild.id(), GuildCrownService.SPEND), services.guildCrowns().history(guild.id()));
            });
        } catch (RuntimeException failure) {
            player.sendMessage(Message.raw(failure.getMessage() == null ? "Guild Crowns are not available right now." : failure.getMessage()));
            return;
        }
        var choices = new ArrayList<ChoicePage.Choice>();
        for (long amount : AMOUNTS) {
            if (amount > snap.myCrowns()) continue;
            choices.add(new ChoicePage.Choice("Give " + crowns(amount) + " of your own to " + snap.guildName() + ". They stay with the guild.", "Give " + crowns(amount),
                (r, s) -> ChoicePage.open(r, s, player, "Give " + crowns(amount) + "?",
                    "Your " + crowns(amount) + " go to " + snap.guildName() + " for good. You cannot take them back, even if you leave the guild.",
                    List.of(new ChoicePage.Choice("Give them to the guild", "Confirm", (rr, ss) -> give(plugin, rr, ss, player, amount)),
                        new ChoicePage.Choice("Keep your Crowns", "Back", (rr, ss) -> open(plugin, rr, ss, player))))));
        }
        StringBuilder text = new StringBuilder(snap.guildName() + " has " + crowns(snap.guildCrowns()) + ". You have " + crowns(snap.myCrowns()) + ".\n");
        text.append(snap.maySpend() ? "Your role may spend them on more guild plots and hexagons from the Claim Map." : "Members with the Spend guild Crowns permission use them on more guild plots and hexagons.");
        int shown = 0;
        for (var entry : snap.history()) {
            if (shown++ == 4) break;
            String who = entry.member() == null ? "" : services.accounts().find(entry.member()).map(a -> a.displayName()).orElse("A member");
            text.append("\n").append(switch (entry.reason()) {
                case "gift" -> who + " gave " + crowns(entry.delta());
                case "purchase" -> who + " bought " + crowns(entry.delta()) + " for the guild";
                case "spend" -> who + " spent " + crowns(-entry.delta());
                case "reversed" -> "A store refund took back " + crowns(-entry.delta());
                default -> crowns(entry.delta()) + " came back from a sold hexagon";
            });
        }
        if (choices.isEmpty()) choices.add(new ChoicePage.Choice("You need at least " + crowns(AMOUNTS[0]) + " to give. Crowns can also be bought for the guild in the store.", "Close", (r, s) -> {}));
        ChoicePage.open(ref, store, player, "Guild Crowns", text.toString(), choices);
    }

    private static void give(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, long amount) {
        try {
            long now = plugin.getServices().guildCrowns().give(player.getUuid(), amount, "ui:" + UUID.randomUUID());
            player.sendMessage(Message.raw("You gave " + crowns(amount) + ". Your guild now has " + crowns(now) + "."));
        } catch (DomainException refused) {
            player.sendMessage(Message.raw(refused.getMessage()));
        }
        open(plugin, ref, store, player);
    }

    private static String crowns(long amount) {
        return String.format(Locale.ROOT, "%,d", amount) + (amount == 1 ? " Crown" : " Crowns");
    }
}
