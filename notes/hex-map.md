# Hex plots: the Claim Map (wave 2, map agent)

Branch `wave2/hex-map`. Seam (d) of `docs/implementation/hex-plots-api.md`, plus `MyPlotsPage`, the deed's claim route,
`NativeMenuActions` `HOUSING_CLAIM` and the Architect's Ledger "Claim" entry (contract).

## STATUS

Done and usable, behind the development commit stand in until the coordinator wires the plots engine (one line, below).

- **Claim Map page** (`claimmap/ClaimMapPage` + `ClaimMapPage.ui`): Citadel Codex layout. Left: an 880 x 770 picture of
  the land (11 x 11 cells, 2.5 pixels a block, north up) made from the world map tiles, stitched and painted at run
  time with every hexagon tinted and outlined by state from `HexLandView.suggestedColor`, road and plaza surfaces
  highlighted, one bold border around each plot, a bright ring on every claimable cell, unclaimable open land dimmed,
  unexplored ground hatched, the player as a brass dot. Over it: one transparent hexagon button per cell (hover and
  press show the hexagon), the selected cell outline and the shape preview (white, or coral when refused), a compass,
  pan arrows (3 columns / 4 rows) and "Back to me". Right: what the cell is in plain words, the layout picker for
  triangle and flower plots (only layouts covering the clicked cell, allowed ones first), cost, "your land" (N of max
  hexagons), a confirm step ("Yes, claim it" / "Not yet"), the commit's answer, and a legend.
- **Modes**: claim (personal, "Beside my guild" tab when in a guild, guild estate from the guild route), add a hexagon
  (frontier cells green, price per cell from `HexPlotSettings.expansionPrice`), packed home (slides the saved shape
  over the clicked cell without turning; with the move engine wired, every place it fits near the view glows green
  and each layout is checked by `restoreCheck`; the commit is `claimCells`, which the integration branch routes to
  `restoreCells` for a packed owner). A player who already has a plot here is switched to add a
  hexagon automatically; a packed owner to packed mode.
- **Live**: listens to `HexLandEvents` (redraws when changed cells are on screen; a lattice change recentres), re-reads
  the account after a successful commit, asks the world map for chunks it had not drawn yet and repaints once.
- **Pictures**: per player PNG pushed as a runtime common asset (Aetherhaven's proven path, ported, no reflection), two
  names used in turn so `AssetPath` always changes; removed from the registry when the page closes. Painted on a
  two thread worker pool, never on the world thread; stale paintings are dropped.
- **Routing** (`claimmap/ClaimMapRoutes`): hex worlds open the Claim Map, rectangle worlds keep `PlotClaimPage`.
- **My Plots**: hex plots show "N hexagons"; "Add a hexagon" opens add mode (in the plot's world); "Choose a plot"
  and "Place packed plot" go through the router.
- Gate: `gradlew --no-daemon build` green (no Java deprecation warnings), `asset_validate` 0 errors / 35 warnings,
  `ui_check_java` 0 errors / 115 warnings, 18 new tests in `claimmap/ClaimMapTest`.

### One line wiring at merge (coordinator)

```java
// plugin setup, after claims exist:
com.hexvane.eterniamod.claimmap.HexClaimCommits.install(com.hexvane.eterniamod.claimmap.HexClaimCommits.adapt(
    getClaims()::claimCells, getClaims()::expandPlot,
    r -> new com.hexvane.eterniamod.claimmap.HexClaimCommits.Result(r.ok(), r.message(), r.plotId(), r.reason())));
// move: packed homes (same shape, same turn) checked and listed by the move engine
com.hexvane.eterniamod.claimmap.ClaimMapRestore.install(new com.hexvane.eterniamod.claimmap.ClaimMapRestore() {
    public com.hexvane.eterniamod.hexplots.HexClaimRules.Result check(com.hypixel.hytale.server.core.universe.world.World w, com.hexvane.eterniamod.domain.Owner o, java.util.UUID a, com.hexvane.eterniamod.housing.HousingRules.Scope s, java.util.Collection<com.hexvane.eterniamod.hexplots.HexCoord> c) { return getRelocation().restoreCheck(w, o, a, s, c); }
    public java.util.List<com.hexvane.eterniamod.hexplots.PlotCells> destinations(com.hypixel.hytale.server.core.universe.world.World w, com.hexvane.eterniamod.domain.Owner o, java.util.UUID a, com.hexvane.eterniamod.housing.HousingRules.Scope s, int x, int z, int r, int n) { return getRelocation().restoreDestinations(w, o, a, s, x, z, r, n); } });
// borders: picked cells glow in the world while the map is open
com.hexvane.eterniamod.claimmap.ClaimMapFocus.install(new com.hexvane.eterniamod.claimmap.ClaimMapFocus() {
    public void show(java.util.UUID p, String w, java.util.Set<com.hexvane.eterniamod.hexplots.HexCoord> c, long ms) { com.hexvane.eterniamod.boundary.HexBorderFocus.show(p, w, c, ms); }
    public void clear(java.util.UUID p) { com.hexvane.eterniamod.boundary.HexBorderFocus.clear(p); } });
```

`HexClaimCommits` has exactly the plots engine's two methods and parameters (`PlayerRef, World, PlotCells,
HexClaimRules.Kind` / `PlayerRef, World, String plotId, HexCoord`); only the result type is the map's own (the plots
`HexCommitResult` is not on this branch and was not copied). The commit already tells the player in chat; the page only
shows `message()` in its status line.

## Pictures (`notes/pictures/hex-map/`)

All drawn by `ui_render` from the page's **own commands** (`ClaimMapScreen` through `ClaimMapLogic`, dumped by
`ClaimMapTest.scenarioCommandsAndPictures` into `build/claim-map-scenes/claim-map.test.json`, copied to
`tests/hytale/claim-map.test.json`) over a **real `ClaimMapRenderer` picture** of procedural terrain in the world map
tile format (river, forest, sand, two spline roads made with `SplineGeometry`, a plaza).

| File | Shows |
| --- | --- |
| `01-first-visit` | newcomer, nothing picked, hint text |
| `02-busy-street` | guild member: claimable, open, street, neighbours, own guild, other guild, being claimed |
| `03-claimable`, `04-confirm` | free cell beside the road picked; confirm step |
| `05-taken-long-name` | another guild's land with a very long name (wraps to two lines), cell cut by the map edge (clipped) |
| `06-triangle` | upgraded plot: manor triangle layout 2 of 6 previewed |
| `07-too-far` | open cell, refused layout in coral, "Too far from a road" |
| `08-expand`, `09-expand-result` | add a hexagon: every state incl. "Your plot"; 3,000 coins confirm; the dev commit's answer |
| `10-edge-of-world` | no roads, unexplored east half |
| `11-guild-tab` | "Beside my guild" tab, cell touching the guild estate |
| `12-loading` | first open before the picture arrives |
| `13-packed-home` | packed triangle home slid onto a cell by the road (layout 1 of 3, never turned) |
| `map-only-*` | the painted picture alone |

To redraw: `gradlew test --tests *ClaimMapTest*`, copy `build/claim-map-scenes/*.png` into
`src/main/resources/Common/Icons/ItemsGenerated/` (temporarily: never commit them), copy the json to `tests/hytale/`,
delete old outputs, `test_run` filter "claim map", then delete the copied PNGs. Without them the renders show the
loading pattern (still valid). Static textures: `python scripts/generate-claim-map-art.py`.

## DECISIONS

- **One picture, buttons on top.** The terrain, tints, borders, roads and player are one PNG (crisp anti aliased
  hexagons, one asset push per pan or land change). Selection and shape previews are static textured overlays moved
  with `Anchor`, so clicking is instant and never pushes a picture.
- **Window snapped to the lattice** (even start row, whole columns). Every cell lands on the same pixel spot in every
  window, so the 126 cell buttons are laid out once; panning swaps the picture and what each button means. Buttons
  are "bricks" (cell width by row pitch) that tile the map exactly; only the small tip triangles land in a neighbour's
  brick (the hover texture shows the hexagon so this reads right). Exact hexagon hit testing is
  `ClaimMapGeometry.cellAtPixel` (tests).
- **Marks clip through a scrolling box** (`#MarksClip`, scrollbar size 0): the only clipping container the client has.
- Scale 2.5 px/block, 11 x 11 cells, no zoom (optional in the brief; not done).
- Preview rules are the pure core rules (`HexClaimRules.claim` with the account data loaded once off the world
  thread), not `HexClaimCheck.validate`, which reloads account data on the world thread per call. The commit
  validates again.
- Packed homes preview by sliding the saved cells (same turn): the move engine's `restoreCheck` when wired, else every
  cell free and one beside a road (or guild land). Green cells in packed mode are the union of the engine's
  destinations near the view.
- In add mode the green state means "can join your plot" (legend says "Room to grow"); `FRONTAGE_FREE` elsewhere is
  shown as open land so only real options glow.
- A player who already owns a plot in this world is sent straight to add a hexagon (the deed never dead ends).
- Bramble's "Claim a plot" in a hex world tops up the toolkit silently and opens the map instead of the walk message.
- Palette strengths (`ClaimMapPalette`): owned 0.48 to 0.56, street 0.36, claimable 0.24 fill + bright ring, open land
  darkened 0.38. Hues are the shared palette only.
- Text: all page text is `eternia_hexplots.map.*` (translation messages); reasons via `HexLand.message`.

## SHARED EDITS (outside `claimmap/`)

- `customization/EterniaHousingTool`: deed `CLAIM` route goes through `ClaimMapRoutes.claim` (contract).
- `runtime/NativeMenuActions`: `HOUSING_CLAIM` opens the map in hex worlds; guild "Claim an estate" via router (contract).
- `customization/ArchitectLedgerPage`: "Claim" via router (contract).
- `housing/MyPlotsPage`: hexagon counts, "Add a hexagon", router for claim and packed (contract).
- `socialui/EterniaServicesPage`: Journal Home tab shows a "Claim a plot" button when the player has no plot (one
  ternary in the journal button row). Not in my contract list; the brief asked for the Journal Home button.
- `command/EterniaPlayCommand`: `/e claim` via router (one call).
- `Server/Languages/en-US/eternia_hexplots.lang`: a `map.*` block inserted before `# Plot shapes` (plots agent also
  appends to this file; keep both blocks).
- No `EterniaModPlugin` or `EterniaServices` edits (wiring is the coordinator's line above).

## CHANGELOG (proposed, player facing)

- Claim land on the new Claim Map: see the land around you, pick a hexagon beside a road, and confirm. No more
  walking the deed around.
- Plots that can be claimed glow green on the map, and every neighbour, guild and street has its own colour.
- Grow your plot from My Plots with "Add a hexagon": the map shows where it can grow and what each hexagon costs.
- Manor and guild plots let you turn the shape over the hexagon you picked before you claim it.

## REQUESTS

- **Coordinator**: the two install lines above. Also: pack fails to boot on 0.7.6 in a fresh MCP server
  (`Eternia_Gatehouse_Warden` model "Failed to validate asset", then the gatehouse NPC role fails and the mod is
  rejected). Not a map file; it blocked my live bot test on 15523.
- **Move agent / coordinator**: wire `ClaimMapRestore` (above) to `NativeRelocationCoordinator.restoreCheck(World,
  Owner, UUID, HousingRules.Scope, Collection<HexCoord>)` and `restoreDestinations(World, Owner, UUID, Scope, int x,
  int z, int radius, int limit)` (called with radius 7, limit 60 around the view's middle cell). Without it the map
  previews with its own same shape, same turn check. The commit needs nothing: `claimCells` routes a packed owner to
  `restoreCells` on the integration branch.
- **Borders agent**: `HexBorderFocus.show(UUID, String, Set<HexCoord>, long)` / `clear(UUID)` are called through
  `ClaimMapFocus` with the picked layout (or, in add mode with nothing picked, every growth cell), renewed on every
  change for 10 minutes, cleared on close.
- **Hex core (optional)**: a public `HexClaimCheck.validate` overload taking preloaded eligibility would let the map
  use the commit's exact account checks without a world thread read per click.

## NOT SEEN IN THE CLIENT (nobody has)

- **Any of it in game.** No server could boot (above). Everything is offline: unit tests, the renderer's own PNGs and
  `ui_render` reconstructions of the page's real commands.
- Runtime picture push: that a 880 x 770 PNG under `Icons/ItemsGenerated/` shows in an `AssetImage` (Aetherhaven
  proves 40 px thumbnails on 0.7.x; a large one is unproven), how long the push and atlas rebuild take, and whether
  the old picture flickers.
- That the scrolling box clips the marks in the client (the renderer clips; the client should, untested).
- Hover and press textures on the cell buttons, and the click sound on each cell.
- Real world map tiles: colours, resolution and how they look under the tints (code takes any tile size).
- `HexLandEvents` refresh while a neighbour claims; the commit path end to end with the plots engine.
- Text sizes at UI scales other than 1080p.
