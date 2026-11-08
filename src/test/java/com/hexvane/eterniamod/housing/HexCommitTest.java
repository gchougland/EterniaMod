package com.hexvane.eterniamod.housing;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.domain.JournalService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The plots engine's pure commit pieces: scope and price mapping, results, and paid hexagon recovery. */
class HexCommitTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final Set<HexCoord> STREET = HexClaimRules.streetCells(L, List.of(new PlotRect(0, 15, 128, 5)), List.of());
    private static final HousingService.HexContext CONTEXT = new HousingService.HexContext(L, STREET, Set.of(), HexPlotSettings.DEFAULTS);
    private static final HexCoord HOME = new HexCoord(0, 1);

    private final EterniaServices services = new EterniaServices(new InMemoryStore(), Clock.systemUTC(), UUID::randomUUID);

    @Test void kindsMapToClaimScopes() {
        for (var kind : HexClaimRules.Kind.values()) assertEquals(kind, HexClaimCheck.kind(NativeClaimCoordinator.scope(kind)));
    }

    @Test void theLargerStartShapeMeansTheUpgradedPlot() {
        assertFalse(NativeClaimCoordinator.paidFor(HexClaimRules.Kind.PUBLIC, 1));
        assertTrue(NativeClaimCoordinator.paidFor(HexClaimRules.Kind.PUBLIC, 3));
        assertTrue(NativeClaimCoordinator.paidFor(HexClaimRules.Kind.GUILD_MEMBER, 3));
        assertFalse(NativeClaimCoordinator.paidFor(HexClaimRules.Kind.GUILD_ESTATE, 7), "estates start with seven either way, so no upgrade is needed");
    }

    @Test void expansionPriceComesFromTheData() {
        assertEquals(HexPlotSettings.current().expansionPrice(1), NativeClaimCoordinator.expansionPrice(1));
        assertEquals(HexPlotSettings.current().expansionPrice(6), NativeClaimCoordinator.expansionPrice(6));
    }

    @Test void refusalsCarryFriendlyTextAndTheReason() {
        var r = HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.COINS, 2000));
        assertFalse(r.ok());
        assertEquals("You need 2,000 coins for this hexagon.", r.playerMessage());
        assertEquals("eternia_hexplots.reason.coins", r.message().getMessageId());
        assertNull(r.plotId());
        var ok = HexCommitResult.success(HexPlotText.plots("expand.paid", "Your plot grew to {cells} hexagons. {coins} coins paid.", "cells", 2, "coins", 1000L), "p", HexClaimRules.Result.of(HexClaimRules.Reason.EXPAND_ALLOWED));
        assertTrue(ok.ok());
        assertEquals("Your plot grew to 2 hexagons. 1,000 coins paid.", ok.playerMessage());
        assertFalse(ok.playerMessage().contains("-") || ok.playerMessage().contains("—"), "no dashes in player text");
    }

    @Test void aPaidHexagonLostInACrashArrivesOnWorldLoad() {
        UUID mira = UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(mira, "Mira");
        Owner owner = Owner.player(mira);
        services.economy().credit(owner, 1500, "mint");
        var claimed = services.housing().claimCells(owner, UUID.randomUUID(), UUID.randomUUID(), null,
            HousingService.ClaimLocation.ofCells("housing", PlotCells.of(L, Set.of(HOME)), HousingService.ClaimScope.PUBLIC, null, false),
            HexClaimRules.Candidate.of(HexClaimRules.Kind.PUBLIC, false), CONTEXT, owner);
        assertTrue(claimed.ok());
        var op = services.journal().find(claimed.slot().operationId()).orElseThrow();
        services.journal().advance(op.id(), op.revision(), JournalService.State.WORLD_APPLIED);
        var slot = services.housing().activate(owner, op.id());

        // The world's plot record, as the native claim tail made it.
        var plot = new HubPlotRecord(slot.propertyId(), "housing", HubPlotFootprint.forCells(claimed.cells(), 70), mira);
        Map<UUID, HubPlotRecord> world = new HashMap<>(Map.of(plot.getPlotId(), plot));
        List<HubPlotRecord> saved = new ArrayList<>();

        // Paid, then the server stops before the world shows the new hexagon.
        HexCoord added = new HexCoord(0, 2);
        var paid = services.housing().expandPlot(owner, slot.propertyId(), added, slot.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, owner);
        assertTrue(paid.ok());
        assertEquals(500, services.economy().balance(owner).available());
        assertFalse(plot.containsColumn((int) L.centerX(added), (int) L.centerZ(added)), "not on the world record yet");

        var pending = services.housing().pendingExpansions("housing");
        assertEquals(1, pending.size());
        assertTrue(NativeClaimCoordinator.applyExpansion(services.housing(), pending.getFirst(), world::get, saved::add));
        assertEquals(PlotCells.of(L, Set.of(HOME, added)), plot.getCells());
        assertTrue(plot.containsColumn((int) L.centerX(added), (int) L.centerZ(added)), "protected and shown now");
        assertEquals(70, plot.getFootprint().resolveVisualCenterY(), "same glow height");
        assertEquals(List.of(plot), saved);
        assertTrue(services.housing().pendingExpansions("housing").isEmpty());
        assertEquals(Set.of(), services.housing().reservedCells("housing"));
        assertEquals(500, services.economy().balance(owner).available(), "charged exactly once");

        // A second load finds nothing to do.
        assertFalse(NativeClaimCoordinator.applyExpansion(services.housing(), pending.getFirst(), world::get, saved::add));
        assertEquals(1, saved.size());
    }

    @Test void aPaidHexagonWaitsWhenThePlotIsNotInThisWorld() {
        UUID bo = UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(bo, "Bo");
        Owner owner = Owner.player(bo);
        services.economy().credit(owner, 1500, "mint");
        var claimed = services.housing().claimCells(owner, UUID.randomUUID(), UUID.randomUUID(), null,
            HousingService.ClaimLocation.ofCells("housing", PlotCells.of(L, Set.of(HOME)), HousingService.ClaimScope.PUBLIC, null, false),
            HexClaimRules.Candidate.of(HexClaimRules.Kind.PUBLIC, false), CONTEXT, owner);
        var op = services.journal().find(claimed.slot().operationId()).orElseThrow();
        services.journal().advance(op.id(), op.revision(), JournalService.State.WORLD_APPLIED);
        var slot = services.housing().activate(owner, op.id());
        services.housing().expandPlot(owner, slot.propertyId(), new HexCoord(0, 2), slot.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, owner);
        var pending = services.housing().pendingExpansions("housing").getFirst();
        assertFalse(NativeClaimCoordinator.applyExpansion(services.housing(), pending, id -> null, p -> fail("nothing to save")));
        assertEquals(1, services.housing().pendingExpansions("housing").size(), "kept for the next load");
    }
}
