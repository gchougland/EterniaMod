# Hexagon plots: plan

Status: **proposal with a tested prototype**, not wired into live claims. Written 2026-11-07 for Gibson's
decision to move housing from rectangles to hexagons.

**Verdict: feasible, and a good fit.** Hexagons cut cleanly from Hytale's block grid with 2:1 stair
edges, tile with no gaps, and with one cell size every existing house size (14, 22, 38) fits with
today's five block setback unchanged. The engine needs nothing new: plots are already plain data
checked by our own code. The work is a rewrite of the plot geometry layer (claim, protection, border,
placement, relocation) behind one new "set of cells" type, about **2 to 3 weeks** of focused work
plus a playtest, in six phases that each ship on their own.

Prototype: `src/main/java/com/hexvane/eterniamod/hexplots/` (6 classes, 3 test classes, 23 tests).
Pictures: `notes/pictures/hexplots/` (drawn by `tools/hexplots/HexPlotDiagrams.java` from the prototype
itself, so they cannot drift from the code).

| Picture | Shows |
| --- | --- |
| `01-cell-outline.png` | One cell block by block, its sizes, the 14 x 14 house and the setback |
| `02-seven-cell-plot-manor-22.png` | A seven cell plot: a 22 x 22 manor on three cells, four yard cells |
| `03-guild-estate-keep-38.png` | Guild estate: seven cell start, 38 x 38 keep, three added cells, two member plots |
| `04-street-along-curved-road.png` | A curved public road, its street cells, and a mixed street of plots |

## 1. The cell

**Pointy top hexagon (tips north and south, flat sides east and west), 32 blocks across the flats,
36 blocks tip to tip, 896 blocks.** `HexLattice.DEFAULT = new HexLattice(32, 20, 0, 0)`.

- Flat sides: 20 blocks straight along Z.
- Slanted sides: exact 2:1 stairs. Each row the cell grows two blocks on each side. Every step is the
  same, so the aurora border and claim grid draw an even staircase, never a 2, 2, 1 rhythm.
- Tips: two blocks wide (the cell is mirror symmetric both ways).
- Rows are 28 blocks apart; odd rows are shifted 16 blocks east. Every cell is an exact whole block
  translate of every other one, so a packed plot can be restored onto any other cell of the same shape.
- Block centres never sit on a cell edge, so every block belongs to exactly one cell. No tie rules.

```
One cell, 32 x 36 blocks, 896 blocks. '#' = this cell, '.' = neighbours. North is up.
 0 ...............##...............
 1 .............######.............
 2 ...........##########...........
 3 .........##############.........
 4 .......##################.......
 5 .....######################.....
 6 ...##########################...
 7 .##############################.
 8 ################################
   ... rows 9 to 26 are full width (32) ...
27 ################################
28 .##############################.
29 ...##########################...
30 .....######################.....
31 .......##################.......
32 .........##############.........
33 ...........##########...........
34 .............######.............
35 ...............##...............
```

### Why this size

The decisive measure is the largest square house a set of cells holds with the five block setback
from the plot's **outer** border (cell edges inside one plot need no setback). Computed by
`HexFit.largestSquare`, asserted in `HexShapeAndFitTest`:

| Cells | Shape | Largest square house | Today's house it matches |
| --- | --- | --- | --- |
| 1 | single | **14** | free house (24 x 24 plot) |
| 3 | triangle (three cells meeting at a corner) | **22** | upgraded house (32 x 32 plot) |
| 4 | diamond | 38 in one turn only, smaller in the other two | guild keep, zero room |
| 7 | flower (centre and its six neighbours) | **54** | guild keep (38) with 8 blocks to spare |

A hexagon wastes its corners for a square house, so one cell is 896 blocks against 576 today for the
same 14 x 14 house. The corners become garden and prop space. Alternatives looked at:

- **28 wide, setback reduced to 3** (644 blocks, close to today's 576 of land): also gives 14 / 22, but
  changes the setback rule and puts neighbours' houses six blocks apart. Kept as plan B; it is one
  constant (`new HexLattice(28, 16, ...)` plus `HOUSE_SETBACK = 3`).
- **True 30 degree slopes**: the stair runs alternate 2, 2, 1, which reads as a wobble on the border glow.
- **Flat top** (tips east and west): the same lattice turned 90 degrees. No advantage; pointy north and
  south keeps rows running east and west, like the Hub's main road.

## 2. World layout

Each housing world gets one fixed lattice (origin and cell size saved in its infrastructure file, set
when the world gets the housing role, never changed while claims exist).

- **Street cells**: every cell a public road surface or public plaza touches. Public land, never
  claimable, free for lamps, benches and trees by builders.
- **Frontage cells**: free cells sharing a side with a street cell. A new public plot or guild estate
  must include at least one frontage cell. This replaces "within five blocks of the road centerline":
  it is easier to see (the claim grid tints frontage cells) and needs no distance maths.
- Roads stay splines and stay where they are. A road eats every cell it touches, so a road laid along
  a row seam takes two rows of cells (visible in picture 04). Builders get a lattice overlay in the
  Road Designer preview so they can run roads down the middle of a row.
- Plots touch each other directly: neighbouring houses are still at least ten blocks apart because of
  the setback on both sides.

## 3. What changes, subsystem by subsystem

The core change is one new value type, **`PlotCells`** (lattice id plus a sorted set of `HexCoord`),
stored on every plot. The old bounding box stays as a cached fast reject.

| Subsystem | Today | With hexagons |
| --- | --- | --- |
| Claim rules | `HousingRules.claim` on a `PlotRect` (size entitlement, overlap, road centerline within 5) | `HexClaimRules.claim`: start shape by entitlement, cells free, not street, connected, one frontage cell. Guild members: touch the estate or a joined member plot |
| Expansion | none (fixed sizes, paid upgrade) | `HexClaimRules.expand`: one free, non street cell sharing a side with the plot, up to the cap; priced per cell |
| Guild estate | 48 x 48 or 36 x 64, upgraded 64 / 32 x 128 | starts as a 7 cell flower, grows to 19 |
| House placement | `BuildingPlacementValidator`: footprint in plot, `containsWithSetback(5)`, road setback | `HexFit.fits(cells, footprint, 5)`; "Find valid spot" uses `HexFit.nearestFit`. The road setback check stays but is automatically met because roads only run through street cells |
| House footprints | house sits anywhere in the rectangle | each house has a catalog shape (1, 3 or 7 cells, `HexShape.forHouseSide`). The cells it stands on are house cells; the rest are yard |
| Props, generators | anywhere in the plot | anywhere in the plot, limit per cell where the prop stands (`PROPS_PER_CELL = 24`, `GENERATORS_PER_CELL = 2` proposed) |
| Protection | `HubPlotManager.findPlotContainingHorizontal` scans bounding boxes | `cellAt(x, z)` then a `Map<HexCoord, plotId>` lookup: O(1) and simpler. All other protection logic unchanged |
| Border glow and fog | `BoundaryGeometry` samples four rectangle edges | samples the unit edges from `HexLattice.borderEdges(cells)`, already one block tiles with a horizontal flag, which is what the aurora quads use. Cached per plot |
| Claim UI grid | `ClaimGrid` draws a rectangle of debug cylinders | snaps to the cell under the player; draws the chosen cells' staircase border, neighbours as six straight lines each, tints frontage and street cells. "Add a hexagon" mode for expansion |
| Saved data | `HubPlotFootprint` min/max; `housing_slot` row `minX, minZ, width, depth` | add `cells` (`"q:r;q:r;..."`, `HexCoord.toString` / `parse`) and `lattice` to both. Bounding box kept and derived |
| Packing and moving | snapshot the bounding box; restore at a new rectangle | snapshot the bounding box, restore only columns inside the cells; destination must be the same shape in the same turn (cells are translates, not rotations) |
| Guild community roads | endpoints inside guild plots, gap up to 5 unclaimed cells | unchanged except "inside plot" goes through the cell lookup |
| Personal paths, palettes | `PathPlanner`, `CustomizationService` against the plot rectangle | same code, "inside plot" through the cell lookup; setback against the outer border edges |
| Admin setup | refuses road edits that strand a claim (centerline distance) | refuses road edits that would turn a claimed cell into a street cell, or leave a plot with no frontage |
| Website | reads plot width/depth through game sync | show cell count instead; check `website/server/game-sync.js` and `native-export.js` |

### Classes and rough size

| Class | Change | Size |
| --- | --- | --- |
| `hexplots/*` (this prototype) | promote to live, add `PlotCells` | small |
| `hub/HubPlotRecord`, `HubPlotFootprint`, `HubPlotManager` | cells field, `containsColumn`, cell index | medium |
| `housing/HousingRules`, `ClaimEligibility`, `NativeClaimCoordinator` | delegate to `HexClaimRules`, cell entitlements, expansion commit | medium |
| `housing/PlotClaimPage`, `ClaimGrid`, `MyPlotsPage` (+ `.ui`) | cell picker, add a hexagon flow, grid drawing | large (the main UX work) |
| `domain/HousingService` | `ClaimLocation` gains cells, expansion operation and journal state | medium |
| `housing/HousingProtection`, `NativeHousingChecks` | cell lookup | small |
| `boundary/BoundaryGeometry`, `BoundaryFogSystem`, `hub/HubPlotBoundaryWireframe`, `HubPlotVisibilityOverlay` | edge list instead of rectangles | medium |
| `placement/BuildingPlacementValidator`, `PropPlacementValidator`, open helpers, `ui/*PlacementPage` | `HexFit`, per cell limits | medium |
| `housing/relocation/*` (about 1,260 lines) | cell masks on capture and restore, same shape destination | medium, risky |
| `customization/*`, `guildroads/*`, `setup/InfrastructureRules`, `pathtool/SplineRoadTool` (street overlay) | swap containment and strand checks | small each |
| `localplayground/LocalPlayground`, `runtime/NativeSmoke`, `NativeGuildSmoke`, `town/TownBuilder` | sample plots on the lattice | small |

About 30 files touch plot geometry (`grep getFootprint()` finds 29). Most change one line once
`HubPlotRecord.containsColumn(x, z)` exists.

## 4. Risks

1. **Relocation and packing** are the most delicate code in the mod (journals, verified snapshots).
   Masking the bounding box snapshot by cells must be proven with the same interruption tests the
   current flow has. Keep rectangle snapshots and add a mask, rather than a new snapshot format.
2. **Debug shape budget**: a staircase border is many short cylinders (136 per cell edge set). Draw the
   staircase only for the selected plot and straight six line hexes for neighbours; measure on a client.
3. **Roads eat land**: a road on a row seam takes two rows. Mitigation: lattice overlay in the Road
   Designer. If it still wastes too much, allow a cell clipped by fewer than N road blocks to stay
   claimable with those columns protected, as today. Start strict; it is one predicate in `Land.of`.
4. **Existing Hub roads and plazas** were laid without the lattice. Pick the lattice origin per world
   to suit them (two integers); preview with an admin command before saving it.
5. **Prefab houses are not square**: footprints are rectangles from `PlotFootprintUtil`; `HexFit` already
   takes width and depth separately. A cell holds wider than tall rectangles (for example 20 x 14) better
   than squares, which is a bonus.
6. **Rotated shapes don't move**: a triangle plot can be restored only onto a triangle in the same turn.
   The move picker should list only matching placements; the shape has two turns, so plenty exist.
7. **No live testing yet**: this branch has unit tests and pictures only. The border look and claim
   grid need a client check.

## 5. Build order

Each phase builds green and could ship alone; nothing changes for players until phase 3.

1. **Data** (2 days): `PlotCells`, lattice in world infrastructure, `cells` on `HubPlotRecord` and
   `housing_slot`, `containsColumn`, cell index in `HubPlotManager`. Rectangle plots keep working.
2. **Rules** (2 days): `HexClaimRules` and `HexFit` behind `HousingRules` and both placement
   validators, guarded by a world flag `plotShape: hex`. Unit tests port from `HousingRulesTest`.
3. **Claim page** (4 days): cell picker, frontage tint, staircase grid, review and confirm. New `.ui`
   rendered with `ui_render` empty, typical and overfull.
4. **Borders and protection** (2 days): edge based aurora, wireframe and fog; protection via cell index.
5. **Expansion and yards** (3 days): "Add a hexagon" with price, per cell prop limit, My Plots shows
   cells. Guild estate flower start, member plots join by touching.
6. **Relocation, roads, setup** (3 to 4 days): masked snapshots, same shape moves, Road Designer
   lattice overlay, strand checks, website cell count. Full local playtest, then remove the rectangle
   path in the hex world.

## 6. Proposed answers to the open questions

| Question | Proposal | Why |
| --- | --- | --- |
| Hex orientation and outline | Pointy north/south, 32 x 36, 2:1 stairs, 2 block tips | Even stairs, exact tiling, translates move cleanly, and 14 / 22 / 38 houses fit with today's setback |
| How many hexagons does a guild start with | **7, a flower** | The keep needs the centre plus all six neighbours to fit 38 x 38 with room to nudge it (the 4 cell diamond fits only one way round, with zero room). It also reads as a guild seat on the map |
| Shapes for larger houses | **1** cottage (to 14), **3 in a triangle** manor (to 22), **7 flower** keep (to 38) | Smallest shapes that hold each size in every turn |
| Must new hexagons touch the plot | **Yes, share a full side** (corners alone do not count); a plot never splits | Keeps one border, one walk, simple protection and glow. `HexContiguity.canAdd / canRemove` |
| Start sizes | Free personal: 1 cell. Today's paid upgrade: 3 cells (triangle). Guild estate: 7 | Matches today's entitlements one to one |
| Growth caps | Personal 7 cells, guild estate 19 (flower plus the next ring) | A personal flower fits the 22 manor plus four yards; 19 cells is about 7 times today's guild land |
| Per cell limits | 24 props and 2 generators per cell, counted where each stands | Simple to explain: grow the plot to place more |
| Guild member plots | Must touch the estate or a member plot already joined to it | Replaces "within five blocks of the guild house chain" |
| Road rule | A plot needs one cell beside a street cell | Replaces the five block centerline distance |
| Hexagon price | Coins per added cell, rising with plot size (for example 1,000 times cells owned) | Leaves Crowns for cosmetics; numbers are Gibson's call |

## 7. The one test plot

No migration code. Before turning on hex plots in a world: pack or unclaim the test plot with the
normal flow (so its snapshot and journal close cleanly), delete nothing by hand, then set the world's
lattice. The old rectangle code path stays until phase 6 so nothing breaks in other worlds meanwhile.

## 8. Prototype reference

| Class | What it does |
| --- | --- |
| `HexCoord` | axial coords, neighbours (E, NE, NW, W, SW, SE), distance, rings, disks, turning, mirroring, `q:r` text |
| `HexLattice` | cell outline in blocks, block to cell, cell to blocks (`spans`), bounds, cells touched by a rectangle, border edges |
| `HexShape` | catalog: single, triangle, diamond, flower; turned variants; placements covering a cell |
| `HexFit` | does a rectangle fit with setback, largest square, nearest valid spot, cells under a house |
| `HexContiguity` | connected, frontier, can add, can remove |
| `HexClaimRules` | street and frontage cells, claim, expand, per cell prop allowance, start sizes and caps |

Redraw the pictures after changing the lattice:

```
.\gradlew.bat --no-daemon compileJava
java -cp build/classes/java/main tools/hexplots/HexPlotDiagrams.java notes/pictures/hexplots
```
