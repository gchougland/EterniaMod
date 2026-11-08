# Hex plots: core API

Status: **live data and rules layer** (branch `wave2/hex-core`). Read with `hex-plots-plan.md` (the accepted design).
This is the contract the plots, relocation, borders and claim map agents build on. Nothing here draws UI; the only
player visible change so far is that a world given the housing role through `/e admin setup` now records hex plots
(the claim pages still use rectangles until the plots agent switches them).

Package map:

| Package | What lives there | Game types? |
| --- | --- | --- |
| `hexplots` | geometry, cells, rules, settings, land view, events | only `HexLand` (the facade) |
| `housing` | `HexClaimCheck` (claim check and reserve), `WorldPlan` lattice fields | yes |
| `hub` | cells on `HubPlotFootprint` / `HubPlotRecord`, cell index in `HubPlotManager` | yes |
| `domain` | `HousingService.claimCells / expandPlot / completeExpansion`, cells on `ClaimLocation` | no |
| `setup` | `LatticeSetup` (`/e admin setup lattice`) | yes |

## 1. Where data lives

| Data | File / store | Field(s) | Notes |
| --- | --- | --- | --- |
| World lattice and plot shape | `housing-infrastructure.json` (plugin data dir), `HousingInfrastructure.WorldPlan` | `plotShape` (`"hex"` / `"rect"`), `lattice` `{cellWidth, bodyDepth, originX, originZ}` | Both optional. Missing `plotShape` means rectangles (every world saved before hex plots). A hex world always has a lattice. |
| Native plot projection | `worlds/<world>/hub_plots.json`, `HubPlotFootprint` | `lattice` (`hex:32:20:0:0`), `cells` (`q:r;q:r`) | `minX..maxZ` stay and are always the bounds of the cells. |
| Durable property | domain store namespace `housing_slot` | `lattice`, `cells`, `expansion` (op id), `expansionCell` (`q:r`) | `minX, minZ, width, depth` derived from cells. Empty strings for rectangle plots. |
| Journal | namespace `operation` | kind `CLAIM` (same as rect claims) and `EXPAND` (detail `"<property> <q:r>"`) | |
| Coins spent | namespace `ledger` | kind `SPEND`, receipts `hexplot-claim:<op>` / `hexplot-expand:<op>` | New `EconomyService.spendIn` (coins leave the economy). |
| Tuning | `Server/EterniaMod/Housing/HexPlots.json` | start shapes, caps, prices, per cell limits, setback, estate members | Placeholders; tune in data. Loaded once at start into `HexPlotSettings.current()`. |
| Player text | `Server/Languages/en-US/eternia_hexplots.lang` | `reason.*`, `state.*`, `shape.*` | Prefix `eternia_hexplots.` |

Lattice id format: `hex:<cellWidth>:<bodyDepth>:<originX>:<originZ>`. Same id means same columns, so saved cells are
always unambiguous. The grid cannot change while the world has any plot or unfinished claim (`InfrastructureRules`).

## 2. Threading summary

| Call | Thread |
| --- | --- |
| Everything in `HexCoord`, `HexLattice`, `HexShape`, `HexFit`, `HexContiguity`, `HexClaimRules`, `PlotCells`, `HexPlotSettings`, `HexLandView` | any (pure, immutable) |
| `HexLand.view`, `HexLand.context`, `HexClaimCheck.*`, `HubPlotManager.*`, `HubPlotRecord.containsColumn` | world thread of that world |
| `HousingService.reservedCells`, `hasReservedGround` | any (committed rows, no I/O) |
| `HousingService.claimCells / expandPlot / completeExpansion / pendingExpansions` | any; does domain I/O, so the world thread only where the existing claim commit already does |
| `HexLand.viewer`, `HexLand.loadNames` | off the world thread (domain reads) |
| `HexLandEvents` listeners | called on the thread that made the change; keep tiny, hand off with `world.execute` |

## 3. Types and methods

### Geometry (unchanged prototype, promoted)

- `HexCoord(q, r)`: axial cell. `neighbors()`, `distance`, `adjacent`, `ring`, `disk(center, radius)`, `parse("q:r")`, `READING_ORDER`.
- `HexLattice(cellWidth, bodyDepth, originX, originZ)`: `cellAt(x, z)`, `spans(cell)`, `bounds(cell|cells)`, `cellsTouching(rect|rects)`, `borderEdges(cells)` (unit staircase edges for glow), `corners(cell)` (six ideal corners for straight line drawing), `centerX/Z`.
  - New: `id()`, `parseId(id)`, `withOrigin(x, z)`, `centredOn(x, z)`, `cellsTouching(Collection<PlotRect>)`.
- `HexShape`: `SINGLE`, `TRIANGLE`, `DIAMOND`, `FLOWER`; `variants()`, `placementsCovering(cell)`, `forHouseSide(side)`.
- `HexFit`: `fits(lattice, cells, houseRect, setback)`, `nearestFit(...)`, `largestSquare(...)`, `cellsUnder(lattice, house)`.
- `HexContiguity`: `isConnected`, `frontier`, `canAdd`, `canRemove`, `edgeCells`.

### `PlotCells` (value type)

```java
PlotCells cells = PlotCells.of(lattice, List.of(new HexCoord(0, 1)));
cells.text();          // "0:1"
cells.latticeId();     // "hex:32:20:0:0"
PlotCells.parse(id, text);
cells.contains(cell); cells.containsColumn(x, z); cells.bounds(); cells.with(cell); cells.translate(offset);
```

Sorted in reading order, 1 to 64 cells, equality by lattice and cells.

### `PlotShape`

`RECT` / `HEX`, saved as `"rect"` / `"hex"`. `PlotShape.parse("hex")`.

### `HousingInfrastructure.WorldPlan` (new members)

- `shape()`: `HEX` or `RECT` (null in file means `RECT`).
- `hexPlots()`: housing on and shape `HEX`. Use this to decide which claim path a world takes.
- `hexLattice()`: `Optional<HexLattice>`.
- `withPlots(shape, lattice)`, `withHousingDefaults(defaultLattice)` (first time housing: hex on the default lattice; an explicit choice is kept).
- Saving a plan fires `HexLandEvents` (`LATTICE` on a grid or shape change, `ROADS` with changed street cells plus their neighbours on road or plaza edits).

### `HubPlotFootprint` / `HubPlotRecord` / `HubPlotManager`

```java
var footprint = HubPlotFootprint.forCells(cells, groundY);   // new hex plot projection
plot.setFootprint(plot.getFootprint().withCells(grown));     // expansion
plot.getCells(); plot.isHex();
plot.containsColumn(x, z);        // cells for hex plots, rectangle otherwise
manager.plotAtColumn(x, z);       // O(1) for hex plots, then a scan of rectangle plots
manager.plotAtCell(cell);         // O(1)
manager.cellOwners();             // Map<HexCoord, UUID plotId>, read only
manager.indexedLattice();
```

`HubPlotFootprint.containsHorizontal` / `containsBlock` are now cell exact for hex plots, and
`findPlotContainingHorizontal` uses the index, so every existing caller of those (about 30 files) is already correct
for hex plots. `containsFootprint` / `containsFootprintHorizontal` remain box only: placement must use `HexFit`.
`addPlot`, `removePlot` and `updatePlot` keep the index in step and fire `CLAIM`, `UNCLAIM` or `EXPAND` events.
Corrupt saved cells throw instead of silently protecting the whole box.

### `HexPlotSettings` (data)

```java
var s = HexPlotSettings.current();
s.startShape(Kind.PUBLIC, upgraded);   // SINGLE, TRIANGLE when upgraded; estates FLOWER
s.maxCells(Kind.GUILD_ESTATE);         // 19 (never below the start shape)
s.expansionPrice(cellsOwned);          // Coins + CoinsPerOwnedCell * owned, 1,000 per owned cell now
s.propsPerCell(); s.generatorsPerCell(); s.houseSetback(); s.guildEstateMembers(); s.defaultLattice();
```

### `HexClaimRules` (pure rules, same code for preview and commit)

```java
Result r = HexClaimRules.claim(land, new Candidate(kind, upgraded, guildMembers, guildHasEstate, localTesting), cells, anchors, settings);
Result r = HexClaimRules.expand(land, kind, currentCells, cell, settings);
Set<HexCoord> opts = HexClaimRules.expansionOptions(land, kind, currentCells, settings);
Result r = HexClaimRules.placeProp(plotCells, propsPerCell, cell, settings);
Result r = HexClaimRules.placeGenerator(plotCells, generatorsPerCell, cell, settings);
Result r = HexClaimRules.structure(plotCells, houseRect, roads, settings);   // HexFit + road setback
HexClaimRules.hasShape(cells, HexShape.TRIANGLE); HexClaimRules.sameShape(a, b);
HexClaimRules.joinedGuildLand(estateCells, memberPlots);
HexClaimRules.streetCells(lattice, roadRects, plazaRects);
```

`Result(reason, count)`: `valid()`, `reason()`, `count()` (fills `{count}`), `message()` (English fallback).
`Reason.langKey()` is the player text key; `HexLand.message(result)` builds the `Message` with the count. Never show
`reason.id()` to players.

Check order for a claim: size, connected, shape, each cell (street, taken, reserved, blocked), then estate (members,
one estate per guild, frontage), member (touches joined guild land) or public (frontage).

### `HexLandView` (pure read model) and `HexLand` (entry point)

```java
// World thread:
HexLandView view = HexLand.view(world).orElseThrow();          // empty if the world has no lattice
var viewer = new HexLandView.Viewer(playerId, guildId, HexClaimRules.Kind.PUBLIC); // intent null = just looking
List<HexLandView.CellInfo> cells = view.around(x, z, 8, viewer, HexLand.names());  // 217 cells
HexLandView.CellInfo one = view.cell(cell, viewer, HexLand.names());
view.isStreet(cell); view.isFrontage(cell); view.isFree(cell); view.plotAt(cell); view.plotAtColumn(x, z);
view.guildLand(guildId); view.expansionOptions(plotId, kind, settings); view.land();   // for HexClaimRules previews
HexLandView.kindOf(plotInfo);

// Off the world thread:
HexLandView.Viewer viewer = HexLand.viewer(services, playerId, intent);
HexLand.loadNames(services, ownerIds);   // fills the shared name cache used by HexLand.names()
```

`CellInfo(cell, state, plotId, ownerKind, ownerId, ownerName)`. States:

| State | Meaning |
| --- | --- |
| `STREET` | a public road or plaza touches it |
| `FRONTAGE_FREE` | free, and a new plot of this viewer's `intent` containing it meets the road or guild rule (a start plot needs at least one such cell; its other cells may be `FREE`) |
| `FREE` | free but not a valid start cell for this viewer now (no frontage, blocked by road work, or no intent) |
| `MINE` | the viewer's own plot |
| `MY_GUILD` | the viewer's guild estate or a member plot attached to the viewer's guild |
| `OTHER_PLAYER` | another player's plot with no guild |
| `OTHER_GUILD` | another guild's estate or member plot |
| `RESERVED` | held by an unfinished claim, restore or paid expansion |

`HexLand.view` caches per world and rebuilds only what changed (infrastructure revision, plot manager revision,
reservation set, road work rectangles). Calling it every refresh is fine.

Suggested palette (`HexLandView.suggestedColor(state)`, one palette for claim map, map overlay and border glow):

| State | Colour | Hex |
| --- | --- | --- |
| MINE | teal | `#2EC4B6` |
| MY_GUILD | gold | `#E9B949` |
| OTHER_PLAYER | soft blue | `#8FB8DE` |
| OTHER_GUILD | violet | `#9B7ED9` |
| FRONTAGE_FREE | claimable green | `#6CC56C` |
| FREE | muted moss | `#5E6B57` |
| STREET | stone | `#A39E93` |
| RESERVED | amber | `#E58E4C` |
| selected cell | bright white outline | `#F8FAFC` (`HexLandView.SELECTED_COLOR`) |

### `HexLandEvents`

```java
Runnable stop = HexLandEvents.listen(change -> { if (change.world().equals(name)) dirty.addAll(change.cells()); });
// change.cause(): CLAIM, EXPAND, UNCLAIM, RESERVATION, ROADS, LATTICE; change.wholeWorld() for LATTICE
HexLandEvents.fire(world, cells, cause);   // for flows that change land outside the paths below
```

Fired by: `HubPlotManager.addPlot / removePlot / updatePlot` (so packing, restoring, moving and unclaiming are
covered), `HousingInfrastructure.saveWorld` (roads, plazas, grid), `HousingService.claimCells / expandPlot`
(`RESERVATION`). Run `stop` when a page closes.

### Claims: `HexClaimCheck` (housing) and `HousingService` (domain)

```java
// Preview, world thread (loads account data, as NativeClaimCoordinator.validate does):
HexClaimRules.Result r = HexClaimCheck.validate(plugin, world, actorId, cells, HousingRules.Scope.PUBLIC, paid);
HexClaimCheck.kind(scope); HexClaimCheck.startShape(scope, paid);

// Durable reservation, world thread:
HousingService.HexOutcome out = HexClaimCheck.reserve(plugin, world, actorId, cells, scope, paid, propertyId, operationId);
if (!out.ok()) { player.sendMessage(HexLand.message(out.result())); return; }
```

`reserve` reloads eligibility, validates, builds the `HexContext` and calls
`HousingService.claimCells(owner, propertyId, operationId, attachedGuild, ClaimLocation.ofCells(...), candidate, context, payer)`.
The domain re-reads every `housing_slot` row of the world **inside its transaction**, so of two racing claims for one
cell exactly one commits (the other gets `RESERVED` or `TAKEN`). Replays with the same operation id return the saved
slot and charge nothing. The row is left `CLAIMING` with journal `PREPARED`, exactly like a rectangle claim.

**Native tail the plots agent writes** (mirror of the end of `NativeClaimCoordinator.claim`):

```java
var plot = new HubPlotRecord(propertyId, world.getName(), HubPlotFootprint.forCells(out.cells(), groundY), scope == GUILD_ROOT ? null : actor);
plot.setGuildOwnerUuid(scope == GUILD_ROOT ? guild : null); plot.setAttachedGuildUuid(scope == GUILD_MEMBER ? guild : null);
NativeRelocationCoordinator.captureBaseline(plugin, world, plot);   // box snapshot; relocation agent adds the mask
manager.addPlot(plot); manager.saveIfDirty();                        // fires CLAIM
services.batch(() -> { journal.advance(op, rev, WORLD_APPLIED); housing.activate(owner, op); return null; });
```

`NativeClaimCoordinator.recoverClaims` already finishes an interrupted hex claim (it compares boxes, which are derived
from cells on both sides).

Expansion:

```java
var ctx = HexLand.context(plugin, world).orElseThrow();
var out = services.housing().expandPlot(owner, propertyId, cell, slot.revision(), operationId, kind, ctx, payer);
// out.result() CHANGED if the row moved since the player looked, COINS (count = price), TAKEN, TOUCH, LIMIT ...
// on ok: native update, then acknowledge
plot.setFootprint(plot.getFootprint().withCells(out.cells())); manager.updatePlot(plot); manager.saveIfDirty(); // fires EXPAND
services.housing().completeExpansion(owner, operationId);
// on world load: for each services.housing().pendingExpansions(world): apply location().cells() to the projection, then completeExpansion
```

While an expansion is pending the plot cannot start a move (`beginRelocation` refuses) and the paid cell shows
`RESERVED`. Payer: the player for personal plots; for an estate the caller decides (guild wallet `Owner.guild` or the
acting member) and must check guild permissions first.

Other domain changes: `ClaimLocation` has an optional `cells` component (8 argument constructor still works),
`ofCells`, `containsColumn`, `overlaps` (cell exact on one lattice, box otherwise), `withCells`, `box()`.
`beginClaim` and `updateRestoreLocation` save and compare cells. `hasReservedGround` is cell exact and cached.
`reservedCells(world)` feeds `RESERVED`.

### Admin: `/e admin setup lattice <mode>`

| Mode | Does |
| --- | --- |
| `show` | draws the 19 cells around you for 20 seconds, coloured by state, and prints shape, grid, your cell, street count, plot count |
| `here` | review: move the grid so a cell is centred on you |
| `origin` | review: grid back to origin 0, 0 |
| `hex` / `rect` | review: switch the world's plot shape (the grid is kept) |

All go through the normal setup review and `/e admin setup confirm`; every change is refused while the world has any
plot or unfinished claim. Choosing the housing role for a world the first time turns on hex plots on the default grid.

## 4. Seams and file ownership

Each existing file belongs to exactly one agent from here. Files in **hex core** are frozen API: extend by adding
methods in a follow up only after telling the others; new files are free. If a seam needs a change in another seam's
file, ask that agent.

| Owner | Owns (edit freely) |
| --- | --- |
| **hex core** (done) | `hexplots/*`, `housing/HexClaimCheck`, `housing/HousingInfrastructure`, `hub/HubPlotFootprint`, `hub/HubPlotRecord`, `hub/HubPlotManager`, `hub/HubPlotWorldFile`, `domain/HousingService`, `domain/EconomyService`, `setup/LatticeSetup`, `setup/InfrastructureRules` (lattice lock only, see b), `Server/EterniaMod/Housing/HexPlots.json`, `eternia_hexplots.lang` (others may add keys under their own prefix, see below) |

### (a) Plots engine

Native claim commit with cells, protection, placement, per cell limits, expansion commit, deed and My Plots.

- `housing/NativeClaimCoordinator` (add `claimCells` native tail; branch `validate`/`claim` on `plan.hexPlots()`)
- `housing/ClaimEligibility` (hex helpers already added: `checkCells`, `hexKind`)
- `housing/HousingRules` (keep for rect worlds; route hex worlds to `HexClaimCheck`)
- `housing/HousingProtection`, `housing/NativeHousingChecks`, `housing/HousingAccess`, `housing/HousingInventory`, `housing/HousingCustody`, `housing/HousingUseCapabilities`
- `housing/MyPlotsPage` (+ `.ui`), `housing/VoluntaryPlotMover` (entry point only; relocation logic is b), deed items and interactions
- `placement/*` (`BuildingPlacementValidator`, `PropPlacementValidator`, open helpers, `PlotFootprintUtil`), `ui/BuildingPlacementPage`, `ui/PropPlacementPage`, `prop/PropBoundsUtil`
- `hub/PropBreakBlockSystem`, `hub/EterniaWorldRegistries`
- `customization/*` (inside plot through `containsColumn`, setback against the outer edges)
- lang: `eternia_hexplots.plots.*` keys
- To do: `HexFit` in both validators and "Find valid spot"; per cell prop and generator counts via `HexClaimRules.placeProp / placeGenerator`; expansion commit and pending expansion recovery on world load; deed and My Plots show cells.

### (b) Relocation, packing, setup, fixtures, website

- `housing/relocation/*` (cell masks on capture and restore, same shape same turn destination, `updateRestoreLocation` with `ClaimLocation.ofCells`)
- `setup/InfrastructureSetup` (strand checks: refuse road edits that turn a claimed cell into street or leave a plot without frontage; add them to `InfrastructureRules.validate` below the lattice lock)
- `setup/ManagedHubServices`, `setup/paving/*`, `pathtool/*` (Road Designer lattice overlay, street preview), `guildroads/*` ("inside plot" through the cell lookup)
- `localplayground/LocalPlayground`, `runtime/NativeSmoke`, `runtime/NativeGuildSmoke`, `town/TownBuilder` (call `withHousingDefaults` or `withPlots`, sample plots on the lattice)
- `runtime/GuildWaygate*`, `runtime/NativeTravelService`, `inventory/ui/NativeShopTravel`, `collections/CollectionRuntime` (plot lookups)
- `website/**` (cell count from `housing_slot.cells` / hub plot `cells`)

### (c) Borders and world map overlay

- `boundary/*` (`BoundaryGeometry` from `HexLattice.borderEdges`, `BoundaryFogSystem`), `hub/HubPlotBoundaryWireframe`, `hub/HubPlotVisibilityOverlay`, `hub/HubPlotVisibilityState`
- new world map overlay class(es) under `boundary/` or a new `hexmap/` package
- listens to `HexLandEvents`; colours from `HexLandView.suggestedColor`

### (d) Claim map page

- `housing/PlotClaimPage` (+ `Common/UI/Custom/EterniaMod/PlotClaimPage.ui`), `housing/ClaimGrid`
- new claim map page files and `.ui`; lang `eternia_hexplots.map.*`
- calls `HexLand.view` / `HexClaimCheck.validate` for previews and `HexClaimCheck.reserve` for the commit, then hands the
  native tail to the plots engine method (a) exposes (agree a single `NativeClaimCoordinator.claimCells(...)` signature
  with agent a first)

Shared files nobody should need: `EterniaModPlugin` (hex core added two lines), `domain/EterniaServices`.

## 5. Things to know

- Rect worlds are untouched: no lattice, no cells, all old code paths. Mixed worlds are not supported; `plotShape` is
  per world and locked while plots exist.
- Bounding boxes of neighbouring hex plots overlap (tips interleave). Never use box overlap to decide hex conflicts:
  use cells (`ClaimLocation.overlaps` already does).
- `HexLand.view` returns plots from the native projection; durable but not yet projected cells appear as `RESERVED`.
- `HexClaimCheck.reserve` refuses packed owners with `PACKED`; restoring a packed plot onto cells is relocation work.
- Guild community roads, unfinished paving and unfinished spline edits make the cells they touch `blocked` (shown as
  `FREE`, refused as `BLOCKED`); any unfinished spline road in the world refuses all hex claims (`ROAD_WORK`), as rect
  claims do today.
- `HousingRules.Result` is still used by the rect path; the hex path returns `HexClaimRules.Result`.
