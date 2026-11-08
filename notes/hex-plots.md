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
