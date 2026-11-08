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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HexHousingServiceTest {
    private static final HexLattice L = HexLattice.DEFAULT;
    /** Row 0, cells (0..3, 0), is a public road. */
    private static final Set<HexCoord> STREET = HexClaimRules.streetCells(L, List.of(new PlotRect(0, 15, 128, 5)), List.of());
    private static final HousingService.HexContext CONTEXT = new HousingService.HexContext(L, STREET, Set.of(), HexPlotSettings.DEFAULTS);
    private static final HexCoord SOUTH = new HexCoord(0, 1);

    private final EterniaServices services = new EterniaServices(new InMemoryStore(), Clock.systemUTC(), UUID::randomUUID);

    private UUID account(String name) {
        UUID id = UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(id, name);
        return id;
    }

    private static HousingService.ClaimLocation at(Collection<HexCoord> cells) {
        return HousingService.ClaimLocation.ofCells("housing", PlotCells.of(L, cells), HousingService.ClaimScope.PUBLIC, null, false);
    }

    private HousingService.HexOutcome claim(UUID player, Collection<HexCoord> cells) {
        return services.housing().claimCells(Owner.player(player), UUID.randomUUID(), UUID.randomUUID(), null, at(cells),
            HexClaimRules.Candidate.of(HexClaimRules.Kind.PUBLIC, cells.size() == 3), CONTEXT, Owner.player(player));
    }

    private HousingService.Slot activate(Owner owner, UUID operation) {
        var journal = services.journal().find(operation).orElseThrow();
        services.journal().advance(operation, journal.revision(), JournalService.State.WORLD_APPLIED);
        return services.housing().activate(owner, operation);
    }

    private HousingService.Slot home(UUID player, Collection<HexCoord> cells) {
        var outcome = claim(player, cells);
        assertTrue(outcome.ok(), outcome.result().message());
        return activate(Owner.player(player), outcome.slot().operationId());
    }

    @Test void claimSavesCellsOnTheSlotRowAndReservesThemUntilActive() {
        UUID mira = account("Mira");
        var outcome = claim(mira, Set.of(SOUTH));
        assertTrue(outcome.ok());
        assertEquals(HousingService.State.CLAIMING, outcome.slot().state());
        var row = services.store().transaction(tx -> tx.find("housing_slot", Owner.player(mira).key())).orElseThrow();
        assertEquals("0:1", row.value("cells"));
        assertEquals("hex:32:20:0:0", row.value("lattice"));
        assertEquals(String.valueOf(L.minX(SOUTH)), row.value("minX"));
        assertEquals("36", row.value("depth"));
        var location = services.housing().location(Owner.player(mira)).orElseThrow();
        assertEquals(PlotCells.of(L, Set.of(SOUTH)), location.cells());

        assertEquals(Set.of(SOUTH), services.housing().reservedCells("housing"));
        int cx = (int) L.centerX(SOUTH), cz = (int) L.centerZ(SOUTH);
        assertTrue(services.housing().hasReservedGround("housing", cx, cz));
        var box = L.bounds(SOUTH);
        assertFalse(services.housing().hasReservedGround("housing", box.x(), box.z()), "the box corner belongs to a neighbour cell");

        activate(Owner.player(mira), outcome.slot().operationId());
        assertEquals(Set.of(), services.housing().reservedCells("housing"));
        assertTrue(services.housing().hasReservedGround("housing", cx, cz), "active plots stay protected");
    }

    @Test void rejectedClaimsSaveNothing() {
        UUID mira = account("Mira"), bo = account("Bo");
        home(mira, Set.of(SOUTH));
        assertEquals(HexClaimRules.Reason.STREET, claim(bo, Set.of(new HexCoord(1, 0))).result().reason());
        assertEquals(HexClaimRules.Reason.TAKEN, claim(bo, Set.of(SOUTH)).result().reason());
        assertEquals(HexClaimRules.Reason.ROAD, claim(bo, Set.of(new HexCoord(0, 5))).result().reason());
        assertEquals(HexClaimRules.Reason.SHAPE, claim(bo, Set.of(new HexCoord(1, 1), new HexCoord(2, 1), new HexCoord(3, 1))).result().reason());
        assertTrue(services.housing().find(Owner.player(bo)).isEmpty());
        assertTrue(claim(bo, Set.of(new HexCoord(1, 1), new HexCoord(2, 1), new HexCoord(1, 2))).ok(), "a triangle beside the road");
    }

    @Test void concurrentClaimsForOneCellHaveOneWinner() throws Exception {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 8; i++) players.add(account("P" + i));
        AtomicInteger wins = new AtomicInteger(), lost = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (UUID p : players) tasks.add(pool.submit(() -> {
                start.await();
                var outcome = claim(p, Set.of(SOUTH));
                if (outcome.ok()) wins.incrementAndGet();
                else {
                    assertTrue(Set.of(HexClaimRules.Reason.RESERVED, HexClaimRules.Reason.TAKEN).contains(outcome.result().reason()));
                    lost.incrementAndGet();
                }
                return null;
            }));
            start.countDown();
            for (var task : tasks) task.get();
        }
        assertEquals(1, wins.get());
        assertEquals(7, lost.get());
    }

    @Test void claimPriceIsChargedOnceEvenWhenReplayed() {
        UUID mira = account("Mira");
        services.economy().credit(Owner.player(mira), 150, "mint");
        var priced = new HousingService.HexContext(L, STREET, Set.of(), new HexPlotSettings(32, 20, null, 7, 19, 100, 0, 1000, 24, 2, 5, 5));
        UUID property = UUID.randomUUID(), op = UUID.randomUUID();
        var first = services.housing().claimCells(Owner.player(mira), property, op, null, at(Set.of(SOUTH)), HexClaimRules.Candidate.of(HexClaimRules.Kind.PUBLIC, false), priced, Owner.player(mira));
        var again = services.housing().claimCells(Owner.player(mira), property, op, null, at(Set.of(SOUTH)), HexClaimRules.Candidate.of(HexClaimRules.Kind.PUBLIC, false), priced, Owner.player(mira));
        assertEquals(100, first.charged());
        assertTrue(again.ok());
        assertEquals(0, again.charged());
        assertEquals(50, services.economy().balance(Owner.player(mira)).available());
        UUID bo = account("Bo");
        var broke = services.housing().claimCells(Owner.player(bo), UUID.randomUUID(), UUID.randomUUID(), null, at(Set.of(new HexCoord(2, 1))), HexClaimRules.Candidate.of(HexClaimRules.Kind.PUBLIC, false), priced, Owner.player(bo));
        assertEquals(HexClaimRules.Reason.COINS, broke.result().reason());
        assertEquals(100, broke.result().count());
    }

    @Test void expansionChecksRevisionChargesAndJournals() {
        UUID mira = account("Mira");
        Owner owner = Owner.player(mira);
        services.economy().credit(owner, 1500, "mint");
        var slot = home(mira, Set.of(SOUTH));
        HexCoord next = new HexCoord(0, 2);
        UUID op = UUID.randomUUID();

        var stale = services.housing().expandPlot(owner, slot.propertyId(), next, slot.revision() - 1, UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, owner);
        assertEquals(HexClaimRules.Reason.CHANGED, stale.result().reason());

        var grown = services.housing().expandPlot(owner, slot.propertyId(), next, slot.revision(), op, HexClaimRules.Kind.PUBLIC, CONTEXT, owner);
        assertTrue(grown.ok(), grown.result().message());
        assertEquals(1000, grown.charged(), "1,000 coins times one owned cell");
        assertEquals(PlotCells.of(L, Set.of(SOUTH, next)), grown.cells());
        assertEquals(500, services.economy().balance(owner).available());
        assertEquals(Set.of(next), services.housing().reservedCells("housing"), "paid but not yet applied to the world");
        assertEquals(1, services.housing().pendingExpansions("housing").size());
        assertThrows(DomainException.class, () -> services.housing().beginRelocation(owner, UUID.randomUUID(), true), "no moving while a hexagon is being added");

        var replay = services.housing().expandPlot(owner, slot.propertyId(), next, slot.revision(), op, HexClaimRules.Kind.PUBLIC, CONTEXT, owner);
        assertTrue(replay.ok());
        assertEquals(0, replay.charged());
        var busy = services.housing().expandPlot(owner, slot.propertyId(), new HexCoord(-1, 2), grown.slot().revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, owner);
        assertEquals(HexClaimRules.Reason.CHANGED, busy.result().reason(), "one expansion at a time");

        var done = services.housing().completeExpansion(owner, op);
        assertEquals(done, services.housing().completeExpansion(owner, op), "idempotent");
        assertEquals(JournalService.State.COMPLETED, services.journal().find(op).orElseThrow().state());
        assertEquals(Set.of(), services.housing().reservedCells("housing"));
        assertTrue(services.housing().pendingExpansions("housing").isEmpty());

        var poor = services.housing().expandPlot(owner, slot.propertyId(), new HexCoord(-1, 2), done.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, owner);
        assertEquals(HexClaimRules.Reason.COINS, poor.result().reason());
        assertEquals(2000, poor.result().count());
        assertEquals(500, services.economy().balance(owner).available(), "nothing charged on a refusal");
        assertEquals(2, services.housing().location(owner).orElseThrow().cells().size());
    }

    @Test void neighboursCannotGrowIntoEachOther() {
        UUID mira = account("Mira"), bo = account("Bo");
        var a = home(mira, Set.of(SOUTH));
        home(bo, Set.of(new HexCoord(1, 1)));
        var outcome = services.housing().expandPlot(Owner.player(mira), a.propertyId(), new HexCoord(1, 1), a.revision(), UUID.randomUUID(), HexClaimRules.Kind.PUBLIC, CONTEXT, Owner.player(mira));
        assertEquals(HexClaimRules.Reason.TAKEN, outcome.result().reason());
    }

    @Test void guildMembersJoinTheEstate() {
        UUID leader = account("Leader"), member = account("Member"), stranger = account("Stranger");
        UUID guild = services.guilds().create(leader, "Hearth", "create").id();
        services.guilds().acceptInvite(member, services.guilds().invite(leader, member));
        Owner estateOwner = Owner.guild(guild);
        var flower = HexCoord.disk(new HexCoord(0, 2), 1);
        var estate = services.housing().claimCells(estateOwner, UUID.randomUUID(), UUID.randomUUID(), null,
            HousingService.ClaimLocation.ofCells("housing", PlotCells.of(L, flower), HousingService.ClaimScope.GUILD, guild, false),
            new HexClaimRules.Candidate(HexClaimRules.Kind.GUILD_ESTATE, false, 5, false, false), CONTEXT, estateOwner);
        assertTrue(estate.ok(), estate.result().message());
        activate(estateOwner, estate.slot().operationId());

        var memberAt = (java.util.function.Function<HexCoord, HousingService.HexOutcome>) cell -> services.housing().claimCells(Owner.player(member), UUID.randomUUID(), UUID.randomUUID(), guild,
            HousingService.ClaimLocation.ofCells("housing", PlotCells.of(L, Set.of(cell)), HousingService.ClaimScope.GUILD, guild, false),
            HexClaimRules.Candidate.of(HexClaimRules.Kind.GUILD_MEMBER, false), CONTEXT, Owner.player(member));
        assertEquals(HexClaimRules.Reason.ANCHOR, memberAt.apply(new HexCoord(5, 6)).result().reason());
        assertTrue(memberAt.apply(new HexCoord(0, 4)).ok(), "touches the estate");
        assertThrows(DomainException.class, () -> services.housing().claimCells(Owner.player(stranger), UUID.randomUUID(), UUID.randomUUID(), guild,
            HousingService.ClaimLocation.ofCells("housing", PlotCells.of(L, Set.of(new HexCoord(1, 4))), HousingService.ClaimScope.GUILD, guild, false),
            HexClaimRules.Candidate.of(HexClaimRules.Kind.GUILD_MEMBER, false), CONTEXT, Owner.player(stranger)), "only members attach");
    }

    @Test void hexAndRectangleClaimsDoNotOverlap() {
        UUID mira = account("Mira"), bo = account("Bo");
        home(mira, Set.of(SOUTH));
        var box = L.bounds(SOUTH);
        // A legacy rectangle on the hex cell's box corner, which belongs to a neighbour cell, does not overlap.
        var corner = new HousingService.ClaimLocation("housing", box.x(), box.z(), 2, 2, HousingService.ClaimScope.PUBLIC, null, false);
        assertDoesNotThrow(() -> services.housing().beginClaim(Owner.player(bo), UUID.randomUUID(), UUID.randomUUID(), null, corner));
        UUID cy = account("Cy");
        var middle = new HousingService.ClaimLocation("housing", (int) L.centerX(SOUTH), (int) L.centerZ(SOUTH), 2, 2, HousingService.ClaimScope.PUBLIC, null, false);
        assertThrows(DomainException.class, () -> services.housing().beginClaim(Owner.player(cy), UUID.randomUUID(), UUID.randomUUID(), null, middle));
    }
}
