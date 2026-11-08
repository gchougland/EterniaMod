# Hex plots engine (wave 2, plots agent)

## SIGNATURES (final, for the map agent)

In `com.hexvane.eterniamod.housing.NativeClaimCoordinator` (reach it with `EterniaModPlugin.get().getClaims()`):

```java
/** Validates again at commit, reserves, runs the native world tail, completes on the world thread. */
public CompletableFuture<HexCommitResult> claimCells(PlayerRef player, World world, PlotCells cells, HexClaimRules.Kind kind);
/** Adds one side sharing cell to the player's plot (or their guild estate when allowed), charging the price. */
public CompletableFuture<HexCommitResult> expandPlot(PlayerRef player, World world, String plotId, HexCoord cell);
/** Price of the next hexagon for a plot that owns cellsOwned cells (show before confirm). */
public static long expansionPrice(int cellsOwned);
```

- `PlayerRef` = `com.hypixel.hytale.server.core.universe.PlayerRef`; `PlotCells`, `HexCoord`, `HexClaimRules.Kind`
  (`PUBLIC`, `GUILD_ESTATE`, `GUILD_MEMBER`) from `hexplots`. No new kind enum: the core's is reused.
- `plotId` is the plot's UUID text (`HubPlotRecord.getPlotId().toString()`, `HexLandView.CellInfo.plotId()`).
- Any thread. The future completes on the world thread and never completes exceptionally.
- Both methods already send the player their friendly line; pages may show `result.message()` again.
- Paid start shape is inferred from the cell count (3 cells for a personal plot means the upgraded plot).

`com.hexvane.eterniamod.housing.HexCommitResult`:

```java
record HexCommitResult(boolean ok, String playerMessage, String plotId, Message message, HexClaimRules.Result reason)
```

`playerMessage` is English (logs); `message` is the same line as a translation; `reason` is the rule result (for a
refusal, colour or explain the cell with it).

## STATUS

Done for seam (a) minus `MyPlotsPage` and deed routing. Build green (`.\gradlew.bat --no-daemon build`, 551 tests,
0 failures, no Java deprecation warnings). `asset_validate` 0 errors / 35 warnings, `ui_check_java` 0 errors / 115
warnings (both unchanged from the base).

- **Claim commit** `claimCells`: loads every chunk column and section under the cells (the claim map can claim land
  the player is not standing on), validates again with fresh account data (`HexClaimCheck.validate`), captures the
  terrain baseline, reserves durably (`HexClaimCheck.reserve`), adds the plot (`HubPlotFootprint.forCells`, glow height
  from the ground under the cells), journal `WORLD_APPLIED` and `activate`, welcomes a new guild estate, then tells
  the player ("Your cottage plot is ready! ..."). `HubPlotManager.addPlot` fires `CLAIM`; the domain fires
  `RESERVATION`. Never completes exceptionally.
- **Expansion commit** `expandPlot`: who may grow it (owner; for an estate the guild role `housing.resize`, i.e. the
  leader), finishes any earlier paid hexagon first, then `HousingService.expandPlot` (validates, charges the player),
  applies the cells to the plot record (`updatePlot` fires `EXPAND`), `completeExpansion`.
- **Recovery** of paid but unapplied hexagons: `recoverClaims` (already called on `StartWorldEvent`) now ends with
  `recoverExpansions`, which applies every `pendingExpansions(world)` row to its plot and acknowledges it.
- **Rect entry points** `validate(world, actor, rect, ...)` and `claim(world, actor, rect, ...)` route to the hex path
  in hex worlds (start shape over the hexagon under the rectangle's centre, preferring a free placement with frontage).
  Rect worlds are unchanged.
- **Protection**: `HousingProtection`, `NativeHousingChecks.mutation`, `HousingInventory`, `PropBreakBlockSystem`,
  `HubBuildMode`, `CustomizationMenus` all decide through `findPlotContainingHorizontal` / `containsHorizontal` /
  `findPlotAtPlayerOrTarget`, which the core made cell exact. Checked each; no box decision left in the seam.
  `PropBoundsUtil.findAimedPlot` keeps a box only as a ray cull before checking the props themselves.
- **Placement**: `HexPlacement` (new) holds the hex rules. Houses need their shape's hexagons (cottage 1, manor 3,
  keep 7; checked again when the placement opens, with a message saying what to do), then fit the cells with the
  5 block setback from the outer edge (`HexFit`), then road setback. Additions: same fit. Decorations: inside the
  cells, then 24 per hexagon (`HexClaimRules.placeProp`), counted on the hexagon under the centre of each prop's
  blocks; generators (prop category `generator`) 2 per hexagon (`placeGenerator`). "Find valid spot" starts at
  `HexFit.nearestFit`, then the other fitting spots by distance. `PlacementText` turns codes into text.
- **Pages**: the prop placement page's details line shows how many decorations still fit in the hexagon (hex plots
  only). Both pages show the new reasons.
- **Customization**: path routes stay on the plot's cells (`PathPlanner.Area`), palette blocks keep the setback from
  the outer cell edges.

## DECISIONS

- `HexClaimRules.Kind` reused (no new `HexClaimKind`). `plotId` is UUID text.
- `HexCommitResult` has two extra fields beyond the contract: `message` (translation) and `reason` (rule result).
- The paid start shape is read from the cell count (3 cells on a personal plot = the upgraded plot).
- Baseline is captured **before** the durable reservation, so a capture failure leaves nothing to recover; a
  reservation lost to a race after a capture leaves one unused baseline file (small, harmless).
- Expansion price is paid by the acting player, also for a guild estate: there is no guild treasury yet. Only roles
  with `housing.resize` (leader only today) may grow the estate.
- A decoration stands on the hexagon under the centre of its blocks. Additions count against no limit (they belong
  to the house). No prop is a generator today; a prop with `"category": "generator"` would be (the catalog validator
  does not accept that category yet, so the limit is wired but unused).
- On a hex plot the prop page's technical details line (sign / origin coordinates) is replaced by the hexagon room
  line; rect plots keep it.

## SHARED EDITS

None (`EterniaModPlugin`, `EterniaServices`, `CHANGELOG.md`, `README.md` untouched). Hex core: only lang keys under
`eternia_hexplots.plots.*` added, as the API doc allows.

## CHANGELOG (proposed, player facing)

- Place your house anywhere it fits on your hexagons. Larger houses need more hexagons: a manor needs three, a keep seven.
- Each hexagon of your plot holds up to 24 decorations. The placement window shows how many more fit.
- Grow your plot one hexagon at a time with coins. A hexagon you paid for always arrives, even after a restart.
- Garden paths and house palettes now follow the shape of your plot.

## REQUESTS

- **map agent**: wire `NativeClaimCoordinator.claimCells / expandPlot` into `claimmap/HexClaimCommits` (one line at
  merge, e.g. a small adapter calling `plugin.getClaims()`). Show `expansionPrice(cells)` before confirm. The
  result's message is already sent to the player; pages may show it again.
- **move agent**: (1) the terrain baseline is captured over the claim's box only; an added hexagon outside the old
  box has no baseline yet, so packing a grown plot needs the baseline extended (or captured per cell on expansion).
  (2) Packed owners get `PACKED` from `HexClaimCheck.reserve`; restoring onto cells is relocation work.
- **borders agent**: `BuildingPlacementWireframeOverlay` / `PropPlacementWireframeOverlay` draw the plot through
  `HubPlotBoundaryWireframe.sendPlotBoundary` (yours); it should draw the hex outline for hex plots.
- **catalog**: allow `"category": "generator"` in `CatalogContentValidator` when generator props exist.

## NOT SEEN IN THE CLIENT

- No server or bot was run: there is no entry point that calls `claimCells` / `expandPlot` until the claim map
  lands, so the commits, chunk loading, baseline capture far from the player, the claim and expansion chat lines and
  the recovery on world start are verified by unit tests and code reading only.
- The new placement text was rendered offline (`notes/pictures/hex-plots/`: prop page typical and full, building
  page edge reason), not in the client.
- "Find valid spot" on a hex plot and the house shape message when opening placement have not been tried in game.
