# Hex borders (wave 2, seam c)

Branch `wave2/hex-borders`. In world glow, plot outline overlay and world map overlay for hexagon plots, all coloured
with the shared palette (`HexLandView.suggestedColor`) by relation to the viewer.

## STATUS

Done, nobody has seen it in the client yet (see the last section).

- **In world glow** (`boundary/BoundaryFogSystem`): in a hex world the rune line and rising lights follow the real
  block staircase from `HexLattice.borderEdges` (cached per distinct set of cells, `HexBorderIndex`), coloured mine
  (teal), my guild (gold), other player (soft blue), other guild (violet). On a side shared by two plots the viewer's
  own colour wins (then guild, then strangers). Rect worlds keep the old glow untouched.
- **Claimable hint**: while the player holds the Plot Deed, free frontage hexagons in their cell and the ring around
  it get a faint green dash every second block out to 12 blocks (rune only, no lights). Streets and owned cells never.
  A flow can replace that set with `HexBorderFocus.show(player, world, cells, millis)` (expansion options, claim map
  selection) and stop with `HexBorderFocus.clear(player)`.
- **Plot outline overlay** (`/e plots show`, `HubPlotVisibilityOverlay`): hex plots coloured by relation; the 4 nearest
  plots within 64 blocks get the exact staircase, the rest six straight lines per hexagon side; top outline and corner
  posts are straight lines. Rect plots stay white boxes.
- **Placement previews** (coordinator request): `HubPlotBoundaryWireframe.sendPlotBoundary` now draws the hex
  staircase outline for hex plots, so the house and decoration placement wireframes (`placement/*WireframeOverlay`,
  plots agent's files) show the real outline with **no edit** on their side. Helper for anything else:
  `BoundaryGeometry.outline(PlotCells, boolean staircase)` returns the ground line segments.
- **World map overlay** (`hexmap/HexMapOverlayService`, ported from Aetherhaven): per player painted tiles with a crisp
  border in the palette colour (2 blocks wide: 1 px at the default map scale 0.5, 2 px at scale 1), a see through fill
  (32% own and guild, 22% others), a dark one pixel shadow outside the border so it reads on any ground, and a faint
  stone tint on street cells. Only tiles the client already has, nearest first, 12 tiles per player per 750 ms pass,
  6 ms pass budget with a rotating player cursor. Layout cached per tile, painted tile cached per player; both
  invalidated per tile by `HexLandEvents` (`HexMapLandStamps`), by the engine regenerating the tile, or by the viewer's
  guild changing. Tiles that lose their plots, and every tile when a world stops being hex, get the plain tile back.
- Gate: `.\gradlew.bat --no-daemon build` green (557 tests, no Java deprecation warnings), `asset_validate` 0 errors /
  35 warnings (all pre-existing), `ui_check_java` 0 errors / 115 warnings (all pre-existing; no UI was added).
  `particle_check` visible, no errors or warnings on every new system I checked (Mine, Mine_Faint, Guild,
  Neighbor_Soft, Rival_Faint, Claimable); all 13 are generated from one template and `BoundaryParticleAssetTest` now
  checks every one structurally plus each rune's colour.

## Budget numbers (measured by tests and the picture tool)

| Shape | Unit edges | Staircase debug lines | Straight debug lines |
| --- | --- | --- | --- |
| 1 cell | 136 | 68 | 6 |
| 3 cell triangle | 272 | 136 | 12 |
| 4 cell diamond | 320 | 168 | 14 |
| 7 cell flower | 408 | 204 | 18 |

- Plot outline overlay per hex plot: ground lines + 2 x straight (top line and post). Single cell: 80 detailed, 18 far.
  Typical street picture (7 plots, 4 detailed): 308 staircase lines + 42 straight lines on the ground layer.
  Worst case per viewer with the cap: 4 x 204 + 2 x 18 x plots + 18 x far plots.
- Glow per viewer per 1.2 s refresh: at most 32 plot emitters (each 1 rune + up to 6 lights, so at most 224 particles)
  and 20 claimable emitters (1 particle each). Typical street with the deed: 15 + 8.
  Scans run every 0.1 s but `HexLand.view` is asked at most once a second per world unless an event marks it dirty.
- Map: 12 tiles per player per 750 ms, 96 nearest candidates sorted, 6 ms per world pass. Typical street: 47 of 91
  tiles painted, at most 50 colours per tile at scale 0.5 (8 bit indices), about 380 bytes per painted tile.
  Crowded: 62 tiles, 55 colours. Scale 1: about 1.2 KB per tile.

## Pictures (`notes/pictures/hex-borders/`, drawn by `tools/hexborders/HexBorderPictures.java` from the real code)

| File | Shows | Verdict |
| --- | --- | --- |
| `01-world-borders-typical.png` | top down mixed street: outlines at the overlay's level of detail, the viewer's glow emitters (by relation and fade) and claimable dashes with the deed | staircase reads cleanly, colours distinct; claimable dashes only on the side toward the viewer (12 block reach), as intended |
| `02-world-borders-crowded.png` | crowded street, 4 detailed plots (404 lines), the rest six line (128 lines) | fine; viewer stands mid plot, more than 8 blocks from every side, so no glow, by design |
| `03-map-typical-default-scale.png` | plain vs painted tiles at scale 0.5, all four relations and the street tint | crisp 1 px borders with the dark shadow, fills not muddy; best picture |
| `04-map-typical-full-scale.png` | same at scale 1 | 2 px borders, smooth |
| `05-map-crowded-default-scale.png` | crowded street | readable; neighbouring other player plots show a double blue seam |
| `06-map-empty-default-scale.png` | street only, no plots | only the faint stone band |
| `particle-rival.png` | `particle_render` of the violet glow | rune and lights visible on dark and bright |

Redraw: `.\gradlew.bat --no-daemon compileJava`, then in Git Bash with `MSYS_NO_PATHCONV=1`:
`java -cp "build/classes/java/main;<HytaleServer.jar>" tools/hexborders/HexBorderPictures.java notes/pictures/hex-borders`.
Glow textures and particles: `python scripts/generate-hex-border-glow.py`.

## DECISIONS

- One particle system per relation and strength (12 + 1 claimable) with the colour baked into the rune texture and the
  light keyframes, instead of tinting one system with the spawn `Color`: how the client combines spawn colour with a
  spawner's own colour keys is not documented, baked colours are certain and checkable with `particle_check`.
- Relation colours come only from `HexLandView.suggestedColor` through `HexBorderRelation.color()`; the Python script
  repeats the hex values (a test checks each rune's line colour against the palette).
- Viewer guild is a domain read: `HexViewers` caches it off the world thread for 45 s. Until the first answer arrives a
  guild mate's plot shows as another player for a moment.
- The map overlay reads the engine map tracker's private "tiles on the client" set by reflection, exactly as
  Aetherhaven does on 0.7.x. It is not a deprecated API; there is no public getter. If a game update renames it, the
  overlay logs once and draws nothing. Sending tiles the client does not have would be overwritten by the tracker.
- The overlay is on for everyone in hex worlds (no toggle), started from `BoundaryBootstrap` and closed with the glow,
  so `EterniaModPlugin` needed no edit.
- Claimable hint defaults to the PUBLIC intent (free frontage); flows that know better pass exact cells to
  `HexBorderFocus`.
- Shared plot sides on the map draw both owners' 1 px borders side by side, so neighbours stay distinguishable.

## SHARED EDITS

None. `EterniaModPlugin` and `domain/EterniaServices` untouched; the map overlay, viewer cache and focus are registered
inside `boundary/BoundaryBootstrap` (my file).

## REQUESTS

- **map agent** (claim map): call `HexBorderFocus.show(player, world, selectedOrOptionCells, 3000)` while the claim map
  or "add a hexagon" is open (renew each refresh) and `HexBorderFocus.clear(player)` on close, so the chosen cells
  glow in the world. Use `HexBorderRelation.color()` / `HexLandView.suggestedColor` for the same colours.
- **plots agent / coordinator**: nothing to wire for placement previews; `sendPlotBoundary` is hex aware. If a caller
  wants only segments: `BoundaryGeometry.outline(footprint.getCells(), true)`.
- **coordinator**: when a flow changes guild membership, `HexViewers.put(player, guild)` recolours at once (otherwise
  within 45 s).
- **hex core (palette)**: no change needed; other player blue at 22% fill reads pale minty green over grass on the map,
  the border carries the colour. Fine for now.

## CHANGELOG (proposed, player facing)

- Plot borders now glow along the edge of each hexagon, in teal for your home, gold for your guild, blue for other
  players and violet for other guilds.
- While you hold your plot deed, hexagons you could claim nearby are outlined in green.
- Plots now show on your world map in the same colours, with roads shaded so streets are easy to spot.

## NOT SEEN IN THE CLIENT (nobody has)

- Any of it. A server start on 15522 failed at plugin setup because the MCP run folder has no `ETERNIA_MODE=local`
  (a fresh folder each start), so there is no live capture of `UpdateWorldMap` packets; the map pictures are the
  painter's own output decoded from the packed `MapImage`, on synthetic terrain.
- Whether the client keeps a per player painted tile when it zooms the map (Aetherhaven's experience says yes on 0.7.x).
- The default map scale on the live server (code handles any scale; assumed 0.5 from `WorldMapSettings`).
- Glow colours in daylight and at night, and whether violet and blue are told apart at a glance on the ground.
- Debug line performance with 4 staircase plots (about 800 cylinders) in `/e plots show`.
