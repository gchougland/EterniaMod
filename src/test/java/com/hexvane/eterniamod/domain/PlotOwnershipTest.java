package com.hexvane.eterniamod.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.hexplots.PlotRules;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Prowl's plot design (2026-11-19): several plots per owner, their prices, refunds and guild Crowns. */
class PlotOwnershipTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    /** A long public road along row 0, so every cell of rows -1 and 1 sits beside it. */
    private static final Set<HexCoord> STREET = HexClaimRules.streetCells(L, List.of(new PlotRect(-2000, 15, 4000, 5)), List.of());
    /** One hexagon start shapes for everyone, so the test is about plots and Crowns rather than shapes. */
    private static final HexPlotSettings CROWNS = new HexPlotSettings(44, 28, singles(), 10, 22, 0, 0, 0, 24, 2, 5, 3, 500);

    private static java.util.Map<HexPlotSettings.StartKey, com.hexvane.eterniamod.hexplots.HexShape> singles() {
        var m = new java.util.EnumMap<HexPlotSettings.StartKey, com.hexvane.eterniamod.hexplots.HexShape>(HexPlotSettings.StartKey.class);
        for (var key : HexPlotSettings.StartKey.values()) m.put(key, com.hexvane.eterniamod.hexplots.HexShape.SINGLE);
        return m;
    }
    private static final HousingService.HexContext CONTEXT = new HousingService.HexContext(L, STREET, Set.of(), CROWNS);

    private final EterniaServices services = new EterniaServices(new InMemoryStore(), Clock.systemUTC(), UUID::randomUUID);
    private int next = 0;

    private UUID account(String name, long crowns) {
        UUID id = UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(id, name);
        if (crowns > 0) services.premium().creditLocalExample(id, crowns, "local-playground:" + id);
        return id;
    }

    /** A fresh road-side cell nobody has claimed yet. */
    private HexCoord freeCell() {
        int q = (next++) * 3;
        return new HexCoord(q - (1 >> 1), 1);
    }

    private HousingService.HexOutcome claim(Owner owner, HexCoord cell, Owner payer, UUID actor) {
        var location = HousingService.ClaimLocation.ofCells("housing", PlotCells.of(L, List.of(cell)),
            owner.kind() == Owner.Kind.GUILD ? HousingService.ClaimScope.GUILD : HousingService.ClaimScope.PUBLIC,
            owner.kind() == Owner.Kind.GUILD ? owner.id() : null, false);
        var kind = owner.kind() == Owner.Kind.GUILD ? HexClaimRules.Kind.GUILD_ESTATE : HexClaimRules.Kind.PUBLIC;
        var candidate = new HexClaimRules.Candidate(kind, false, 3, false, true);
        return services.housing().claimCells(owner, UUID.randomUUID(), UUID.randomUUID(), null, location, candidate, CONTEXT, payer, actor);
    }

    private HousingService.Slot activate(HousingService.HexOutcome outcome) {
        var op = outcome.slot().operationId();
        var journal = services.journal().find(op).orElseThrow();
        services.journal().advance(op, journal.revision(), JournalService.State.WORLD_APPLIED);
        return services.housing().activate(outcome.slot().propertyId(), op);
    }

    @Test void aPlayerHoldsUpToFivePlotsAndEveryOneAfterTheFirstCostsCrowns() {
        UUID mira = account("Mira", 10_000);
        Owner me = Owner.player(mira);
        var plots = new ArrayList<HousingService.Slot>();
        for (int i = 0; i < 5; i++) {
            var outcome = claim(me, freeCell(), me, mira);
            assertTrue(outcome.ok(), "plot " + (i + 1) + ": " + outcome.result().message());
            assertEquals(i == 0 ? 0 : 1500, outcome.charged(), "the first plot is free, the rest cost 1,500 Crowns");
            plots.add(activate(outcome));
        }
        assertEquals(10_000 - 4 * 1500, services.premium().balance(mira).available());
        assertEquals(5, services.housing().plotCount(me));
        var sixth = claim(me, freeCell(), me, mira);
        assertEquals(HexClaimRules.Reason.PLOT_LIMIT, sixth.result().reason());
        assertEquals(plots.getFirst().propertyId(), services.housing().home(me).orElseThrow().propertyId(), "the oldest plot is home");
        assertEquals(5, services.housing().properties(me).size());
    }

    @Test void anotherPlotNeedsTheCrownsForIt() {
        UUID poor = account("Poor", 100);
        Owner me = Owner.player(poor);
        activate(claim(me, freeCell(), me, poor));
        var second = claim(me, freeCell(), me, poor);
        assertEquals(HexClaimRules.Reason.PLOT_CROWNS, second.result().reason());
        assertEquals(100, services.premium().balance(poor).available(), "nothing taken when refused");
    }

    @Test void aSoldHexagonGivesBackOnlyWhatItCost() {
        UUID mira = account("Mira", 2000);
        Owner me = Owner.player(mira);
        var home = activate(claim(me, freeCell(), me, mira));
        var location = services.housing().property(home.propertyId()).orElseThrow().location();
        HexCoord start = location.cells().cells().getFirst();
        var neighbours = start.neighbors().stream().filter(c -> c.r() != 0).toList();
        // A free hexagon (price 0) and a bought one (500).
        var free = services.housing().expandPlot(me, home.propertyId(), neighbours.get(0), home.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, me, 0L, mira);
        assertTrue(free.ok(), free.result().message());
        services.housing().completeExpansion(home.propertyId(), services.journal().unfinished().stream().filter(o -> o.kind().equals("EXPAND")).findFirst().orElseThrow().id());
        var slot = services.housing().slot(home.propertyId()).orElseThrow();
        var paid = services.housing().expandPlot(me, home.propertyId(), neighbours.get(1), slot.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, me, 500L, mira);
        assertTrue(paid.ok(), paid.result().message());
        assertEquals(1500, services.premium().balance(mira).available());
        services.housing().completeExpansion(home.propertyId(), services.journal().unfinished().stream().filter(o -> o.kind().equals("EXPAND")).findFirst().orElseThrow().id());
        assertEquals(0, services.housing().paidPrice(home.propertyId(), neighbours.get(0)));
        assertEquals(500, services.housing().paidPrice(home.propertyId(), neighbours.get(1)));
        slot = services.housing().slot(home.propertyId()).orElseThrow();
        var soldFree = services.housing().sellCell(me, home.propertyId(), neighbours.get(0), slot.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CROWNS, Set.of(), Set.of(), me);
        assertTrue(soldFree.ok(), soldFree.result().message());
        assertEquals(0, soldFree.charged(), "a free hexagon gives nothing back");
        slot = services.housing().slot(home.propertyId()).orElseThrow();
        var soldPaid = services.housing().sellCell(me, home.propertyId(), neighbours.get(1), slot.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CROWNS, Set.of(), Set.of(), me);
        assertEquals(500, soldPaid.charged());
        assertEquals(2000, services.premium().balance(mira).available());
    }

    @Test void guildCrownsGoOneWayAndOnlyTheSpendPermissionSpendsThem() {
        UUID leader = account("Leader", 5000), member = account("Member", 1000);
        var guild = services.guilds().create(leader, "Builders", "create-1");
        services.guilds().acceptInvite(member, services.guilds().invite(leader, member));
        assertEquals(800, services.guildCrowns().give(member, 800, "gift-1"));
        assertEquals(800, services.guildCrowns().give(member, 800, "gift-1"), "a retried gift is not paid twice");
        assertEquals(200, services.premium().balance(member).available());
        Owner estate = Owner.guild(guild.id());
        activate(claim(estate, freeCell(), estate, leader));
        // A plain member may give Crowns but cannot spend them.
        var refused = assertThrows(DomainException.class, () -> claim(estate, freeCell(), estate, member));
        assertEquals(DomainException.Code.FORBIDDEN, refused.code());
        var poor = claim(estate, freeCell(), estate, leader);
        assertEquals(HexClaimRules.Reason.GUILD_CROWNS, poor.result().reason(), "the guild has 800 of the 2,500 a second plot costs");
        services.guildCrowns().give(leader, 2000, "gift-2");
        var second = claim(estate, freeCell(), estate, leader);
        assertTrue(second.ok(), second.result().message());
        assertEquals(300, services.guildCrowns().balance(guild.id()));
        assertEquals(3000, services.premium().balance(leader).available(), "the leader's own Crowns were not touched by the guild purchase");
        activate(second);
        assertEquals(HexClaimRules.Reason.GUILD_PLOT_LIMIT, claim(estate, freeCell(), estate, leader).result().reason());
    }

    @Test void theDesignNumbersAreShipped() {
        var rules = PlotRules.load(PlotRules.class.getClassLoader());
        assertEquals(5, rules.player().maxPlots());
        assertEquals(1500, rules.player().extraPlotCrowns());
        assertEquals(4, rules.player().houseMax());
        assertEquals(6, rules.player().productionMax());
        assertEquals(2, rules.productionFree(false, true), "the season bonus adds a free production hexagon");
        assertEquals(1, rules.productionFree(false, false));
        assertEquals(0, rules.hexagonPrice(false, true, 0, false), "the first production hexagon is free");
        assertEquals(500, rules.hexagonPrice(false, true, 1, false));
        assertEquals(0, rules.hexagonPrice(false, true, 1, true), "with the bonus, the second is free too");
        assertEquals(500, rules.hexagonPrice(false, false, 1, false), "a second housing hexagon costs 500");
        assertEquals(2, rules.guild().maxPlots());
        assertEquals(2500, rules.guild().extraPlotCrowns());
        assertEquals(10, rules.guild().houseMax());
        assertEquals(12, rules.guild().productionMax());
        assertEquals(0, rules.hexagonPrice(true, false, 3, false), "a guild's first four housing hexagons are free");
        assertEquals(1000, rules.hexagonPrice(true, false, 4, false));
        assertEquals(0, rules.hexagonPrice(true, true, 1, false));
        assertEquals(1000, rules.hexagonPrice(true, true, 2, false));
        assertEquals(1, rules.player().samePerPlot());
        assertEquals(2, rules.guild().samePerPlot());
    }

    @Test void boughtHexagonPricesMoveWithARestoredPlot() {
        var from = PlotCells.of(L, List.of(new HexCoord(0, 1), new HexCoord(1, 1)));
        var to = PlotCells.of(L, List.of(new HexCoord(5, 3), new HexCoord(6, 3)));
        var row = new com.hexvane.eterniamod.persistence.TransactionalStore.Row("housing_slot", "p", 3, java.util.Map.of(
            "lattice", from.latticeId(), "cells", from.text(), "paid", "1:1=500"));
        var destination = HousingService.ClaimLocation.ofCells("housing", to, HousingService.ClaimScope.PUBLIC, null, false);
        String moved = HousingService.movedPaid(row, destination);
        assertEquals(500, HousingService.paidFor(moved, new HexCoord(6, 3)), "the bought hexagon keeps its price after the move");
        assertEquals(0, HousingService.paidFor(moved, new HexCoord(5, 3)), "the free one still gives nothing back");
    }

    private void voucher(UUID player, String content, long count) {
        services.ownership().grant(new OwnershipService.GrantRequest("test-voucher:" + UUID.randomUUID(), Owner.player(player), content, OwnershipService.Kind.QUANTITY, count, null));
    }

    @Test void aPlotVoucherPaysForAnotherPlotInsteadOfCrowns() {
        UUID mira = account("Mira", 0);
        Owner me = Owner.player(mira);
        voucher(mira, RankRewards.PLOT, 1);
        activate(claim(me, freeCell(), me, mira));
        var second = claim(me, freeCell(), me, mira);
        assertTrue(second.ok(), second.result().message());
        assertEquals(0, second.charged(), "the voucher pays");
        assertEquals(0, services.ownership().available(me, RankRewards.PLOT), "and is spent");
        activate(second);
        assertEquals(HexClaimRules.Reason.PLOT_CROWNS, claim(me, freeCell(), me, mira).result().reason(), "with no voucher left, Crowns again");
    }

    @Test void aHexagonVoucherPaysForAHexagonOfItsKindOnly() {
        UUID mira = account("Mira", 0);
        Owner me = Owner.player(mira);
        voucher(mira, RankRewards.HOUSE_HEX, 1);
        var home = activate(claim(me, freeCell(), me, mira));
        var start = services.housing().property(home.propertyId()).orElseThrow().location().cells().cells().getFirst();
        var neighbours = start.neighbors().stream().filter(c -> c.r() != 0).toList();
        var wrongKind = services.housing().expandPlot(me, home.propertyId(), neighbours.get(0), home.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, me, 500L, mira, RankRewards.PRODUCTION_HEX);
        assertEquals(HexClaimRules.Reason.CROWNS, wrongKind.result().reason(), "a house hexagon voucher does not pay for a production hexagon");
        var house = services.housing().expandPlot(me, home.propertyId(), neighbours.get(0), home.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, me, 500L, mira, RankRewards.HOUSE_HEX);
        assertTrue(house.ok(), house.result().message());
        assertEquals(0, house.charged());
        assertEquals(0, services.ownership().available(me, RankRewards.HOUSE_HEX));
        services.housing().completeExpansion(home.propertyId(), services.journal().unfinished().stream().filter(o -> o.kind().equals("EXPAND")).findFirst().orElseThrow().id());
        assertEquals(0, services.housing().paidPrice(home.propertyId(), neighbours.get(0)), "selling it later gives no Crowns back");
    }

    @Test void aGuildPlotVoucherComesFromTheSpender() {
        UUID leader = account("Leader", 0);
        UUID guild = services.guilds().create(leader, "Voucher Guild", "voucher-guild").id();
        Owner estate = Owner.guild(guild);
        voucher(leader, RankRewards.GUILD_PLOT, 1);
        activate(claim(estate, freeCell(), estate, leader));
        var second = claim(estate, freeCell(), estate, leader);
        assertTrue(second.ok(), second.result().message());
        assertEquals(0, second.charged());
        assertEquals(0, services.ownership().available(Owner.player(leader), RankRewards.GUILD_PLOT));
    }
}
