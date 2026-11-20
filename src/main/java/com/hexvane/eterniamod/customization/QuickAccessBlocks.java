package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.socialui.EterniaServicesPage.Desk;
import com.hexvane.eterniamod.socialui.EterniaServicesPage.Section;
import com.hexvane.eterniamod.socialui.SocialUiActions;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;

/**
 * The quick access blocks players buy for their plots: each opens one Hub service where it stands (mail, which is read
 * only at a mailbox, the Crown Store, buying season passes, the market, production, the guild page, the guild treasury). Who may
 * use one is decided before this runs ({@code HousingProtection}): personal ones by the plot owner, guild ones by any
 * member. World thread.
 */
public final class QuickAccessBlocks {
    private QuickAccessBlocks() {}

    /** True for the routes these blocks use. */
    public static boolean handles(String route) { return route.startsWith("QA_"); }

    public static void open(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, String route) {
        var services = plugin.getServices();
        var actions = plugin.getMenuActions();
        switch (route) {
            case "QA_MAIL" -> SocialUiBootstrap.openMailbox(ref, store, player);
            case "QA_STORE" -> actions.perform(SocialUiActions.Action.STORE_OPEN, ref, store, player);
            // The season desk itself, as at the Hub: buy passes here; the Journal only switches the active one.
            case "QA_SEASON" -> SocialUiBootstrap.openDesk(ref, store, player, services, Desk.GATEHOUSE, Section.SEASON, true);
            case "QA_SHOP" -> SocialUiBootstrap.open(ref, store, player, services, actions, Section.SHOP);
            case "QA_GUILD" -> SocialUiBootstrap.open(ref, store, player, services, actions, Section.GUILD);
            case "QA_PRODUCTION" -> {
                if (!com.hexvane.eterniamod.production.ProductionJournalPage.open(ref, store, player))
                    player.sendMessage(Message.raw("Production is not available right now."));
            }
            case "QA_TREASURY" -> ChoicePage.open(ref, store, player, "Guild Treasury", "Give your own Coins or Crowns to your guild. Gifts stay with the guild.",
                List.of(new ChoicePage.Choice("See and give the guild's Crowns, spent on more guild plots and hexagons", "Guild Crowns",
                        (r, s) -> com.hexvane.eterniamod.runtime.GuildCrownsMenu.open(plugin, r, s, player)),
                    new ChoicePage.Choice("See and give the guild's Coins, spent on guild land in adventure worlds", "Guild Coins",
                        (r, s) -> com.hexvane.eterniamod.wildland.GuildCoinsMenu.open(plugin, r, s, player))));
            default -> { }
        }
    }
}
