# Codebase audit for the Eternia implementation plan

> Implementation update: server shutdown no longer runs `syncAssets`. Both `runServer` and `runServerNoSync` retain source resources. `syncAssets` is a manual import only; the original planning notes below describe the earlier behavior.

This audit is based on source and resource inspection on September 12, 2026. **Existing behavior below is implemented in source, not verified in a running Hytale client or server during this planning task.** No compilation or gameplay tests were run. Paths are relative to this document unless an external repository is named.

## Eternia's current foundation

Eternia already has a useful housing placement prototype: administrators create rectangular plots and assign players, players place a prefab house or props using tokens, and placements are recorded per world. It is a good starting point for the placement experience. It is not yet the public housing, guild, or account platform described in the feature request.

| Area | Existing source and behavior | Planning decision |
| --- | --- | --- |
| Plugin lifecycle | [EterniaModPlugin.java](../../src/main/java/com/hexvane/eterniamod/EterniaModPlugin.java) loads building and prop catalogs, registers commands and placement systems, and sends common UI assets to joining clients. | Retain client asset delivery; split feature registration into modules as systems are added. |
| Administrative plots | [EterniaPlotsCommand.java](../../src/main/java/com/hexvane/eterniamod/command/EterniaPlotsCommand.java) creates 4–128 block rectangles, assigns/unassigns owners, lists/removes plots, and toggles outlines. Commands require the WorldEditor permission group. | Keep admin tooling, but route it through the same new claim/move services as player actions, with explicit audited admin overrides. Current creation does not check overlap, roads, anchors, entitlement, or one-plot limits. |
| Plot ownership and contents | [HubPlotRecord.java](../../src/main/java/com/hexvane/eterniamod/hub/HubPlotRecord.java) holds plot/world/owner IDs, footprint, one building, and a prop list. | Migrate to a housing domain supporting player and guild owners, claim provenance, object ownership, revision numbers, and lifecycle state. Rename the `hub` concept because the housing world is separate from the hub. |
| Persistence | [HubPlotManager.java](../../src/main/java/com/hexvane/eterniamod/hub/HubPlotManager.java) loads/saves `worlds/<sanitized-world-name>/hub_plots.json`, with a temporary file and one backup. [EterniaWorldRegistries.java](../../src/main/java/com/hexvane/eterniamod/hub/EterniaWorldRegistries.java) keeps managers by world name and saves on unload. | Preserve existing records via an importer; use durable, versioned repositories and recoverable operations for public release. Atomic JSON replacement alone cannot transact inventory, chunks, entities, and metadata together. |
| Building selection and placement | [BuildingPlacementPage.java](../../src/main/java/com/hexvane/eterniamod/ui/BuildingPlacementPage.java), [BuildingPlacementCameraUtil.java](../../src/main/java/com/hexvane/eterniamod/placement/BuildingPlacementCameraUtil.java), and [BuildingPlacementClientPrefabPreview.java](../../src/main/java/com/hexvane/eterniamod/placement/BuildingPlacementClientPrefabPreview.java) provide prefab previews, rotation, nudging, and a bird's-eye camera with pan/zoom. | Retain and generalize the interaction primitives for plot claim and housing inventory flows. Claim confirmation and claim-token eligibility are new features. |
| Placement outlines | [BuildingPlacementWireframeOverlay.java](../../src/main/java/com/hexvane/eterniamod/placement/BuildingPlacementWireframeOverlay.java) draws a plot boundary and building bounds, using white for valid and red for invalid. [HubPlotBoundaryWireframe.java](../../src/main/java/com/hexvane/eterniamod/hub/HubPlotBoundaryWireframe.java) provides reusable boundary rendering. | Add the requested green/red ground grid and a textual reason list. Current rendering is a wireframe, not the complete requested claiming grid. Check coexistence with other overlays because clearing uses `ClearDebugShapes`. |
| Management block | [EterniaManagementBlock.java](../../src/main/java/com/hexvane/eterniamod/hub/EterniaManagementBlock.java), [ManagementBlockLinker.java](../../src/main/java/com/hexvane/eterniamod/hub/ManagementBlockLinker.java), and [BuildingPickupPage.java](../../src/main/java/com/hexvane/eterniamod/ui/BuildingPickupPage.java) link a building management block to a plot and expose pickup. | Turn this into the housing management entry point. Crafting/redeeming plot tokens, catalog inventory, mail, shop, permissions, additions, and upgrades need new services and UI. |
| Props | [PropPlacementPage.java](../../src/main/java/com/hexvane/eterniamod/ui/PropPlacementPage.java), [PropPlacementCommit.java](../../src/main/java/com/hexvane/eterniamod/placement/PropPlacementCommit.java), and [PropPackageCommit.java](../../src/main/java/com/hexvane/eterniamod/placement/PropPackageCommit.java) place props from item metadata and package an intact prop back into a token with a wand. | Keep preview and object selection; replace token-only ownership with an authoritative inventory/entitlement service and provenance on every placed instance. |
| Prefab operations | [BuildingPrefabOps.java](../../src/main/java/com/hexvane/eterniamod/prefab/BuildingPrefabOps.java), [PropPrefabOps.java](../../src/main/java/com/hexvane/eterniamod/prefab/PropPrefabOps.java), and [PrefabEntityOps.java](../../src/main/java/com/hexvane/eterniamod/prefab/PrefabEntityOps.java) place blocks, handle furniture/block entities, and link spawned entities to instances. | Retain the Hytale-specific mechanics behind a transactional world-edit adapter. Add operation results, chunk readiness, exact snapshots, and recovery before relocation or guild eviction. |
| Authoring tools | [PrefabBrowserPage.java](../../src/main/java/com/hexvane/eterniamod/ui/PrefabBrowserPage.java) and [PrefabDefinitionFactory.java](../../src/main/java/com/hexvane/eterniamod/prefab/PrefabDefinitionFactory.java) support inspecting/registering prefab content. | Retain as admin authoring tools; add schema validation and author diagnostics rather than requiring Java edits for each content item. |

## Extensibility already present, and its limits

[BuildingDefinition.java](../../src/main/java/com/hexvane/eterniamod/building/BuildingDefinition.java) and [PropDefinition.java](../../src/main/java/com/hexvane/eterniamod/prop/PropDefinition.java) describe IDs, display names, prefab paths, anchor offsets, and default rotation. Buildings additionally declare a management-block position. Local data-directory JSON definitions override bundled IDs.

The loaders do **not** currently discover all bundled definition files. [BuildingCatalog.java](../../src/main/java/com/hexvane/eterniamod/building/BuildingCatalog.java) hardcodes `hub_house.json`; [PropCatalog.java](../../src/main/java/com/hexvane/eterniamod/prop/PropCatalog.java) hardcodes `aqua_lamp.json`. The resources also contain `potion_shelf.json` and `cacti.json`, but merely packaging these extra files does not enroll them in the classpath catalog. Replace the hardcoded lists with a generated catalog index or explicit content-pack manifest, keeping deterministic overrides and validation.

Useful existing content examples are [hub_house.json](../../src/main/resources/Server/EterniaMod/Buildings/hub_house.json), [aqua_lamp.json](../../src/main/resources/Server/EterniaMod/Props/aqua_lamp.json), and [potion_shelf.json](../../src/main/resources/Server/EterniaMod/Props/potion_shelf.json). Treat these as demonstration content, not an obligation to use their final IDs or house dimensions.

Definitions currently lack content versions, categories, acquisition/reward IDs, player/guild eligibility, attachment sockets, palette regions, spawn markers, interaction capabilities, pet constraints, and entitlement requirements. Add these intentionally through the planned schemas. Validate offset lengths: the current readers can return a non-null array of the wrong length and callers index three entries.

## Feature gaps

No implementation of the following requested systems was found in the inspected source/resource inventory:

- Hub NPC services, Prowl greeter/dialogue, world selector, or a minigame service with a Coming Soon entry.
- Player plot-claim token flow, claim eligibility, public roads and protected road columns, public/guild anchor connectivity, portal proximity, paid plot-size entitlements, or whole-plot relocation.
- House additions, palette application, path creation, housing inventory/reward ledger, property-roaming pets, and a follower-pet system.
- Last-logout tracking and safe return-home spawning after more than 30 minutes.
- Guild membership, roles, permissions, guild-owned content, guild management UI, shared inventory, or delayed guild-member eviction.
- Offline mail and attachment escrow, marketplace listings/purchases, online presence, parties, or guild message boards.
- Tebex fulfillment, supporter subscriptions, premium currency accounting, prefix/suffix titles, and character cosmetics.
- Season pass definitions, permanent archived passes, per-pass progress, quest objectives, XP collection/anti-farming rules, or reward claims.
- Account linking, website authentication, account-stat projections, web APIs, or storefront integration.

These are new domains. Plan for small vertical slices with representative content rather than treating them as options added to the current placement page.

## Correctness work required before public housing

### Placement and claim validation

[BuildingPlacementValidator.java](../../src/main/java/com/hexvane/eterniamod/placement/BuildingPlacementValidator.java) checks ownership, a vacant building slot, prefab availability, horizontal containment, and some solid content. [PropPlacementValidator.java](../../src/main/java/com/hexvane/eterniamod/placement/PropPlacementValidator.java) adds solid collision checking. Neither applies the requested setbacks, road protection, claim adjacency, guild permissions, or content entitlements.

Place all authoritative checks in the mutation service and repeat them immediately before commit under the relevant claim/object reservation. The current UI validates before invoking commit, but the commit methods themselves are not a suitable authorization boundary for future commands, NPCs, scheduled jobs, and website requests.

[HubPlotFootprint.java](../../src/main/java/com/hexvane/eterniamod/hub/HubPlotFootprint.java) mixes visual height with containment and supports rectangles with inclusive coordinates. Separate legal horizontal claim geometry, build limits, protected columns, and visual bounds. Preserve coordinate conventions in the importer. Distances and five-block margins need explicit geometry rules and boundary tests.

Building placement carves explicit prefab air as well as placing solids. Road protection therefore must validate **every mutated cell**, including clearing, terrain restoration, additions, palettes, and paths; checking only solid prefab bounds is insufficient. Public road columns must remain protected above and below road level even when a plot overlaps the road.

### Transactions and recovery

[BuildingPlacementCommit.java](../../src/main/java/com/hexvane/eterniamod/placement/BuildingPlacementCommit.java) and [PropPlacementCommit.java](../../src/main/java/com/hexvane/eterniamod/placement/PropPlacementCommit.java) consume the item before resolving and applying the prefab. Failures after consumption have no compensating refund path in these methods. They can also report success after `saveIfDirty()` logs a persistence failure. [BuildingPickupCommit.java](../../src/main/java/com/hexvane/eterniamod/placement/BuildingPickupCommit.java) removes the building and saves metadata before granting the return token, which requires a recoverable protocol to prevent loss or duplicate rewards on crashes.

Use durable operation IDs and a staged state machine for placing, packaging, moving, upgrading, and evicting. Reserve ownership/token balances and source/target plots; validate and snapshot; apply edits on the world thread in bounded work units; verify; finalize metadata and delivery; recover or compensate incomplete operations after restart. A SQL transaction does not itself make world chunks and player inventories transactional.

World mutation helpers currently skip some unavailable chunk-section cells. An operation must ensure all affected chunks are loaded and remain available, record failures, and refuse to finalize a partial edit. Prefab entity spawning/removal is queued using `world.execute`; include queued entity work in completion verification instead of assuming metadata persistence proves completion.

### Snapshots and object provenance

[ReplacedBlockCell.java](../../src/main/java/com/hexvane/eterniamod/hub/ReplacedBlockCell.java) stores coordinates, block ID, and rotation. [WorldBlockSnapshotUtil.java](../../src/main/java/com/hexvane/eterniamod/prefab/WorldBlockSnapshotUtil.java) captures only nonempty blocks. This is terrain restoration metadata, not a full movable house template. It does not preserve arbitrary block-entity components/container contents, fluids, and player edits as a portable property package.

Building pickup uses the current catalog prefab and removes only cells still matching that prefab; changed/player-added blocks can remain behind. The returned item identifies the building style. It does not include the whole decorated property. Prop records store instance ID, catalog ID, world anchor, and rotation, without the player/guild ownership provenance needed to return guild-owned items separately.

Before implementing moves or 48-hour evictions, define a versioned property snapshot with local transforms, building/addition/palette versions, placed-instance provenance, approved block/entity state, pets, and safe delivery references. Keep source-terrain restoration data separate from the portable property contents. Pin prefab content versions or persist the operation's applied cells so updating a catalog prefab does not change how an old placement is removed.

### Protection and scale

[PropBreakBlockSystem.java](../../src/main/java/com/hexvane/eterniamod/hub/PropBreakBlockSystem.java) blocks player break events on registered prop cells. [ManagementBreakBlockSystem.java](../../src/main/java/com/hexvane/eterniamod/hub/ManagementBreakBlockSystem.java) protects linked management blocks. These are useful hooks but do not constitute general claim protection. Audit placement, breaking, interactions/containers, entities, explosions/environment changes, and every Eternia world-edit path against the same authorization policy.

The current plot manager linearly scans plot collections. Prop protection resolves prefabs while scanning a plot's props. Introduce a world/chunk spatial index and cached instance bounds before the housing population grows. Keep index data rebuildable from durable plot and object records.

## Migration plan implications

1. Back up existing plugin data and corresponding worlds together. Stop writes during export/import and retain the original `hub_plots.json` files unchanged for rollback.
2. Treat the current JSON format as legacy schema 0: [HubPlotWorldFile.java](../../src/main/java/com/hexvane/eterniamod/hub/HubPlotWorldFile.java) has no explicit schema version. Add an idempotent import manifest and a per-record outcome report.
3. Preserve plot IDs, owner UUIDs, world mapping, inclusive X/Z bounds, building/prop anchors and rotations, and replaced terrain records. Map old content IDs through an explicit alias table if renamed.
4. Resolve worlds to stable IDs. Current filenames sanitize world names by replacing punctuation, so distinct world names can collide; do not derive the new canonical world identity from a sanitized directory alone.
5. Report overlaps, duplicate-owner claims, missing definitions/assets, malformed bounds, unassigned plots, and placements that violate new size/setback/road rules. Existing admin plots can have arbitrary sizes. Quarantine or explicitly grandfather these records for operator review; do not silently delete or resize them.
6. Import known object provenance as `legacy-player` only where the plot owner is known. Legacy data cannot prove a guild-owned object or restore state that was never captured. Report those limitations instead of fabricating ownership or granting duplicate new rewards.
7. Rebuild spatial and entity indexes, compare record counts and sampled world contents, and validate that every imported object has a stable owner and instance identity. Confirm existing snapshots remain terrain snapshots, not relocation entitlements.
8. Exercise import twice on a copy and verify no duplicated plots, objects, inventories, or grants. Rehearse rollback with the matching world snapshot before enabling production writes.

## Validation to schedule during implementation

[build.gradle.kts](../../build.gradle.kts) targets Java 25 and configures JUnit 5, but no test sources were found. It includes `verifyReleaseJar` to prevent bundling HytaleServer and related blocked artifacts. Do not describe these checks as passed based on this audit.

Add meaningful tests around the new domain boundaries:

- Geometry: five-block boundaries, road-column intersection at all heights, rotated footprints/additions, non-square plots, overlap and simultaneous claims, public versus guild anchors, and disconnected guild chains.
- Transactions: failure after reservation, token debit, each chunk-edit stage, metadata write, and reward delivery; restart recovery and duplicate command/job delivery; full player inventory; missing chunks and assets.
- Ownership: per-object player/guild provenance, role changes while a menu is open, guild departure/rejoin timing, 48-hour deadline persistence, guild inventory return, and whole-property move integrity.
- Persistence/content: legacy import repeatability, corrupted data behavior, world-name collision handling, catalog discovery, invalid definitions, and old prefab-version pickup/migration.
- Runtime integration: client asset delivery, claim grid colors and explanations, camera reset on cancellation/disconnect/world change, furniture/block entities, multi-chunk basements, safe spawn selection, environmental protection, and bounded work per tick.

When testing resources in a local server, use the existing `runServerNoSync` task if editing source assets during the session. The normal `runServer` task finalizes with `syncAssets`, which copies build resources back into source and can overwrite concurrent source edits. Pin the currently dynamic `hytale-mod` plugin version (`0.+`) and the validated server build for reproducible implementation checkpoints.

## Aetherhaven reference audit

The following references were inspected in the local Aetherhaven repository. They are implementation examples to adapt into Eternia's own services and asset namespace, not dependencies that Eternia must load. No Aetherhaven files were changed. Source presence establishes a reference pattern; it does not replace validation against Eternia's pinned server and client build.

| Eternia feature | Verified Aetherhaven reference | What to adapt and what remains new |
|---|---|---|
| Prowl greeter | [Prowl model definition][ah-prowl], [model][ah-prowl-model], [texture][ah-prowl-texture], [icon][ah-prowl-icon], [human parent][ah-human] | The model explicitly inherits `Aetherhaven_Human`, which inherits `Player` and supplies talking/waving/emote animations. Copy or adapt the dependency chain and rewrite asset IDs. Use a new stationary Eternia greeter role and dialogue; the existing [Prowl townsfolk record][ah-prowl-character] is part of Aetherhaven's resident system. |
| Placement menu and session | [PlotPlacementPage][ah-placement-page], [PlotPlacementSession][ah-placement-session], [PlotPlacementCommit][ah-placement-commit] | Reuse the separation of preview/session/UI/commit and the rotation/nudge controls. The page's second confirmation currently applies to **moving** a building; Eternia must also confirm every initial plot claim. Keep authorizations in the committing service. |
| Grid and building preview | [PlotPlacementWireframeOverlay][ah-overlay], [PlotPlacementClientPrefabPreview][ah-ghost] | Current wireframe is an axis-aligned box: **valid white, invalid red**, nearby plots silver. It is not a green/red terrain grid. Add Eternia's terrain-projected claim cells and violation explanations. The prefab ghost caches block/fluid/entity payloads by construction and rotation, then sends position-only updates while nudging. |
| Bird's-eye placement | [PlotPlacementCameraUtil][ah-camera], [placement page][ah-placement-page] | Existing server-camera packets provide top-down cursor aiming, pan and zoom. Distance is currently clamped to 12–48 blocks, with default 20; large guild plots require different framing. Verify camera/ghost cleanup on cancellation, disconnect, world transfer and failure. |
| Claim and footprint rules | [PlotPlacementValidator][ah-validator], [PlotFootprintUtil][ah-footprint] | Existing checks concern town permission, town territory, charter overlap and other building footprints. Eternia's road-column reservations, public/guild anchor graph, setbacks and claim entitlements are new rules. Reuse geometry helpers without carrying over town semantics. |
| Plot moves and packaging | [PlotBuildingRelocation][ah-relocation], [PropPlotTeardown][ah-prop-teardown] | Existing relocation clears authored prefab cells and rebuilds the authored prefab with palette choices; it packages intersecting props and coordinates shop/portal metadata. It does **not** capture and move an arbitrarily edited, furnished whole property. Implement Eternia's durable snapshots, ownership separation and recoverable move transaction before consuming move tokens or evicting plots. |
| Housing Ledger and tokens | [town planning desk asset][ah-desk], [TownPlanningCraftingWindow][ah-desk-window], [PlotCraftingPage][ah-crafting-page] | Aetherhaven separates a tools/misc crafting desk from the building-token crafting bench, which includes catalog browsing and a 3D preview. Adapt these into Eternia's Housing Ledger and housing inventory. Town-charter progression, treasury costs and construction wait times are separate Aetherhaven choices. |
| House content definitions | [ConstructionCatalog][ah-construction-catalog], [house definition example][ah-house] | JSON catalogs expose stable IDs, prefab paths, anchors, management-block positions and style IDs. Eternia adds player/guild eligibility, content versions, safe spawn markers, palette slots, addition sockets and explicit placement bounds. Keep acquisition rules separate from asset geometry. |
| Decorative props | [PropCatalog][ah-prop-catalog], [PropInstance][ah-prop-instance], [prop definition example][ah-prop-example] | Catalogs load shipped definitions and authored overrides. Prop instances track pose and linked entity/trigger-volume IDs, but **do not record player-versus-guild ownership or entitlement provenance**. Add that provenance before guild items can be placed on personal properties. |
| Palettes and upgrades | [BlockPaletteCatalog][ah-palette-catalog], [BlockPaletteApplyService][ah-palette-apply], [palette definitions][ah-palettes], [explicit remap example][ah-remap-example] | Reuse unlock/catalog/remap patterns. Existing application uses prefab-associated cells and current block types. Eternia should declare compatible roof/wall/floor slots and preserve customizations and content ownership while applying a palette. |
| Personal paths and guild roads | [PathSplineUtil][ah-path-spline], [PathGrounding][ah-path-grounding], [PathCementService][ah-path-cement] | Reuse sampling, grounding and sparse undo snapshots behind a simple door-to-road connector. The full designer has more modes than this feature needs. Existing placement can clear foliage/rubble above cells; every affected cell must pass Eternia's claim and road policy. Guild road creation additionally needs corridor reservation and permissions. |
| Physical player shops | [ShopSpotRecord][ah-shop-record], [ShopSpotPurchaseService][ah-shop-purchase], [ShopSpotItemDelivery][ah-shop-delivery] | Player listings already track seller and stock, reject buying one's own listing, charge buyers and credit sales. Remove town/NPC/opening-hour coupling. Purchase spans multiple state writes and delivery can drop overflow onto the ground; use durable escrow, idempotent settlement and mailbox delivery for Eternia. Physical storefront/display patterns do not supply a server-wide listing index or atomic marketplace. |
| Hub dialogue | [DialogueCatalog][ah-dialogue-catalog], [DialogueChoiceDefinition][ah-dialogue-choice], [DialogueActionRegistry][ah-dialogue-actions] | Reuse JSON nodes/choices, visibility and enabled conditions, icons and registered actions. Register a small set of Eternia actions for help, initial claim grants and opening service pages; enforce entitlements and permissions again inside those actions. |
| Quest objectives and pass events | [QuestObjective][ah-quest-objective], [QuestKillProgressSystem][ah-quest-kills], [QuestCraftProgressSystem][ah-quest-crafts], [WorldQuestProgressionService][ah-world-quest] | Useful references for typed objectives, entity/tag matching and per-player progression. A permanent season-pass service, selectable archived passes, event deduplication, anti-farming rules and free/paid reward receipts remain new. Do not import town-specific progression into global player passes. |
| Player guild permissions | [TownMemberPermissions][ah-town-permissions], [TownMemberPermissionsPage][ah-town-permissions-page], [TownMemberBlockAccess][ah-town-access] | These are actual **player town membership** permissions, including plots/construction, treasury, shops, block edits, harvests, containers and doors. Adapt the menu and access-check patterns into Eternia's role-based guild permissions. The separate [GuildHallAdventurerPoolService][ah-npc-guild] cycles **NPC adventurers** at dawn; it is not a player guild service. |
| Pets | No dedicated player-pet system was identified in the inspected Java inventory/search. | Implement follower ownership, equip limits, property roaming, persistence and cosmetic behavior as a new domain. NPC movement may offer lower-level examples, but a ready-made pet service was not verified. |

Existing player-facing explanations of the reference workflow are [plot tokens][ah-token-guide] and [plot placement staff][ah-placement-guide]. The [crossmod integration tutorial][ah-crossmod] illustrates catalog extension patterns. Eternia's content authoring guide should describe its smaller stable schemas and provide one working example per content type, rather than requiring familiarity with Aetherhaven's town systems.

[ah-prowl]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Models/Townsfolk/Prowl.json
[ah-prowl-model]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Common/NPC/Prowl/prowl_hytale.blockymodel
[ah-prowl-texture]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Common/NPC/Prowl/prowl_hytale.png
[ah-prowl-icon]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Common/Icons/ModelsGenerated/Prowl.png
[ah-human]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Models/Human/Aetherhaven_Human.json
[ah-prowl-character]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Aetherhaven/Townsfolk/prowl.json
[ah-placement-page]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/ui/PlotPlacementPage.java
[ah-placement-session]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/placement/PlotPlacementSession.java
[ah-placement-commit]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/placement/PlotPlacementCommit.java
[ah-overlay]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/placement/PlotPlacementWireframeOverlay.java
[ah-ghost]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/placement/PlotPlacementClientPrefabPreview.java
[ah-camera]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/placement/PlotPlacementCameraUtil.java
[ah-validator]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/placement/PlotPlacementValidator.java
[ah-footprint]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/placement/PlotFootprintUtil.java
[ah-relocation]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/placement/PlotBuildingRelocation.java
[ah-prop-teardown]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/prop/PropPlotTeardown.java
[ah-desk]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Item/Items/Aetherhaven/Aetherhaven_Town_Planning_Desk.json
[ah-desk-window]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/construction/TownPlanningCraftingWindow.java
[ah-crafting-page]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/ui/PlotCraftingPage.java
[ah-construction-catalog]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/construction/ConstructionCatalog.java
[ah-house]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Aetherhaven/Buildings/plot_house.json
[ah-prop-catalog]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/prop/PropCatalog.java
[ah-prop-instance]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/prop/PropInstance.java
[ah-prop-example]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Aetherhaven/Props/candle_stump.json
[ah-palette-catalog]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/blockpalette/BlockPaletteCatalog.java
[ah-palette-apply]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/blockpalette/BlockPaletteApplyService.java
[ah-palettes]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Aetherhaven/BlockPalettes/palettes.json
[ah-remap-example]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Aetherhaven/BlockPalettes/examples/crossmod_remap_group.json.example
[ah-path-spline]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/pathtool/PathSplineUtil.java
[ah-path-grounding]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/pathtool/PathGrounding.java
[ah-path-cement]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/pathtool/PathCementService.java
[ah-shop-record]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/shopspot/ShopSpotRecord.java
[ah-shop-purchase]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/shopspot/ShopSpotPurchaseService.java
[ah-shop-delivery]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/shopspot/ShopSpotItemDelivery.java
[ah-dialogue-catalog]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/dialogue/DialogueCatalog.java
[ah-dialogue-choice]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/dialogue/data/DialogueChoiceDefinition.java
[ah-dialogue-actions]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/plugin/DialogueActionRegistry.java
[ah-quest-objective]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/quest/data/QuestObjective.java
[ah-quest-kills]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/quest/QuestKillProgressSystem.java
[ah-quest-crafts]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/quest/QuestCraftProgressSystem.java
[ah-world-quest]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/worldnpc/WorldQuestProgressionService.java
[ah-town-permissions]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/town/TownMemberPermissions.java
[ah-town-permissions-page]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/ui/TownMemberPermissionsPage.java
[ah-town-access]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/town/TownMemberBlockAccess.java
[ah-npc-guild]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/guild/GuildHallAdventurerPoolService.java
[ah-token-guide]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Aetherhaven/GuideTopics/en-US/mechanic_plot_tokens.md
[ah-placement-guide]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Aetherhaven/GuideTopics/en-US/mechanic_plot_placement_staff.md
[ah-crossmod]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/tutorials/crossmod-integration.md

## Plot boundary particle source evidence

The proposed [default plot boundary fog](housing-and-guilds.md#default-plot-boundary-fog) is always enabled, appears only near the border, and stays at most one block above the ground. These source findings support an implementation path; the appearance, client cleanup, and visual height envelope still need runtime verification. Proximity distances and refresh timings in the specification are proposed tuning values, not values supplied by the owner.

- **Current Eternia behavior needs replacement for ordinary borders.** [HubPlotVisibilityState.java](../../src/main/java/com/hexvane/eterniamod/hub/HubPlotVisibilityState.java) stores an initially empty set of enabled viewers. [HubPlotVisibilityOverlay.java](../../src/main/java/com/hexvane/eterniamod/hub/HubPlotVisibilityOverlay.java) draws every plot only when enabled and clears debug shapes globally for that viewer. [HubPlotBoundaryWireframe.java](../../src/main/java/com/hexvane/eterniamod/hub/HubPlotBoundaryWireframe.java) sends six-hour debug cylinders and diagonals using the 100-block visual height in [EterniaModConstants.java](../../src/main/java/com/hexvane/eterniamod/EterniaModConstants.java). Automatic local fog needs its own proximity updater; these debug boxes are not a fog implementation or its visibility setting.
- **Per-viewer delivery is supported in the inspected server source.** [ParticleUtil.java][hy-particle-util], in the final `spawnParticleEffect` overload, accepts an explicit `List<Ref<EntityStore>>`, constructs `SpawnParticleSystem` with `maxDuration`, and sends it directly to each selected player's packet handler. A singleton recipient list targets one viewer. Pass `sourceRef = null` for this use: a recipient equal to `sourceRef` is explicitly skipped. Select nearby viewers in the same world and nearby boundary segments before sending; do not substitute distance to the plot's filled area, which is zero anywhere inside it.
- **Lifetimes and culling exist, but culling is not a height clamp.** [ParticleSystem.java][hy-particle-system] defines `LifeSpan`, `CullDistance`, `BoundingRadius`, and `IsImportant`. A nonpositive system lifespan is unlimited; cull distances below one fall back to the default of 40, and bounding radii below one fall back to 10. `IsImportant` can bypass distance and occlusion checks. Use finite lifetimes and ordinary culling, with server recipient filtering as the proximity authority. The inspected send API exposes a maximum duration but no per-emission removal handle; use short-lived emissions and verify expiration after leaving range, changing worlds, and disconnecting.
- **Author the entire visible envelope below one block.** [ParticleSpawner.java][hy-particle-spawner] exposes emission offsets, individual particle lifespan, spawn rate, concurrent limits, velocity, and attractors. [ParticleAnimationFrame.java][hy-particle-animation] exposes scale and opacity animation; animation scale multiplies the initial scale. Limit emission height, drift/acceleration, sprite dimensions, rotation, and animation growth together. `BoundingRadius` only controls visibility tests. No inspected setting guarantees that particle geometry stays below a specified world height, so test the authored envelope at all camera angles and on slopes and stairs.
- **Ground height requires more than an unfiltered heightmap.** [BlockChunk.java][hy-block-chunk] documents `getHeight(x, z)` as the highest nontransparent block, which can be a roof or canopy. [ChunkSectionBlockUtil.java](../../src/main/java/com/hexvane/eterniamod/world/ChunkSectionBlockUtil.java) already exposes in-memory chunk reads. Use those with a bounded surface sampler or maintained terrain-surface cache for each nearby boundary segment, invalidate affected samples when terrain changes, and skip unavailable chunks. [BuildingPlacementAnchorUtil.java](../../src/main/java/com/hexvane/eterniamod/placement/BuildingPlacementAnchorUtil.java) only picks a position around a supplied target; it is not a terrain-following sampler. Account for the viewer's local surface/vertical context so a nearby X/Z coordinate in a basement does not activate a distant above-ground border.
- **Aetherhaven offers an appearance reference, not a ready-made border effect.** [Aetherhaven_Smokestack_Wisp.particlespawner][ah-boundary-wisp] uses a smoke texture, linear blending, and opacity animation. Its five-to-six-second particles, upward velocity, and acceleration exceed the requested short wall when used unchanged. Create an Eternia-owned low-drift effect with finite lifetimes and the verified height envelope rather than copying the smokestack's motion settings.

[hy-particle-util]: C:/Users/gchou/Documents/HytaleModding/HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/universe/world/ParticleUtil.java
[hy-particle-system]: C:/Users/gchou/Documents/HytaleModding/HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/asset/type/particle/config/ParticleSystem.java
[hy-particle-spawner]: C:/Users/gchou/Documents/HytaleModding/HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/asset/type/particle/config/ParticleSpawner.java
[hy-particle-animation]: C:/Users/gchou/Documents/HytaleModding/HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/asset/type/particle/config/ParticleAnimationFrame.java
[hy-block-chunk]: C:/Users/gchou/Documents/HytaleModding/HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/universe/world/chunk/BlockChunk.java
[ah-boundary-wisp]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/resources/Server/Particles/Aetherhaven/Smokestack/Spawners/Aetherhaven_Smokestack_Wisp.particlespawner
