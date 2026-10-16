package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.ui.IllustratedMenuPage;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Travel picker opened by public portals and unlocked home teleporters. */
public final class WorldDestinationsMenu {
    private WorldDestinationsMenu() {}
    public static void open(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        open(plugin,ref,store,player,(r,s)->{});
    }
    static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,java.util.function.BiConsumer<Ref<EntityStore>,Store<EntityStore>> guard) {
        guard.accept(ref,store);
        if (!plugin.getTravel().canUseTravel(ref, store, player.getUuid())) {
            player.sendMessage(Message.raw("Stand at a public portal or inside your plot with Home Waygate unlocked."));
            return;
        }
        String current = store.getExternalData().getWorld().getName();
        var choices = new ArrayList<IllustratedMenuPage.Card>();
        plugin.getInfrastructure().worlds().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String id = entry.getKey();
            var destination = entry.getValue();
            if (id.equals(current) || destination.arrival() == null || Universe.get().getWorld(id) == null) return;
            choices.add(new IllustratedMenuPage.Card(UiPresentation.friendlyId(id), description(destination.role()), image(destination.role()), "Travel", (r, s) -> {
                guard.accept(r,s);
                // The travel service rechecks access and availability when the player clicks.
                plugin.getTravel().selectWorld(player, id);
                var component = s.getComponent(r, Player.getComponentType());
                if (component != null) component.getPageManager().setPage(r, s, Page.None);
            }));
        });
        var component = store.getComponent(ref, Player.getComponentType());
        if (component != null) IllustratedMenuPage.open(ref,store,player,"Travel","WHERE WILL YOU GO NEXT?","You are in " + UiPresentation.friendlyId(current) + ". Choose a destination to step through the Waygate.",choices);
    }
    private static String description(String role){return switch(role.toLowerCase(java.util.Locale.ROOT)){
        case "hub" -> "Meet your neighbors, visit the shops and return to the heart of Eternia.";
        case "housing" -> "Return to your neighborhood, decorate your home and visit friends.";
        case "adventure", "wilderness", "survival" -> "Explore the world, gather resources and make your next discovery.";
        default -> "Step into this world and see what awaits on the other side.";
    };}
    private static String image(String role){return "EterniaMod/Icons/"+switch(role.toLowerCase(java.util.Locale.ROOT)){
        case "hub" -> "guild";case "housing" -> "housing";case "adventure", "wilderness", "survival" -> "worlds";default -> "worlds";
    }+".png";}

}
