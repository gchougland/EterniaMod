package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.ColumnMask;
import com.hexvane.eterniamod.housing.relocation.HexRelocation;
import com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions;
import com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator;
import com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotBuilding;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.localplayground.HexFixtures;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.setup.InfrastructureRules;
import com.hexvane.eterniamod.setup.InfrastructureSetup;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.joml.Vector3i;

/**
 * Native acceptance for hex plots (run by {@link NativeSmoke} in its own flat world): claims on the grid, a house and
 * decorative entities, packing that leaves the neighbour whose box overlaps untouched, the destination list, a same
 * shape restore three hexagons east, growing the plot (baseline extended), a second pack and restore, and the strand
 * check for a road over a claimed hexagon. Logs {@code ETERNIA_NATIVE_HEX_PASS}.
 */
final class NativeHexSmoke {
    private NativeHexSmoke() {}

    static final String WORLD = "eternia_smoke_hex";
    private static final UUID MIRA = UUID.fromString("ffffffff-ffff-4fff-8fff-fffffffffaa1");
    private static final UUID NOOR = UUID.fromString("ffffffff-ffff-4fff-8fff-fffffffffaa2");
    /** Mira's manor: a triangle on rows 1 and 2, beside the road on row 0. */
    private static final List<HexCoord> MANOR = List.of(new HexCoord(2, 1), new HexCoord(3, 1), new HexCoord(2, 2));
    /** Noor's cottage: west of the manor's south hexagon, inside the manor's box. */
    private static final HexCoord COTTAGE = new HexCoord(1, 2);

    static void run(EterniaModPlugin plugin) throws Exception {
        World world = com.hexvane.eterniamod.localplayground.LocalPlayground.createFlatWorld(WORLD).join();
        var lattice = HexFixtures.lattice();
        var row0 = new PlotRect(-32, lattice.minZ(new HexCoord(0, 0)) + lattice.tipDepth() + 8, 320, 4);
        // A small plaza on hexagon 0:2 gives the cottage its road frontage.
        var plaza = new PlotRect(lattice.minX(new HexCoord(0, 2)) + 8, lattice.minZ(new HexCoord(0, 2)) + 14, 4, 4);
        var plan = HexFixtures.hex(new HousingInfrastructure.WorldPlan("housing", List.of(), List.of(), new HousingInfrastructure.Point(0.5, 1, 0.5), true)
            .withAreas(Map.of("hex-row-road", new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD, row0),
                "hex-plaza", new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL, plaza))));
        plugin.getInfrastructure().saveWorld(plugin.getInfrastructure().snapshot().revision(), WORLD, plan);
        com.hexvane.eterniamod.customization.HousingWorldPolicy.refreshAll().join();
        var loads = new ArrayList<CompletableFuture<?>>();
        for (int x = -2; x <= 9; x++) for (int z = -1; z <= 5; z++) loads.add(world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(x, z), GetChunkFlags.SET_TICKING));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
        loads.clear();
        for (int x = -2; x <= 9; x++) for (int z = -1; z <= 5; z++) for (int sy = 0; sy < NativeSnapshotStore.MAX_Y / ChunkUtil.SIZE; sy++)
            loads.add(world.getChunkStore().getChunkSectionReferenceAsync(x, sy, z, GetChunkFlags.SET_TICKING));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
        var done = new CompletableFuture<Void>();
        world.execute(() -> {
            try { exercise(plugin, world); done.complete(null); } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.join();
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_HEX_PASS: hex claims, masked baselines, pack beside an overlapping neighbour, destination list, same shape restore, grown baseline, second move, road strand check");
    }

    private static void exercise(EterniaModPlugin plugin, World world) throws Exception {
        world.getWorldConfig().setSpawningNPC(false);
        world.getWorldConfig().setBlockTicking(false);
        var services = plugin.getServices();
        plugin.getRuntime().welcome(MIRA, "HexSmokeMira");
        plugin.getRuntime().welcome(NOOR, "HexSmokeNoor");
        Owner mira = Owner.player(MIRA);
        services.ownership().grant(new OwnershipService.GrantRequest("hex-smoke-size", mira, "eternia:plot/personal_32", OwnershipService.Kind.CAPABILITY, 1, null));
        services.ownership().grant(new OwnershipService.GrantRequest("hex-smoke-moves", mira, HousingService.MOVE_CREDIT, OwnershipService.Kind.QUANTITY, 2, null));
        services.ownership().grant(new OwnershipService.GrantRequest("hex-smoke-shelf", mira, "eternia:prop/potion_shelf", OwnershipService.Kind.QUANTITY, 1, null));
        services.economy().credit(mira, 1_000_000, "hex-smoke-coins");

        UUID cottage = HexFixtures.claim(plugin, world, NOOR, List.of(COTTAGE), HousingRules.Scope.PUBLIC, 0);
        UUID manor = HexFixtures.claim(plugin, world, MIRA, MANOR, HousingRules.Scope.PUBLIC, 0);
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        var plot = manager.getPlot(manor);
        var lattice = plot.getCells().lattice();
        var box = plot.getCells().bounds();
        if (!box.overlaps(manager.getPlot(cottage).getCells().bounds())) throw new IllegalStateException("Fixture needs neighbours whose boxes overlap");

        // A marker in the cottage, inside the manor's box: packing and restoring the manor must never touch it.
        int markX = lattice.minX(COTTAGE) + 28, markZ = lattice.minZ(COTTAGE) + 18;
        if (!box.contains(markX, markZ) || manager.plotAtColumn(markX, markZ) == null || !manager.plotAtColumn(markX, markZ).getPlotId().equals(cottage))
            throw new IllegalStateException("Marker column is not in the cottage inside the manor box");
        var marker = BlockType.getAssetMap().getAsset("Rock_Stone_Brick");
        if (!ChunkSectionBlockUtil.setBlock(world, markX, 1, markZ, marker, 0)) throw new IllegalStateException("Could not set the cottage marker");
        int markerId = ChunkSectionBlockUtil.blockId(world, markX, 1, markZ);

        // House on the manor's north west hexagon, a potion shelf (three decorative entities) on its south hexagon.
        var house = HexFixtures.centre(lattice, MANOR.getFirst(), 1);
        var placed = NativePlacementTransactions.place(plugin, world, plot, MIRA, "hub_house", house, Rotation.None, PrefabResolveUtil.resolvePrefabBuffer("House.prefab.json"), true);
        plot.setBuilding(new HubPlotBuilding("hub_house", house.x, house.y, house.z, Rotation.None, List.of()));
        manager.updatePlot(plot);
        manager.saveIfDirty();
        services.housing().updateBuildingPresent(mira, manor, true);
        NativePlacementTransactions.complete(plugin, placed.operation());
        var shelfAt = HexFixtures.centre(lattice, MANOR.get(2), 1);
        var shelf = NativePlacementTransactions.place(plugin, world, plot, MIRA, "potion_shelf", shelfAt, Rotation.None, PrefabResolveUtil.resolvePrefabBuffer(plugin.getPropCatalog().get("potion_shelf").getPrefabPath()), false);
        plot.addProp(new HubPlotProp(shelf.instanceId(), "potion_shelf", shelfAt.x, shelfAt.y, shelfAt.z, Rotation.None));
        manager.updatePlot(plot);
        manager.saveIfDirty();
        NativePlacementTransactions.complete(plugin, shelf.operation());
        Set<UUID> decorations = entityIds(world, plot);
        if (houseBlocks(world, house) == 0) throw new IllegalStateException("Fixture house has no blocks");
        if (decorations.size() != 3) throw new IllegalStateException("Potion shelf entities did not spawn on the manor");

        // Strand check: a road over the manor's hexagon is refused with the plot named.
        var claims = InfrastructureSetup.claims(plugin, world);
        var current = plugin.getInfrastructure().world(WORLD).orElseThrow();
        var areas = new java.util.TreeMap<>(current.areas());
        areas.put("hex-bad-road", new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD, new PlotRect(house.x + 8, house.z, 3, 3)));
        boolean refused = false;
        try { InfrastructureRules.validateHex(current, current.withAreas(areas), claims.hex()); } catch (IllegalArgumentException expected) { refused = expected.getMessage().contains("HexSmokeMira"); }
        if (!refused) throw new IllegalStateException("A road over a claimed hexagon was not refused with the plot's owner named");

        // Pack: the manor's cells return to their baseline; the cottage corner inside the manor's box is untouched.
        UUID move = UUID.randomUUID();
        plugin.getRelocation().pack(world, mira, move, false);
        if (services.housing().find(mira).orElseThrow().state() != HousingService.State.PACKED || manager.getPlot(manor) != null) throw new IllegalStateException("Hex pack did not take custody");
        if (ChunkSectionBlockUtil.blockId(world, markX, 1, markZ) != markerId || manager.getPlot(cottage) == null) throw new IllegalStateException("Packing the manor changed the neighbour's hexagon");
        if (houseBlocks(world, house) != 0) throw new IllegalStateException("Packing left the house standing");
        if (!NativeSnapshotStore.entities(world, NativeRelocationCoordinator.bounds(manager.getPlot(cottage))).isEmpty()) throw new IllegalStateException("Decorations leaked into the neighbour");

        // Destinations: the same shape, same turn only.
        var packedCells = PlotCells.of(lattice, MANOR);
        var destination = packedCells.translate(new HexCoord(3, 0));
        var options = plugin.getRelocation().restoreDestinations(world, mira, MIRA, HousingRules.Scope.PUBLIC, (int) lattice.centerX(destination.cells().getFirst()), (int) lattice.centerZ(destination.cells().getFirst()), 2, 40);
        if (!options.contains(destination) || options.stream().anyMatch(o -> !HexClaimRules.sameShape(o.cells(), MANOR))) throw new IllegalStateException("Destination list missed the free spot or listed another shape");
        var turned = List.of(new HexCoord(6, 1), new HexCoord(5, 2), new HexCoord(6, 2));
        if (plugin.getRelocation().restoreCheck(world, mira, MIRA, HousingRules.Scope.PUBLIC, turned).reason() != HexClaimRules.Reason.RESTORE_SHAPE) throw new IllegalStateException("A turned shape was offered");
        if (!plugin.getRelocation().restoreCheck(world, mira, MIRA, HousingRules.Scope.PUBLIC, destination.cells()).valid()) throw new IllegalStateException("A valid destination was refused");

        // Restore three hexagons east: everything moves by exactly the lattice offset.
        var shift = HexRelocation.move(packedCells, destination);
        plugin.getRelocation().restoreCells(world, MIRA, mira, move, destination, 0, HousingRules.Scope.PUBLIC);
        var restored = manager.getPlot(manor);
        if (restored == null || !destination.equals(restored.getCells())) throw new IllegalStateException("Restore did not land on the chosen hexagons");
        var building = restored.getBuilding();
        if (building == null || building.getAnchorX() != house.x + shift.dx() || building.getAnchorZ() != house.z + shift.dz() || building.getAnchorY() != house.y)
            throw new IllegalStateException("House did not move with its hexagons");
        if (houseBlocks(world, new Vector3i(building.getAnchorX(), building.getAnchorY(), building.getAnchorZ())) == 0 || houseBlocks(world, house) != 0) throw new IllegalStateException("Restored house is not where its hexagons went");
        if (!entityIds(world, restored).equals(decorations)) throw new IllegalStateException("Restore changed decoration identities");
        if (ChunkSectionBlockUtil.blockId(world, markX, 1, markZ) != markerId) throw new IllegalStateException("Restore changed the neighbour's hexagon");
        if (!HexLand.view(plugin, world).orElseThrow().plotAt(destination.cells().getFirst()).plotId().equals(manor)) throw new IllegalStateException("Claim map does not see the restored plot");

        // Grow by one hexagon: the domain commit, the extended baseline, then the projection.
        var slot = services.housing().find(mira).orElseThrow();
        var grow = UUID.randomUUID();
        var added = destination.cells().get(1).neighbor(0);   // east of the north east hexagon
        var out = services.housing().expandPlot(mira, manor, added, slot.revision(), grow, HexClaimRules.Kind.PUBLIC, HexLand.context(plugin, world).orElseThrow(), mira);
        if (!out.ok()) throw new IllegalStateException("Expansion refused: " + out.result().message());
        restored.setFootprint(restored.getFootprint().withCells(out.cells()));
        NativeRelocationCoordinator.extendBaseline(plugin, world, restored);
        NativeRelocationCoordinator.extendBaseline(plugin, world, restored);   // idempotent
        manager.updatePlot(restored);
        manager.saveIfDirty();
        services.housing().completeExpansion(mira, grow);

        // Second move: four hexagons back west, onto land the first pack freed.
        UUID second = UUID.randomUUID();
        plugin.getRelocation().pack(world, mira, second, false);
        var back = out.cells().translate(new HexCoord(-3, 0));
        plugin.getRelocation().restoreCells(world, MIRA, mira, second, back, 0, HousingRules.Scope.PUBLIC);
        var home = manager.getPlot(manor);
        if (home == null || !back.equals(home.getCells()) || home.getBuilding().getAnchorX() != house.x || !entityIds(world, home).equals(decorations))
            throw new IllegalStateException("Second move of the grown plot did not return everything");
        if (ChunkSectionBlockUtil.blockId(world, markX, 1, markZ) != markerId) throw new IllegalStateException("Second move changed the neighbour's hexagon");
        if (services.housing().find(mira).orElseThrow().state() != HousingService.State.ACTIVE || !services.journal().unfinished().isEmpty())
            throw new IllegalStateException("Hex moves left unfinished work");
    }

    private static Set<UUID> entityIds(World world, HubPlotRecord plot) {
        var ids = new HashSet<UUID>();
        for (var ref : NativeSnapshotStore.entities(world, NativeRelocationCoordinator.bounds(plot), ColumnMask.of(plot.getCells())))
            ids.add(ref.getStore().getComponent(ref, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType()).getUuid());
        return ids;
    }

    /** Non air blocks in the house's 9 x 9 footprint above the ground. */
    private static int houseBlocks(World world, Vector3i anchor) {
        int count = 0;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 1; y <= 8; y++)
            if (ChunkSectionBlockUtil.blockId(world, anchor.x + x, anchor.y + y - 1, anchor.z + z) != 0) count++;
        return count;
    }
}
