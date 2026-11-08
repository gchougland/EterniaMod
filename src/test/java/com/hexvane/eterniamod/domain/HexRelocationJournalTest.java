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
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Hex versions of the pack and restore guarantees: the journal and slot states a native move walks through, a
 * restart at every step, cell exact destinations (neighbour boxes overlap), and the lock after an uncertain write.
 */
class HexRelocationJournalTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    private static final Set<HexCoord> STREET = HexClaimRules.streetCells(L, List.of(new PlotRect(0, 15, 256, 5)), List.of());
    private static final HousingService.HexContext CONTEXT = new HousingService.HexContext(L, STREET, Set.of(), HexPlotSettings.DEFAULTS);

    private final InMemoryStore store = new InMemoryStore();
    private EterniaServices services = new EterniaServices(store, Clock.systemUTC(), UUID::randomUUID);

    /** A server restart: same durable store, fresh services and caches. */
    private void restart() { services = new EterniaServices(store, Clock.systemUTC(), UUID::randomUUID); }

    private UUID account(String name) {
        UUID id = UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(id, name);
        return id;
    }

    private static PlotCells cells(Collection<HexCoord> list) { return PlotCells.of(L, list); }

    private static HousingService.ClaimLocation at(PlotCells cells) {
        return HousingService.ClaimLocation.ofCells("housing", cells, HousingService.ClaimScope.PUBLIC, null, true);
    }

    private UUID home(UUID player, List<HexCoord> list) {
        var outcome = services.housing().claimCells(Owner.player(player), UUID.randomUUID(), UUID.randomUUID(), null, at(cells(list)),
            HexClaimRules.Candidate.of(HexClaimRules.Kind.PUBLIC, list.size() == 3), CONTEXT, Owner.player(player));
        assertTrue(outcome.ok(), outcome.result().message());
        var op = outcome.slot().operationId();
        var journal = services.journal().find(op).orElseThrow();
        services.journal().advance(op, journal.revision(), JournalService.State.WORLD_APPLIED);
        services.housing().activate(Owner.player(player), op);
        return outcome.slot().propertyId();
    }

    /** The domain half of NativeRelocationCoordinator.pack: begin, verified snapshot, world applied, packed. */
    private UUID pack(Owner owner) {
        UUID move = UUID.randomUUID();
        services.housing().beginRelocation(owner, move, false);
        var op = services.journal().find(move).orElseThrow();
        op = services.journal().attachVerifiedSnapshot(move, op.revision(), "snapshots/" + move + "-pack.json", "a".repeat(64));
        services.journal().advance(move, op.revision(), JournalService.State.WORLD_APPLIED);
        services.housing().acknowledgePacked(owner, move);
        return move;
    }

    private void credit(UUID player) {
        services.ownership().grant(new OwnershipService.GrantRequest("move:" + player, Owner.player(player), HousingService.MOVE_CREDIT, OwnershipService.Kind.QUANTITY, 1, null));
    }

    private static final List<HexCoord> TRIANGLE = List.of(new HexCoord(0, 1), new HexCoord(1, 1), new HexCoord(0, 2));

    @Test void packedHexPlotFreesItsCellsAndKeepsThemForTheRestorePicker() {
        UUID mira = account("Mira");
        Owner owner = Owner.player(mira);
        credit(mira);
        home(mira, TRIANGLE);
        assertTrue(services.housing().hasReservedGround("housing", (int) L.centerX(new HexCoord(1, 1)), (int) L.centerZ(new HexCoord(1, 1))));
        pack(owner);
        restart();
        var property = services.housing().property(owner).orElseThrow();
        assertEquals(HousingService.State.PACKED, property.slot().state());
        assertEquals(cells(TRIANGLE), property.location().cells(), "packed cells survive a restart");
        assertFalse(services.housing().hasReservedGround("housing", (int) L.centerX(new HexCoord(1, 1)), (int) L.centerZ(new HexCoord(1, 1))), "packed land is free again");
        assertEquals(Set.of(), services.housing().reservedCells("housing"));
        assertEquals(0, services.ownership().available(owner, HousingService.MOVE_CREDIT), "move credit held while packed");
    }

    @Test void restoreReservesExactlyTheDestinationCellsBesideAnOverlappingNeighbourBox() {
        UUID mira = account("Mira"), noor = account("Noor");
        Owner owner = Owner.player(mira);
        credit(mira);
        home(mira, TRIANGLE);
        UUID move = pack(owner);
        // Noor takes part of Mira's old land: packing really freed it.
        home(noor, List.of(new HexCoord(1, 1)));
        var destination = cells(TRIANGLE).translate(new HexCoord(3, 0));   // (3,1) (4,1) (3,2)
        var neighbour = cells(List.of(new HexCoord(2, 1)));
        assertTrue(destination.bounds().overlaps(cells(List.of(new HexCoord(2, 2))).bounds()), "boxes interleave");
        home(account("Ivo"), neighbour.cells());
        services.housing().beginRestore(owner, move);
        restart();
        services.housing().updateRestoreLocation(owner, move, at(destination));
        assertEquals(destination.asSet(), services.housing().reservedCells("housing"), "restoring cells show as reserved");
        var op = services.journal().find(move).orElseThrow();
        services.journal().advance(move, op.revision(), JournalService.State.WORLD_APPLIED);
        restart();
        var slot = services.housing().activate(owner, move);
        assertEquals(HousingService.State.ACTIVE, slot.state());
        assertEquals(destination, services.housing().location(owner).orElseThrow().cells());
        assertEquals(Set.of(), services.housing().reservedCells("housing"));
        assertEquals(0, services.ownership().available(owner, HousingService.MOVE_CREDIT), "the move used its credit once");
        assertTrue(services.journal().unfinished().isEmpty());
    }

    @Test void restoreOntoAnOwnedCellIsRefusedAndTheSlotStaysRestorable() {
        UUID mira = account("Mira"), noor = account("Noor");
        Owner owner = Owner.player(mira);
        credit(mira);
        home(mira, TRIANGLE);
        UUID move = pack(owner);
        home(noor, List.of(new HexCoord(4, 1)));
        services.housing().beginRestore(owner, move);
        var clash = cells(TRIANGLE).translate(new HexCoord(3, 0));
        var error = assertThrows(DomainException.class, () -> services.housing().updateRestoreLocation(owner, move, at(clash)));
        assertEquals(DomainException.Code.CONFLICT, error.code());
        // Nothing was written: another free spot still works.
        var free = cells(TRIANGLE).translate(new HexCoord(6, 0));
        assertEquals(HousingService.State.RESTORING, services.housing().updateRestoreLocation(owner, move, at(free)).state());
    }

    @Test void interruptedPackBeforeTheWorldChangesCancelsAndReturnsTheCredit() {
        UUID mira = account("Mira");
        Owner owner = Owner.player(mira);
        credit(mira);
        home(mira, TRIANGLE);
        UUID move = UUID.randomUUID();
        services.housing().beginRelocation(owner, move, false);
        restart();
        services.housing().cancelRelocationBeforeMutation(owner, move);
        assertEquals(HousingService.State.ACTIVE, services.housing().find(owner).orElseThrow().state());
        assertEquals(1, services.ownership().available(owner, HousingService.MOVE_CREDIT));
        assertEquals(cells(TRIANGLE), services.housing().location(owner).orElseThrow().cells(), "the plot never left its cells");
    }

    @Test void uncertainWorldWriteLocksTheMoveUntilAnAdministratorRecovers() {
        UUID mira = account("Mira");
        Owner owner = Owner.player(mira);
        credit(mira);
        home(mira, TRIANGLE);
        UUID move = UUID.randomUUID();
        services.housing().beginRelocation(owner, move, false);
        var op = services.journal().find(move).orElseThrow();
        op = services.journal().attachVerifiedSnapshot(move, op.revision(), "snapshots/pack", "b".repeat(64));
        // NativePlacementTransactions.lock: the adapter crashed after its first world write.
        services.journal().advance(move, op.revision(), JournalService.State.RECOVERY_REQUIRED);
        restart();
        assertThrows(DomainException.class, () -> services.housing().cancelRelocationBeforeMutation(owner, move), "never guess after a write");
        assertThrows(DomainException.class, () -> services.housing().acknowledgePacked(owner, move));
        assertThrows(DomainException.class, () -> services.housing().beginRelocation(owner, UUID.randomUUID(), false));
        assertTrue(services.housing().hasReservedGround("housing", (int) L.centerX(new HexCoord(0, 1)), (int) L.centerZ(new HexCoord(0, 1))), "locked land stays protected");
        assertEquals(cells(TRIANGLE).asSet(), services.housing().reservedCells("housing"), "and shows as reserved on the claim map");
        // recoverPackToSource: verified source restored, journal back to SNAPSHOT_READY, then cancelled.
        op = services.journal().find(move).orElseThrow();
        services.journal().advance(move, op.revision(), JournalService.State.SNAPSHOT_READY);
        services.housing().cancelRelocationBeforeMutation(owner, move);
        assertEquals(HousingService.State.ACTIVE, services.housing().find(owner).orElseThrow().state());
        assertEquals(1, services.ownership().available(owner, HousingService.MOVE_CREDIT));
    }

    @Test void pendingExpansionBlocksAMoveUntilItIsApplied() {
        UUID mira = account("Mira");
        Owner owner = Owner.player(mira);
        credit(mira);
        services.economy().credit(owner, 100_000, "mint:" + mira);
        UUID property = home(mira, List.of(new HexCoord(0, 1)));
        var slot = services.housing().find(owner).orElseThrow();
        UUID grow = UUID.randomUUID();
        var out = services.housing().expandPlot(owner, property, new HexCoord(1, 1), slot.revision(), grow, HexClaimRules.Kind.PUBLIC, CONTEXT, owner);
        assertTrue(out.ok(), out.result().message());
        restart();
        assertThrows(DomainException.class, () -> services.housing().beginRelocation(owner, UUID.randomUUID(), false), "the grown baseline is not captured yet");
        services.housing().completeExpansion(owner, grow);
        UUID move = pack(owner);
        assertEquals(2, services.housing().property(owner).orElseThrow().location().cells().size(), "both hexagons travel");
        assertNotNull(move);
    }
}
