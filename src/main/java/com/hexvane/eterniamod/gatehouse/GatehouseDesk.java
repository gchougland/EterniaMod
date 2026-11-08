package com.hexvane.eterniamod.gatehouse;

import com.hexvane.eterniamod.socialui.SeasonHall;
import com.hexvane.eterniamod.ui.CurrencyUi;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Fills GatehouseDesk.ui, Varek's desk home: the pass banners with claim and switch buttons, the bounty board and the
 * premium card. Routes it binds (handled by the services page): {@code gate_view:<pass>}, {@code gate_claim:<pass>},
 * {@code gate_activate:<pass>}, {@code gate_quests} and {@code external:STORE_OPEN}.
 */
public final class GatehouseDesk {
    private GatehouseDesk() {}

    public static final String DOCUMENT = "EterniaMod/GatehouseDesk.ui";

    public static void render(UICommandBuilder c, GatehouseBoard board, BiConsumer<String, String> bind, boolean busy, String status) {
        c.set("#GateGreeting.TextSpans", Message.raw(board.greeting()));
        c.set("#GateStatus.TextSpans", Message.raw(busy ? "Working…" : status));
        boolean any = !board.passes().isEmpty();
        c.set("#GatePasses.Visible", any);
        c.set("#GateEmpty.Visible", !any);
        c.set("#PassCount.TextSpans", Message.raw(passCount(board)));
        if (!any) c.set("#GateEmptyText.TextSpans", Message.raw("Varek will have the first pass ready the moment a season begins. Your quests and rewards start then."));
        for (int i = 0; i < board.passes().size(); i++) {
            SeasonHall.Banner pass = board.passes().get(i);
            String column = "#GatePasses[" + i + "]";
            c.append("#GatePasses", "EterniaMod/GatehousePass.ui");
            String banner = column + " #PassSlot[0]";
            c.append(column + " #PassSlot", "EterniaMod/SeasonBanner.ui");
            SeasonHall.fillBanner(c, banner, pass);
            if (!busy) bind.accept(banner, "gate_view:" + pass.id());
            boolean claim = pass.ready() > 0;
            c.set(column + " #PassClaim.Visible", claim);
            c.set(column + " #PassClaim.TextSpans", Message.raw(claim ? "Claim " + pass.ready() : "Claim"));
            c.set(column + " #PassClaim.Disabled", busy);
            if (claim && !busy) bind.accept(column + " #PassClaim", "gate_claim:" + pass.id());
            c.set(column + " #PassActivate.Visible", !pass.active());
            c.set(column + " #PassActivate.Disabled", busy);
            c.set(column + " #PassEarning.Visible", pass.active());
            if (!pass.active() && !busy) bind.accept(column + " #PassActivate", "gate_activate:" + pass.id());
        }
        bounties(c, board, bind, busy);
        premium(c, board, bind, busy);
    }

    static String passCount(GatehouseBoard board) {
        int count = board.passes().size();
        if (count == 0) return "";
        String owned = count == 1 ? "1 pass, yours to keep" : count + " passes, all yours to keep";
        return board.active().isPresent() ? owned + ". Only the active one earns XP." : owned + ". Choose one to earn XP.";
    }

    private static void bounties(UICommandBuilder c, GatehouseBoard board, BiConsumer<String, String> bind, boolean busy) {
        var bounties = board.bounties();
        c.set("#BountyAll.Visible", bounties != null);
        c.set("#BountyAll.Disabled", busy);
        c.set("#BountyOpen.TextSpans", Message.raw(bounties == null || bounties.size() == 0 ? "" : bounties.open() == 0 ? "All done for now" : bounties.open() + " open"));
        if (bounties == null) {
            c.set("#BountyPass.TextSpans", Message.raw(board.passes().isEmpty() ? "Closed until the first season" : "No active pass"));
            note(c, 0, board.passes().isEmpty() ? "Bounties are posted when a season opens."
                : "Make a pass active and its daily and weekly bounties appear here.");
            return;
        }
        c.set("#BountyPass.TextSpans", Message.raw(bounties.passName()));
        if (!busy) bind.accept("#BountyAll", "gate_quests");
        int row = 0;
        if (bounties.size() == 0) { note(c, row, "No bounties have been posted for this pass yet."); return; }
        row = group(c, row, "Daily", bounties.dailyReset(), bounties.daily());
        row = group(c, row, "Weekly", bounties.weeklyReset(), bounties.weekly());
        group(c, row, "Season", "Until you finish", bounties.lasting());
    }

    private static int group(UICommandBuilder c, int row, String name, String reset, List<GatehouseBoard.Bounty> list) {
        if (list.isEmpty()) return row;
        c.append("#BountyList", "EterniaMod/GatehouseBountyHead.ui");
        String head = "#BountyList[" + row++ + "]";
        c.set(head + " #BtyHeadName.TextSpans", Message.raw(name));
        c.set(head + " #BtyHeadReset.TextSpans", Message.raw(reset));
        for (var bounty : list) {
            c.append("#BountyList", "EterniaMod/GatehouseBounty.ui");
            String card = "#BountyList[" + row++ + "]";
            c.set(card + " #BtyName.TextSpans", Message.raw(bounty.name()));
            c.set(card + " #BtyGoal.TextSpans", Message.raw(bounty.goal()));
            c.set(card + " #BtyReward.TextSpans", Message.raw(bounty.done() ? "Done" : "+" + CurrencyUi.amount(bounty.xp()) + " XP"));
            c.set(card + " #BtyCoins.TextSpans", Message.raw(CurrencyUi.amount(bounty.coins())));
            c.set(card + " #BtyCount.TextSpans", Message.raw(bounty.count() + " / " + bounty.total()));
            bar(c, card + " #BtyFill", card + " #BtyRest", bounty.count(), bounty.total());
        }
        return row;
    }

    private static void note(UICommandBuilder c, int row, String text) {
        c.append("#BountyList", "EterniaMod/GatehouseBountyNote.ui");
        c.set("#BountyList[" + row + "] #BtyNoteText.TextSpans", Message.raw(text));
    }

    private static void premium(UICommandBuilder c, GatehouseBoard board, BiConsumer<String, String> bind, boolean busy) {
        var premium = board.premium();
        boolean offer = premium != null && !premium.owned();
        c.set("#PremiumTitle.TextSpans", Message.raw(premium != null && premium.owned() ? "Premium track is yours" : "Unlock the premium track"));
        String text = premium == null ? "Every season has a second row of rewards. It opens in the Crown Store with the season."
            : premium.owned() ? "Both reward rows of " + premium.passName() + " are yours. Thank you for supporting Eternia!"
            : "A second row of rewards on every level of " + premium.passName() + ", yours forever.";
        if (offer && !premium.saleNote().isEmpty()) text = premium.saleNote();
        c.set("#PremiumText.TextSpans", Message.raw(text));
        c.set("#PremiumBuy.Visible", offer);
        c.set("#PremiumBuy.TextSpans", Message.raw(offer && !premium.onSale() ? premium.closedLabel() : "Crown Store"));
        c.set("#PremiumBuy.Disabled", busy || offer && !premium.onSale());
        if (offer && premium.onSale() && !busy) bind.accept("#PremiumBuy", "external:STORE_OPEN");
    }

    private static void bar(UICommandBuilder c, String fill, String rest, long value, long total) {
        int amount = total <= 0 ? 0 : (int) Math.max(0, Math.min(1000, (double) value / total * 1000));
        c.set(fill + ".Visible", amount > 0);
        c.set(rest + ".Visible", amount < 1000);
        c.set(fill + ".FlexWeight", Math.max(1, amount));
        c.set(rest + ".FlexWeight", Math.max(1, 1000 - amount));
    }
}
