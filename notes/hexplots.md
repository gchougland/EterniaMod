# hexplots (wave 1)

## STATUS

- Verdict: hexagon plots are feasible. Plan: `docs/implementation/hex-plots-plan.md`.
- Proposed cell: pointy north/south hexagon, 32 x 36 blocks (896), clean 2:1 stair edges, 2 block tips,
  exact tiling. With today's five block setback (from the outer plot border only) one cell holds a
  14 x 14 house, a three cell triangle 22 x 22, a seven cell flower 38 x 38 with room to spare.
- Prototype package `com.hexvane.eterniamod.hexplots` (HexCoord, HexLattice, HexShape, HexFit,
  HexContiguity, HexClaimRules). Not referenced by any live code. Tests: HexLatticeTest,
  HexShapeAndFitTest, HexClaimRulesTest.
- Pictures in `notes/pictures/hexplots/` (01 cell outline, 02 seven cell plot with 22 manor, 03 guild
  estate with 38 keep, 04 street along a curved road), drawn by `tools/hexplots/HexPlotDiagrams.java`
  from the prototype classes. All four opened and checked.
- Live housing behaviour unchanged. No server started, no assets or UI touched.
- Estimated live build: 2 to 3 weeks in six phases (see plan section 5).

Text for docs/STATUS.md:

> Hex plots (2026-11-07): prototype and plan only, nothing live. One cell is a 32 x 36 block hexagon
> with 2:1 stair edges; 1, 3 and 7 cell shapes hold today's 14, 22 and 38 houses with the current
> setback. See docs/implementation/hex-plots-plan.md.

## DECISIONS

Proposed, for Gibson to confirm (reasons in the plan, section 6):

- Pointy north/south hexagons, 32 across the flats, 36 tip to tip, 2:1 stairs. Plan B: 28 x 30 with a
  three block setback if land per cell should stay closer to today's 576 blocks.
- Setback stays five blocks, measured from the plot's outer border only.
- House shapes: cottage 1 cell (to 14), manor 3 cell triangle (to 22), keep 7 cell flower (to 38).
- Guild estate starts with 7 cells (flower). Personal: 1 free, 3 with today's upgrade.
- New cells must share a full side with the plot; plots never split. Caps: personal 7, guild 19.
- Cells touched by a public road or plaza become public street cells. A plot needs one cell beside a
  street cell. Guild member plots must touch the estate or a joined member plot.
- Yard limits counted per cell: 24 props, 2 generators (numbers are placeholders).
- No migration: pack the one test plot through the normal flow before switching a world to hexagons.

## REQUESTS

- Gibson: confirm cell size (32 x 36 vs plan B 28 x 30), guild start of 7, growth caps and the price
  per added cell.
- Coordinator: nothing shared was edited.
- `/tools/` is gitignored; `tools/hexplots/HexPlotDiagrams.java` was force added. Coordinator: keep it, or add `!/tools/hexplots/` to .gitignore.

## SHARED EDITS

None.

## CHANGELOG

None (no player facing change).
