# Hex move (wave 2, move agent, seam b)

## STATUS

Done for seam (b), with two parts thinner than asked (see NOT DONE). Build green (`.\gradlew.bat --no-daemon build`,
558 tests, 0 failures, no Java deprecation warnings). `asset_validate` 0 errors / 35 warnings, `ui_check_java`
0 errors / 115 warnings (both unchanged from the base). `npm test` in `website/`: 53 tests, 51 pass, 2 skipped.

### Relocation and packing (`housing/relocation/*`)

- **Cell masks, same snapshot format.** `ColumnMask` (new) is the set of block columns a snapshot may read or write:
  the whole box for rectangle plots, the cells for hex plots (neighbouring hex boxes overlap where tips interleave).
  `NativeSnapshotStore.capture / entities / apply / verify` take the mask; the envelope stays
  `eternia-native-snapshot-1` and gains an optional `cells` field (`{lattice, cells}`), covered by the SHA-256.
  `save` refuses content outside the cells; `load` refuses a file whose content lies outside its saved cells or whose
  mask is unreadable (never widens to the box). Old snapshots have no `cells` and load as before.
- **Every hex snapshot is masked**: plot baseline (`captureBaseline`), pack (current, personal delta, guild returns),
  restore (destination baseline, after image, rebased object images), placement and pickup
  (`NativePlacementTransactions`), failed prop recovery, admin `recoverPackToSource`.
- **Pack** additionally refuses when the baseline's cells differ from the plot's ("finish adding its newest hexagon").
- **Restore onto cells**: `NativeRelocationCoordinator.restoreCells(world, actor, owner, op, PlotCells destination,
  groundY, scope)`. Destination must be the same shape in the same turn (`HexRelocation.move`, an exact whole block
  translate), on the same grid, free, with road frontage (public, estate) or touching guild land (member). Every check,
  including structure setback (`HexClaimRules.structure`), protected columns and the rebased object images, runs before
  the first world write; after it, the same journal and lock as the rectangle restore. Rectangle restore refuses hex
  packages and hex worlds (and the reverse) with a clear message.
- **Destination list for pickers / the map**: `restoreDestinations(world, owner, actor, scope, x, z, radius, limit)`
  returns every valid placement near a block, nearest first; `restoreCheck(world, owner, actor, scope, cells)` is the
  preview twin; `packedCells(owner)`. Pure core in `HexRelocation.check / destinations`.
- **Grown plots**: `NativeRelocationCoordinator.extendBaseline(plugin, world, plot)` adds the new hexagons' ground to
  the baseline (idempotent, old files kept, pointer replaced atomically). This answers the plots agent's request (1).
- **PACKED lifted in the preview**: `HexClaimCheck.validate` answers a packed owner with the restore rule (same shape,
  same turn) instead of the start shape, so the claim map colours valid spots. `reserve` still returns `PACKED` for a
  packed owner, which now means "call `restoreCells`" (the restore has its own journal and reservation).

### Setup and roads

- `InfrastructureRules.validate(..., hexClaims, ...)` + `validateHex`: a road or plaza edit is refused when it would
  touch a claimed hexagon, or take away the last hexagon beside a street from a public plot or estate that had one.
  Messages name the plot: "This road or plaza would cover hexagon 4:1 of Mira's plot at hexagon 3:1...". Hex plots no
  longer use box checks (box overlap is wrong for hex). `InfrastructureSetup.claims(plugin, world)` collects both kinds
  and is shared by setup review and the Road Designer commit (`SplineRoadService.validateInfrastructure`).
- Road Designer (`pathtool/RoadLatticePreview`, `SplineRoadPreviewSystem`, `SplineRoadHud`): in hex worlds it draws
  the 19 hexagons around the builder plus every hexagon the road touches (cap 48), toned with the core palette (free,
  street, plot, new street amber, covers a plot red), ivory dashes along each nearby row's middle line, and the panel
  says "Hexagon grid: turns 6 hexagons into street." A road cell inside a claimed hexagon is a blocked column in the
  preview.
- `PlotAreas` (new, `housing/`): a plot's land as exact rectangles (box, or the block rows of its cells), cell exact
  `overlaps`, and an `arrival` point inside the first hexagon. Used by `guildroads/*` (zones, easements, before pack),
  `paving/RoadPaving`, `ManagedHubServices`, `NativeTravelService` (plot arrival), `NativeShopTravel`
  (`containsColumn`), `CollectionRuntime` (pets stay inside the cells). Waygates already went through the core's cell
  exact lookup.

### Fixtures

- `localplayground/HexFixtures` (new): hex plan on the data grid, a no-player claim that runs `HexClaimCheck.reserve`
  plus the documented native tail (masked baseline, projection, journal, activate).
- `LocalPlayground`: new villages are hex worlds; the seller's shop is hexagon 1:0 east of the plaza, the Lantern
  Company estate a flower around 1:2 whose west hexagon meets the example road. Old rectangle villages keep working.
  Its native check asserts the sample hexagons.
- `TownBuilder`: a world that gains housing from `town place` gets hex plots on the data grid.
- `runtime/NativeHexSmoke` (new, run by `NativeSmoke` after the guild stage, own flat world `eternia_smoke_hex`):
  claims a cottage and a manor whose boxes overlap, house and potion shelf (three entities), strand check names the
  owner, pack (neighbour marker untouched, house gone), destination list and turned shape refusal, `restoreCells`
  three hexagons east (house offset, entity identities), expansion with `extendBaseline` (twice, idempotent), second
  pack and restore of the grown plot, no unfinished journal. Logs `ETERNIA_NATIVE_HEX_PASS`.

### Website

- `website/server/plot-size.js` (new): `withPlotSize` adds `shape`, `cellCount`, `cells`, `sizeLabel` ("3 hexagons");
  rectangle rows keep width and depth. `bridge.js` runs the account overview's housing through it. Finding: no page
  showed plot width or depth before, and `game-sync.js` / `native-export.js` carry no plot sizes, so nothing visible
  changes until a page shows `sizeLabel`.

### Tests (new)

`HexRelocationTest` (move offsets, turned and other grid refusals, destination rule, destination list, masks across
overlapping boxes, trim and refuse outside content, grown baseline merge), `HexRelocationJournalTest` (hex versions of
the pack and restore guarantees with a restart at each step: packed cells survive, cell exact restore beside an
overlapping box, conflict leaves the slot restorable, cancel before mutation returns the credit, an uncertain write
locks and stays protected and reserved, a pending expansion blocks a move), `HexStrandRulesTest`, `RoadLatticePreviewTest`,
`website/test/plot-size.test.js`.

Pictures (drawn from the real classes by `tools/hexplots/HexMoveDiagrams.java`), `notes/pictures/hex-move/`:
`01-road-designer-empty`, `02-road-designer-row-middle`, `03-road-designer-crowded`,
`04-relocation-masks-and-destinations`. Judged: tones read apart, row dashes visible, red covers stand out; the
panel line fits the HUD's help label.

## SHARED EDITS

- `housing/HexClaimCheck` (hex core, frozen): `validate` branches to the restore rule for a packed owner; `reserve`
  javadoc. This is the "lift the PACKED refusal" the brief asked for.
- `setup/InfrastructureRules`: hex strand checks below the core's lattice lock (as section 4 b says).
- `runtime/NativeSmoke`: one line calling `NativeHexSmoke.run`, plus the pass message.
- No edits to `EterniaModPlugin`, `EterniaServices`, `CHANGELOG.md`, `README.md`.

## CHANGELOG (proposed, player facing)

- Moving your home keeps its shape: pick any free spot where the same hexagons fit, turned the same way.
- Hexagons you add to your plot move with it.
- Builders see the hexagon grid while drawing roads, and which hexagons a road turns into street.
- Your plot size shows as a number of hexagons.

## DECISIONS

- Mask, not a new format: the envelope only gains `cells`; a hex snapshot's box and coordinates are unchanged, so all
  rectangle code (overlay, difference, transforms) runs as is on masked documents.
- A packed hex plot cannot be set down in a rectangle world or the reverse (no migration code, plan section 7).
- Destination rule for restores mirrors claims minus the start shape: an expanded five hexagon plot may be set down as
  five hexagons; caps are not rechecked (the plot already had them).
- Restores precompute the rebased object images before the first world write, unlike the rectangle path, so a bad
  image refuses instead of locking.
- Object images on hex plots are trimmed to the plot's cells on restore (columns outside the cells are never plot
  content).
- Town worlds get the default grid; the town is not re-aligned to it. Use `/e admin setup lattice here` before claims.
- Strand check compares frontage before and after, like the rectangle rule: a plot that already lacked a road does not
  block unrelated edits.

## REQUESTS

- **plots** / coordinator at merge: in the expansion native tail and in pending expansion recovery, call
  `NativeRelocationCoordinator.extendBaseline(plugin, world, plot)` with the plot record already carrying the grown
  cells, before `manager.updatePlot`. Without it, packing a grown plot is refused (safe, but stuck).
- **plots** / coordinator: in `NativeClaimCoordinator.claimCells`, when the owner's slot is `PACKED` (or
  `HexClaimCheck.reserve` returns `PACKED`), call `plugin.getRelocation().restoreCells(world, actor, owner,
  slot.operationId(), cells, groundY, scope)` instead of claiming, mirroring the rectangle branch at line 54.
- **map**: for a packed owner, colour destinations with `getRelocation().restoreDestinations(...)` or
  `restoreCheck(...)` (same shape, same turn); `HexClaimCheck.validate` already answers with that rule.
- **plots**: `VoluntaryPlotMover` UI copy may say "same shape, turned the same way".
- **borders**: none.

## NOT DONE

- `runtime/NativeGuildSmoke` still runs its guild estate, member plot, guild road and forced return in the rectangle
  smoke world. Guild flows in hex worlds are covered only by unit rules and by the playground village claiming a hex
  estate; a hex guild road and a forced return of a hex member plot have never run.
- `TownBuilder` lots are not claims, so no sample plots were added there; only the world's grid.
- Website pages do not yet display `sizeLabel` (none showed sizes before).

## NOT SEEN IN THE CLIENT / NEVER RUN

No server was available to this agent. Never run: `NativeHexSmoke` (all of it), the masked capture and verify on a real
world, `restoreCells`, `extendBaseline`, the Road Designer overlay (debug lines and HUD text), the playground hex
village, the strand check through the setup and Road Designer pages, hex plot arrival and shop visit landing, pets in
hex plots. **Coordinator: please run the native smoke** (`ETERNIA_NATIVE_SMOKE=1`, fresh local data, no PostgreSQL)
and look for `ETERNIA_NATIVE_HEX_PASS` and `ETERNIA_NATIVE_PLAYGROUND_PASS`; then hold the Road Designer in a hex world
and check the line budget (up to 48 hexagons, 6 lines each, plus about 60 row dashes every 600 ms) does not stutter.
Process termination at each journal step of a hex move has not been qualified (same gap as rectangle moves).
