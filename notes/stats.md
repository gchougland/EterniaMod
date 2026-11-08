# Stats (wave 1)

Per player stat tracking from day one, the server firsts record, a read API for leaderboards, contests and the
website, and an "Adventure Records" page.

## What is tracked

Per player, per world (world id), plus totals across worlds:

| Stat | Field | Source (verified on the 0.7.6 jar with javap) |
| --- | --- | --- |
| Chests opened | `chests` | `UseBlockEvent.Post` on a block whose Use interaction contains an `OpenContainerInteraction` |
| Loot chests opened | `lootChests` (also counted in `chests`) | `UseBlockEvent.Pre` on a block carrying `LootChestBlock`; the system overrides `shouldProcessEvent` because `LootChests` cancels the native open to show the personal copy. Not in housing worlds. Reopening the same chest within 30 s is one visit. |
| Blocks travelled | `blocks` | `DelayedEntitySystem` (1 s) reading `TransformComponent`; see the travel filter below |
| Creatures defeated, by kind | `kills`, `kill.<npc role>` | `DeathSystems.OnDeathSystem` on `NPCEntity`; killer from `Damage.EntitySource` (projectiles included). Every NPC death with a player killer counts, no family filter. |
| Deaths, by cause and killer | `deaths`, `death.<DamageCause id>`, `slainBy.<role>` / `slainBy.player` | `OnDeathSystem` on `PlayerRef`; cause from `DeathComponent.getDeathCause()` or the damage's cause index (`Damage.getCause()` is deprecated) |
| Bosses defeated | `bosses`, `boss.<id>.<difficulty>`, `first.<id>.<difficulty>` (instant of that player's first) | same NPC death system, roles from `Server/EterniaMod/Stats/bosses.json`; every non creative player within `CreditRange` (64) shares the victory; deaths by the `Command` cause never count |
| Playtime | not copied | `account.playSeconds` (added at logout by `AccountService`) plus the live session for online players |

Creative mode never counts (kills, chests, travel, boss credit). Server firsts: `stats_first/<boss>.<difficulty>`,
written once, earliest victory wins, announced to everyone online in brass text.

**Travel filter** (`TravelTracker`): a sample in another world, after any `Teleport` component change (own
`RefChangeSystem`), after a death, after a gap over 10 s, or faster than 40 blocks a second only moves the
baseline. Steps under 0.05 blocks are wobble. Fractions carry over.

**Saving**: game listeners only add to the in-memory `StatsTally` (one lock, no store access). One worker thread
(`eternia-stats`) flushes every 60 s, when a player leaves, right after a boss falls, before a stats read, and at
shutdown (`StatsRuntime.close()` runs before `runtime.close()`). A flush writes at most 64 player/world slots per
transaction; a failure puts back only the unsaved slots (`StatsService.FlushFailure`), so nothing is lost or
counted twice. Nothing writes to the entity store.

## Read API (`com.hexvane.eterniamod.stats.StatsService`, via `EterniaServices.stats()`)

Hytale free, usable from the website bridge or contests:

- `Optional<PlayerStats> player(UUID)`: counters, `kills` / `deaths` / `slainBy` maps, `bosses` (boss, difficulty,
  victories, first victory), per world counters, `playSeconds`. Empty only when the player never joined.
- `List<Ranked> top(String stat, int n)` for any of `StatsFields.RANKED` (`chests`, `lootChests`, `blocks`,
  `kills`, `deaths`, `bosses`, `playtime`); shared ranks on ties, zero never ranked, n capped at 100. Scans the
  namespace: wrap in `readView` for a hot leaderboard.
- `long kills(UUID, String role)`: saved kills of one creature role (for the titles goals).
- `List<First> firsts()`: every server first, oldest first.
- `apply(...)` is the writer; only `StatsRuntime` calls it.

Live hooks (`StatsRuntime.active()`): `onKill((player, world, role) -> ...)` runs on the world thread right after a
kill is counted (for titles granting from goals); `read(supplier)` flushes then reads off the world thread;
`bosses()` is the boss list. Pending changes reach `kills(...)` within a minute; call `read` to flush first.

## Player facing

- `/eternia stats` (alias `/e stats`): your Adventure Records page. `/eternia stats <player>`: anyone who has
  joined (tab completes online names; `me` works). Unknown names get a friendly message.
- Page: `StatsPage.ui`, Citadel Codex frame with Home and Close, profile card with playtime and your server firsts,
  six highlight tiles with new icons, Favourite Foes (top 8) and Boss Victories (6). Opens at once, fills when read.
- `/eternia admin stats <player>`: text summary. `/eternia admin stats`: server firsts and the top 3 of every stat.

Pictures (ui_render of the exact commands `StatsView` produces; the test `StatsPageViewTest` writes them to
`build/stats-ui/*.json`): `notes/pictures/stats/empty.png`, `typical.png`, `overfull.png` (another player, huge
numbers, every boss row, more firsts than fit). The overfull picture was drawn before the player title went from
24 to 22 px; nothing else differs.

## SHARED EDITS

- `EterniaModPlugin.java`: field `private com.hexvane.eterniamod.stats.StatsRuntime stats;`; in `setup()` after
  `lootChests = ...register(this);` the line `stats = com.hexvane.eterniamod.stats.StatsRuntime.register(this);`;
  in `shutdown()` before `lifecycle.close()` the line `if (stats != null) stats.close();` (must stay before
  `runtime.close()`).
- `domain/EterniaServices.java`: field, constructor line `stats=new com.hexvane.eterniamod.stats.StatsService(store,clock);`
  after `lootChests=...`, accessor `public com.hexvane.eterniamod.stats.StatsService stats(){return stats;}`.
- `command/EterniaModCommand.java`: `this.addSubCommand(new com.hexvane.eterniamod.stats.StatsCommand());`
- `command/EterniaAdminCommand.java`: new line `addSubCommand(new com.hexvane.eterniamod.stats.StatsAdminCommand());`
  after the constructor's long `addSubCommand` line.
- `runtime/PlayerLifecycle.java`: **not touched**. Stats registers its own `PlayerDisconnectEvent` listener and
  `Teleport` change system; playtime is read from the account record that lifecycle already maintains.

Owned new files: `stats/*.java` (StatsFields, StatsTally, TravelTracker, BossCatalog, StatsService, StatsRuntime,
StatsView, StatsText, StatsSummary, StatsPage, StatsCommand, StatsAdminCommand), `eternia_stats.lang`,
`StatsPage.ui`, `Icons/Stats*.png|svg` (9), `Server/EterniaMod/Stats/bosses.json`, `scripts/generate-stats-icons.py`,
tests `StatsServiceTest`, `StatsTrackingTest`, `StatsPageViewTest` (21 tests).

## CHANGELOG

- **Adventure Records**: type `/e stats` to see your chests opened, blocks travelled, creatures and bosses defeated, times fallen and time in Eternia. Add a name to see another adventurer's records.
- **Server Firsts**: the first adventurer to defeat each boss, on each difficulty, is announced to everyone and remembered for good.

## STATUS

Player stats (wave 1): chests (loot and other containers), blocks travelled with teleport filtering, creatures by
kind, deaths by cause and killer, boss victories with each player's first, server firsts, playtime from the account.
Batched in memory and saved every minute, on logout, after a boss and at shutdown (`stats_player`, `stats_world`,
`stats_first`). `StatsService` read API: player, top N, kills of a role, firsts. `/e stats [player]` page and
`/e admin stats [player]`. Verified by unit tests and ui_render; not yet seen in game.

## DECISIONS

- Bosses are a data list (`Server/EterniaMod/Stats/bosses.json`): the game has no boss flag. Gobliterator counts on
  its final phase role, `_HM` roles are "hard". Feastmaster counts both its combat and feeding roles. Dragons are
  listed though their roles are placeholders today.
- Everyone within 64 blocks shares a boss victory and can share a server first (a party fight is one victory).
  The earliest victory in a flush wins; ties go to UUID order.
- Creative mode never counts; boss deaths caused by the `Command` damage cause never count.
- Stats are keyed by world id, so instance worlds with generated names get their own rows. The page names the
  world with its display name, else a friendly version of the id.
- Opening the same chest again within 30 seconds is one visit, so spam clicking does not farm the leaderboard.
  Loot chest opens are counted on every visit, not only the first roll.
- Separate listeners from `activities/`: the activity systems only fire in adventure worlds, in Adventure mode and
  for roles with season XP; stats must see every kill everywhere. The death query (`NPCEntity`) and the killer
  lookup mirror `ActivityBootstrap.Kills`.
- The live session adds to playtime only for online players, so a session left open by a crash does not keep growing.

## REQUESTS

- Needs eyes in game (nobody has run it): that `UseBlockEvent.Post` fires for a normal chest open and not for a
  protected one; that the loot chest Pre count still runs after `LootChests` cancels it; the final Gobliterator
  role really is `Goblin_Gobliterator_Phase3(_HM)` and the Feastmaster/Broodmother/Voidtaken Bear roles die as named;
  travel numbers on a horse and while gliding; the boss announcement text colour; the page in the real client
  (fonts, the 22 px title with a 16 character name).
- `LootChests` knows when a chest is rolled for the first time (`Opened.firstOpen`). One line there could count
  "loot chests discovered" (distinct) if the team wants it; not done because `loot/` is not a shared file.
- Titles: `StatsRuntime.active().onKill(...)` and `StatsService.kills(player, role)` are ready for granting from
  `goals.json`.
- The website could expose `top(...)` and `firsts()` through `GameBridgeServer`; not wired.
- A Hytale MCP test case (`tests/hytale/stats.test.json`) with these three renders as goldens would keep the page
  honest; the commands are in `build/stats-ui/*.json` after `gradlew test`.
- Gate numbers: asset_validate 0 errors, 35 warnings (the main checkout shows the same 35 today); ui_check_java 0
  errors, 115 warnings, none from stats files.
