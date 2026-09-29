# Adding Eternia content

This is the **target authoring contract for the planned system**. The new catalogs, schemas, handlers and validation command described here are not implemented yet. Examples are proposed JSON, not files to drop into today's loaders. During implementation, update this guide against the shipped schema and a tested sample of every content kind.

## What works today

Eternia currently has building and prop definition classes, token metadata, a prefab browser/definition factory, and local JSON overrides. Bundled catalog loading hardcodes a small file list, so adding a new bundled JSON file alone is insufficient. See [the codebase audit](codebase-audit.md#extensibility-already-present-and-its-limits) and the actual [building example](../../src/main/resources/Server/EterniaMod/Buildings/hub_house.json) and [prop example](../../src/main/resources/Server/EterniaMod/Props/aqua_lamp.json).

The first catalog milestone replaces that discovery limitation. Preserve existing IDs through aliases when moving to the target catalog. Continue using native Hytale models, textures, prefabs and UI assets where appropriate; the Eternia content registry adds gameplay metadata and ownership rules.

All GUI and website presentation follows the [Citadel theme](theme.md) and [shared design tokens](theme-tokens.json). Author flat geometric/vector-style interface art, consistent silhouettes and icons; do not add detailed textures or simulated parchment/wood/stone backgrounds. Keep the actual 3D game-content assets separate from their flat UI presentation. Iterate through the [local workflow](local-development.md), then test native exported UI assets in the actual Hytale client.

## Target folder layout and discovery

```text
src/main/resources/
  Server/EterniaMod/Content/
    pack.json
    PlotShapes/
    Houses/
    Props/
    Additions/
    Palettes/
    Improvements/
    Pets/
    Cosmetics/
    Titles/
    Rewards/
    Recipes/
    DiscoveryTokens/
    Activities/
    Quests/
    Seasons/
    NPCs/
    Dialogues/
    Destinations/
    Offers/
    SupporterBenefits/
  Server/Prefabs/EterniaMod/
  Server/Models/EterniaMod/
  Server/NPC/Roles/EterniaMod/
  Server/Item/Items/EterniaMod/
  Common/EterniaMod/
    Models/
    Textures/
    Icons/
  Server/Languages/en-US/
```

These directories are proposed. Native asset folder requirements and localization filename conventions must be confirmed against the pinned Hytale build; do not teach a new folder layout as a working engine API before that check.

Use a small explicit pack manifest or a build-generated equivalent index. Runtime discovery must not depend on listing a JAR as a directory. For the first implementation, the explicit manifest is the source of truth:

```json
{
  "schemaVersion": 1,
  "packId": "eternia:core",
  "contentVersion": "1.0.0",
  "definitions": [
    "Houses/cottage.json",
    "Props/oak_chair.json",
    "Additions/cottage_porch.json",
    "Rewards/starter_housing.json"
  ]
}
```

Paths resolve inside the pack; reject traversal/absolute definition paths. Author tools update the manifest. Deployed server data-directory packs can override an ID only with an explicit pack priority/version policy; duplicate IDs within one priority are validation errors, not filesystem-order winners.

Every definition has `schemaVersion`, `kind`, immutable `id`, integer `revision`, and localized display/description keys where shown to players. Content IDs are lowercase namespaced strings such as `eternia:house/cottage`. Native Hytale asset IDs are separate fields resolved by the adapter, not automatically the same as an Eternia content ID.

## Standard author workflow

1. Duplicate a tested sample of the same kind and choose a new immutable ID. Do not duplicate its instance IDs or receipt IDs; those are runtime data.
2. Author/import the native prefab/model/icon. Record dimensions, origin, rotation, structural bounds and every cell that can be cleared or changed. Check the art and animation dependencies.
3. Fill in type-specific metadata below. Add localized name, description, icon and tags. Tags help filtering; they do not grant behavior or ownership.
4. Add the definition to `pack.json`. Link it from a reward, offer, recipe, quest or service entry as appropriate. A content definition by itself does not make something purchasable or free.
5. Run the planned content validator, review its fit/compatibility report, then load a staging server with one test entitlement. Inspect placement/equip/UI and package/restore/reconnect behavior.
6. Add a changelog entry and release the content pack with its schema and content version. Preserve old revisions needed by placed instances and permanent passes.

The implementation should provide a Gradle task named `validateEterniaContent` and an in-game admin command `/eternia content validate`. **Those names are proposed tasks/commands**, not existing instructions to execute now. The command reports errors with pack, ID, file and field location. Existing prefab browsing remains an authoring entry point; the new tool should export a valid definition and manifest entry rather than requiring hand registration in Java.

## Houses and guild houses

Build a prefab in a disposable authoring world. Mark the local origin and front direction, then tag safe spawn, management block, entrance, shop visit point, service points, palette regions and addition sockets. Exclude test NPCs, inventories of valuable test items and uncontrolled entity behavior.

Example proposed definition:

```json
{
  "schemaVersion": 1,
  "kind": "house",
  "id": "eternia:house/cottage",
  "revision": 1,
  "displayNameKey": "eternia.house.cottage.name",
  "descriptionKey": "eternia.house.cottage.description",
  "iconAsset": "EterniaMod/Icons/Cottage.png",
  "ownerScopes": ["PLAYER"],
  "prefab": "EterniaMod/Houses/Cottage_v1.prefab.json",
  "origin": [0, 0, 0],
  "front": "POSITIVE_Z",
  "structureBounds": {"min": [0, 0, 0], "size": [12, 8, 12]},
  "markers": {
    "homeSpawn": [6, 1, 5],
    "management": [1, 1, 1],
    "entrance": [6, 0, 12],
    "shopVisit": [8, 0, 12]
  },
  "materialSlots": ["walls", "roof", "floor", "trim"],
  "regionMap": "EterniaMod/Houses/Cottage_v1.regions.json",
  "sockets": [
    {"id": "front", "type": "cottage_porch_v1", "position": [4, 0, 12], "yaw": 0},
    {"id": "below", "type": "cottage_basement_v1", "position": [2, 0, 2], "yaw": 0}
  ],
  "tags": ["starter", "small"]
}
```

All sample asset paths above are illustrative; author them before enabling the definition. The structure bounds use exclusive upper coordinates and include roofs/foundations. The complete mutation mask also covers prefab air; calculate and validate it from the prefab rather than trusting a smaller hand-entered bound. Region maps assign authored cells to material slots; their format is part of the catalog implementation.

The free 24 × 24 plot leaves 14 × 14 for the full structure after setbacks. A 12 × 12 shell leaves room for a two-block porch along one direction; bigger additions may require another placement or larger plot. Test every quarter-turn. The validator should report minimum compatible plot shapes based on the full addition assembly, not label every small-looking house “free compatible.”

For a guild house, create another house definition with `ownerScopes: ["GUILD"]`, larger compatible bounds, and guild-specific socket types. Reuse the house handler. Add a luxury mailbox or teleporter appearance by referencing existing service capabilities; do not implement another mail/travel service for guild art.

To release a structural prefab update, create a new revision and migration/preview path. Existing houses remain pinned to their old revision until safely upgraded. Changing the prefab on disk must not change how an old placement is removed or snapshotted.

## Props, additions, finishes and improvements

| Add this content | Required metadata and author steps | Must verify |
| --- | --- | --- |
| Furniture, bed, counter, shelf, painting, wall decor | `kind=prop`; prefab/model, icon, origin/front, full bounds, support/placement surface (`GROUND`, `WALL`, `CEILING`), indoor/outdoor tags, owner scopes, instance-count policy | Rotation/support alignment, collision, protected road-column exclusion, instance provenance, package/re-place preserving state |
| Tree, fountain, fence, statue, gazebo, light | Same prop handler; exterior tag, complete overhang bounds, approved particles/entities and interaction handler if any | Leaves/water/effects cannot change protected columns or neighbor plots; large props fit actual footprint |
| Porch/deck/side room | `kind=addition`; prefab, compatible socket types, transform, structural and mutation bounds, material slots, own child sockets if supported | Union of shell and additions satisfies road/border setbacks; disconnect/remove preview preserves affected props |
| Treasure-room/dungeon basement | Addition with below-ground socket, depth, entry connection and spawn-safe navigation markers | World Y bounds, terrain/fluid snapshot, road columns, chunk edges, valid entrance and removal preservation |
| Roof/wall/floor palette | `kind=palette`; compatible material slots/style tags, semantic source family → target block family mapping with orientation variants | Tagged structure cells only; stairs/slabs/rotations compatible; player edits and props not recolored |
| Path appearance | `kind=improvement`, existing `path_style` handler; material mapping and allowed width/edge profiles | Door-to-road connector stays inside authorized footprint/easement and terminates beside public road |
| Mailbox/shop/teleporter skin | Prop/improvement references `mailbox`, `shop` or `teleporter` capability handler; appearance asset separate from unlock | The same functionality across art variants; membership/current entitlement re-checked on use |

Use common handlers for category behavior. A fence is a prop with authored bounds and support behavior; a fancier fountain is new content. A working irrigation device would be a new behavior and requires code, authorization and lifecycle tests.

Example proposed addition:

```json
{
  "schemaVersion": 1,
  "kind": "addition",
  "id": "eternia:addition/cottage_porch",
  "revision": 1,
  "displayNameKey": "eternia.addition.cottage_porch.name",
  "prefab": "EterniaMod/Additions/CottagePorch_v1.prefab.json",
  "ownerScopes": ["PLAYER"],
  "socketTypes": ["cottage_porch_v1"],
  "structureBounds": {"min": [0, 0, 0], "size": [4, 3, 2]},
  "materialSlots": ["floor", "trim"],
  "ownershipMode": "QUANTITY"
}
```

Changing a palette or path style is a recoverable operation, with preview, original-cell preservation and a bounded undo record. A palette does not authorize painting road blocks. A basement prefab cannot reserve space beyond the plot just because it is underground.

## Plot sizes and shapes

Add a `plot_shape` definition with tier, owner scope, width/depth or footprint mask, exact occupied area and allowed quarter-turns. Connect paid tiers to an entitlement; free-tier eligibility belongs to onboarding/guild policy, not the token's display name.

For a new equal-area shape, count occupied cells, calculate the five-block structural inset, and test a representative compatible house. Reject disconnected masks, holes that create misleading usable area, or shapes that bypass road/overlap rules. Keep the initial catalog at the two player squares and four guild options specified in [housing](housing-and-guilds.md#plot-sizes). No new Java is needed for another supported footprint; a new geometry primitive may require a handler.

## Rewards, recipes and discovery tokens

Content ownership and acquisition are separate. This lets the same chair be free in one bundle, discovered in-world, awarded by a pass or sold without duplicating its definition.

```json
{
  "schemaVersion": 1,
  "kind": "reward_bundle",
  "id": "eternia:reward/starter_housing",
  "revision": 1,
  "rewards": [
    {"type": "unlock", "contentId": "eternia:house/cottage"},
    {"type": "quantity", "contentId": "eternia:prop/oak_chair", "amount": 1},
    {"type": "capability", "capabilityId": "housing.mailbox"},
    {"type": "move_credit", "amount": 1}
  ]
}
```

The required `oak_chair` definition must also be authored. Onboarding references this bundle with a once-per-account grant receipt. Do not put the free move credit in a repeatable recipe or blindly regrant a starter bundle when replacing a lost tool.

| Acquisition source | How to add it |
| --- | --- |
| Free | Add reward bundle; reference it in a named onboarding/service grant policy with explicit once/repeat eligibility |
| Found in world | Define a discovery token with native item/icon, reward bundle, source drop/loot table and binding; mint a unique redemption ID; add the item to the selected world content source |
| Crafted in Housing Ledger | Define recipe with required materials, entitlement prerequisites, output quantity and allowed owner scope; authorize and reserve inputs before output delivery |
| Free pass reward | Reference the reward bundle in a free-track tier with immutable reward slot ID |
| Paid pass reward | Same on paid track; possession of that season's paid entitlement is checked at claim time |
| Tebex product | Create an offer mapping to the reward bundle; connect the verified package ID in deployment configuration after testing |

Native token stacks must not duplicate runtime redemption/instance IDs when split or cloned. For repeated stackable discovery items, use a proven per-unit custody quantity or separate serials. Paid/permanent unlocks default bound; ordinary loot tradeability is explicit. The reward service supplies operation/claim IDs at runtime—authors never paste an actual purchase transaction into JSON.

## Titles, cosmetics, pets and supporter benefits

| Kind | Author contract | Staging demonstration |
| --- | --- | --- |
| `title` | Localized text, `PREFIX` or `SUFFIX`, styling token, icon; acquisition through rewards | Pair with another title; check chat, roster and nameplate length/readability |
| `cosmetic` full look | Supported rendering adapter, appearance/model assets, original-appearance restoration and compatibility tags | Third/first person, armor, animations, movement, world transfer, unequip, reconnect |
| `cosmetic` wearable | Supported slot, assets, tint/attachment transform, outfit conflict rules | Slot replacement, equipped armor conflicts, official appearance compatibility; do not publish until adapter proven |
| `pet` | Model/animations, follower controller profile, cosmetic-only flag, bounds, idle/roam behavior, supported accessory sockets, permitted world roles | Single follower invariant, plot containment, summon/unequip, reconnect, no loot/combat/crop effects |
| Pet home/toy | Prop with pet-home/toy handler and compatible pet tags | Assignment and decor work even if owner is offline; no duplicated pet ownership |
| Pet accessory | Cosmetic attachment referencing supported pet socket | Safe fit, detach, pack and restore; underlying pet remains owned independently |
| `supporter_benefits` | Named timed capabilities plus separately identified permanent billing-period gifts | Renewal/cancel/expiry do not remove permanent unrelated entitlements; repeated period grants deduplicate |

Use one example of each supported behavior, then let artists add variations. Custom scripts embedded in content files are unnecessary; a small allowlisted set of handlers is easier to validate and operate.

## NPCs, dialogue and world destinations

A dialogue definition is a graph of named nodes with localized text, choices, optional visible/disabled conditions and typed actions. Built-in actions include `open.housing`, `open.guild`, `open.market`, `open.store`, `open.seasons`, `open.world_select`, `show.help` and `grant.onboarding`. Each handler calls the authoritative service and re-checks eligibility.

To add another NPC, define appearance/role and dialogue ID, add a spawn placement referencing a stable world ID and transform, then bind interaction to the dialogue handler. Prowl requires its model's human-animation parent and common texture/model dependencies; see [the reference map](codebase-audit.md). No dialogue action may run arbitrary administrator commands from JSON.

For a new world destination, register ID, display text/icon, world reference, world role, safe spawn/portal, availability and entry condition. A public road portal also needs a footprint anchor and protected corridor authored by staff. A paid teleporter only grants access to existing eligible destinations; it does not bypass destination checks.

The minigame NPC uses a disabled/coming-soon service action until a real minigame provider exists. Its presence must not make a minigame objective appear completable in a pass.

## Activities, objectives and permanent seasons

### Add an ordinary quest

Choose an existing objective handler and eligible activity source. Define target IDs/tags, count versus distinct count, progress scope, localized instructions and completion reward/XP. For acquisition quests, specify the source of acquisition; never count every inventory-add event.

```json
{
  "schemaVersion": 1,
  "kind": "quest",
  "id": "eternia:quest/resource_sampler",
  "revision": 1,
  "displayNameKey": "eternia.quest.resource_sampler.name",
  "objectives": [
    {
      "id": "gather_distinct",
      "handler": "acquire_distinct_resources",
      "activityTags": ["adventure.resource_acquired"],
      "targetTags": ["starter_resource"],
      "requiredDistinct": 3,
      "eligibleSources": ["natural_mining", "mature_harvest", "adventure_loot"]
    }
  ],
  "completion": {"seasonXp": 2000},
  "repeatPolicy": "ONCE_PER_SEASON"
}
```

The adapter maps actual native resource IDs into `starter_resource`; those mappings must exist in an activity definition. Kill-count and total-resource-count quests use the corresponding existing handlers. Avoid objectives that depend on content players cannot reach on their selected pass.

### Add a new season

Create a unique season ID, publish both tracks, set cumulative XP thresholds, reference permanent quests/chapters, and define a paid offer. The `level` numbers here use cumulative thresholds, not per-level costs:

```json
{
  "schemaVersion": 1,
  "kind": "season",
  "id": "eternia:season/foundations",
  "revision": 1,
  "displayNameKey": "eternia.season.foundations.name",
  "progressPolicy": "PERMANENT",
  "paidEntitlementId": "eternia:pass/foundations_paid",
  "referenceWeeks": 12,
  "levels": [
    {"level": 1, "totalXp": 2000, "free": [{"slotId": "f01", "rewardId": "eternia:reward/chair"}], "paid": [{"slotId": "p01", "rewardId": "eternia:reward/porch"}]},
    {"level": 2, "totalXp": 4000, "free": [{"slotId": "f02", "rewardId": "eternia:reward/prefix"}], "paid": [{"slotId": "p02", "rewardId": "eternia:reward/pet"}]}
  ],
  "questChapters": [
    {"id": "chapter01", "unlock": {"type": "season_selected"}, "quests": ["eternia:quest/resource_sampler"]}
  ]
}
```

This is a deliberately short test season, not the full 30-level pacing example. Define its referenced rewards before validating. For the production example, extend to 30 cumulative thresholds ending at 60,000 XP and author enough representative rewards; no end date is added. Chapters after the first unlock through progress, never a calendar-only deadline.

Check that normal activity and quest bonuses match the intended pacing, late paid purchase reveals earned paid slots, selecting another pass retains progress, and every old-season objective remains available. Once published, preserve IDs and reward-slot meanings. To replace an impossible objective, supply an explicit state migration and preserve partial progress or grant a documented completion credit.

### Add a new activity type

This is the point where Java is needed: implement an activity adapter for the authoritative successful gameplay event, player attribution, world eligibility and replay prevention. Register its stable namespaced event type, schema and supported objective handlers. Add a representative objective definition and a meaningful duplicate/invalid-event test.

Optional Aetherhaven integration lives behind an adapter and declares a dependency. If it is absent, content validation prevents assigning its quests and provides the migration/equivalent objective for existing passes. Minigames follow the same contract when they exist. Do not invent an event name and assume the other mod emits it.

## Store offers and subscriptions

An offer describes presentation, eligible recipient scope, product behavior and reward mapping. Tebex owns real-money prices and payment; deployment configuration maps actual provider package IDs to stable Eternia offer revisions. Do not publish invented package IDs or command placeholders as if they work.

```json
{
  "schemaVersion": 1,
  "kind": "offer",
  "id": "eternia:offer/cottage_porch",
  "revision": 1,
  "displayNameKey": "eternia.offer.cottage_porch.name",
  "recipientScope": "PLAYER",
  "purchaseType": "ONE_TIME_QUANTITY",
  "rewardId": "eternia:reward/porch",
  "fulfillmentPolicy": "PER_PURCHASED_UNIT"
}
```

For reusable unlocks, show already-owned status and an explicit duplicate policy. For quantity items, show how many copies are granted. A guild offer targets a guild ID verified from current membership/authority. A supporter offer specifies recurring benefit periods and gift receipts; a paid-pass offer grants that season's permanent paid entitlement. A plot upgrade displays legal-placement limits and any possible move-credit cost before checkout.

Use Tebex test delivery to prove one purchase, multiple quantity, duplicate event, offline buyer, subscription renewal/end, failed grant retry and reversal. Only then expose the offer. Content changes that alter what a purchased offer grants create a new revision; retain the promised mapping on existing purchase records.

Future premium-currency offers and pass rewards require the full wallet feature to be enabled. The validator rejects unsupported reward/price currencies, preventing a designer from releasing unspendable paid value by adding JSON alone.

## Validation and maintenance rules

| Check | Failure example |
| --- | --- |
| Discovery and IDs | Definition missing from manifest; duplicate ID/revision; path escaping its pack |
| References | Missing prefab/icon/model/reward/socket/quest/offer; missing Prowl parent animation set |
| Schema | Unknown handler/kind; wrong vector length; negative quantity; unsupported enum/version |
| Geometry | Free house exceeds 14 × 14 structural inset; rotated addition crosses boundary; declared bounds omit prefab air writes |
| Ownership | Recipe creates a paid unlock; guild content accidentally granted to player scope; discovery token lacks safe redemption policy |
| Progression | Non-increasing thresholds, reused reward slot, impossible dependency, calendar expiry on a permanent season |
| Commerce | Offer has no verified deployment mapping; a timed rank revokes unrelated permanent grants; unknown premium currency |
| Runtime resources | Entity/component cannot be snapshotted; wardrobe adapter unproven; malformed native item metadata |
| Localization/UI | Missing name/description, invalid formatting, oversized titles, missing acquisition explanation |

Use a fail-closed validated catalog snapshot: a failed reload retains the last known good catalog. Never silently drop definitions that have placed/owned instances. Prefer restart for native asset changes until safe reload is proven; do not rely on rebuilding common assets to update arbitrary server state while players use it.

Operator tools should show unknown/missing content as recoverable records with IDs and counts. Tombstone retired definitions, preserve old prefab versions and add aliases/migrations. Removing a JSON file is not a refund or permission to delete player property.

## Minimal demonstration content

Build only enough content to exercise each handler: one player shell, one guild shell, one free and one paid plot tier per owner with required guild rectangle choices, one indoor prop, one outdoor prop, one wall item, a porch, a basement, a palette, a path style, mailbox/shop/teleporter appearances, a prefix and suffix, one supported full look and wearable, one pet with a home/accessory, one discovery token and recipe, and a small free/paid test season with count/distinct quests. Use the requested hub NPCs and one adventure destination.

Reuse these assets across free, discovery, pass and paid test grants where useful. The proof of extensibility is that adding a **second item of an existing kind** requires assets, definitions, localization and acquisition wiring, with no Java edits. New behavior still requires code and a documented handler contract.
