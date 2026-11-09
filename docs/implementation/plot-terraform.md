# Plot terraform: levelling claimed hexagons

Notion task: "Auto terraform terrain for claimed hexagons". Branch `wave2/plot-terraform`.

New hexagons are shaped just before they are claimed or added to a plot, so the land players get is mostly flat.
Two looks, switchable while the server runs, so Gibson can compare them:

- **Plateau**: every column of the new hexagons goes to one height. Nothing outside them changes, so each plot is a
  crisp hexagon step (the 2:1 lattice stairs make a Giant's Causeway outline). Raised ground shows grass, two blocks
  of subsoil, then `PlateauBlock` (basalt) on its sides.
- **Smooth**: the hexagons go to one height, a band `BlendWidth` wide inside the plot eases toward a neighbouring
  plot's or road's edge height, and a skirt `SkirtWidth` wide outside the plot, on free land only, eases back to the
  natural ground. The ease starts as a straight ramp by distance, then gets `SmoothPasses` rounds of the vanilla
  sculpt brush's heightmap smoothing (`Sample.calcDeltaHeights` with `SculptOperation.NORMAL_HEIGHTMAP` and its raise
  and lower thresholds, called directly from the installed `HytaleServer.jar`).

## Target height

`TerraformLevel.decide` (pure, unit tested):

1. **Group.** New hexagons that share a side with land of the same owner take that land's height. The owner is the
   player for a public plot, the guild for its estate and every member plot attached to it. The plot being grown
   wins, then the estate, then the largest joined plot. The height is saved on the plot record (`terrainLevel`); a
   plot from before this feature is measured from its ground.
2. **Road.** Otherwise the median surface height of the road or plaza columns in the street hexagons the new land
   touches, counting only the columns nearest the land (within 4 of the closest).
3. **Ground.** With no road beside it either, the median natural ground under the new hexagons.

## Safety

- **Order.** Shaping runs before the claim or expansion commits, as one serialized job with it
  (`PlotTerraformer.shapeThen`). The terrain baseline the claim saves is therefore the shaped ground: pack up puts back
  shaped land, and nothing shaping did ever counts as a player edit in the pack up comparison. The owner's existing
  hexagons are never touched (an expansion only shapes the new hexagon, while it is still free land).
- **What may change.** Only free land: the new hexagons and, in smooth mode, free hexagons round them. Never a road,
  plaza, pending paving or spline road column (`HousingInfrastructure.protectedColumn`), never free land within
  `RoadClearance` of one, never another plot. Inside those, a column is changed only when every block from its ground
  up to 20 above its top is natural (`TerraformBlocks`: soil, grass, sand, rock, snow, ore, plants, leaves, trunks,
  loose rocks) and it holds no water at the surface. Anything else (planks, bricks, furniture, crops, paths, fences)
  leaves the whole column exactly as it is.
- **Checked twice.** Each column is read again in the tick it is written; if it changed since the survey it is left.
- **Never blocks a claim.** A failed or refused shaping is logged and the claim goes ahead on the land as it is.
- **World thread.** Surveying and writing run on the world thread in slices of at most `MaxColumnsPerTick` (1,024)
  columns, one slice per world task; planning runs on the background thread `Eternia plot terraform`. On the test
  server each slice took under 0.1 s.

## Switching

- `Server/EterniaMod/Housing/PlotTerraform.json`: `Mode` (`Off`, `Plateau`, `Smooth`) and the tuning numbers.
- `/e admin terraform mode <off|plateau|smooth|file>`: an admin choice, saved in `plot-terraform-mode.txt` in the mod
  data folder; `file` clears it. `/e admin terraform status` shows what is in force.
- `/e admin terraform try <plateau|smooth>`: shapes the free hexagon you stand on without claiming it, to compare
  the looks side by side. It refuses claimed or blocked hexagons.

Plots claimed before keep their ground. The rectangle claim path (smokes, old callers) does not shape.

## Code

- `hexplots/terraform/TerraformLevel.java`: target height rules.
- `hexplots/terraform/TerraformField.java`: plateau and smooth height fields on a column grid.
- `hexplots/terraform/TerraformBlocks.java`: which block ids may be touched.
- `hexplots/terraform/TerraformSettings.java`: file settings plus the saved admin choice.
- `hexplots/terraform/PlotTerraformer.java`: survey, plan, write, serialized jobs.
- `housing/NativeClaimCoordinator.java`: `claimCells` and `expandPlot` shape first, then commit and save the level.
- `command/TerraformCommand.java`: `/e admin terraform`.
