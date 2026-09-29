# Commerce fulfillment and gameplay activity adapters

These adapters are implemented entry points. A live Tebex store, public HTTPS callback, mapped package definitions, eligible adventure worlds, and selected crop interaction assets still require operator configuration and live validation. Local tests do not charge money or invent successful payments.

## Tebex setup

Register immutable `CommerceService.Product` definitions before constructing `TebexFulfillment`. `packageId` must be the real numeric Tebex package ID, and its revision must match the explicit package allowlist. Benefits refer to Eternia content IDs. Quantity benefits grant an integer count; unlocks and capabilities grant ownership. Leased supporter benefits use the verified paid period; permanent monthly gifts retain their own source receipts.

The constructor is `TebexFulfillment(services, packageRevisionAllowlist, webhookSecret, executor)`. Mount `webhookHandler()` at the private game bridge's `/v1/commerce/tebex` path. Forward the public website callback's **unchanged raw bytes** and `X-Signature` header to that handler. The callback authenticates with the Tebex signature independently of player login. Keep the secret on servers, never in browser JavaScript. The handler bounds request bodies to 1 MiB and responds with a retryable error if persistence fails.

Call `registerConsole(plugin)` during setup. Configure this as the Tebex package's initial and renewal console command:

```text
eternia-tebex {transaction} {packageId} {id} {purchaseQuantity}
```

These are documented Tebex substitution variables. Package commands may be resent, so command execution is not a unique delivery receipt. No price is accepted, and player command senders are rejected even if they hold administrative permissions. The server persists the expected line before returning from the console command. [Tebex command variables](https://docs.tebex.io/creators/command-management/an-introduction-to-commands)

Enable `payment.completed`, refund/dispute, recurring start/renew/end, and cancellation callbacks. The adapter checks HMAC-SHA256 over the hexadecimal SHA256 digest of the original request body using constant-time comparison. It returns the required event ID for a signed validation webhook. Payment status must be complete. Each mapped product must carry a UUID in its product-level `username.id`; numeric platform-account IDs are rejected. Confirm this identity field using a real Hytale Tebex test payment before enabling purchases. The adapter does not infer the recipient from the customer's display name or email. [Tebex webhook schema and signatures](https://docs.tebex.io/developers/webhooks/overview)

A signed product line and a console command must agree on transaction, package, recipient, quantity, and configured product revision. Either may arrive first. Neither grants alone. The durable join calls the domain fulfillment service, whose purchase-line receipt prevents repeat grants. Run `reconcile()` periodically; each call considers at most 100 ready records, including receipts whose player account became available later. Unknown packages grant nothing. Duplicate occurrences of one package in a payment require review because the command variables do not identify separate occurrences.

Refunds and dispute-open/lost callbacks create a durable transaction tombstone before revoking source grants. An old completion arriving afterward cannot grant those purchases. A dispute win or close does not automatically mint replacement gifts: source restoration requires a reviewed recovery path. Reversal can revoke remaining source quantities and access, but cannot retrieve consumed or traded physical objects. Guild upgrades use the explicit voucher flow below: checkout still identifies the verified paying player, and a subsequent leader confirmation selects the guild. Neither payment callbacks nor current membership automatically choose a guild recipient.

Recurring start/renew callbacks provide the paid period from the verified payment and next billing timestamp. The adapter also accepts a product expiration timestamp in payment completion. Missing paid-period information produces an error rather than an assumed month. Cancellation requests retain access through the paid period; aborted cancellations clear that request. End callbacks persist a timestamped tombstone and revoke leased benefits while keeping permanent gifts, including when the end arrives before its payment. Older end callbacks cannot end a newer paid period. Validate the provider's period fields and event ordering with real test subscriptions before release; dispute restoration remains a reviewed recovery path.

## Purchased guild upgrades

Two permanent voucher content IDs are supported by `GuildBenefitService`:

| Tebex product benefit content ID | Product benefit kind | Guild capability created on redemption |
| --- | --- | --- |
| `eternia:guild_voucher/large_plot` | `QUANTITY`, normally 1 | `eternia:plot/guild_64` |
| `eternia:guild_voucher/teleporter` | `QUANTITY`, normally 1 | `eternia:convenience/teleporter` |

Author an ordinary one-off product under plugin-data `Products/`, with the real Tebex package ID, its immutable revision, `subscription: false`, and a benefit using one of these voucher IDs with `expiresWithSubscription: false`. Map that package/revision in `tebex-packages.json` and add its real checkout page to `store-offers.json` as for other products. A voucher is an account quantity grant, not a transferable carried item. There are no bundled real package IDs or enabled payment URLs.

The purchaser opens **Guild → guild house/estate → Redeem a purchased guild upgrade**. Only the guild leader can redeem. The review names the exact guild and benefit and explains that the donation remains with that guild when the purchaser leaves. The server captures the guild UUID, purchase-source grant and redemption receipt; final confirmation rechecks current membership and `commerce.purchase_for_guild` in the transaction. Joining a different guild or transferring leadership while the confirmation is open cannot redirect or authorize the old donation.

The selected source must be a remaining permanent quantity from a `DELIVERED` Tebex purchase belonging to that same player, with its exact grant receipt listed on that purchase. Operator-created quantities, another player's purchase, expired/revoked grants and every subscription purchase are rejected, including permanent subscription gifts. A player who bought before forming a guild can retain the voucher and explicitly redeem after becoming a leader. A guild that already owns the capability rejects an unnecessary second donation without consuming the voucher.

Redemption consumes exactly one available unit from the chosen source and creates the guild capability plus a durable `grant_derivation` link in the same transaction. It does not combine units from unrelated grants. A duplicate click returns the recorded donation. Refunding that purchase revokes its unspent voucher stock and every derived guild grant atomically, including donations to different guilds made at different times. Independent purchase/gift sources remain intact. Replaying a refunded donation reports its inactive historical state and cannot regrant or spend a different voucher.

The larger-estate capability enables the existing paid guild plot shapes, including 64×64, subject to normal claim permissions, five-member eligibility, placement rules and available slot. It does not instantly resize a placed estate: use the existing pack/restore flow into a supported larger shape. A refund prevents new claims/restores requiring the missing size entitlement; it does not delete or automatically shrink the existing estate. Guild teleport access checks the guild capability and the member's `convenience.use` permission at use time. Members need no separate personal purchase to use that guild convenience.

`GuildBenefitServiceTest` covers repeated confirmation, refunded history, exact-source consumption, stale guild/leadership, another purchaser, subscription rejection, concurrent confirmations, refund racing redemption, multiple child grants and independent-source preservation. Real provider and native menu acceptance still requires configured Tebex test products and an actual local guild estate.

## Activity setup

`GameplayAdapters` constructs `ActivityBootstrap(plugin, services, settings)` and closes it before closing the store. `activity-xp.json` holds an explicit set of eligible world names and exact native-ID-to-XP maps for `kills`, `mining`, and `harvesting`. The optional coin configuration described below can add exact eligible targets with zero base XP. Empty world lists disable native activity capture. Use adventure worlds and selected gameplay targets; housing, the hub, and Creative mode do not award these activities. XP values represent one successful action. Tune the season's total XP against observed normal-play rates to approach the planned five hours per week; this adapter does not fabricate that rate.

The bootstrap installs:

- NPC death handling that requires a newly added `DeathComponent`, a player damage source, Adventure mode, an allowlisted role, and the dead NPC's persistent UUID. Deaths loaded from disk without transient killer evidence do not earn XP.
- A pre-placement durable marker that prevents player-placed positions from being treated as natural mining sources. Persistence failure cancels placement.
- Mining qualification that waits for the cancellable break event to finish and checks that the unchanged target became air. Cancelled, redirected, unloaded, creative, or unchanged targets do not earn XP.
- A bounded background outbox worker that applies up to 100 qualified activities per second, including their saved earned-coin reward. Source, event and ledger receipts make retries safe across service restarts.

Mining sources are `(world UUID, block position)`, with one natural award per source. Placement markers survive restarts and are never cleared by breaking the block. A regenerated adventure world must use a new world UUID. Bulk world editors or custom placers that bypass `PlaceBlockEvent` must call `activitySources().markPlayerPlacement(...)` for their placed positions before allowing gameplay there. Existing modified worlds need a conservative provenance import or a fresh adventure world; an unmarked historical block is otherwise indistinguishable from natural terrain. Synchronous provenance writes currently trade world-thread latency for durable ordering; measure PostgreSQL latency before scale testing.

## Earned coins from verified activity

`GameplayAdapters` also reads plugin-data `activity-coins.json`. A missing file receives conservative empty maps:

```json
{"KILL": {}, "MINE": {}, "HARVEST": {}}
```

Each map uses the exact native target ID as its key and a whole-number amount of earned coins for one qualified action. `KILL` keys are NPC role IDs; `MINE` and `HARVEST` keys are the native block/crop IDs seen by the existing adapters. Inspect the active server assets and test the IDs locally before enabling a payout. An illustrative entry is `"KILL": {"Your_Verified_Npc_Role": 2}`; replace that placeholder with a real selected role. Unknown or differently cased targets receive zero coins. Values must be JSON numbers from 0 to 100,000; fractional values, numeric strings, negative amounts, unsupported activity kinds and overlarge target maps are rejected. No default amount, wildcard or browser-supplied amount exists.

The eligible worlds remain the `worlds` list in `activity-xp.json`; each must have the `adventure` infrastructure role. A target configured only for coins is added to the same native allowlist with zero **base** XP. Existing configured XP is preserved. Such a verified event still updates gameplay stats and may advance an existing season objective, which can award its existing quest XP. Quest completion does not award coins. Coin earnings also work when the player has no selected season.

Kill UUID, natural mining position and crop-generation evidence are identical for XP and coins. A placed-and-broken block, repeated death notification, copied source event or repeated crop generation cannot mint another reward. The existing crop opt-in and generation limitations below apply equally to coin payouts. This does not introduce a separate unverified item-gathering endpoint.

`ActivitySourceService` snapshots the configured coin amount into the durable outbox row when it first accepts the successful source. Changing configuration affects newly accepted activities only; queued rows keep their original amount. Restart to apply file edits. Existing outbox rows without a `coins` field intentionally pay zero, including old pending rows. Already delivered history is never paid retroactively.

During drain, the worker first calls the existing idempotent season/activity receipt, then credits the integer ledger with `activity-coins:<outbox-source-key>`, then marks the row delivered. It always attempts the saved coin credit even when XP reports an already-recorded event. A crash after XP but before credit leaves the payout pending. A crash after credit but before queue acknowledgement replays the same ledger receipt and pays nothing extra. Concurrent workers follow the same receipts. No pass reward, quest reward, premium currency or manual/native receipt fabrication is added by this path.

These earnings fund the existing player-shop and direct-trade balances described in [inventory and trading](inventory-and-trading.md). Coin rates are an economy tuning decision; start with a few verified low payouts and measure generation before broadening the allowlist.

## Crop integration boundary

The installed interaction codec is `EterniaHarvestCrop`. Replace `"Type": "HarvestCrop"` with `"Type": "EterniaHarvestCrop"` **only in the selected crop harvest interaction assets**. Retain their other interaction-chain behavior. The class inherits the standard crop interaction codec, including `RequireNotBroken`.

The crop must also retain its native `FarmingBlock` while mature. Hytale removes that entity immediately when a terminal growth stage has no duration, losing the native generation needed for verified XP. Merely replacing the harvest interaction on an ordinary durationless mature crop does not enable reliable XP. End both the initial and post-harvest stage arrays with this pair:

```json
[
  {"Type":"BlockState","State":"StageFinal","Duration":{"Min":86400,"Max":86400}},
  {"Type":"EterniaAwaitHarvest"}
]
```

Use the crop's actual mature state name. Keep its original earlier growth stages and `StageSetAfterHarvest`; append the pair after those growth stages. The finite duration shown is a mature holding interval, not the regrowth time. The globally registered `EterniaAwaitHarvest` guard refuses the next native growth transition, keeping the preceding mature stage and its farming entity indefinitely. It must follow the mature stage; it is never entered. Native harvest still gives the normal drops, resets the stage set and advances the persisted generation. See the complete `Eternia_Trial_Wheat.json` example; its earlier growth stages are shortened only for local testing.

When deriving a new crop from a native parent, explicitly redeclare the used `BlockType.State.Definitions` with the desired inherited visuals. Otherwise inherited state references can resolve to the parent's generated variants and bypass the child's farming or interaction changes. The trial asset declares its own `Stage1` and `StageFinal` definitions and runtime acceptance verifies that the resolved mature ID and farming stages belong to that asset.

This interaction calls native `FarmingUtil.harvest` and awards only if it returns true, the player is in Adventure mode, the allowlisted crop had growth progress, and the persisted `FarmingBlock` generation advanced exactly once. Each `(world UUID, position, native generation)` awards once across players and restarts. Replanting that resets the native generation cannot replay earlier awards. This is intentionally conservative: a replacement plant may receive no XP for generations already used at the same position. Supporting fresh plant instances without that restriction needs a persistent planting identity integrated with the growth/planting lifecycle. Crops that disappear instead of advancing a farming generation currently harvest normally but do not receive activity XP.

The stock `UseBlockEvent.Post` is not evidence that a crop was successfully harvested: local `UseBlockInteraction` emits it after dispatching the interaction chain. Vanilla `HarvestCrop` assets that have not opted into the new interaction do not emit Eternia harvest progress. Do not claim all crops are wired merely because the codec is registered.

## Validation

Automated tests cover receipt arrival order, changed recipients, replay, concurrent reconciliation, refund-before-completion, HMAC byte sensitivity, UUID validation, placed-block exclusion, world-source replay, and crop-generation replay. The existing local-file tests cover restart durability and failed commit rollback. The PostgreSQL test is opt-in against an isolated configured database and reports skipped when absent.

`ActivityCoinsTest` additionally covers duplicate sources, concurrent drains, stored reward amounts across restart/config edits, crashes after XP or ledger commit, placed-block/remining exclusion and zero-payout legacy rows. `ActivityCoinConfigurationTest` checks exact maps, zero-XP coin-only targets and invalid amounts. Native acceptance should compare the player's ledger balance before and after one real eligible kill/mine/harvest, then verify that placement/replay and disallowed worlds/modes add nothing.

Live tests still need to verify native ECS signatures against the shipped server, an uncancelled break versus protected/cancelled breaks, a placed-and-rebroken ore, an NPC melee/projectile kill, an opted-in crop regrowth, a Tebex test purchase/renewal/refund, and callback retry after a server restart. Follow [local development](../planning/local-development.md) for the local environment.
