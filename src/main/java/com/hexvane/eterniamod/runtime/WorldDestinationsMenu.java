package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.ui.ChoicePage;
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
        if (!plugin.getTravel().canUseTravel(ref, store, player.getUuid())) {
            player.sendMessage(Message.raw("Choose a destination at a public portal or your unlocked home teleporter."));
            return;
        }
        String current = store.getExternalData().getWorld().getName();
        var choices = new ArrayList<ChoicePage.Choice>();
        plugin.getInfrastructure().worlds().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String id = entry.getKey();
            var destination = entry.getValue();
            if (id.equals(current) || destination.arrival() == null || Universe.get().getWorld(id) == null) return;
            choices.add(new ChoicePage.Choice(UiPresentation.friendlyId(id) + " · " + UiPresentation.friendlyId(destination.role()), "Travel", (r, s) -> {
                // The travel service rechecks access and availability when the player clicks.
                plugin.getTravel().selectWorld(player, id);
                var component = s.getComponent(r, Player.getComponentType());
                if (component != null) component.getPageManager().setPage(r, s, Page.None);
            }));
        });
        var component = store.getComponent(ref, Player.getComponentType());
        if (component != null) component.getPageManager().openCustomPage(ref, store,
            new ChoicePage(player, "Travel", "Choose where to go from this portal. You are in " + UiPresentation.friendlyId(current) + ".", choices));
    }
}
