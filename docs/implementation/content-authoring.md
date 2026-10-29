# Add content to Eternia

Content is separated from entitlement sources. A house, prop, skin, title, or pet has a stable ID. Free starter grants, gameplay rewards, permanent season rewards, and Tebex product benefits grant that same ID. Physical tools open menus; copied item metadata never conveys ownership.

## Catalog locations

| Type | Bundled location under `src/main/resources/Server/EterniaMod/` | Data-directory override | Ownership ID |
|---|---|---|---|
| Player/guild house | `Buildings/` | `Buildings/` | `eternia:house/<id>` |
| Prop/addition | `Props/` | `Props/` | `eternia:prop/<id>` |
| Palette | `Palettes/` | `Palettes/` | `eternia:palette/<id>` |
| Path style | `PathStyles/` | `PathStyles/` | `eternia:path/<id>` |
| Title/outfit/wearable/pet/mount | `Collections/` | `Collections/` | Definition's full namespaced ID |
| Full-skin fields | `Appearances/` | `Appearances/` | Referenced by outfit `assetId` |
| Permanent pass | `Seasons/` | `Seasons/` | Definition's full namespaced ID |
| Tebex product revision | `Products/` | `Products/` | Actual package ID + immutable revision |

Bundled catalogs use a `catalog.index` listing JSON filenames, one per line. Data-directory catalogs discover JSON files; restart to load changes. Add native prefab files under `Server/Prefabs/` in the asset pack. Stable ID changes are new content, not a rename of existing ownership. Already registered season and product definitions are immutable; introduce a new pass ID or product revision instead of changing rewards under existing receipts.

## Buildings, props and additions

### Set up content in game

As a WorldEditor, run `/eternia prefab browse`, preview a prefab and choose **Set up for website**. The entry page sets the type (decoration, addition or house), ID, name and details, whether it is sold in the Crown Store, its Crown price and store category, and an optional season pass reward (pass, free or paid track, level). **Save and upload** then:

- creates or relabels the game definition in plugin-data `Buildings/` or `Props/` and registers it at once, as **Create prop** and **Create building** do;
- when sold, registers the next `eternia:store/<id>` Crown item revision and writes it to plugin-data `CrownShop/<id>.json`. An unchanged listing keeps its revision. Crown items have no retire path yet, so turning **Sold** off leaves an existing listing in the store;
- when a draft pass is chosen, puts the reward on that draft (moving it if it was already there). A live pass only accepts a reward it already has;
- saves the entry under plugin-data `Authoring/Content/` and uploads it, with the exact prefab JSON (10 MB limit), to the website.

`/eternia season` opens the season pass designer. It lists live passes from the game and drafts from plugin-data `Authoring/Seasons/`. Drafts change freely: ID, name, description, start and end dates, number of levels, XP per level and rewards (content ID, track, level, amount; props, pets and move credits are counted, everything else unlocks). **Save and upload** sends the draft to the website. **Go live** asks for a second press, then registers the pass with the daily and weekly quest pool of Foundations and writes it to plugin-data `Seasons/<id>.json` so it loads after a restart. A live pass keeps its name, levels and rewards fixed; its description and dates can still change and are uploaded. Dates are shown on the website only; the game does not open or close passes by date.

Uploads use `ETERNIA_WEBSITE_URL` and `ETERNIA_BRIDGE_TOKEN`, sent as `Authorization: Bearer` to the website's `POST /api/server/content` and `POST /api/server/seasons`. The website must have the same value in `GAME_BRIDGE_TOKEN`. Without both, entries are saved in game and the page says the website is not connected. Every action checks WorldEditor permission again on the server; the uploaded author is the admin's verified game profile.

### Website workshop

Use the website **Content workshop** as an admin: import a definition and native prefab JSON, inspect the 3D preview, render a screenshot and transparent icon, and download the native catalog archive. This workflow supports only admins; there are no community submissions. The export preserves the prefab source, emits game definitions/indexes and a hash manifest, and does not publish or grant anything. [Exact authoring/export fields](../../website/README.md).

A native house definition looks like the bundled [starter house](../../src/main/resources/Server/EterniaMod/Buildings/hub_house.json) or [guild hall](../../src/main/resources/Server/EterniaMod/Buildings/guild_hall.json):

```json
{
  "id": "garden_cottage",
  "displayName": "Garden cottage",
  "prefabPath": "GardenCottage.prefab.json",
  "plotAnchorOffset": [0, 0, 0],
  "spawnLocalPos": [0, 1, 0],
  "housingKind": "personal"
}
```

Buildings no longer need a Building Records Shelf. Use the Architect's Ledger or main menu to manage them. Ensure `spawnLocalPos` has a solid floor plus two clear cells. Set `housingKind: "guild"` for guild-only houses. The whole house/addition footprint must stay five blocks inside the plot and five blocks from a road. A 24Ã—24 plot leaves a 14Ã—14 structure envelope. Guild halls use the same engine with a distinct catalog and larger assets.

Props use the same `id`, `displayName`, `prefabPath`, and optional anchor/rotation fields; they omit house-specific management/spawn fields. Set `category: "addition"` for a porch, room, or basement; an addition must touch its placed house and obey structure setbacks. Furniture, trees, fountains, paintings, and statues are ordinary props. Package a placed prop to preserve its exact saved native state; the original quantity remains reserved by that instance.

Validate native component shapes before shipping an entity prefab. The viewer can render a model even when the server rejects an unsupported component or unsafe custody shape. Never ship tradable loot inside a reusable house template. Use the native smoke test and [recovery contract](../native-housing-recovery.md) to confirm supported entity/container handling.

## Palettes and paths

[Cut stone](../../src/main/resources/Server/EterniaMod/Palettes/cut_stone.json) maps authored block IDs for a specific house; [cobblestone](../../src/main/resources/Server/EterniaMod/PathStyles/cobblestone.json) declares one native block type. Add another JSON/index entry and grant its unlock. Palette edits affect verified authored house surfaces, and confirmed paths use clear, level ground from the player's chosen start toward a registered road. Paths remain inside the lot and stop before protected columns. Changes have snapshots and recovery journals.

## Titles, character cosmetics and pets

Collection definitions contain `id`, `name`, `kind`, `assetId`, and `slot` where relevant. Copy the bundled examples as the schema reference.

- `PREFIX_TITLE` / `SUFFIX_TITLE`: `assetId` is the display text. Each selected half is composed into the native nameplate and compatible chat formatting.
- `OUTFIT`: `assetId` is a safe file stem in `Appearances/`. Its JSON overrides native skin fields; omitted fields preserve the player's original skin, and empty strings clear optional pieces.
- `WEARABLE`: `slot` is a native snake_case field such as `overtop`, `pants`, `shoes`, `cape`, or `head_accessory`; `assetId` is the native `Part.Texture[.Variant]` value. Use validated assets the player can legitimately use.
- `PET`: `assetId` is a native model asset. Quantity grants materialize individual owned pets. One may follow, with additional pets assigned to properties. The renderer validates a small model envelope, removes gameplay collision, and does not give pets combat, inventory, drops, or lighting. Rootling is the initial hovering example. A pet whose model asset has a `Walk` animation set walks on the ground instead, and its clips are chosen by `PetBehavior`: `Idle`, `Walk`, `Run` and `Sleep` loop on the movement slot, and the emotes `Happy`, `LookAround`, `Wiggle`, `Surprised` and `Wake` play once on the action slot. A walking model needs all nine. Corn Buddy (`Eternia_Pet_Corn_Buddy`, generated by `scripts/generate-corn-buddy.py`) is the walking example.

The runtime restores the original skin when a selection is removed or loses ownership. Pets keep durable identity in the domain and are recreated as presentation entities; they are excluded from house snapshots. Cross-owner guild assignments are reconciled on membership loss.

## Rewards and acquisition

Use `OwnershipService.GrantRequest(receipt, owner, contentId, kind, quantity, validUntil)` through an authorized adapter. `UNLOCK` is reusable content, `CAPABILITY` grants a size/service feature, and `QUANTITY` is a count of props, pets, or move credits. Receipt IDs are stable per source and recipient. A repeated event must reuse its receipt; generate a new one only for a distinct earned reward.

The [Foundations pass](../../src/main/resources/Server/EterniaMod/Seasons/foundations.json) demonstrates thresholds, free/paid reward rows, count quests, and distinct-resource quests. Another reward row can grant any registered content type. Paid pass access uses `SeasonService.paidEntitlementId(passId)`; for `eternia:foundations` this is `eternia:season_paid/eternia/foundations`. Grant it as an unlock or capability from the actual product. Rewards remain claimable permanently. Register the appropriate paid-pass benefit in a real immutable product revision.

Each quest also has a `coins` amount. The default is 100 Coins. Foundations gives 80 for a daily quest and 300 for a weekly quest. Local practice quests give 100. Set an explicit nonnegative whole number when authoring a new pass; zero disables the Coin reward. The quest journal displays XP and Coins separately, and successful completion pays Coins automatically into the regular player shop wallet. Paid pass ownership is not required. This is a completion reward, in addition to any configured Coins for ordinary activities. It never grants Crowns.

Quest completions show a native success toast with a banner icon, the journal quest title, XP, Coins, and the native short discovery sound. The notification is queued in the same transaction as the reward, waits while the player is offline, and is sent by the activity worker, at most one per player per second. Previously completed quests receiving their new Coins use a Coin icon and do not announce XP again. Activity retries cannot create another notification for the same player, pass, and quest. Toast delivery is best effort after the packet is sent; a disconnect at that moment can hide a toast, and a server crash before acknowledgement can replay it. Rewards remain protected by their receipts.

Quest progress, completion XP, and the Coin credit commit together. A receipt scoped to the player, pass, and quest prevents repeat payouts after retries, switching passes, or reconnecting. Existing stored definitions without a `coins` field are upgraded to 100 without changing their requirements or progress. Opening a quest journal pays any previously completed quests that have not received their Coin reward. Registered amounts remain immutable; publish a new pass ID to change established rewards.

Menus show available Coins and Crowns in the upper right, including the main menu, ledger choice pages, trade desk, and Crown Store. Balances refresh when a page opens or rebuilds after an action. Coins reserved in a trade are excluded from the available balance. Large header balances use compact notation, while shop prices retain their exact whole number amounts. Currency artwork is generated by `scripts/generate-citadel-icons.py`: `coins.png` is the round Coin stack, and `crown.png` is the Crown symbol.

For in-world housing tokens, place the shipped `Eternia_Discovery_Cache` and add its exact position, stable cache ID and reward to `discoveries.json`. The [discovery authoring guide](discoveries.md) includes the complete schema and native test flow. Collection grants the entitlement directly with a durable per-player receipt. The gameplay adapter also provides qualified enemy kill, natural mining, and native crop harvest sources for pass XP and configured coins; add a new activity adapter for future minigames or world-specific quests without changing existing pass progress.

For local testing, use the logged `/e admin grant` command from the [setup guide](README.md). For paid acquisition, define a product with `packageId`, `revision`, `name`, `subscription`, and `benefits` (`contentId`, `kind`, `quantity`, `expiresWithSubscription`). Use leased benefits for supporter access and permanent benefits for monthly gifts. Keep actual product IDs and checkout mappings in operator configuration, and test provider callbacks as described in [commerce setup](commerce-and-activities.md).

## Shared visual theme

### Item icons and previews

Store cards, Collection entries, and both season reward tracks use the same artwork lookup. Put a transparent, square `icon.png` under `Common/UI/Custom/EterniaMod/Catalog/<content ID without eternia:>/`. Add a `screenshot.png` at a 1280 by 800 ratio for the larger store view. For example, the jacket lives in `Catalog/wearable/citadel_jacket/`. Nested IDs such as `season_paid/eternia/foundations` are supported. Existing additions can reuse `Catalog/prop/<id>/` images. Ship these files in the mod asset pack, then rebuild the JAR.

When an item has no preview, `ContentImages` chooses its category symbol. Wardrobe, passes, guild charters, titles, pets, buildings, plots, furnishings, additions, palettes, paths, moving supplies, travel, and Crowns have distinct geometric symbols. Edit `scripts/generate-citadel-icons.py` and run it with Python plus Pillow to regenerate the SVG sources and PNG icons together. New content in an existing category needs no menu changes. A new category needs a mapping in `ContentImages` and an icon from the same generator.

To refresh the bundled Citadel Jacket, Traveler outfit, and Rootling previews, start the local fixture website and run `node website/scripts/render-collection-catalog.js` from the repository root. Set `TEST_BASE_URL` if the website is not on port 3851, and `HYTALE_ASSETS_SRC` if the installed Hytale assets are elsewhere. The script reads the collection definitions, native models, cosmetic textures, and color gradients. It saves 128 pixel icons and larger previews without publishing the raw game assets. Additional cosmetic slots must be mapped in the script before rendering. Buildings and props use the Content workshop renderer described above.

Catalog furniture and houses built as prefabs get their art from a render: `tools/prefabs/catalog-previews.ps1 prop <ids...>` (or `house`) renders each item from its catalog JSON's `prefabPath` and writes `icon.png` and `screenshot.png` into `Catalog/prop/<id>/` or `Catalog/house/<id>/`. Build these prefabs with `tools/prefabs/catalog-kit.mjs`: add table settings and other small decorations with `catalogProp`, and call `checkCatalog` before saving, which rejects containers, benches, child prefabs and entity components the server does not accept. `docs/builds/CATALOG_BRIEF.md` has the frames, sizes and prices.

Season rewards automatically use their content ID to choose artwork. Every reward index keeps its own image, quantity, and claim action on the free or paid track. Native item IDs use the game's item icon.

### Citadel styling

Citadel uses deep green panels, warm ivory text, brass actions, sage focus, thin borders, and geometric corner details. UI chrome contains no detailed textures. The actual in-game prefab models retain their native appearance in the viewer. Web headings use Georgia; native pages use Hytale's supported font.

The shared colors live in [theme tokens](../planning/theme-tokens.json). Run `npm run sync:theme` in `website/` after changing them, and update [Citadel.ui](../../src/main/resources/Common/UI/Custom/EterniaMod/Theme/Citadel.ui) to match. Add new native menus using the existing button/input/row styles instead of drawing a separate theme. Keep red/green validation understandable through accompanying text.

Public portals and guild waygates use the shared `Blocks/Eternia/Waygate.blockymodel` and `Eternia_Waygate` particle system, generated by `scripts/generate-waygate-effects.py` with Python and Pillow. The generator reads the installed Hytale Assets.zip; set `HYTALE_ASSETS_SRC` to use another location. The fixed, fullbright model holds the arch, gold cut gems and ground seal, so they have no particle expiry. The translucent faceted center uses one particle with constant opacity, a one hour lifetime and MaxConcurrentParticles set to 1. Never stack center particles: alpha accumulation causes brightness pulses. Model quads did not blend translucently in the tested client, so the center must remain a particle. The compass also permits only one particle; rising lights remain animated. The shared system attaches at Y 2.0. The palette and diamond motif match the plot border. Stop the local server before rebuilding, and inspect both portal sides after restarting.

Collection categories use `CollectionCardRow.ui`, with four card slots per row and 144 pixel previews. `ContentImages` supplies the same catalog art as store and reward pages. Keep names and availability separate from descriptions. The top collection tabs own navigation; footer buttons should provide actions, not repeat the tabs. Decoration quantities still come from `CollectionInventory`, preserving available, packed and placed counts. Pet cards retain their individual assignment and source grant.

### Eternia Guild Hall

`Buildings/founders_hall.json` and `Prefabs/FoundersHall.prefab.json` contain the adapted Stormwind hall. Its coordinates are centered for Eternia plots, its entrance is defined for arrivals, and it no longer includes a Building Records Shelf. Native decorative doors and the sign retain their geometry; fresh instance UUIDs and custody links are assigned by the normal placement adapter. The prefab contains no Aetherhaven block IDs, POI components, seeded inventory, or custom smoke emitters. Unsupported loose weapon/model displays were omitted. Catalog icon and screenshot images were rendered through the website's native prefab viewer.

Keep this distinct from the old `guild_hall`: changing the dimensions of an existing catalog ID would invalidate packed snapshots. Ship future incompatible house replacements under a new ID, retain the old definition/prefab for restoration, and add an explicit migration if existing placed examples need updating.


A building definition can set `"retired": true` to remove an old style from Housing inventory without deleting the assets needed to pack existing buildings or restore saved plots. The original `guild_hall` is retired; `founders_hall` is the current Eternia Guild Hall. Keep those IDs unchanged so saved ownership and building records remain valid.


### Daily and weekly quest pools

Foundations has 30 daily quests and 15 weekly quests. See [the full quest catalog](quest-catalog.md). Each quest sets `cadence` (`DAILY`, `WEEKLY`, or legacy `ONCE`), `name`, and `goal`. There is one active daily and one active weekly per activity type. Sort each pool by stable quest ID; the UTC date selects a slot with a different activity offset. Choices are deterministic and shared by everyone, so reconnecting cannot reroll them. Adding more quests of an activity expands that pool. Quest names and goal wording can be revised without resetting progress. Registered requirements, targets, rotation and rewards remain immutable; use a new pass ID for changes to those fields.

Daily progress resets at 00:00 UTC. Weekly progress resets Monday at 00:00 UTC. These use the server's real clock, never the world clock. The activity outbox records when gameplay happened, so delayed work stays in its original window. Progress, Coin receipts and completion notices are scoped to player, pass, quest and window. Unfinished progress does not carry into the next window. Pass XP, reward claims and ownership are permanent. Opening or refreshing the journal shows the current choices.

The old shipped three objective Foundations prototype has a narrowly checked upgrade to this pool. Existing pass XP, levels, paid access, claimed rewards and account balances are preserved. Other existing pass definitions are not rewritten.

Targets accept exact native IDs or selectors: `npc:Skeleton` includes skeleton variants, `npc:Skeleton_Archer` includes patrol and wander variants, `ore:iron` includes iron in every host rock, and `crop:wheat` includes native wheat and the playground wheat. Broad selectors are `group:undead`, `group:enemies`, `group:trorks`, `group:ores`, and `group:crops`. Enemies means undead, Trorks, goblins and Outlanders, not animals. Distinct crops normalize native growth state IDs so one crop cannot count as multiple kinds.

Activity credit requires Adventure mode in an adventure world or an explicitly enabled activity world. Recognized enemies, ores and crops have default XP of 100, 40 and 30 respectively; exact `activity-xp.json` mappings override those amounts. In local mode the managed playground village also allows enemy kill credit. Native crop templates and sickle selectors use `EterniaHarvestCrop`, which calls normal harvesting once and records a verified block transition with the native generation and saved harvest time. This also handles mature crops whose farming component was removed, or replanted crops whose generation starts over. Unsupported custom crop templates can opt into this same interaction. Successful harvesting of crops with no regrowth generation is not credited by this adapter.


### Collection categories, custom clothing and mounts

Collection navigation separates Houses, Decorations, Materials, Titles, Wardrobe, Pets, Mounts and Upgrades. `CollectionCategories` classifies housing and upgrade entitlements by content ID; appearance and companions come from their registered kind. `service/` entries such as the free mailbox remain valid entitlements but are hidden because they are not collectible objects. Add actual decorative mailboxes as `prop/` content if desired.

The Prowl Shirt uses a custom chest item for its visual model. `CustomWearables` projects that item into outgoing equipment updates without changing real inventory or armor stats. It hides the underlying top only while worn and restores the actual equipment when removed. Other armor slots and held items retain their original appearance. This currently supports the Prowl Shirt explicitly; extend the projection mapping when adding another custom clothing model. Native character creator wearables continue to use `SkinComposer`.

Run `python scripts/extract-prowl-shirt.py` against the installed Hytale assets to regenerate the shirt from Prowl's cloth meshes and texture. The source Prowl model is unchanged. After editing the model, run `website/scripts/render-collection-catalog.js` with `COLLECTION_IDS=eternia:wearable/prowl_shirt` to regenerate previews and the equipment icon. The model must be under `Common/Items/` and its item icon under `Common/Icons/Items/` for native item validation.

Foundations adds the shirt at paid level 1 without changing existing reward receipts, claimed rewards or XP. It displays first, while the existing Cacti remains available. The migration only accepts this exact additive reward change; arbitrary changes to established rewards remain rejected.

For another horse-shaped mount, add a `MOUNT` collection definition, a native model `assetId`, its catalog entry and artwork, then grant an `UNLOCK`. Horse is a free starter unlock. Summoning requires clear loaded space, mounts the player immediately, and uses native riding controls. Dismount, disconnect and world departure remove the temporary creature. Mount entities are invulnerable and never saved as livestock. Current seat height is 1.6 blocks and clearance is sized for a horse; extend the mount schema and validation before adding differently shaped mounts.

The retired `Eternia_Management_Block` asset remains available for old saved houses and snapshots. New shipped houses omit it, and both the in game prefab importer and website export accept houses without it. Existing shelves are not automatically deleted from player buildings.

The portal compass is a model quad with a looping `Waygate_Idle.blockyanim`, rather than a particle that expires each turn. Its 720 animation frames at 60 frames per second describe a twelve second rotation; public and guild gates share it. The arch, seal, translucent center and rising lights retain their existing appearance. Regenerate with `scripts/generate-waygate-effects.py`.

Block animation tracks must include all five channels: `position`, `orientation`, `shapeStretch`, `shapeVisible`, and `shapeUvOffset`. Use empty arrays for unused channels. Omitting them causes the client to reject the animation. Track keys match model node names.
