package com.hexvane.eterniamod.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Prowl's rule for Eternal's guild house plot (2026-11-25): it comes at once, but it only becomes permanent after three
 * paid months. Ending sooner takes the voucher back, and a guild plot it already paid for is packed up.
 */
class EternalGuildPlotTrialTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final Set<HexCoord> STREET = HexClaimRules.streetCells(L, List.of(new PlotRect(-2000, 15, 4000, 5)), List.of());
    private static final HexPlotSettings SETTINGS = new HexPlotSettings(44, 28, singles(), 10, 22, 0, 0, 0, 24, 2, 5, 3, 500);
    private static Map<HexPlotSettings.StartKey, com.hexvane.eterniamod.hexplots.HexShape> singles() {
        var m = new java.util.EnumMap<HexPlotSettings.StartKey, com.hexvane.eterniamod.hexplots.HexShape>(HexPlotSettings.StartKey.class);
        for (var key : HexPlotSettings.StartKey.values()) m.put(key, com.hexvane.eterniamod.hexplots.HexShape.SINGLE);
        return m;
    }
    private static final HousingService.HexContext CONTEXT = new HousingService.HexContext(L, STREET, Set.of(), SETTINGS);
    private static final RankRewards REWARDS = new RankRewards(Map.of("eternal", List.of(
        new RankRewards.Reward("voucher_guild_plot", RankRewards.GUILD_PLOT, OwnershipService.Kind.QUANTITY, 1, false, 1, 3))));

    private final EterniaServices s = new EterniaServices(new InMemoryStore(), Clock.systemUTC(), UUID::randomUUID, REWARDS);
    private final UUID leader = UUID.randomUUID();
    private final Owner me = Owner.player(leader);
    private final Instant start = Instant.now().minusSeconds(3600);
    private Owner estate;
    private int next = 0;

    EternalGuildPlotTrialTest() {
        s.accounts().recordAuthenticatedLogin(leader, "Leader");
        s.commerce().register(new CommerceService.Product("9", 1, "9", List.of(new CommerceService.Benefit(SupporterRanks.content("eternal"), OwnershipService.Kind.UNLOCK, 1, true)), true));
        estate = Owner.guild(s.guilds().create(leader, "Trial Guild", "trial-guild").id());
    }

    private void pay(String sub, int month) {
        Instant from = start.plusSeconds(86400L * 30 * month);
        s.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex", sub + "-pay-" + month, "line", "9", 1, me, 1, sub, from, from.plusSeconds(86400L * 30)));
        s.ranks().syncPaid(leader);
    }
    private void end(String sub) {
        s.commerce().applySubscriptionControl("tebex", sub, "ENDED", Instant.now().plusSeconds(86400L * 200), "end-" + sub);
        s.ranks().syncPaid(leader);
    }
    private long vouchers() { return s.ownership().available(me, RankRewards.GUILD_PLOT); }

    private HousingService.Slot claim() {
        var location = HousingService.ClaimLocation.ofCells("housing", PlotCells.of(L, List.of(new HexCoord((next++) * 3, 1))), HousingService.ClaimScope.GUILD, estate.id(), false);
        var out = s.housing().claimCells(estate, UUID.randomUUID(), UUID.randomUUID(), null, location, new HexClaimRules.Candidate(HexClaimRules.Kind.GUILD_ESTATE, false, 3, false, true), CONTEXT, estate, leader);
        assertTrue(out.ok(), out.result().message());
        var op = out.slot().operationId();
        s.journal().advance(op, s.journal().find(op).orElseThrow().revision(), JournalService.State.WORLD_APPLIED);
        return s.housing().activate(out.slot().propertyId(), op);
    }
    /** What the pack worker's native pack does to the domain rows. */
    private HousingService.Slot pack(HousingService.Slot slot) {
        UUID op = UUID.randomUUID();
        s.housing().beginRelocation(slot.propertyId(), op, true);
        s.journal().attachVerifiedSnapshot(op, s.journal().find(op).orElseThrow().revision(), "packs/test.json", "a".repeat(64));
        s.journal().advance(op, s.journal().find(op).orElseThrow().revision(), JournalService.State.WORLD_APPLIED);
        return s.housing().acknowledgePacked(slot.propertyId(), op);
    }
    private List<UUID> owed() { return s.housing().owedToPack().stream().map(HousingService.Slot::propertyId).toList(); }

    @Test void theVoucherComesAtOnceAndGoesBackUnusedIfEternalEndsEarly() {
        pay("sub-1", 0);
        assertEquals(1, vouchers(), "given in the first month");
        end("sub-1");
        assertEquals(0, vouchers(), "ended before three months: taken back");
        assertEquals(List.of(), owed());
    }

    @Test void aGuildPlotItPaidForIsPackedUpAndSettingItDownCostsAgain() {
        claim(); // the guild's first plot is free
        pay("sub-1", 0);
        var second = claim();
        assertEquals(0, vouchers(), "the voucher paid for the second plot");
        end("sub-1");
        assertEquals(List.of(second.propertyId()), owed(), "the plot it paid for is owed and waits to be packed");
        var packed = pack(second);
        assertEquals(List.of(), owed(), "once packed it is no longer waiting");
        assertTrue(s.housing().owed(second.propertyId()));
        var refused = assertThrows(DomainException.class, () -> s.housing().beginRestore(packed.propertyId(), packed.operationId(), leader));
        assertTrue(refused.getMessage().contains("guild plot voucher"), refused.getMessage());
        s.ownership().grant(new OwnershipService.GrantRequest("test-voucher", me, RankRewards.GUILD_PLOT, OwnershipService.Kind.QUANTITY, 1, null));
        s.housing().beginRestore(packed.propertyId(), packed.operationId(), leader);
        assertEquals(0, vouchers(), "setting it down spent a guild plot voucher");
        assertFalse(s.housing().owed(second.propertyId()));
    }

    @Test void afterThreePaidMonthsItIsKeptForGood() {
        claim();
        pay("sub-1", 0); pay("sub-1", 1);
        var second = claim();
        pay("sub-1", 2);
        end("sub-1");
        assertEquals(List.of(), owed(), "three paid months: the plot stays");
        assertFalse(s.housing().owed(second.propertyId()));
    }

    @Test void aPermanentVoucherIsSpentFirstSoTheTrialOneJustGoes() {
        claim();
        s.ownership().grant(new OwnershipService.GrantRequest("elder-voucher", me, RankRewards.GUILD_PLOT, OwnershipService.Kind.QUANTITY, 1, null));
        pay("sub-1", 0);
        assertEquals(2, vouchers());
        var second = claim();
        end("sub-1");
        assertEquals(0, vouchers(), "the unused trial voucher was taken back");
        assertEquals(List.of(), owed(), "the plot was paid with the permanent voucher");
        assertFalse(s.housing().owed(second.propertyId()));
    }

    @Test void comingBackBeforeThePackHappensPaysForThePlot() {
        claim();
        pay("sub-1", 0);
        var second = claim();
        end("sub-1");
        assertEquals(List.of(second.propertyId()), owed());
        pay("sub-2", 1);
        assertEquals(List.of(), owed(), "the new trial voucher paid for it");
        assertEquals(0, vouchers());
    }

    @Test void aRankGivenByHandKeepsItAtOnce() {
        s.ranks().give(leader, "eternal", SupporterRanks.titleId("eternal"));
        assertEquals(1, vouchers());
        s.ranks().take(leader, "eternal");
        s.ranks().syncPaid(leader);
        assertEquals(1, vouchers());
    }
}
