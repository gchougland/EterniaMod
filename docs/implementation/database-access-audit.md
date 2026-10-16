# Database access audit

Audited 6 October 2026. Covers the game plugin and website, including direct SQL, every domain service, native adapters, previews, menus, scheduled jobs, authentication, commerce and fixtures.

## Rules for future changes

* Use `EterniaServices.batch` for related domain calls. Do not open a transaction around terrain edits, file access, entity operations, HTTP calls or packet delivery. Capture the native metadata first, load a detached domain projection, then perform native work.
* All domain transactions now memoize `find`, `findAll` and `scan`, including missing records. Saves update that transaction's memoized results. A namespace scanned once can satisfy subsequent key reads without another SQL query.
* Use `readView` only for immutable display projections or an advisory preview. Include the actor and every input affecting the result in a private record key. Never return live entities, mutable collections, or transaction objects.
* Read views share concurrent loads, retain at most 512 entries, and have a maximum permitted lifetime of 30 seconds. Actual game lifetimes are listed below. Dependency namespaces are tracked automatically; a committed local write invalidates every view that read that namespace. Failed transactions do not invalidate or publish data. Failed loads are not cached. A load overlapping another local commit is not retained.
* Cached callbacks cannot write. Inside an authoritative batch, `readView` bypasses the cross-request cache, so a mutation never receives a cached projection from that API.
* Final purchases, guild permissions, transfers, claims, leases, inventory receipts and recovery steps still use fresh records. Do not replace these with a display cache. Read views are process-local; another game process's writes and time-based expiry become visible on the next load after the TTL. This deployment remains a single game authority.
* Do not remove the native inventory and housing journal checkpoints to reduce query counts. Their commits separate persisted intent, filesystem evidence, world mutation and acknowledgement. Combining across those boundaries would break crash recovery.

## Read lifetime and batching map

| Path | Data strategy | Freshness at action |
| --- | --- | --- |
| Plot deed grid | One account eligibility batch on the menu worker; reuse while moving; refresh every 10 seconds and on Review | Confirmation loads eligibility again and reserves the actual coordinates |
| House and prop placement pages | Advisory access checks cached 5 seconds; native geometry still checked; candidate search reuses access | Commit uses the uncached validators, then fresh batched placement authorization |
| Packaging wand overlay | 5 second access and provenance projections per actor/property; one instance scan instead of a transaction per decoration | Actual packing validates current custody and native snapshots |
| Housing inventory | 2 second house ownership and decoration stock projections; one ownership scan per load | Selected item and placement permissions rechecked |
| Main menu Collection and player shop directory | 2 second projections keyed by actor and complete request | Equip, adoption, shop travel and buying validate current records |
| Crown Store | 2 second actor catalog/balance/ownership snapshot; shared ownership scan | Checkout checks current price, revision, ownership and funds |
| Menu wallet | 1 second immutable Coins/Crowns pair, loaded on service workers | Never used as permission to spend |
| Guild board, guild management, mail and trading desk | One fresh batch per page; account names prefetched for boards, roster, inbox and shop directory | Guild privacy and trade/mail state remain fresh |
| Quest journal and passes | Fresh batched page reads and bulk reward receipt reads | Quest getters can backfill owed Coins, so they are deliberately not cached as read-only views; UTC resets remain fresh |
| My plots, travel and recovery menus | Group slot/location and related data; fresh authorization | Recheck location and access before native operations |
| Collection renderer | One batched 10 second projection for online actors, housing, catalog and pets; relevant local writes invalidate it | Expired/revoked selections and pets are filtered; cleanup runs only when that projection finds something to repair. No database poll when nobody is online |
| Pet motion, mount motion, borders and portal visual ticks | Native/in-memory state only | Mount summon batches fresh catalog and ownership reads |
| Road shovel live preview | Native/in-memory geometry; no database check per node movement | Full review and confirmation batch durable claims |
| Guild road review | One grouped domain snapshot before snapshot-file and terrain checks; style ownership grouped | Current membership, easements, custody and operation locks remain authoritative |
| Hub build protection, infrastructure, paving, NPC and portal placement | One property directory read instead of slot plus location per claim | Reserved claims and native service footprints still checked |
| Login provisioning | All starter grants and season selection in one transaction | Idempotent receipts and first-login Coin gift remain durable |
| Account heartbeat | Up to 64 accounts per transaction, with bulk account reads, every 30 seconds | Login/disconnect timestamps still persisted; no deferred currency writes |
| Activity outbox | Empty-result cache 10 seconds, invalidated by new events; up to four atomic batches of 25 per worker pass | Activity source receipts, anti-farming markers, XP and Coins remain durable and idempotent |
| Quest notifications | 5 second pending-notice projection keyed by online actors; reward writes invalidate it; acknowledgement grouped after sending | Failed acknowledgements retry without deliberately re-sending an already-sent packet in this process |
| Tebex delivery | 10 second pending-work cache with write invalidation; batches of 25; bulk receipt lookups; one product read per signed event | Signatures, matching console receipts, reversals, subscription dates and financial receipts remain fresh |
| Guild departure worker | Existing 60 second due-job poll and fresh leases | Real-time deadlines, membership and evacuation/packing recovery cannot use stale leases |
| Private website account bridge | 2 second actor projection and one domain transaction, serialized before HTTP send | Bridge token checked on every request; browser never chooses authority |
| Bridge Crown package catalog | 10 second product projection | Actual fulfillment checks the mapped product revision again |
| Website public page shells, art, CSS, scripts, fonts and health | Served before session middleware; no session SQL for these requests | Account/admin/API routes still require authentication |
| Website content repository | 5 second coalesced immutable-document cache, invalidated on successful local imports, cloned per caller; 128 entries and 16 MiB cap | PostgreSQL uniqueness remains the final guard across website replicas |
| Website sessions and render jobs | Sessions remain in PostgreSQL; queue admission, retry and job-state reads remain fresh | No stale login/admin/session or queue-capacity decisions |
| Startup content registration | Parse files first, register up to 25 definitions per transaction | Immutable definition checks preserved |

## Complete authority inventory

Every game `store.transaction` entry routes through `BatchedStore`, including services that do not need an additional cross-request cache. The reviewed services are:

| Services | Reason to keep authoritative calls fresh |
| --- | --- |
| AccountService, CurrencyGrantService, EconomyService, PremiumService | Identity, first-login gifts, balances, receipts, spending and reversals |
| ActivitySourceService, SeasonService, DiscoveryService | Verified gameplay provenance, replay protection, UTC quest cycles and earned rewards |
| OwnershipService, CollectionService, ProvenanceService | Quantity reservations, grant expiry/revocation, pet selection and instance custody |
| HousingService, JournalService | Claim uniqueness, relocation state, durable operation checkpoints and recovery locks |
| GuildService, GuildBenefitService, GuildCommunicationService, PartyService | Membership, permission changes, departures, shared benefits, private posts and party capacity |
| EscrowService, MailService, MarketService, TradeService | Native delivery checkpoints, mailbox capacity, listing stock, trade confirmations and atomic transfers |
| CommerceService, CommerceIngressService | Verified payment delivery, refund tombstones, subscription controls and duplicate suppression |

Direct database access is restricted to `JdbcStore`, schema migration, the website repository and the PostgreSQL session adapter. `DriverDataSource` and `DatabasePool` establish/reuse connections; they do not bypass domain services. The game pool retains its four-connection maximum and the website repository its six-connection maximum. Connection validation/keepalive and one-time migrations are intentional database traffic.

Native adapters also reviewed: `HousingProtection`, `HousingUseCapabilities`, `HubBuildMode`, `NativeItemEscrow`, `NativePlacementTransactions`, `NativeRelocationCoordinator`, `FailedPropRecovery`, `VoluntaryPlotMover`, `HousingAccess`, `HousingCustody`, `CustomizationService`, `GuildWaygate`, `NativeShopTravel`, `NativeTravelService`, `GuildDepartureWorker`, setup/paving and spline road services, discovery/activity event adapters, admin/currency/party/guild-chat commands, local playground and native smoke fixtures. Native custody, recovery, permission-sensitive commands, database migrations and fixtures use fresh transactions. Fixture setup is event-driven, not a recurring production poll. Guild chat loads authorized recipients in one fresh batch before sending network messages.

## Verification and limits

`ReadViewTest` covers reuse of empty results, dependency invalidation, rollback, failed load recovery, read-only enforcement, mutation bypass, actor keys, TTL, concurrent load sharing and eviction. `DatabaseWorkloadTest` checks that 100 idle activity polls use one transaction, a new event invalidates the result, a 20-event drain uses one transaction and awards Coins exactly once, and cosmetic revocations invalidate cached pets and wardrobe. Existing shop counting tests verify that forty offers load with one transaction and two namespace scans. Claim eligibility tests cover one load reused across one hundred candidate positions and fresh commit checks.

Verified locally: **308 Java tests passed, zero skipped**, including PostgreSQL integration tests; **22 website tests passed, zero skipped**, including PostgreSQL sessions and content persistence. Release jar verification passed. The complete disposable native Hytale smoke run also passed: housing policy, claims, house/palette/path/props, packing, elevation restore, mixed player/guild custody, roads, managed NPCs/portals, example village, crop regrowth and Prowl bounds. This is native server verification, not a manual client visual playtest.

Website cache tests cover coalescing, cloning, expiry, invalidation during an in-flight load, failed load retry and memory limits. PostgreSQL integration tests cover real persistence, rollback, concurrent money/custody operations, content uniqueness and session permissions.

This change reduces repeated database work; it does not promise a measured production latency improvement. Cold reads and authoritative saves still contact PostgreSQL. Hub block-use protection and admin building checks intentionally retain fresh authorization; property-directory reads replace per-plot location lookups. The game schema still stores encoded records and some operations scan a namespace. The shared database advisory lock remains in place. Moving to indexed relational queries or finer-grained locks is separate work requiring migrations and concurrency coverage. Native terrain capture, prefab validation, filesystem flushes and chunk loading can also take time and are not database caches.

To deploy, stop the game server, replace its plugin jar, and restart. Deploy the website changes through Railway as well. No new database schema, environment variables or manual cache clearing are required. Do not clear housing data or reset worlds. Check menu navigation, moving a claim grid, buying an item, quest completion, packing a decoration and changing a guild role on the deployed server to measure the remaining latency over its actual Railway connection.
