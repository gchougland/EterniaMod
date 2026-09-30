# Add content to Eternia

Content is separated from entitlement sources. A house, prop, skin, title, or pet has a stable ID. Free starter grants, gameplay rewards, permanent season rewards, and Tebex product benefits grant that same ID. Physical tools open menus; copied item metadata never conveys ownership.

## Catalog locations

| Type | Bundled location under `src/main/resources/Server/EterniaMod/` | Data-directory override | Ownership ID |
|---|---|---|---|
| Player/guild house | `Buildings/` | `Buildings/` | `eternia:house/<id>` |
| Prop/addition | `Props/` | `Props/` | `eternia:prop/<id>` |
| Palette | `Palettes/` | `Palettes/` | `eternia:palette/<id>` |
| Path style | `PathStyles/` | `PathStyles/` | `eternia:path/<id>` |
| Title/outfit/wearable/pet | `Collections/` | `Collections/` | Definition's full namespaced ID |
| Full-skin fields | `Appearances/` | `Appearances/` | Referenced by outfit `assetId` |
| Permanent pass | `Seasons/` | `Seasons/` | Definition's full namespaced ID |
| Tebex product revision | `Products/` | `Products/` | Actual package ID + immutable revision |

Bundled catalogs use a `catalog.index` listing JSON filenames, one per line. Data-directory catalogs discover JSON files; restart to load changes. Add native prefab files under `Server/Prefabs/` in the asset pack. Stable ID changes are new content, not a rename of existing ownership. Already registered season and product definitions are immutable; introduce a new pass ID or product revision instead of changing rewards under existing receipts.

## Buildings, props and additions

Use the website **Content workshop** as an admin: import a definition and native prefab JSON, inspect the 3D preview, render a screenshot and transparent icon, and download the native catalog archive. This workflow supports only admins; there are no community submissions. The export preserves the prefab source, emits game definitions/indexes and a hash manifest, and does not publish or grant anything. [Exact authoring/export fields](../../website/README.md).

A native house definition looks like the bundled [starter house](../../src/main/resources/Server/EterniaMod/Buildings/hub_house.json) or [guild hall](../../src/main/resources/Server/EterniaMod/Buildings/guild_hall.json):

```json
{
  "id": "garden_cottage",
  "displayName": "Garden cottage",
  "prefabPath": "GardenCottage.prefab.json",
  "plotAnchorOffset": [0, 0, 0],
  "managementBlockLocalPos": [1, 1, 0],
  "spawnLocalPos": [0, 1, 0],
  "housingKind": "personal"
}
```

Author the management block at the declared local position, and ensure `spawnLocalPos` has a solid floor plus two clear cells. Set `housingKind: "guild"` for guild-only houses. The whole house/addition footprint must stay five blocks inside the plot and five blocks from a road. A 24×24 plot leaves a 14×14 structure envelope. Guild halls use the same engine with a distinct catalog and larger assets.

Props use the same `id`, `displayName`, `prefabPath`, and optional anchor/rotation fields; they omit house-specific management/spawn fields. Set `category: "addition"` for a porch, room, or basement; an addition must touch its placed house and obey structure setbacks. Furniture, trees, fountains, paintings, and statues are ordinary props. Package a placed prop to preserve its exact saved native state; the original quantity remains reserved by that instance.

Validate native component shapes before shipping an entity prefab. The viewer can render a model even when the server rejects an unsupported component or unsafe custody shape. Never ship tradable loot inside a reusable house template. Use the native smoke test and [recovery contract](../native-housing-recovery.md) to confirm supported entity/container handling.

## Palettes and paths

[Cut stone](../../src/main/resources/Server/EterniaMod/Palettes/cut_stone.json) maps authored block IDs for a specific house; [cobblestone](../../src/main/resources/Server/EterniaMod/PathStyles/cobblestone.json) declares one native block type. Add another JSON/index entry and grant its unlock. Palette edits affect verified authored house surfaces, and confirmed paths use clear, level ground from the player's chosen start toward a registered road. Paths remain inside the lot and stop before protected columns. Changes have snapshots and recovery journals.

## Titles, character cosmetics and pets

Collection definitions contain `id`, `name`, `kind`, `assetId`, and `slot` where relevant. Copy the bundled examples as the schema reference.

- `PREFIX_TITLE` / `SUFFIX_TITLE`: `assetId` is the display text. Each selected half is composed into the native nameplate and compatible chat formatting.
- `OUTFIT`: `assetId` is a safe file stem in `Appearances/`. Its JSON overrides native skin fields; omitted fields preserve the player's original skin, and empty strings clear optional pieces.
- `WEARABLE`: `slot` is a native snake_case field such as `overtop`, `pants`, `shoes`, `cape`, or `head_accessory`; `assetId` is the native `Part.Texture[.Variant]` value. Use validated assets the player can legitimately use.
- `PET`: `assetId` is a native model asset. Quantity grants materialize individual owned pets. One may follow, with additional pets assigned to properties. The renderer validates a small model envelope, removes gameplay collision, and does not give pets combat, inventory, drops, or lighting. Rootling is the initial hovering example.

The runtime restores the original skin when a selection is removed or loses ownership. Pets keep durable identity in the domain and are recreated as presentation entities; they are excluded from house snapshots. Cross-owner guild assignments are reconciled on membership loss.

## Rewards and acquisition

Use `OwnershipService.GrantRequest(receipt, owner, contentId, kind, quantity, validUntil)` through an authorized adapter. `UNLOCK` is reusable content, `CAPABILITY` grants a size/service feature, and `QUANTITY` is a count of props, pets, or move credits. Receipt IDs are stable per source and recipient. A repeated event must reuse its receipt; generate a new one only for a distinct earned reward.

The [Foundations pass](../../src/main/resources/Server/EterniaMod/Seasons/foundations.json) demonstrates thresholds, free/paid reward rows, count quests, and distinct-resource quests. Another reward row can grant any registered content type. Paid pass access uses `SeasonService.paidEntitlementId(passId)`; for `eternia:foundations` this is `eternia:season_paid/eternia/foundations`. Grant it as an unlock or capability from the actual product. Rewards remain claimable permanently. Register the appropriate paid-pass benefit in a real immutable product revision.

Each quest also has a `coins` amount. The default is 100 Coins, including the Foundations and local practice quests. Set an explicit nonnegative whole number when authoring a new pass; zero disables the Coin reward. The quest journal displays XP and Coins separately, and successful completion pays Coins automatically into the regular player shop wallet. Paid pass ownership is not required. This is a completion reward, in addition to any configured Coins for ordinary activities. It never grants Crowns.

Quest completions show a native success toast with a banner icon, the journal quest title, XP, and Coins. The notification is queued in the same transaction as the reward, waits while the player is offline, and is sent by the activity worker, at most one per player per second. Previously completed quests receiving their new Coins use a Coin icon and do not announce XP again. Activity retries cannot create another notification for the same player, pass, and quest. Toast delivery is best effort after the packet is sent; a disconnect at that moment can hide a toast, and a server crash before acknowledgement can replay it. Rewards remain protected by their receipts.

Quest progress, completion XP, and the Coin credit commit together. A receipt scoped to the player, pass, and quest prevents repeat payouts after retries, switching passes, or reconnecting. Existing stored definitions without a `coins` field are upgraded to 100 without changing their requirements or progress. Opening a quest journal pays any previously completed quests that have not received their Coin reward. Registered amounts remain immutable; publish a new pass ID to change established rewards.

Menus show available Coins and Crowns in the upper right, including the main menu, ledger choice pages, trade desk, and Crown Store. Balances refresh when a page opens or rebuilds after an action. Coins reserved in a trade are excluded from the available balance. Large header balances use compact notation, while shop prices retain their exact whole number amounts. Currency artwork is generated by `scripts/generate-citadel-icons.py`: `coins.png` is the round Coin stack, and `crown.png` is the Crown symbol.

For in-world housing tokens, place the shipped `Eternia_Discovery_Cache` and add its exact position, stable cache ID and reward to `discoveries.json`. The [discovery authoring guide](discoveries.md) includes the complete schema and native test flow. Collection grants the entitlement directly with a durable per-player receipt. The gameplay adapter also provides qualified kill, natural mining, and opted-in crop sources for pass XP and configured coins; add a new activity adapter for future minigames or world-specific quests without changing existing pass progress.

For local testing, use the logged `/e admin grant` command from the [setup guide](README.md). For paid acquisition, define a product with `packageId`, `revision`, `name`, `subscription`, and `benefits` (`contentId`, `kind`, `quantity`, `expiresWithSubscription`). Use leased benefits for supporter access and permanent benefits for monthly gifts. Keep actual product IDs and checkout mappings in operator configuration, and test provider callbacks as described in [commerce setup](commerce-and-activities.md).

## Shared visual theme

### Item icons and previews

Store cards, Collection entries, and both season reward tracks use the same artwork lookup. Put a transparent, square `icon.png` under `Common/UI/Custom/EterniaMod/Catalog/<content ID without eternia:>/`. Add a `screenshot.png` at a 1280 by 800 ratio for the larger store view. For example, the jacket lives in `Catalog/wearable/citadel_jacket/`. Nested IDs such as `season_paid/eternia/foundations` are supported. Existing additions can reuse `Catalog/prop/<id>/` images. Ship these files in the mod asset pack, then rebuild the JAR.

When an item has no preview, `ContentImages` chooses its category symbol. Wardrobe, passes, guild charters, titles, pets, buildings, plots, furnishings, additions, palettes, paths, moving supplies, travel, and Crowns have distinct geometric symbols. Edit `scripts/generate-citadel-icons.py` and run it with Python plus Pillow to regenerate the SVG sources and PNG icons together. New content in an existing category needs no menu changes. A new category needs a mapping in `ContentImages` and an icon from the same generator.

To refresh the bundled Citadel Jacket, Traveler outfit, and Rootling previews, start the local fixture website and run `node website/scripts/render-collection-catalog.js` from the repository root. Set `TEST_BASE_URL` if the website is not on port 3851, and `HYTALE_ASSETS_SRC` if the installed Hytale assets are elsewhere. The script reads the collection definitions, native models, cosmetic textures, and color gradients. It saves 128 pixel icons and larger previews without publishing the raw game assets. Additional cosmetic slots must be mapped in the script before rendering. Buildings and props use the Content workshop renderer described above.

Season rewards automatically use their content ID to choose artwork. Every reward index keeps its own image, quantity, and claim action on the free or paid track. Native item IDs use the game's item icon.

### Citadel styling

Citadel uses deep green panels, warm ivory text, brass actions, sage focus, thin borders, and geometric corner details. UI chrome contains no detailed textures. The actual in-game prefab models retain their native appearance in the viewer. Web headings use Georgia; native pages use Hytale's supported font.

The shared colors live in [theme tokens](../planning/theme-tokens.json). Run `npm run sync:theme` in `website/` after changing them, and update [Citadel.ui](../../src/main/resources/Common/UI/Custom/EterniaMod/Theme/Citadel.ui) to match. Add new native menus using the existing button/input/row styles instead of drawing a separate theme. Keep red/green validation understandable through accompanying text.

### Eternia Guild Hall

`Buildings/founders_hall.json` and `Prefabs/FoundersHall.prefab.json` contain the adapted Stormwind hall. Its coordinates are centered for Eternia plots, its entrance is defined for arrivals, and its management block uses `Eternia_Management_Block`. Native decorative doors and the sign retain their geometry; fresh instance UUIDs and custody links are assigned by the normal placement adapter. The prefab contains no Aetherhaven block IDs, POI components, seeded inventory, or custom smoke emitters. Unsupported loose weapon/model displays were omitted. Catalog icon and screenshot images were rendered through the website's native prefab viewer.

Keep this distinct from the old `guild_hall`: changing the dimensions of an existing catalog ID would invalidate packed snapshots. Ship future incompatible house replacements under a new ID, retain the old definition/prefab for restoration, and add an explicit migration if existing placed examples need updating.
