package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.placement.PlotFootprintUtil;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.joml.Vector3i;

/** Native acceptance fixture only; its caller enforces the isolated, empty local smoke environment. */
final class NativeGuildSmoke {
    private NativeGuildSmoke() {}

    /** Runs on the world thread after NativeSmoke's personal property exercise. */
    static void exercise(EterniaModPlugin plugin, World world) throws Exception {
        long started = System.nanoTime(), stage = started;
        var services = plugin.getServices();
        UUID leader = UUID.fromString("ffffffff-ffff-4fff-8fff-fffffffffff1");
        UUID architect = UUID.fromString("ffffffff-ffff-4fff-8fff-fffffffffff2");
        var members = List.of(architect,
            UUID.fromString("ffffffff-ffff-4fff-8fff-fffffffffff3"),
            UUID.fromString("ffffffff-ffff-4fff-8fff-fffffffffff4"),
            UUID.fromString("ffffffff-ffff-4fff-8fff-fffffffffff5"));
        for (int i = 0; i < members.size(); i++)
            plugin.getRuntime().welcome(members.get(i), "NativeGuildFixture" + (i + 2));
        var guild = services.guilds().create(leader, "Native Smoke Guild", "native-smoke-guild");
        for (UUID member : members)
            services.guilds().acceptInvite(member, services.guilds().invite(leader, member));
        services.guilds().assignRole(leader, architect, "architect");
        check(services.guilds().roster(leader).size() == 5 && services.guilds().eligibleForRoot(leader),
            "Five fixture members must qualify for the free guild estate");
        check(services.guilds().can(architect, guild.id(), "inventory.reserve_for_build"),
            "Architect must have actual guild build-inventory permission");
        Owner guildOwner = Owner.guild(guild.id()), memberOwner = Owner.player(architect);
        stage = stage(plugin, "five authenticated fixture accounts and Architect permissions", stage);

        UUID guildProperty = plugin.getClaims().claim(world, leader, new PlotRect(48, 8, 48, 48),
            0, HousingRules.Scope.GUILD_ROOT, false, UUID.randomUUID());
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        var guildPlot = manager.getPlot(guildProperty);
        check(guildPlot != null && guild.id().equals(guildPlot.getGuildOwnerUuid()),
            "Guild claim must persist its guild owner");
        var hall = placeHouse(plugin, world, guildPlot, leader, "guild_hall", new Vector3i(72, 1, 32));
        check(services.housing().find(guildOwner).orElseThrow().state() == HousingService.State.ACTIVE,
            "Guild root must be active before it anchors a member claim");
        stage = stage(plugin, "48x48 guild root and native Founders' Hall committed", stage);

        UUID memberProperty = plugin.getClaims().claim(world, architect, new PlotRect(100, 8, 24, 24),
            0, HousingRules.Scope.GUILD_MEMBER, false, UUID.randomUUID());
        var memberPlot = manager.getPlot(memberProperty);
        check(memberPlot != null && guild.id().equals(memberPlot.getAttachedGuildUuid()),
            "Personal fixture plot must be attached to this guild");
        var house = placeHouse(plugin, world, memberPlot, architect, "hub_house", new Vector3i(112, 1, 20));
        stage = stage(plugin, "attached member plot and native personal house committed", stage);

        String content = "eternia:prop/potion_shelf";
        services.ownership().grant(new OwnershipService.GrantRequest("native-smoke-guild-shelf",
            guildOwner, content, OwnershipService.Kind.QUANTITY, 1, null));
        var definition = Objects.requireNonNull(plugin.getPropCatalog().get("potion_shelf"));
        var shelfPrefab = Objects.requireNonNull(PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath()));
        var shelf = NativePlacementTransactions.place(plugin, world, memberPlot, architect, "potion_shelf",
            new Vector3i(112, 2, 20), Rotation.None, shelfPrefab, false, guildOwner);
        memberPlot.addProp(new HubPlotProp(shelf.instanceId(), "potion_shelf", 112, 2, 20, Rotation.None));
        manager.updatePlot(memberPlot);
        manager.saveIfDirty();
        NativePlacementTransactions.complete(plugin, shelf.operation());
        Set<UUID> decorativeIds = entityIds(world, memberPlot);
        check(decorativeIds.size() == 3, "Guild shelf must create three distinct native decorative entities");
        var originalShelf = services.provenance().find(shelf.instanceId()).orElseThrow();
        check(originalShelf.owner().equals(guildOwner) && originalShelf.placedBy().equals(architect)
                && originalShelf.propertyId().equals(memberProperty),
            "Placing from guild inventory must retain guild custody on the member's property");
        check(services.ownership().available(guildOwner, content) == 0
                && services.ownership().available(memberOwner, content) == 0,
            "Placed guild quantity must be held, never granted to the member");
        stage = stage(plugin, "guild-owned shelf placed by Architect; three native UUIDs recorded", stage);

        var roadProof = com.hexvane.eterniamod.guildroads.NativeGuildRoadSmoke
            .prepareForMemberPack(plugin, world, leader, architect);
        stage = stage(plugin, "native guild roads and current owner easement verified", stage);
        long moveCredits = services.ownership().available(memberOwner, HousingService.MOVE_CREDIT);
        UUID operation = UUID.randomUUID();
        plugin.getRelocation().pack(world, memberOwner, operation, true);
        com.hexvane.eterniamod.guildroads.NativeGuildRoadSmoke
            .assertMemberPackRestoredTerrain(plugin, world, roadProof);
        var packedSlot = services.housing().find(memberOwner).orElseThrow();
        check(packedSlot.state() == HousingService.State.PACKED && manager.getPlot(memberProperty) == null,
            "Forced pack must release the live projection while retaining the player's packed slot");
        check(entityIds(world, memberPlot).isEmpty(), "Forced pack must remove all three original guild entities");
        var packedShelf = services.provenance().ownedInstances(guildOwner).stream()
            .filter(instance -> instance.id().equals(shelf.instanceId())).findFirst().orElseThrow();
        check(packedShelf.state().equals("PACKED") && packedShelf.owner().equals(guildOwner)
                && packedShelf.sourceReference().equals(originalShelf.sourceReference()),
            "Guild inventory must retain the exact packed instance and its original reserved source");
        var snapshots = NativePlacementTransactions.snapshots(plugin);
        var guildSnapshot = snapshots.load(NativeRelocationCoordinator.parseReference(packedShelf.snapshotRef()));
        check(snapshotEntityIds(guildSnapshot.document()).equals(decorativeIds),
            "Guild return template must preserve all three original native UUIDs");
        var journal = services.journal().find(operation).orElseThrow();
        check(journal.kind().equals("FORCED_RETURN") && journal.state() == JournalService.State.PACKED,
            "Forced pack must use the durable forced-return journal");
        var manifest = BsonDocument.parse(new String(snapshots.files().read(
            new SnapshotFiles.Saved(journal.snapshotRef(), journal.snapshotHash())), StandardCharsets.UTF_8));
        var personalSnapshot = snapshots.load(NativeRelocationCoordinator.parseReference(manifest.getString("personal").getValue()));
        check(snapshotEntityIds(personalSnapshot.document()).isEmpty(),
            "Player's packed template must contain no guild decorative entities");
        Set<String> personalInstances = new HashSet<>();
        manifest.getArray("instances").forEach(value -> personalInstances.add(value.asString().getValue()));
        check(personalInstances.equals(Set.of(house.instanceId().toString())),
            "Player restore manifest must contain only the player's own house instance");
        var returns = manifest.getArray("guildReturns");
        check(returns.size() == 1
                && returns.getFirst().asDocument().getString("instance").getValue().equals(shelf.instanceId().toString())
                && returns.getFirst().asDocument().getString("snapshot").getValue().equals(packedShelf.snapshotRef()),
            "Manifest must identify the exact guild-owned return and verified snapshot");
        stage = stage(plugin, "forced member pack separated guild custody and verified both templates", stage);

        plugin.getRelocation().restore(world, architect, memberOwner, operation, new PlotRect(100, 48, 24, 24),
            0, HousingRules.Scope.PUBLIC, false);
        var restored = manager.getPlot(memberProperty);
        check(restored != null && restored.hasBuilding() && restored.getAttachedGuildUuid() == null
                && restored.getGuildOwnerUuid() == null && restored.isOwnedBy(architect),
            "Restored public house must have only its player owner and no guild attachment");
        check(restored.getBuilding().getAnchorX() == 112 && restored.getBuilding().getAnchorY() == 1
                && restored.getBuilding().getAnchorZ() == 60 && restored.getProps().isEmpty(),
            "Public restore must translate the player's house without transferring the guild shelf projection");
        check(entityIds(world, restored).isEmpty() && entityIds(world, memberPlot).isEmpty(),
            "Guild entities must remain absent from both the vacated and restored player plots");
        var restoredHouse = services.provenance().find(house.instanceId()).orElseThrow();
        check(restoredHouse.state().equals("PLACED") && restoredHouse.owner().equals(memberOwner)
                && restoredHouse.sourceReference().equals("unlock:eternia:house/hub_house"),
            "Restoration must preserve the player's house instance and original unlock source");
        var houseAfter = snapshots.load(new SnapshotFiles.Saved(restoredHouse.nativeData().get("after"),
            restoredHouse.nativeData().get("afterHash")));
        check(houseAfter.document().equals(house.after().document()),
            "Moving between equal flat terrain must preserve the house's original blocks and components exactly");
        snapshots.verify(world, houseAfter, houseAfter.bounds().origin(), Set.of(house.instanceId()), true);
        var stillPacked = services.provenance().find(shelf.instanceId()).orElseThrow();
        check(stillPacked.equals(packedShelf), "Restoring the player must not mutate the guild's packed custody record");
        check(services.provenance().ownedInstances(memberOwner).stream().noneMatch(i -> i.contentId().equals(content))
                && services.ownership().available(memberOwner, content) == 0
                && services.ownership().available(guildOwner, content) == 0,
            "Forced pack and restore must neither transfer nor duplicate the guild's reserved quantity");
        var restoredSlot = services.housing().find(memberOwner).orElseThrow();
        check(restoredSlot.state() == HousingService.State.ACTIVE && restoredSlot.attachedGuild() == null
                && services.ownership().available(memberOwner, HousingService.MOVE_CREDIT) == moveCredits,
            "Forced relocation must clear the attachment and preserve the member's free move credit");
        snapshots.verify(world, hall.after(), hall.after().bounds().origin(), Set.of(hall.instanceId()), true);
        check(services.provenance().find(hall.instanceId()).orElseThrow().owner().equals(guildOwner)
                && services.housing().find(guildOwner).orElseThrow().state() == HousingService.State.ACTIVE,
            "Moving a member must leave the guild hall and its ownership intact");
        check(services.journal().unfinished().isEmpty(), "Guild smoke must leave no unfinished world mutations");
        stage(plugin, "public restore preserved own house and guild hall; guild shelf remains packed", stage);
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_GUILD_SMOKE_PASS: mixed-owner forced relocation; durationMs="
            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static NativePlacementTransactions.Placed placeHouse(EterniaModPlugin plugin, World world,
            HubPlotRecord plot, UUID actor, String catalogId, Vector3i origin) throws Exception {
        var definition = Objects.requireNonNull(plugin.getBuildingCatalog().get(catalogId));
        var prefab = Objects.requireNonNull(PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath()));
        var footprint = PlotFootprintUtil.computeFootprint(origin, Rotation.None, prefab);
        var rule = HousingRules.structure(NativeHousingChecks.rect(plot.getFootprint()),
            NativeHousingChecks.rect(footprint), plugin.getInfrastructure().world(world.getName()).orElseThrow().roads());
        check(rule.valid(), "Fixture house must follow actual native structure setbacks: " + rule.message());
        var placed = NativePlacementTransactions.place(plugin, world, plot, actor, catalogId,
            origin, Rotation.None, prefab, true);
        plot.setBuilding(new HubPlotBuilding(catalogId, origin.x, origin.y, origin.z, Rotation.None, List.of()));
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        manager.updatePlot(plot);
        manager.saveIfDirty();
        plugin.getServices().housing().updateBuildingPresent(NativePlacementTransactions.owner(plot), plot.getPlotId(), true);
        NativePlacementTransactions.complete(plugin, placed.operation());
        return placed;
    }

    private static Set<UUID> entityIds(World world, HubPlotRecord plot) {
        Set<UUID> ids = new HashSet<>();
        var entities = NativeSnapshotStore.entities(world, NativeRelocationCoordinator.bounds(plot));
        for (var ref : entities) {
            var component = ref.getStore().getComponent(ref, UUIDComponent.getComponentType());
            check(component != null && ids.add(component.getUuid()), "Native entities must have unique persistent UUIDs");
        }
        return Set.copyOf(ids);
    }

    private static Set<UUID> snapshotEntityIds(BsonDocument document) {
        Set<UUID> ids = new HashSet<>();
        for (var value : document.getArray("entities", new BsonArray())) {
            var holder = EntityStore.REGISTRY.deserialize(value.asDocument());
            var component = holder.getComponent(UUIDComponent.getComponentType());
            check(component != null && ids.add(component.getUuid()), "Saved entities must have unique persistent UUIDs");
        }
        return Set.copyOf(ids);
    }

    private static long stage(EterniaModPlugin plugin, String label, long since) {
        long now = System.nanoTime();
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_GUILD_SMOKE_STAGE: " + label
            + "; durationMs=" + TimeUnit.NANOSECONDS.toMillis(now - since));
        return now;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
