package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.domain.GuildService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The guild's Coins, which pay for guild land in adventure worlds, and giving some of your own. */
public final class GuildCoinsMenu {
    private static final long[] AMOUNTS = {100, 500, 1000, 5000};

    private GuildCoinsMenu() {}

    public static void open(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        var services = plugin.getServices();
        UUID me = player.getUuid();
        GuildService.Guild guild;
        long guildCoins, myCoins;
        try {
            var standing = services.guilds().standing(me).orElse(null);
            if (standing == null) { player.sendMessage(Message.raw("Join a guild first.")); return; }
            guild = services.guilds().find(standing.guildId()).orElseThrow();
            guildCoins = services.economy().balance(Owner.guild(guild.id())).available();
            myCoins = services.economy().balance(Owner.player(me)).available();
        } catch (RuntimeException failure) {
            player.sendMessage(Message.raw("Guild Coins are not available right now."));
            return;
        }
        List<ChoicePage.Choice> choices = new ArrayList<>();
        for (long amount : AMOUNTS) {
            if (amount > myCoins) continue;
            choices.add(new ChoicePage.Choice("Give " + WildMapLogic.coins(amount) + " to " + guild.name() + ". They stay with the guild.", "Give",
                (r, s) -> give(plugin, r, s, player, amount)));
        }
        if (choices.isEmpty()) choices.add(new ChoicePage.Choice("You need at least " + WildMapLogic.coins(AMOUNTS[0]) + " to give.", "Close", (r, s) -> {}));
        ChoicePage.open(ref, store, player, "Guild Coins",
            guild.name() + " has " + WildMapLogic.coins(guildCoins) + ". You have " + WildMapLogic.coins(myCoins)
                + ".\nGuild Coins pay for guild land in adventure worlds. Members whose role may claim guild land spend them on the land map's Guild tab.", choices);
    }

    private static void give(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, long amount) {
        try {
            long now = plugin.getServices().wildLand().giveGuildCoins(player.getUuid(), amount, "ui:" + UUID.randomUUID());
            player.sendMessage(Message.raw("You gave " + WildMapLogic.coins(amount) + ". Your guild now has " + WildMapLogic.coins(now) + "."));
        } catch (DomainException refused) {
            player.sendMessage(Message.raw(refused.getMessage()));
        }
        open(plugin, ref, store, player);
    }
}
