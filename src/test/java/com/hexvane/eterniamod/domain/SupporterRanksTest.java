package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.ranks.Rank;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupporterRanksTest {
    private static Set<String> owns(String... content) { return Set.of(content); }

    @Test void ladderStepsChainFromNothing() {
        assertEquals(List.of(), SupporterRanks.held(owns()));
        assertEquals(List.of("wanderer", "starborn", "celestial"), SupporterRanks.held(owns(SupporterRanks.step("none", "celestial"))),
            "buying Celestial outright also holds the ranks below it");
        assertEquals(List.of("wanderer", "starborn", "celestial", "cosmic"),
            SupporterRanks.held(owns(SupporterRanks.step("none", "wanderer"), SupporterRanks.step("wanderer", "celestial"), SupporterRanks.step("celestial", "cosmic"))));
    }

    @Test void anUpgradeWithoutItsBaseWaits() {
        assertEquals(List.of(), SupporterRanks.held(owns(SupporterRanks.step("starborn", "celestial"))));
        assertEquals(List.of("wanderer"), SupporterRanks.held(owns(SupporterRanks.step("none", "wanderer"), SupporterRanks.step("starborn", "celestial"))),
            "an upgrade from a rank the player does not have gives nothing");
    }

    @Test void founderAndEternalSitBesideTheLadder() {
        assertEquals(List.of("wanderer", "starborn", "founder", "eternal"),
            SupporterRanks.held(owns(SupporterRanks.step("none", "starborn"), SupporterRanks.content("founder"), SupporterRanks.content("eternal"))));
    }

    @Test void stepsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> SupporterRanks.step("celestial", "starborn"));
        assertThrows(IllegalArgumentException.class, () -> SupporterRanks.step("none", "founder"));
    }

    @Test void xpBonusTakesTheBestLadderRankAndFounderAndEternalAdd() {
        assertEquals(0, SupporterRanks.xpBonusPercent(List.of()));
        assertEquals(30, SupporterRanks.xpBonusPercent(List.of("wanderer", "starborn", "celestial", "cosmic")));
        assertEquals(80, SupporterRanks.xpBonusPercent(List.of("cosmic", "eternal")));
        assertEquals(100, SupporterRanks.xpBonusPercent(List.of("wanderer", "starborn", "celestial", "cosmic", "founder", "eternal")), "double XP with everything");
        assertEquals(50, SupporterRanks.xpBonusPercent(List.of("eternal")));
        assertEquals(50, SupporterRanks.xpBonusPercent(List.of("cosmic", "founder")));
        assertEquals(70, SupporterRanks.xpBonusPercent(List.of("eternal", "founder")));
        assertEquals(20, SupporterRanks.xpBonusPercent(List.of("founder")));
    }

    @Test void everySupporterRankIsARankWithAMatchingTitle() {
        for (String id : SupporterRanks.ALL) {
            var rank = Rank.find(id).orElseThrow();
            assertEquals(Rank.Kind.SUPPORTER, rank.kind(), id);
            assertEquals(rank.titleId(), SupporterRanks.titleId(id));
        }
    }

    // ---------------------------------------------------------------------------------------------------- purchases

    private record World(EterniaServices s, UUID player) {
        void product(String pkg, String content, boolean subscription) {
            s.commerce().register(new CommerceService.Product(pkg, 1, pkg, List.of(new CommerceService.Benefit(content, OwnershipService.Kind.UNLOCK, 1, subscription)), subscription));
        }
        CommerceService.Purchase buy(String pkg, String payment) {
            return s.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex", payment, "line", pkg, 1, Owner.player(player), 1, "", null, null));
        }
        Set<String> ranks() { return s.ranks().ranks(player); }
    }
    private static World world() { return world(RankRewards.load()); }
    private static World world(RankRewards rewards) {
        var s = new EterniaServices(new InMemoryStore(), java.time.Clock.systemUTC(), UUID::randomUUID, rewards); UUID p = UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(p, "Supporter");
        var w = new World(s, p);
        w.product("1", SupporterRanks.step("none", "starborn"), false);
        w.product("2", SupporterRanks.step("starborn", "celestial"), false);
        w.product("3", SupporterRanks.content("founder"), false);
        return w;
    }

    @Test void aDeliveredPurchaseGivesTheRankAndItsTitleAtOnce() {
        var w = world();
        assertEquals("DELIVERED", w.buy("1", "pay-1").state());
        assertEquals(Set.of("wanderer", "starborn"), w.ranks());
        assertTrue(w.s().ownership().owns(Owner.player(w.player()), SupporterRanks.titleId("starborn")));
        w.buy("2", "pay-2");
        assertEquals(Set.of("wanderer", "starborn", "celestial"), w.ranks());
    }

    @Test void refundingTheBaseTakesTheUpgradeDownToo() {
        var w = world();
        var base = w.buy("1", "pay-1"); w.buy("2", "pay-2");
        w.s().commerce().reverse(base.id(), "refund-1", "refund");
        assertEquals(Set.of(), w.ranks());
        assertFalse(w.s().ownership().owns(Owner.player(w.player()), SupporterRanks.titleId("celestial")));
    }

    @Test void theSyncNeverTakesAHandGivenRank() {
        var w = world();
        w.s().ranks().give(w.player(), "founder", SupporterRanks.titleId("founder"));
        var paid = w.buy("3", "pay-3");
        w.s().commerce().reverse(paid.id(), "refund-3", "refund");
        assertEquals(Set.of("founder"), w.ranks(), "Founder given by hand stays after a refunded Founder purchase");
        assertEquals(List.of(), w.s().ranks().paid(w.player()));
    }

    @Test void eternalLastsOnlyWhileItsSubscriptionIsPaid() {
        var w = world();
        w.product("9", SupporterRanks.content("eternal"), true);
        Instant start = Instant.now().minusSeconds(3600);
        w.s().commerce().fulfill(new CommerceService.VerifiedPurchase("tebex", "sub-pay", "line", "9", 1, Owner.player(w.player()), 1, "sub-1", start, start.plusSeconds(86400)));
        assertTrue(w.ranks().contains("eternal"));
        w.s().commerce().applySubscriptionControl("tebex", "sub-1", "ENDED", Instant.now(), "end-1");
        assertFalse(w.ranks().contains("eternal"));
    }

    @Test void ranksAddToSeasonPassXp() {
        var w = world();
        w.s().seasons().register(new SeasonService.Definition("eternia:test", "Test pass", List.of(1000L), List.of(), List.of()));
        w.s().seasons().select(w.player(), "eternia:test");
        record(w, 100);
        assertEquals(100, xp(w));
        w.buy("2", "pay-2"); w.buy("1", "pay-1"); // Celestial, 20%, then Founder adds 20% more
        w.buy("3", "pay-3");
        record(w, 100);
        assertEquals(240, xp(w));
    }
    private static void record(World w, long xp) {
        w.s().seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(), w.player(), SeasonService.ActivityKind.MINE, "Iron", 1, xp, UUID.randomUUID().toString(), true));
    }
    private static long xp(World w) { return w.s().seasons().progress(w.player()).stream().filter(p -> p.id().equals("eternia:test")).findFirst().orElseThrow().xp(); }

    // ---------------------------------------------------------------------------------------------------- rewards

    @Test void theBundledRewardsMatchTheSheet() {
        var rewards = RankRewards.load();
        for (String rank : SupporterRanks.ALL) assertFalse(rewards.of(rank).isEmpty(), rank);
        assertEquals(3, rewards.of("eternal").stream().filter(r -> r.slot().startsWith("cosmetic")).count());
        assertEquals(2, rewards.of("eternal").stream().filter(r -> r.content().equals(RankRewards.PLOT)).findFirst().orElseThrow().quantity());
        assertTrue(rewards.of("wanderer").stream().noneMatch(r -> r.content().startsWith("eternia:voucher/")), "Wanderer gives no plot");
        assertTrue(rewards.of("cosmic").stream().anyMatch(r -> r.content().equals(RankRewards.GUILD_PLOT)));
    }

    @Test void buyingFounderGivesItsTitlesAndVouchersOnce() {
        var w = world();
        var founder = w.buy("3", "pay-3");
        Owner me = Owner.player(w.player());
        var own = w.s().ownership();
        assertTrue(own.owns(me, "eternia:title/founding"));
        assertTrue(own.owns(me, "eternia:title/member"));
        assertEquals(1, own.available(me, RankRewards.PLOT));
        assertEquals(2, own.available(me, RankRewards.HOUSE_HEX));
        assertEquals(2, own.available(me, RankRewards.PRODUCTION_HEX));
        w.s().welcomePlayer(w.player(), "Supporter");
        assertEquals(1, own.available(me, RankRewards.PLOT), "logging in again gives nothing twice");
        w.s().commerce().reverse(founder.id(), "refund-3", "refund");
        assertFalse(own.owns(me, "eternia:title/founding"), "a refund takes the reward items back");
        assertEquals(0, own.available(me, RankRewards.HOUSE_HEX), "and the unused vouchers");
        w.buy("3", "pay-3-again");
        assertTrue(own.owns(me, "eternia:title/founding"), "buying again gives them again");
        assertEquals(1, own.available(me, RankRewards.PLOT));
    }

    @Test void buyingAHighRankOutrightAlsoGivesTheLowerRanksRewards() {
        var w = world();
        w.buy("1", "pay-1"); // Starborn outright: Wanderer and Starborn sets
        assertEquals(1, w.s().ownership().available(Owner.player(w.player()), RankRewards.PLOT), "Starborn's plot voucher; Wanderer has none");
    }

    @Test void aSlotFilledInLaterReachesEarlierBuyers() {
        var s = new EterniaServices(new InMemoryStore()); UUID p = UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(p, "Early");
        var blank = new RankRewards(Map.of("wanderer", List.of(new RankRewards.Reward("pet", "", OwnershipService.Kind.QUANTITY, 1))));
        var filled = new RankRewards(Map.of("wanderer", List.of(new RankRewards.Reward("pet", "eternia:pet/corn_buddy", OwnershipService.Kind.QUANTITY, 1))));
        new RankService(s.store(), java.time.Clock.systemUTC(), UUID::randomUUID, s.ownership(), blank).give(p, "wanderer", SupporterRanks.titleId("wanderer"));
        assertEquals(0, s.ownership().available(Owner.player(p), "eternia:pet/corn_buddy"));
        var later = new RankService(s.store(), java.time.Clock.systemUTC(), UUID::randomUUID, s.ownership(), filled);
        later.syncPaid(p); later.syncPaid(p);
        assertEquals(1, s.ownership().available(Owner.player(p), "eternia:pet/corn_buddy"), "given once when the sheet names it");
    }

    @Test void refundingAnUpgradeTakesBackOnlyThatRanksRewards() {
        var w = world();
        Owner me = Owner.player(w.player());
        w.buy("1", "pay-1");
        var upgrade = w.buy("2", "pay-2");
        assertEquals(2, w.s().ownership().available(me, RankRewards.PLOT), "Starborn's and Celestial's plot vouchers");
        w.s().commerce().reverse(upgrade.id(), "refund-2", "refund");
        assertEquals(Set.of("wanderer", "starborn"), w.ranks());
        assertEquals(1, w.s().ownership().available(me, RankRewards.PLOT), "Starborn's voucher stays");
    }

    @Test void eternalEndingKeepsItsRewardsButARefundTakesThem() {
        var w = world();
        Owner me = Owner.player(w.player());
        w.product("9", SupporterRanks.content("eternal"), true);
        Instant start = Instant.now().minusSeconds(3600);
        var first = w.s().commerce().fulfill(new CommerceService.VerifiedPurchase("tebex", "sub-pay", "line", "9", 1, me, 1, "sub-1", start, start.plusSeconds(86400)));
        assertEquals(2, w.s().ownership().available(me, RankRewards.PLOT));
        w.s().commerce().applySubscriptionControl("tebex", "sub-1", "ENDED", Instant.now(), "end-1");
        assertFalse(w.ranks().contains("eternal"));
        assertEquals(2, w.s().ownership().available(me, RankRewards.PLOT), "the subscription ending is not a refund");
        w.s().commerce().reverse(first.id(), "refund-sub", "chargeback");
        assertEquals(0, w.s().ownership().available(me, RankRewards.PLOT), "a chargeback of the payment that gave them takes them back");
    }

    @Test void eternalPetAndTitlesNeedItActiveAndTheThreeMonthRewardWaits() {
        // The world runs this reward set and no other: one rank service, as the game has (a second one beside the
        // bundled set shared its while active rounds and missed its own grants once the bundled Eternal pet was filled).
        var custom = new RankRewards(Map.of("eternal", List.of(
            new RankRewards.Reward("pet", "eternia:pet/corn_buddy", OwnershipService.Kind.QUANTITY, 1, true, 1),
            new RankRewards.Reward("title1", "eternia:title/wayfarer", OwnershipService.Kind.UNLOCK, 1, true, 1),
            new RankRewards.Reward("cosmetic1", "eternia:wearable/test_cloak", OwnershipService.Kind.UNLOCK, 1, false, 1),
            new RankRewards.Reward("three", "eternia:voucher/test_three", OwnershipService.Kind.QUANTITY, 1, false, 3))));
        var w = world(custom);
        Owner me = Owner.player(w.player());
        var own = w.s().ownership();
        var ranks = w.s().ranks();
        w.product("9", SupporterRanks.content("eternal"), true);
        Instant start = Instant.now().minusSeconds(3600);
        java.util.function.BiConsumer<String, Integer> pay = (sub, month) -> {
            Instant from = start.plusSeconds(86400L * 30 * month);
            w.s().commerce().fulfill(new CommerceService.VerifiedPurchase("tebex", sub + "-pay-" + month, "line", "9", 1, me, 1, sub, from, from.plusSeconds(86400L * 30)));
            ranks.syncPaid(w.player());
        };
        pay.accept("sub-1", 0);
        assertEquals(1, own.available(me, "eternia:pet/corn_buddy"));
        assertTrue(own.owns(me, "eternia:title/wayfarer"));
        assertTrue(own.owns(me, "eternia:wearable/test_cloak"));
        assertEquals(0, own.available(me, "eternia:voucher/test_three"), "not before three paid months");
        w.s().commerce().applySubscriptionControl("tebex", "sub-1", "ENDED", Instant.now(), "end-1"); ranks.syncPaid(w.player());
        assertEquals(0, own.available(me, "eternia:pet/corn_buddy"), "the pet needs Eternal active");
        assertFalse(own.owns(me, "eternia:title/wayfarer"), "so do the titles");
        assertTrue(own.owns(me, "eternia:wearable/test_cloak"), "the rest stays after the first month");
        pay.accept("sub-2", 1);
        assertEquals(1, own.available(me, "eternia:pet/corn_buddy"), "coming back gives the pet again");
        assertTrue(own.owns(me, "eternia:title/wayfarer"));
        assertEquals(0, own.available(me, "eternia:voucher/test_three"), "two paid months");
        pay.accept("sub-2", 2);
        assertEquals(1, own.available(me, "eternia:voucher/test_three"), "the third paid month gives it");
        w.s().commerce().applySubscriptionControl("tebex", "sub-2", "ENDED", Instant.now().plusSeconds(86400L * 120), "end-2"); ranks.syncPaid(w.player());
        assertEquals(1, own.available(me, "eternia:voucher/test_three"), "and it is kept for good");
        assertEquals(0, own.available(me, "eternia:pet/corn_buddy"));
    }

    @Test void theLadderShowsTheSheetsNamesAndTakesThemInCommands() {
        assertEquals(List.of("Wanderer", "Settler", "Steward", "Elder"), SupporterRanks.LADDER.stream().map(id -> Rank.find(id).orElseThrow().displayName()).toList());
        assertEquals(Rank.STARBORN, Rank.find("settler").orElseThrow());
        assertEquals(Rank.COSMIC, Rank.find("Elder").orElseThrow());
        for (String rank : SupporterRanks.ALL) assertTrue(RankRewards.load().of(rank).stream().anyMatch(r -> r.slot().equals("house")), rank + " gives a house");
    }

    @Test void theBundledEternalSetFollowsProwlsRules() {
        var eternal = RankRewards.load().of("eternal");
        for (String slot : List.of("pet", "title1", "title2")) assertTrue(eternal.stream().filter(r -> r.slot().equals(slot)).findFirst().orElseThrow().whileActive(), slot);
        var guildPlot = eternal.stream().filter(r -> r.content().equals(RankRewards.GUILD_PLOT)).findFirst().orElseThrow();
        assertEquals(3, guildPlot.keepAfter(), "given at once, kept for good after three paid months");
        assertEquals(1, guildPlot.months());
        assertTrue(eternal.stream().filter(r -> r.content().equals(RankRewards.PLOT)).noneMatch(r -> r.whileActive() || r.months() > 1), "plot vouchers stay after the first month");
    }
}
