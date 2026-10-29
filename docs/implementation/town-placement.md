# Placing the Hub town on terrain

`/e admin town place` builds the whole Eternia town (`docs/builds/TOWN_PLAN.md`) on the land where the admin stands, and
`/e admin town undo` puts the land back. The town follows the terrain (Gibson, 2026-10-28): only the town square is
levelled; streets, lots, walls and roads rise and fall with the ground.

## What it does, in order
1. **Survey.** Loads the chunks under the town and its roads and reads the natural ground height of every column (the
   top solid soil, rock or sand, skipping plants, logs and leaves).
2. **Plan heights** (`town/TownTerrainPlanner`, pure, unit tested). The ground is smoothed (two box blurs).
   - The town square and its buildings sit on one level pad at the median smoothed height under them.
   - Street cells take the smoothed ground, limited so neighbouring cells differ by at most half a block per block
     (a one block step every two blocks at the steepest), and joined to the pad.
   - Each lot sits at the height of the street cell in front of its door, so every entrance is one step up.
   - Gardens and greens blend between streets and lots with slopes no steeper than one block per block.
   - The wall follows the ground along its line with the same half-block limit, held level across every tower and
     gate; the wall walk climbs with stairs where it steps.
   - Each road out of town follows its line with the same limit, with banks easing back into the land on both sides.
3. **Back up.** Every column the town will touch is captured to plugin data (`town-backups/<id>/`) before any change.
4. **Shape the ground.** Cuts and fills every column to its planned height: stone below, soil and grass on top in
   gardens, paving in streets.
5. **Paste.** The square's prefabs on the pad, then every lot's kit piece (pre-turned copies in `Hub/Kit/Turned/`,
   pasted unturned because the engine's turned paste moves props off their cells and does not turn them), then the
   greens' features.
6. **Walls.** Built column by column from the plan's wall numbers at each column's height: footing down to the
   ground, wall, walk, parapets, towers, gatehouses (pre-turned kit pieces) and stairs from the Wall Lane.
7. **Roads.** Grades each road out of town (at least 5 wide: the King's Road and Harbour Road 9, the West Road 7, the
   housing side roads 5) and registers it through the Road Designer's service, which lays its paving and protects it,
   so players and guilds can claim along it.
8. **Register.** The walled town as the public plaza, the arrival point on the podium, the Waygate's world portal and
   the six Hub NPCs at their desk spots.

Work is spread over ticks with progress messages; an error stops it and says where, and `town undo` restores the backup.

## Data
- `Server/EterniaMod/Town/Eternia_Town.json`, exported by `node tools/prefabs/Eternia_Old_Town.build.mjs --plan` from the same
  layout numbers as the prefab builds: pad pieces, lots (prefab, position, footprint, the street cell in front),
  green features and trees, street cells, wall numbers, roads (straight lines with nodes, width), plaza, arrival, NPC
  and portal spots.
- Turned kit pieces: `tools/prefabs/Eternia_Kit_Turned.build.mjs` writes `Hub/Kit/Turned/<Name>_T<1..3>`.

## Using it
1. Stand where the middle of the town square should be, in a world you are allowed to build in (WorldEditor). The land
   within about 130 blocks, and along the roads out to 360 blocks south, 170 east and 260 west, must hold no registered
   roads, plots or plazas.
2. `/e admin town place` surveys and plans, then shows a summary (the square's height, how deep it cuts and fills, how
   much is built). Press **Build**. Progress messages follow; a full town takes a few minutes.
3. Stand on the podium's arrival point (the message gives it) if you want a different arrival, otherwise it is set.
   Existing Hub NPCs and portals in the world stay where they are; move them with Hub services, Manage.
4. `/e admin town undo` removes the roads, the NPCs and portal the town placed, the plaza, every prop, and restores the
   backed-up land. If a step fails, the message says which, and undo can be run again after fixing it.

Backups live in the plugin data folder under `town/` (one manifest per world, snapshots in `town/backups/<id>/`).

## Notes
- Towers and gatehouses are kit prefabs (`Eternia_Wall_Tower`, `_Corner`, `_Great`, `Eternia_Gatehouse`, built by
  `Eternia_Wall_Towers.build.mjs` and `Eternia_Gatehouse.build.mjs`): only the engine's prefab paste writes the extra
  cells of two-high pieces (spire slopes, doors). The wall body is built block by block (`town/TownWalls`).
- Kit pieces are pasted unturned from `Hub/Kit/Turned/`: the engine's turned paste moves props off their cells (it
  turns them about the origin's corner, not cell centres) and does not turn their yaw.
- Water and very steep land are not special cased: the town cuts and fills to its plan. The summary shows the deepest
  cut and fill; undo if it is not what you want.
- Rebuild the data after changing the town: `node tools/prefabs/Eternia_Old_Town.build.mjs --plan`,
  `node tools/prefabs/Eternia_Kit_Turned.build.mjs`, then `.\tools\prefabs\finalize-dir.ps1 Hub/Kit/Turned`.
