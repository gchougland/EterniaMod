# Hub, social, progression, commerce and website

Status: proposed implementation contracts. See [the roadmap](implementation-plan.md) for dependencies, [housing](housing-and-guilds.md) for property rules, and [integration research](integration-research.md) for externally verified capabilities.

## Hub and first-session flow

Use data-defined NPC placements, appearances, dialogue and allowlisted actions. NPCs are protected, persistent service entities, not combat targets or sources of loot/XP. A restart repairs missing service NPCs without spawning duplicates. The hub contains:

| Service | Behavior |
| --- | --- |
| Prowl greeter | Welcome to Eternia; explain adventure worlds, free housing, roads/anchors, trading, guilds and permanent passes; link to the relevant service menus. Offer a repeatable Help option and remember completed onboarding |
| Player Shop NPC | Search all player listings, filter items/prices, see stock and travel to a seller's safe storefront entrance |
| Player Housing NPC | Explain sizes, grant/reissue claim token and tools, open housing inventory, show current plot, return to house/public housing portal, recover packed property |
| Guild Housing NPC | Open guild creation/join management, explain five-member eligibility, claim/root status and guild inventory for authorized members |
| Store NPC | Show product categories and ownership, then open official Tebex store UI or validated checkout link; never collect card data in game |
| Minigame NPC | Clearly display “Coming Soon” with short flavor text; no matchmaking button or promised launch date |
| World Select Portal | Open available world list with brief purpose, readiness and entry conditions; safely transfer to the selected destination |

The first greeter conversation should fit a few pages: “Welcome to Eternia”; “Adventure for resources and progress”; “Claim your free home”; “Trade and build a community.” Direct the player to the housing NPC and public housing portal, while allowing them to explore first. The free plot entitlement is account-based and does not require finishing every dialogue.

Copy Prowl's model, texture, icon and required human-animation parent into Eternia-owned asset IDs. Use an Eternia NPC role and dialogue rather than importing Aetherhaven town population, needs or quest logic. Verified source locations are in [the audit](codebase-audit.md).

## Travel and returning home

`TravelService` owns hub, housing, public portal, house entrance, guild entrance and adventure destinations. Destinations reference stable world/plot IDs and safe markers, not stale coordinates embedded in tokens. Validate world availability, access, combat/activity restrictions, cooldown, spawn clearance and chunk readiness before transferring. Capture a return point for shop visits; returning cannot be used to bypass the same adventure restrictions.

Public road portals provide free housing → hub travel, with world selection available from the hub. A personal paid teleporter provides housing → hub or directly to eligible adventure worlds. Guild teleporters provide those destinations to all current members when the guild owns the capability. Ordinary public travel remains available independently of owning a paid item.

### Login decision

Persist each account's last disconnect time and last valid world/location. Periodically persist a session heartbeat as a crash-recovery approximation; do not update last logout merely because the player changes worlds.

1. First login or no trustworthy previous location: route to hub onboarding.
2. A reconnect after **30 minutes or less** uses the normal last location if valid.
3. A return after **more than 30 minutes** uses a safe authored spawn point inside the current personal house.
4. If the house is packed, absent, moving, inaccessible, or has no safe interior point, try a safe point on the player's plot, then the nearest available public housing portal, then the hub. Never place the player in a block, basement void, road hazard or unloaded world.
5. A deleted/closed adventure instance also falls back safely even on a short reconnect. After a server crash, use the best persisted session time conservatively; do not fabricate an exact disconnect time.

House moves update the spawn transform, while temporary restoration operations suspend it. Player-created beds may select an interior spawn candidate, but a house's free authored marker ensures a bed purchase is unnecessary.

The local Hytale source exposes `PlayerConnectEvent`, `PlayerDisconnectEvent` and `AddPlayerToWorldEvent`; prototype routing in the appropriate lifecycle phase so the player does not visibly spawn once and then receive multiple competing teleports. Exact integration timing remains a runtime check.

## One ownership model for all rewards

Free grants, in-world tokens, season rewards and paid products all resolve to the same typed reward grant. Every grant has a source receipt, recipient principal, content ID, quantity or validity period, and binding policy.

| Content behavior | Ownership meaning |
| --- | --- |
| House style, palette, title, full skin, wearable | Permanent reusable unlock; placement/equip constraints still apply |
| Prop/addition copy | Owned instance or quantity, transitioning between available, reserved and placed; packaging returns the same ownership |
| Pet | Owned pet instance; assignment determines follower/property/unequipped state |
| Plot tier / teleporter | Owner capability, checked at placement/use; appearance is separate |
| Move credit | Consumable quantity with exactly-once spending |
| Paid season track | Permanent access to one season's paid rewards, including already-earned levels |
| Supporter rank | Time-bounded benefit source with a recorded paid interval |
| Earned currency / future premium currency | Distinct integer ledger assets with explicit allowed uses |

An in-world discovery token has a unique redemption identity and an authored reward reference. Losing it before redemption follows its declared trade/binding rules. After redemption, ownership is durable. Default purchased/pass unlocks and claim/move/property handles are account-bound; ordinary world loot may be tradable. Storefront products and tooltips show whether a purchase is an unlock or a finite copy. Server checks enforce this regardless of physical token metadata.

Provide a durable Delivery Inbox separate from the free mailbox's gameplay capacity. Grants, refunds, recovered house tokens and trade settlement can wait there without falling on the ground. Delivery to native inventory reserves the grant and completes a journaled handoff; idempotency in SQL alone is insufficient to protect an interrupted native-inventory write.

## Mail and private messages

Every player gets a free mailbox capability and simple prop. The hub Housing NPC also opens mail so an unclaimed or packed plot never makes items inaccessible. The initial mailbox supports offline text, item attachments, unread status, sender lookup by current name with UUID resolution, sent history and claim/claim-all.

Suggested launch limits: 20 active user messages, up to five attached item stacks per message, and rate-limited sending. These are tuneable operational limits, not a reason to create a large settings UI. System deliveries use the separate durable inbox. If the user mailbox is full, reject new user mail before removing sender items or hold it in an explicit pending queue; never discard it. Unclaimed item mail has no automatic destructive expiry. Text-only retention can be an operator policy with a visible notice.

Sending attachments transfers them into escrow and records recipient/message in one recoverable operation. Claiming moves escrow into a delivery receipt, then native inventory when space is available. Item quantity, durability, custom metadata and binding are preserved. Deleting mail with unclaimed attachments is blocked until the sender/recipient uses an explicit return/claim flow. Refund/rejection returns use the durable inbox.

Mail attachments are gifts/transfers. Purchases and exchanges use the market/direct-trade service; a text promise in a mail is not an atomic trade. Support block/mute and report actions with enough record IDs for moderation. Private content is visible only to participants and authorized moderation workflows, not public account pages.

Paid mailbox skins can reuse the same free capability. A larger paid user-mail capacity remains a follow-up decision; if introduced, capacity never shrinks by deleting stored items when an entitlement changes.

## Player shops and the location decision

Use one global listing index for the server. Each listing has seller UUID, item escrow, unit price in earned currency, available/reserved quantity, storefront plot ID and status. Items must satisfy the declared trade policy; bound houses, paid unlocks, claim tokens, supporter benefits and future premium currency are excluded by default.

| Purchase location | Strengths | Costs |
| --- | --- | --- |
| Entirely at hub | Fast buying, easy bulk purchases, fewer travel failures | Little reason to visit housing; house shops become decorative |
| Hub search, buy at house | Brings visitors to decorated properties; matches the proposed social loop | Travel friction and privacy/safe-arrival handling |
| Both | Convenience and optional visits | More UX decisions before learning whether visits work |

**Recommendation for the first release:** hub search → visit house → buy. Give each house a small free shop fixture and a public entrance/service area. The seller opts their shop into public visits and can keep house interiors private. The catalog listing must show if stock changes or the shop is unavailable. The seller does not need to be online and the shop does not depend on a daytime schedule.

Do not implement a separate economy for house checkout. One `MarketPurchaseService` can later be called from the hub if player testing supports direct checkout. Start without arbitrary listing fees or taxes; add a server policy only when the economy has a reason for one.

### Listing and buying

1. Seller selects eligible native items, price and quantity. Move stock to authoritative listing escrow; the decoration model is a display, not a second item container.
2. Buyer searches, inspects price/stock and requests a visit. Validate current shop accessibility and safe entrance, then travel. Keep listings discoverable while seller is offline.
3. At the fixture, show the current unit price, quantity and total; confirm against a listing revision. Reserve stock and buyer funds with a unique purchase ID.
4. In the settlement transaction, debit buyer earned currency, credit seller proceeds, decrement stock, and create buyer delivery. An interrupted native handoff remains recoverable through the inbox.
5. A stock/price revision or insufficient funds produces a fresh quote, never an unexpected charge. Exactly one buyer wins the last available unit.

While a plot moves, pack/resizes or enters eviction, suspend visits and checkout; keep escrow and proceeds intact. Resume using the stable storefront/plot reference when possible. When a house stays packed, delist and return stock through seller delivery. A visitor cannot use the seller's personal teleporter, private chest, mailbox or pet controls.

### Direct and guild trading

Provide a small two-party trade window. Reserve both item offers and earned-currency amounts; both players confirm the same immutable offer revision. Editing either offer resets confirmations. Disconnect or timeout cancels unfinished trades and releases reserves. Completed settlement is independent of whether recipients have native inventory space.

Guild roster and house menus add “Trade” and shared-listing shortcuts; they do not remove checks or trust one party automatically. A guild treasury and build stock have separate owners and withdrawal permissions. Member deposits explicitly indicate whether they are donations or private custody.

## Guild social features

The [guild spec](housing-and-guilds.md#guild-formation-and-management) defines menu pages and permissions. The minimum social implementation adds:

- Online/last-seen roster with current world/activity, respecting account visibility outside the guild.
- Small parties with leader, invites, membership, activity/world display and follow-up travel prompts. Parties are temporary, can include non-guild friends, and survive a short disconnect through a bounded reconnect window. No dungeon matchmaking is required yet.
- Guild-only chat and a persistent notice board with short posts, pinned announcements and moderation permissions. Seed no elaborate forum or calendar system.
- Guild mailbox/trade fixture and shared inventory, with explicit access for recipients and handlers.
- Shared hub/world travel and larger cosmetic housing options through the same personal-house service handlers.

Leaving a guild immediately removes guild presence/private-content access and conveniences, while the personal-plot grace period remains as specified. A guild title or rank does not grant server administration powers.

## Titles, skins, ranks and pets

### Titles and appearance

Let players independently equip one owned prefix and one owned suffix around their name, for example “Wandering Mira the Stargazer.” Define localized text, side, display order and length limits; keep supporter badge styling separate so it does not consume a player's title choices. Strip player-supplied formatting where needed and validate chat/nameplate/UI widths.

Collections has pages for Titles, Outfits, Wearables and Pets. Full looks and wearable pieces are Eternia-scoped entitlements. Store the player's original appearance, apply the selected supported rendering adapter, resolve conflicts between full outfits and wearable slots in a clear preview, and restore the original on unequip or when leaving the feature's scope. A cosmetic model must preserve normal player hitbox/movement.

The server model and skin structures support useful primitives, but arbitrary new character-creator wearable application needs a prototype. Keep the desired full-skin **and** wearable features in the roadmap; do not sell a failed prototype. These purchases do not imply ownership in Hytale's global account wardrobe. See [the verified capability boundary](integration-research.md#skins-wearables-and-model-support).

### Monthly supporter

Start with one monthly supporter product. Grant a badge/rank and a small cosmetic benefit bundle, such as a supporter title display and cosmetic variants. Do not add combat strength, preferential pass XP, trading power or mandatory functionality without a separate product decision.

The subscription entitlement records its current paid interval. Renewal extends access once; canceling renewal preserves the paid interval; expiry removes only time-bound badge/access. Clearly distinguish any permanent monthly gift from access leased during subscription. Existing purchased house sizes, styles, pets and earned pass rewards do not disappear when supporter status ends. Prevent duplicate monthly gifts with a receipt keyed to the provider's paid billing period.

### Pets

One owned pet can follow a player at a time. Other owned pet instances can be assigned to their property or an authorized guild property, with several simultaneous residents under an operational entity limit. A pet cannot be both the follower and a resident at once.

Implement a lightweight follower controller: owner tracking, safe local movement, catch-up teleport when far away, despawn/respawn on eligible world transfers and disconnect, and idempotent recovery keyed to pet instance ID. Property pets roam inside their permitted footprint/height and avoid roads, inaccessible rooms and neighboring claims. Loaded-area simulation only; no need to tick all offline pets.

Pets cannot fight, collect items, trigger doors/loot, grief crops, block players, or generate pass progress. Use one sample species, a pet home and an accessory to demonstrate extensibility. Feeding/play animations and care state can follow as optional cosmetic activities; no starvation, lost purchases, paid healing or functional advantage is implied.

## Permanent season passes

Each `SeasonDefinition` contains level thresholds, a free track, a paid track, quests, display order and activity availability. It has no gameplay expiry timestamp. Release dates organize the menu, not eligibility to progress or claim. Older passes remain selectable and completable forever, including their free track. Recommended default: their paid upgrade stays purchasable as well; any future sales-retirement policy must preserve access for existing owners.

Players select **one active season** for new XP and objectives. Switching is free and preserves all seasons' progress and unclaimed rewards. Both tracks share one XP total. A late paid upgrade enables claiming every already-earned paid reward once. Buying a pass never resets XP or consumes free rewards. Completed seasons remain visible with owned/unclaimed status and prompt the player to choose another active pass.

Free rewards can contain housing items, titles, character cosmetics and a pet; paid rewards use the same types with distinct content. The reward interface includes premium currency for future use, but production content validation rejects that reward type until the complete wallet/spend feature is available.

### Five-hours-per-week pacing

The request provides a playtime target but no season length. Use the following explicit **illustrative default**, then calibrate with actual activity data:

| Parameter | Example |
| --- | --- |
| Reference release cadence | 12 weeks, without an expiry or weekly play requirement |
| Levels | 30 |
| Level cost | 2,000 XP each; 60,000 total |
| Target ordinary activity rate | Median mixed gameplay yields roughly 1,000 qualified XP/hour |
| Without quest bonuses | 60 hours total, or about five hours/week across 12 weeks |
| Permanent catch-up quests | 12 chapters granting about 2,000 bonus XP each; 24,000 total possible |
| With all quest bonuses | About 36 hours of ordinary activity at the example rate, or three hours/week, with objectives designed to overlap that play |

General formula: `totalXP = referenceWeeks × 5 × measuredOrdinaryXPPerHour`. Quests reduce required ordinary XP; they are not an extra mandatory playtime bill. This is not hourly AFK XP, an XP cap, or a promise every activity earns the same amount. Measure combat, gathering, farming and mixed play separately; adjust event weights so common play styles can approach the target. Keep calibration versioned and avoid raising the required total for an already-released season.

Quest chapters unlock through durable progress, not calendar weeks. Unfinished chapters bank forever. Short-session players can work toward larger bonuses without logging in on specific dates. Show remaining XP and meaningful objectives; avoid daily streak pressure.

### Activity and objective contract

Normalize successful gameplay into `ActivityEvent` with event ID, actor UUID, timestamp, world/activity eligibility, target ID/tags, quantity, source instance/generation, and correlation ID. Run filtering and attribution server-side. Event adapters are code; seasons and ordinary objective definitions are data.

| Activity/objective | Qualification |
| --- | --- |
| Kill X mobs | Confirmed eligible NPC death with player/party contribution policy; one credit per event/player. Exclude summoned cosmetic pets, protected hub NPCs and repeated dead-entity notifications |
| Mine blocks | Successful permitted removal of an eligible natural-resource generation. Exclude canceled breaks, admin edits, prefab placement/removal and blocks players placed to re-break |
| Harvest crops | Successful mature crop harvest/growth-cycle transition attributed to the player; no credit from interaction attempts that fail or repeated clicks on the same growth cycle |
| Acquire N resources | Credit only a defined eligible acquisition source (mining/harvest/loot), not every inventory change; stacking/splitting/trading/dropping items cannot replay acquisition |
| Collect N different resources | Maintain a set of qualifying resource IDs/tags for the objective; repeated units of the same ID do not advance distinct count |
| Minigame objective | Future authoritative completion event from an enabled minigame adapter; disabled while only the Coming Soon NPC exists |
| Aetherhaven or Eternia-specific objective | Optional namespaced adapter emitting a trusted event; no hard dependency or assumption that Aetherhaven runs on Eternia |

For group kills, choose a documented contribution rule (initially nearby contributing party members, capped by valid membership) so kill stealing and AFK tagging do not dominate progression. Deduplicate at the recipient/event level. Events credited to the active season stay assigned there after switching; do not replay historical events into every archived pass.

A generic cancellable block-break hook is too early to award XP by itself. The local source shows death systems for kill tracking and a harvest interaction whose success depends on `FarmingUtil.harvest`; the implementation spike must select post-success observation/adapters. Do not invent a universal `HarvestEvent` or award XP on raw inventory additions.

Reward claiming reserves a unique `(account, season, track, level, rewardIndex)` receipt and grants through the ownership service. Full inventory is a pending delivery, not a failed achievement. Quests use separate unique completion receipts so XP bonuses cannot replay.

### Keeping old passes completable

Pin released reward and objective versions. Never reuse a season or reward ID for another meaning. If a mob/resource is renamed, maintain aliases; if an activity is removed, supply an equivalent supported objective or an explicit completion credit migration. If an optional integration is missing, avoid assigning its objectives to new players and migrate already-assigned objectives without losing progress. A catalog reload cannot quietly remove claimed entitlements or strand an old pass.

## Tebex commerce

Use the official Tebex Hytale plugin for the in-game store/command integration and Tebex checkout for payment. The account website can direct players to hosted checkout first; a headless storefront is a later UI refinement. Verified provider behavior and sources are in [integration research](integration-research.md#tebex-plugin-and-website-store).

Initial product categories cover house styles, additions, props, palettes/path appearances, plot upgrades, personal/guild convenience teleporters, titles, supported skins/wearables, pets, move credits, paid pass access and monthly supporter. Use one representative product per behavior, not a large launch catalog.

### Fulfillment contract

- Map provider package/line IDs to versioned Eternia reward bundles on the server. Player-supplied content IDs, prices and browser redirects cannot authorize grants.
- Prefer plugin delivery as the sole grant path if it supplies sufficient immutable recipient and transaction identity. Use verified webhooks for status/reconciliation. If that release lacks those identities or durable acknowledgement, use verified webhook fulfillment as the sole grant source and the plugin for storefront/notifications. Select and document one path during P0; never grant independently from both.
- Normalize payment lines into a durable command with provider transaction, line/quantity, recipient UUID, product revision and status. Unknown recipient/mapping stays pending for repair, not guessed from a display name.
- Persist before acknowledgement. Apply grants once per purchased unit/line, preserving the provider transaction separately from individual webhook IDs. A failed game/database operation remains retryable.
- Reconcile provider state against Eternia delivery records and expose admin repair/replay by immutable transaction identity. Manual grants use separate audited receipt IDs.
- Display pending/delivered/reversed status in game and on the website. A buyer can be offline when ownership is granted; native item delivery waits safely.

### Refunds, cancellations and entitlements

A reversal affects its source grant, not every way the player could own that item. Keep previously placed content recoverable; do not demolish a paid plot or erase chest items. If a plot-size entitlement is reversed, freeze new use of excess space and offer a preserved recovery/resize flow according to the published product terms. Used consumables require an explicit support disposition; do not blindly run an inverse command that duplicates credits or makes balances negative.

Monthly cancellation is different from refund and end-of-term expiry. Preserve the paid interval and permanent gifts already earned under their terms. Record every status transition and make reconciliation safe to repeat. The integration research documents current policy considerations for functional conveniences and cosmetic compatibility; verify these when the actual products are published.

### Future premium currency

The first release uses direct money → content entitlements. This is a proposed launch assumption: the request's “Premium Currency Shop” may mean a wallet is wanted at launch, while pass currency rewards are explicitly future work. If a launch wallet is intended, move this bounded wallet package into P8; the entitlement architecture remains the same. Earned coins remain the player trading currency. When adding premium currency, implement a separate wallet/ledger, Tebex top-ups, catalog offers spending it, refunds, caps/formatting and season reward grants together. No cash-out, conversion to earned coins, player transfer, or premium-currency market listings are assumed. Never reuse an earned-coin column for paid value.

## Account website

Build a small responsive account portal with Eternia's own functionality and design, using the same tools as Aetherhaven's website: Node/Express, JavaScript ES modules, npm, plain HTML/CSS/browser JS, and Railway. See [website stack and setup](website-stack.md) for verified files, deployment and configuration. Core pages:

| Page | Minimum content |
| --- | --- |
| Home | Eternia overview, connect instructions, service status, store entry |
| Sign in | Hytale OAuth/OIDC using Eternia's new client application; private account access after verified profile-to-game-UUID mapping |
| Dashboard | Character name/UUID-linked account, current supporter rank and validity, selected title, play/activity stats, current pass summary |
| Collection / Housing | Owned unlocks, plot size/location status, house and guild summary, pending recovery/delivery notices |
| Seasons | Active/archived progress, free/paid reward ownership, paid upgrade link; server remains the progression authority |
| Store | Categories, previews, personal/guild recipient, ownership/duplicate checks, one-time versus monthly terms, Tebex checkout |
| Purchases | Pending/delivered/reversed transactions and a recovery/support reference |
| Account settings | Revoke website sessions, unlink/reverify identity where supported, privacy controls for public stats |

Initially keep mail contents and guild-sensitive records in game; the website shows counts/status where useful. Stats are typed aggregate counters (such as playtime, eligible mobs killed, resources gathered and pass completion), not a dump of the game database. Display last-updated time for projections. Public pages omit private messages, account links, purchase records and exact private house access data.

### Identity flow

Aetherhaven already implements the relevant website OAuth/OIDC flow. Use it as the setup reference, with an **independent Eternia OAuth Client Application** created by the owner after the new website URL exists:

1. Build and test the website skeleton locally first. Establish a provider-supported callback: use a permitted local callback if available, otherwise an approved HTTPS route to the local site or Railway staging. Production ultimately uses its own exact HTTPS origin and `/auth/callback`. Public pages and health checks work before OAuth is configured; sign-in shows a clear unavailable state outside the isolated local fixture harness.
2. The owner creates the new application and registers the exact callback URL once that website route is ready. Configure issuer, client ID, client secret, redirect URI and a separate Eternia session secret through private local environment settings, then Railway variables for deployment. Do not reuse Aetherhaven's client, sessions or secrets.
3. `/auth/login` starts authorization code flow with state and PKCE S256 through OIDC discovery. The inspected issuer is `https://connect.accounts.hytale.com`; the source requests `openid hytale:profile`. Resolve authorization/token/userinfo endpoints from discovery rather than copying guessed endpoint URLs.
4. `/auth/callback` validates one-use state and PKCE, exchanges the code on the backend, retrieves verified userinfo and opens a freshly regenerated secure, HttpOnly session. Use a maintained OIDC validation layer for issuer/token/nonce checks as applicable to the returned tokens; keep secrets and tokens out of browser bundles/logs. Add timeouts, CSRF protection and clear expired/denied-login handling.
5. Map the verified OAuth profile to Eternia's in-game account. Aetherhaven source distinguishes `profile.uuid` and `sub`; store issuer-qualified identity and profile UUID separately, verify their relationship against an authenticated game session, and choose a canonical game UUID. Do not accept arbitrary `X-Player-Uuid` headers or displayed names as dashboard identity. If an account has not joined the server yet, show a linked profile without inventing game stats; resolve first-join mapping through trusted identity data.
6. `/api/me` returns the minimum required account view. Logout revokes the session; identity/permission changes revalidate access. Persist sessions in the planned database so a Railway restart does not silently lose all account sessions.

If a supported identity cannot be mapped unambiguously, use a short-lived one-use in-game proof **only to resolve that mapping**, not as the default website sign-in replacement. Test with a fresh account, a renamed display name, an expired/denied callback, mismatched state and server restart before connecting purchases. The full deployment/env contract is in [website stack and setup](website-stack.md); the earlier server-provider authentication research is a separate protocol and does not replace this website client.

The API owns browser sessions and read projections, and can enqueue narrow authenticated commands such as selecting a season or creating a checkout. Only Eternia services may change game ownership, currency, membership or progress. Never expose arbitrary server command execution or direct database writes to the browser.

Guild purchases resolve a guild ID server-side and check the buyer's current authority before checkout; revalidate fulfillment target and retain paid guild ownership if the purchaser later leaves. If the target guild is disbanded/unavailable before delivery, hold the purchase for supported guild recovery or provider refund; never silently redirect it to the buyer or their new guild. Disable gifting for the first release unless a defined gift flow verifies and displays the recipient. Browser return pages may refresh fulfillment status but cannot assert payment success.

Deploy website/API on Railway with private service credentials, least-privilege database roles, HTTPS and compatible backup/recovery procedures. Aetherhaven's filesystem JSON/session storage is a reference, while Eternia's transactional MMO data requires the planned database. The game server's hosting is a separate decision: validate authenticated, routable TLS connectivity to the database or bridge and do not assume Railway-private hostnames work from an external game host. This plan does not publish or connect external services.
