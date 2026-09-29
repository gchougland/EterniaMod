# Housing customization and physical tools

The housing ledger opens native house management, customization, mailbox and shop menus. Housing can share the Hub world: use `/e admin setup` and choose **World role → Hub with player and guild housing**. See [world and Hub NPC setup](world-setup.md); no JSON editing is required. Worlds with housing enabled use Eternia's house, prop, palette and path tools: ordinary hand block placement, bucket placement/refilling, and free fluid/fire simulation are disabled throughout that world. Administrators can continue constructing checked public roads through setup's **Pave road** action. The existing management shelf uses the ledger interaction. `Eternia_Plot_Deed` opens the same authenticated claim page as the housing NPC; `Eternia_Architect_Ledger` opens the services menu. Reissuing a missing physical tool grants no plot, movement credit, currency or content rights. Tools have no crafting recipe or fuel/charcoal resource outputs.

## Add a palette or path style

Add bundled JSON to `src/main/resources/Server/EterniaMod/Palettes` or `PathStyles` and list its filename in that folder's `catalog.index`. Restart after changes. A server can override or add definitions in its plugin data directory's `Palettes` and `PathStyles` folders. Definitions load in deterministic order; duplicate IDs within one layer, invalid IDs, missing manifests and malformed definitions reject startup.

The shipped palette is:

```json
{"id":"cut_stone","name":"Cut stone masonry","houseId":"hub_house","blocks":{"Rock_Stone_Cobble":"Rock_Stone_Brick"}}
```

Grant the property owner `eternia:palette/cut_stone` as an unlock. A palette selects positions from the original authored house snapshot, then changes only those source-material positions in the current verified house. Source and destination must be valid native plain cube blocks without block entities. Functional blocks, multiblock filler cells and protected columns are rejected. The original surface mask survives repeated palettes and whole-house relocation; it does not discover player-placed surfaces by their current material. Guild users also require `housing.palette`.

A path definition is:

```json
{"id":"cobblestone","name":"Cobblestone path","blockId":"Rock_Stone_Cobble"}
```

Grant `eternia:path/cobblestone` to the property owner. Stand on level natural ground outside the door and choose the path style. The bounded four-neighbor search routes around placed catalog objects and stops on the owner's side within five columns of a public or guild community road. The route contains at most 128 cells, remains inside the lot, does not replace road or portal columns, and rejects fluids, functional blocks and multiblock fillers. This first tool handles flat paths; it does not terraform slopes or construct outside the lot. A guild member also requires `housing.road.manage`. Remove the existing path before choosing a new route.

Both tools show a native confirmation page with green markers for up to 128 affected cells and the complete affected block count. A preview expires after 90 seconds. Markers are sent only to that player and expire independently; closing a page or shutting down cancels refresh tasks.

## Persistence and recovery

Native edits use verified sparse before/after snapshots, an operation journal and a final native chunk flush. Confirmation checks ownership, permissions, current property state, protected columns, provenance revision and the exact affected block/component/fluid values again. Path confirmation also checks that another path or catalog object has not been placed since preview. Palette changes update the existing provenance record without changing its owner, grant source or instance identity. Path removal restores its saved ground and keeps its instance packed for reuse.

An interrupted mutation locks the owner for recovery instead of retrying blindly. The operator hook is `CustomizationBootstrap.service().rollback(world, operationId)` on that world's thread; the command integration routes unfinished `CUSTOMIZE_*` journals there. It verifies snapshot hashes and restores only cells whose current value is a recognized before/after value. Unexpected edits or changed ownership require review. Completed and cancelled operations cannot be rolled back through this hook.

## Native protection scope

Catalog house/prop volumes, including authored air, are protected against ordinary player placement, breaking and block damage. Paths protect only their exact sparse ground cells. Use the ledger's pack and customization tools to change these objects. This prevents reusable house styles and palettes from yielding native crafting materials. Breaking terrain outside catalog volumes still requires `housing.block.break`; separate guild `housing.block.build` checks remain in the event adapter, but the housing gameplay policy disables ordinary native hand placement. Shipped prefab components contain no seeded item inventories; keep future reusable templates free of tradeable contents.

The supplemental native systems also validate every rotated filler cell before a multiblock is placed and cancel actorless block damage throughout housing worlds and protected infrastructure columns. Missing provenance/snapshot data fails closed. Snapshot masks are cached by immutable instance revision with a bounded cache.

Guild community roads join the dynamic all-height protected column set, including roads awaiting recovery. House/addition placement, restoration and palette setback checks include both public and guild roads; the personal path tool may approach either. Claim topology continues to use only the administrator's public roads and portal registry, so a guild road cannot establish public anchors. The trusted road journal adapter uses a world-thread `HousingInfrastructure.withGuildRoadWrite` scope limited to its exact straight segment (at most 128 columns) when restoring or changing that road. It cannot bypass public roads or portals, does not change structure setback lists, and restores nested scopes after errors. Other players and threads retain the road protection throughout the operation.

Guild block use is classified by the loaded native interaction and block features: doors require `housing.door.use`, beds `housing.bed.use`, ordinary crafting benches `housing.bench.use`, and the ledger `housing.visit`. Actual containers retain `housing.container.open`; processing benches also require it because their input/output inventory is shared. Unknown interaction behavior keeps the restrictive container permission. Guild pages show online/offline roster status and offer member removal and leadership transfer with named confirmation; current membership, permission and rank are checked again by the domain service at commit.

| Verified native behavior | Required capability |
|---|---|
| `IsDoor` plus `DoorInteraction` | `housing.door.use` |
| Native bed mount points plus `BedInteraction` | `housing.bed.use` |
| Native bench configuration plus `OpenBenchPageInteraction` | `housing.bench.use` |
| `ProcessingBench` or `OpenProcessingBenchInteraction` | Both `housing.bench.use` and `housing.container.open` |
| Runtime/prototype `ItemContainerBlock`, `OpenContainerInteraction`, `OpenItemStackContainerInteraction`, or `OpenTreasureContainerInteraction` | `housing.container.open`; this takes precedence over decorative door/bed labels |
| Exact `Eternia_Management_Block` with only `EterniaHousingTool` behavior and no inventory | `housing.visit`; each ledger action checks its own permissions |
| Exact `Eternia_World_Portal` with only `EterniaHousingTool` behavior and no inventory | Public use at registered portal columns; otherwise normal `housing.visit`. Selecting travel separately requires public proximity or a current home/guild teleporter entitlement |
| Unknown, unresolved, or oversized interaction graph | `housing.container.open` |

This is enforced at `UseBlockEvent.Pre`, which native `UseBlockInteraction` dispatches before calling the selected block's own interaction. It does not infer permissions from strings such as “door” in an arbitrary item ID. Interaction traversal is bounded at 128 entries and depth 32. Guild rosters show live connection presence when opened or refreshed; they do not display a cached last-login value as “online.”

Block events alone cannot contain fluids: `PlaceFluidInteraction.interactWithBlock` and `RefillContainerInteraction.firstRun` write fluid cells without a cancellable placement/use event, while fire emits a non-cancellable `EnvironmentBreakBlockEvent`. Therefore `HousingWorldPolicy` assigns the dedicated `Eternia_Housing` gameplay asset, whose `World.AllowBlockPlacement=false` is checked by both native fluid entry points before any mutation. It also adds `Fluid` to `WorldConfig.DisabledFluidTickers`, suppressing all native fluid families including Fire. The loaded ticking fluid catalog must carry the inherited `Fluid` tag; new fluid content must preserve that tag. This policy applies at startup and infrastructure reload to worlds with role `housing`, and to role `hub` entries with `housingEnabled: true`. It affects the whole combined Hub world, including its public plaza; it is not a plot-local gameplay setting. NPC service interactions and public portals remain available. Pure hubs and adventure worlds keep their own settings; enabling housing on an adventure role is rejected.

The native policy intentionally disables hand block placement along with fluid buckets. Eternia's checked house/prop/palette/path writes use `NativeSnapshotStore.apply` and `BlockOperations.setBlock` directly, so they remain available. Native block gathering is also disabled to avoid extracting renewable template content through a gathering interaction. Avoid third-party editing interactions or direct fluid/block writes in housing unless they use Eternia's checked native adapter; native server plugins can bypass gameplay permissions by writing world state directly.

Previous gameplay and disabled ticker settings are persisted by native world UUID in `housing-world-policy.json` before applying the restriction. Removing a housing role, or turning off the optional housing flag on a hub, restores each field only if its current value still matches Eternia's applied value; independent operator changes are retained. Do not turn off housing on a live claimed world as a building shortcut: its housing event protections would also stop. A pre-existing `Eternia_Housing` setting with no saved history is retained rather than guessing its former configuration. Call `HousingWorldPolicy.refreshAll()` after infrastructure reload and await its future outside any world thread; never block a world thread waiting for itself.

Source evidence: [PlaceFluidInteraction](../../../HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/modules/interaction/interaction/config/client/PlaceFluidInteraction.java), [FireFluidTicker](../../../HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/asset/type/fluid/FireFluidTicker.java), [EnvironmentBreakBlockEvent](../../../HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/event/events/ecs/EnvironmentBreakBlockEvent.java), [BlockPlaceUtils](../../../HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/modules/interaction/BlockPlaceUtils.java), [BlockHarvestUtils](../../../HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/modules/interaction/BlockHarvestUtils.java), [ExplosionUtils](../../../HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/entity/ExplosionUtils.java). These are source observations; native startup and client interaction checks are separate validation steps.
