# Hex core (wave 2)

## STATUS

Done. Plan phases 1 and 2 (data and rules) are live behind the world flag `plotShape`. Build green
(`.\gradlew.bat --no-daemon build`, 538 tests, no deprecation notes). API contract: `docs/implementation/hex-plots-api.md`.

Delivered:

- World lattice and `plotShape` in `housing-infrastructure.json` (`WorldPlan.shape() / hexPlots() / hexLattice()`), old
  files read as rectangles, new housing worlds (admin role change) default to hex on the data default grid.
- `/e admin setup lattice show|here|origin|hex|rect` with a drawn preview and the normal review and confirm; refused
  while the world has any plot or unfinished claim.
- `PlotCells` on `HubPlotFootprint` (`lattice`, `cells`), `HubPlotRecord.containsColumn`, `HubPlotManager` cell index
  (`plotAtCell`, `plotAtColumn`, `cellOwners`), cell exact `containsHorizontal` so existing protection callers are right.
- `housing_slot` rows carry `lattice`, `cells`, `expansion`, `expansionCell`; `ClaimLocation` cell aware overlap.
- `HexLand` / `HexLandView` read model with per viewer cell states, disk queries, cached per world.
- `HexClaimRules` data driven (`Server/EterniaMod/Housing/HexPlots.json`), reasons as an enum with lang keys
  (`eternia_hexplots.lang`).
- `HousingService.claimCells / expandPlot / completeExpansion / pendingExpansions`, `EconomyService.spendIn`.
- `HexClaimCheck.validate / reserve` (rules wired behind `ClaimEligibility`).
- `HexLandEvents` fired by plot manager mutations, infrastructure saves and domain reservations.

## DECISIONS

- Lattice id is the lattice itself (`hex:32:20:0:0`), so cells can never be read against the wrong grid and no
  registry is needed.
- `HubPlotFootprint.containsHorizontal` became cell exact for hex plots instead of adding a parallel API, so about 30
  existing callers are correct without edits. `containsFootprint*` stays box only (placement must use `HexFit`).
- Domain commits read ownership from `housing_slot` rows inside the transaction (authoritative, race safe); street and
  blocked cells are passed in (`HexContext`) because world infrastructure is not domain data.
- Rule rejections are returned (`HexOutcome`), not thrown; invariant breaks (non member attaching, reused operation
  id) still throw `DomainException`.
- Expansion writes the new cells durably first (row is the truth) and marks the cell reserved until the native
  projection is updated and `completeExpansion` is called. No cancel path is needed: recovery just reapplies.
- Blocked cells (guild roads, unfinished paving, unfinished spline edits) show as `FREE` (not claimable) rather than
  a separate state, to keep the eight states the brief asked for.
- `MY_GUILD` / `OTHER_GUILD` include member plots attached to a guild, so guild land reads as one colour.
- Claim price defaults to 0 Coins, expansion to 1,000 Coins times cells owned; all in `HexPlots.json`.
- No `CHANGELOG.md` entry yet: nothing player facing is finished until the claim map ships. Proposed lines below.

## CHANGELOG (proposed, player facing)

- Housing plots are now hexagons. Pick hexagons beside a road to start your home.
- Free plots start with one hexagon, upgraded plots with three, and guild estates with seven.
- Grow your plot one hexagon at a time with Coins, up to seven hexagons for a home and nineteen for a guild estate.

## NOT DONE / FOR THE NEXT AGENTS

- Native claim tail with cells (projection, baseline, activate) and the hex branch in `NativeClaimCoordinator`
  (seam a). Recipe in the API doc.
- Pending expansion recovery on world load and the expansion UI (seam a).
- Strand checks for road edits in hex worlds (claimed cell becoming street, plot losing frontage) (seam b).
- Restoring packed plots onto cells, masked snapshots (seam b). `HexClaimCheck.reserve` returns `PACKED` meanwhile.
- No live client test of `/e admin setup lattice show` drawing yet (debug cylinders, same call as the existing setup
  outline).
