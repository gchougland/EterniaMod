# Titles (wave 1)

A first set of 33 mob suffix titles ("Seedling the Trork Slayer"), drawn from NPC roles in the installed
0.7.6 game (`Server/NPC/Roles` in `Assets.zip`), plus player text and earn goals for every title.

## How titles work today (end to end)

- **Definition**: `Server/EterniaMod/Collections/<slug>.json` listed in `catalog.index`, read by
  `runtime/GameplayContent` into `CollectionService.Definition(id, name, kind, slot, assetId)`. For titles
  `assetId` is the text shown on the nameplate; `name` is the card name. The record constructor is the
  only validator (namespaced id, nonblank name up to 100, assetId up to 200). `CatalogContentValidator` and
  `CatalogAssetValidator` only check buildings and props, never collections.
- **Ownership**: a grant of the content id (`UNLOCK`), from a season reward row, a Crown Store item
  benefit, a Tebex product or `/e admin grant`.
- **Equip**: Collection > Titles card, `collection_equip:<id>` -> `CollectionService.equip` stores
  `prefix`/`suffix` in `collection_selection`.
- **Shows**: `CollectionRuntime` composes `prefix + username + suffix` into the `DisplayNameComponent`
  (nameplate) and the chat formatter (`CollectionBootstrap`). Collection card: `EterniaServicesPage.collectionView`
  (owned titles only, detail "Equipped" or "Suffix title", generic `Icons/title.png`). Website: `web/js/collection.js`
  Titles shelf, names from the bridge.

## What I added

- 33 `SUFFIX_TITLE` definitions in `Server/EterniaMod/Collections/`, appended to `catalog.index` (nothing reordered).
  Every suffix starts with "the" or "of" so it reads naturally after a name, is at most 24 characters,
  and `name == assetId` like the existing titles.
- `Server/Languages/en-US/eternia_titles.lang`: `<slug>.name` and `<slug>.description` (how it is earned)
  for all 35 titles including Wayfarer and of the Hearth.
- `Server/EterniaMod/Titles/goals.json`: per title, the intended source (`FREE`, `SEASON_PASS`,
  `CROWN_STORE`) and, where it fits, a kill goal (`targets` in `QuestTargets` selector syntax, `required`).
  Nothing loads it yet; it is the data the stats wiring should read.
- `collections/Titles.java`: `isTitle`, `slug`, `descriptionKey`, `description(contentId)` (a
  `Message.translation` of the earn text) so any page can show how a title is earned in one call.
- `src/test/.../collections/TitleCatalogTest` (5 tests): every indexed title loads through the same Gson +
  record constructor as startup; ids, file names and shown text unique; text rules (no dashes, starts with
  the/of, at most 24 characters); lang name and description present for every title and nothing extra;
  every goal selector matches a real 0.7.6 role name.
- `tests/hytale/titles-collection.test.json`: four `ui_render` cases of the Journal Collection Titles view
  built from the commands `EterniaServicesPage.build` sends (wallet, header, nav tabs, side tabs, card rows,
  fields, buttons), saving pictures to `notes/pictures/titles/`. All pass with no command errors.

## Pictures (opened and judged)

- `notes/pictures/titles/collection-typical.png`: Wayfarer, of the Hearth and four mob titles, one equipped, status line.
- `notes/pictures/titles/collection-longest.png`: the six longest names (the Crocodile Wrangler, the Gobliterator's End...). All fit on one line in the 6 per row grid, nothing cut.
- `notes/pictures/titles/collection-overfull.png`: all 35 titles; rows scroll cleanly.
- `notes/pictures/titles/collection-empty.png`: no titles owned.

Verdict: names fit, no truncation or crowding, so `CollectionCardRow.ui` needed no change. Two weak spots I
could not fix inside my files (see REQUESTS): every card shows the same "T" badge, and an equipped card
looks the same as the others apart from its small detail line. Locked titles are not shown at all
(the view lists owned titles only), so there is no locked state to render.

## Earning by gameplay: not wired, here is the plan

Season quests only award pass XP and Coins, never content, so a kill based title cannot be granted
through the existing quest hooks without new code in shared domain files. The stats agent's kill
tracking is the right place:

1. On each verified kill (the `ActivityBootstrap` death hook knows the NPC role name), the stats service
   increments a per player counter per role, batched.
2. A small title granter loads `Titles/goals.json`; for each goal it sums the counters of roles matching
   any `targets` selector (`QuestTargets.matches`) and, when the sum reaches `required`, calls
   `ownership().grant(new GrantRequest("title:" + titleId + ":" + player, Owner.player(player), titleId, UNLOCK, 1, null))`.
   The receipt key makes it idempotent, so replays and restarts never double grant.
3. Only goals with source `FREE` should auto grant. `SEASON_PASS` goals are suggested season quests
   (the title itself goes on a reward row); `CROWN_STORE` titles have no goal.

Caveats: today the kill activity only fires for targets that give pass XP (default `group:enemies`:
undead, Trork, Goblin, Outlander). Feran, Scarak, Void, wildlife and boss kills are not reported, so the
stats hook must count all NPC deaths, not only XP ones. The Gobliterator has several phase roles;
`npc:Goblin_Gobliterator` would count each phase death, so that goal should count the final phase only
(`Goblin_Gobliterator_Phase3`, `_Phase3_HM`) once stats exists.

## Titles

| Id | Shows as (after the name) | How it is earned (player text) | Intended source | Kill goal (for stats wiring) |
| --- | --- | --- | --- | --- |
| `eternia:title/trork_slayer` | the Trork Slayer | Defeat 100 Trorks. | Free | npc:Trork x 100 |
| `eternia:title/of_the_trork_warband` | of the Trork Warband | A season pass reward for those who face the Trork war camps. | Season pass | none |
| `eternia:title/chieftain_breaker` | the Chieftain Breaker | Defeat 10 Trork Chieftains. | Free | npc:Trork_Chieftain x 10 |
| `eternia:title/goblin_bane` | the Goblin Bane | Defeat 100 Goblins. | Free | npc:Goblin x 100 |
| `eternia:title/of_the_scrap_heap` | of the Scrap Heap | Found in the Crown Store, for fans of goblin junk. | Crown Store | none |
| `eternia:title/gobliterators_end` | the Gobliterator's End | Defeat the Gobliterator in its workshop. | Free | npc:Goblin_Gobliterator x 1 |
| `eternia:title/thief_catcher` | the Thief Catcher | Catch 10 Goblin Thieves before they get away. | Free | npc:Goblin_Thief x 10 |
| `eternia:title/kweebec_friend` | the Kweebec Friend | A season pass reward for helping the Kweebec villages. | Season pass | none |
| `eternia:title/of_the_kweebec_grove` | of the Kweebec Grove | Found in the Crown Store, grown with love by the Kweebecs. | Crown Store | none |
| `eternia:title/windwalker` | the Windwalker | A season pass reward for braving the Feran deserts. | Season pass | npc:Feran_Windwalker x 25 |
| `eternia:title/feran_tracker` | the Feran Tracker | Defeat 50 Feran warriors. | Free | npc:Feran_Burrower, npc:Feran_Longtooth, npc:Feran_Sharptooth, npc:Feran_Windwalker x 50 |
| `eternia:title/outlander_hunter` | the Outlander Hunter | Defeat 100 Outlanders. | Free | npc:Outlander x 100 |
| `eternia:title/cultbreaker` | the Cultbreaker | A season pass reward for breaking the Outlander cults. | Season pass | npc:Outlander_Cultist, npc:Outlander_Priest x 25 |
| `eternia:title/bonebreaker` | the Bonebreaker | Defeat 200 Skeletons. | Free | npc:Skeleton x 200 |
| `eternia:title/gravewarden` | the Gravewarden | A season pass reward for laying the restless dead to sleep. | Season pass | group:undead x 500 |
| `eternia:title/pirates_bane` | the Pirate's Bane | Defeat a Skeleton Pirate Captain. | Free | npc:Skeleton_Pirate_Captain x 1 |
| `eternia:title/ghoulhunter` | the Ghoulhunter | Defeat 50 Ghouls or Zombies. | Free | npc:Ghoul, npc:Zombie x 50 |
| `eternia:title/of_the_shadow_vigil` | of the Shadow Vigil | A season pass reward for standing watch against the Shadow Knights. | Season pass | npc:Shadow_Knight x 1 |
| `eternia:title/scarak_hunter` | the Scarak Hunter | Defeat 100 Scarak. | Free | npc:Scarak x 100 |
| `eternia:title/broodbreaker` | the Broodbreaker | A season pass reward for facing the Scarak Broodmother. | Season pass | npc:Scarak_Broodmother x 1 |
| `eternia:title/voidwarden` | the Voidwarden | A season pass reward for holding back the Void. | Season pass | npc:Void_Spawn, npc:Crawler_Void, npc:Eye_Void, npc:Larva_Void, npc:Void_Spectre x 50 |
| `eternia:title/dragonslayer` | the Dragonslayer | Defeat a Fire Dragon or a Frost Dragon. | Free | npc:Dragon_Fire, npc:Dragon_Frost x 1 |
| `eternia:title/feastbreaker` | the Feastbreaker | Defeat the Feastmaster at his own table. | Free | npc:Dungeon_Feastmaster_Boss x 1 |
| `eternia:title/of_the_wolf_pack` | of the Wolf Pack | Found in the Crown Store, for those who run with wolves. | Crown Store | none |
| `eternia:title/of_the_yeti_peaks` | of the Yeti Peaks | A season pass reward for braving the frozen peaks. | Season pass | npc:Yeti x 1 |
| `eternia:title/bearwrestler` | the Bearwrestler | Defeat 25 bears. | Free | npc:Bear_Grizzly, npc:Bear_Polar x 25 |
| `eternia:title/spiderbane` | the Spiderbane | Defeat 100 spiders. | Free | npc:Spider x 100 |
| `eternia:title/sabertooth_stalker` | the Sabertooth Stalker | Defeat 10 Sabertooth Tigers. | Free | npc:Tiger_Sabertooth x 10 |
| `eternia:title/of_the_emberwulf` | of the Emberwulf | Found in the Crown Store, burning bright like an Emberwulf. | Crown Store | none |
| `eternia:title/crocodile_wrangler` | the Crocodile Wrangler | A season pass reward for surviving the crocodile swamps. | Season pass | npc:Crocodile x 25 |
| `eternia:title/golem_breaker` | the Golem Breaker | Defeat 25 Crystal Golems. | Free | npc:Golem_Crystal x 25 |
| `eternia:title/slothians_friend` | the Slothian's Friend | Found in the Crown Store, for the calm and the cozy. | Crown Store | none |
| `eternia:title/chicken_whisperer` | the Chicken Whisperer | Found in the Crown Store, for the true friend of every hen. | Crown Store | none |

Totals: 17 free, 10 season pass, 6 Crown Store. Existing titles unchanged: Wayfarer (Foundations free
level 5) and of the Hearth (Foundations paid level 10, Crown Store 100 Crowns).

## SHARED EDITS

None. I did not touch `EterniaServicesPage`, `SeasonService`, CrownShop, Seasons or any shared file.
`Collections/catalog.index` gained 33 lines at the end (title content is mine per the contract).

## CHANGELOG

- 33 new titles inspired by the creatures of Orbis, from the Trork Slayer to the Chicken Whisperer.

## STATUS

- Titles: 33 mob suffix titles added, with earn text in `eternia_titles.lang` and planned sources and kill
  goals in `Titles/goals.json`. They can be granted now (`/e admin grant`) but nothing awards them yet: no
  season rows, store items or kill tracking point at them. Collection Titles view rendered offline with all
  35 titles; not seen in the client.

## DECISIONS

- Suffix titles start with "the" or "of" and stay within 24 characters, so "Name the Trork Slayer" reads
  naturally on a nameplate and fits a Collection card on one line. Enforced by `TitleCatalogTest`.
- How a title is earned is player text in `eternia_titles.lang`; where it comes from (free, season pass,
  store) and kill goals are data in `Titles/goals.json`, kept out of the collection definition so the stored
  definition and its registration check do not change.
- Only free titles should be granted automatically from kill counts; season pass and store titles keep
  their own channels.

## REQUESTS

- **Gatehouse (`EterniaServicesPage.collectionView`, titles mode)**: use `Titles.description(id)` as or under
  the card detail so players see how each title was earned; show unowned titles as locked cards with their
  earn text and no button, so players can discover what to chase; give the empty state a next step
  ("Earn titles from season passes, the Crown Store and by defeating creatures."); mark the equipped card
  more strongly (for example its button in the primary style).
- **Season sales / coordinator**: place the 10 `SEASON_PASS` titles on reward rows of the next pass and the 6
  `CROWN_STORE` titles in the Crown Store (suggest 100 Crowns, matching of the Hearth). I set no prices.
- **Stats**: wire the free kill goals as described above.
- **Art**: per title or per faction icons under `Catalog/title/<slug>/icon.png` (picked up automatically by
  `ContentImages`). A wall of 35 identical badges is the main thing that still looks unfinished. Vanilla's
  Memories category icons (Trork, Goblin, Kweebec, Feran, Outlander, Scarak, Undead, Voidspawn...) show the
  look; they are game assets, so I did not copy them. The Citadel icon generator
  (`scripts/generate-citadel-icons.py`) or a fal pass could make faction badges.
