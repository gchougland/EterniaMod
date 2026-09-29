# Eternia implementation and local setup

The plugin and website have separate responsibilities. The Java plugin owns accounts, property slots, guild membership, entitlements, money, inventory custody, and season progress. The Express website reads account data through the authenticated private bridge and owns its admin content revisions and rendering jobs. Browser requests cannot grant game items. Aetherhaven supplies reference techniques and the requested Prowl/viewer assets; Eternia has its own packages, data, identity, and Citadel interface.

## Run locally

For a populated in-game test experience, use **`/e playground`** as a WorldEditor after joining your local server. See the [manual playtesting route](manual-playground.md) and [Crown store guide](crown-store.md). It creates its own persistent example worlds, supplies and shops while retaining your current Hub, house and guild.

Requirements: the installed Hytale server/assets, Java 25, and Node 22 or later for the website. On this machine the bundled Node 24 runtime is available through `website/scripts/local.ps1`; the system Node 18 is too old.

```powershell
.\gradlew.bat --gradle-user-home C:/Users/gchou/.gradle test verifyReleaseJar --offline --no-daemon
.\gradlew.bat --gradle-user-home C:/Users/gchou/.gradle runServerLocal --offline --no-daemon
```

Connect in Hytale to **`127.0.0.1:5523`** (or `localhost:5523`). This task uses `run-local/`, explicit `ETERNIA_MODE=local`, a locked/fsynced local authority file, loopback game port 5523, and normal authenticated multiplayer. Server authentication already saved in `run-local/` is reused. It does not reuse `run/`, copy credentials, start a public listener, or run the old `syncAssets` finalizer. Never copy local fixture identities or authority files to production.

On a fresh directory, use `auth login device` in the server console and complete the displayed authorization flow. If prompted, use `auth select <number>` to select your profile, then `auth persistence Encrypted` to retain authentication for restarts. This is the game server's authentication, separate from the website OAuth application. Do not launch client playtests with `--auth-mode offline`: Hytale rejects offline multiplayer connections, even if the server has valid saved OAuth credentials.

For the original world, `runServer` and `runServerNoSync` still use `run/` and the normal game port (5520 by default). Connect with `localhost` or `127.0.0.1:5520`. These Gradle development tasks now supply `ETERNIA_MODE=local` when the environment does not specify a mode, so a database is not needed for ordinary playtesting. An explicit `ETERNIA_MODE=production` is respected and requires PostgreSQL. The shipped plugin's production default is unchanged. Authentication and world data belong to each run directory separately. Run only one of these servers at a time unless their other listeners are also configured on distinct ports.

All server tasks leave source resources unchanged when they stop. `runServerNoSync` remains a compatibility alias. `syncAssets` is available only as an explicit import of in-game edits from `build/resources/main` into `src/main/resources`; compare and preserve newer source edits before invoking it. Rebuild after changing source UI files so the server supplies the corrected resources on the next connection.

The repeatable headless native acceptance task uses offline authentication and creates a fresh directory under `build/native-smoke/`. It is not intended for connecting a game client:

```powershell
.\gradlew.bat --gradle-user-home C:/Users/gchou/.gradle runServerLocal -PnativeSmoke --offline --no-daemon
```

Its `ETERNIA_NATIVE_SMOKE_PASS` log marker is emitted only after real native claim, house/decorative-entity placement, palette edits, path creation/removal/reuse, packing, and restoration into a larger plot at a different elevation succeed. It checks stable entity identity, original custody, the single move credit, and applying/restoring the housing world policy. A timeout or failure shuts down with a failed test result. It is separate from the Hytale-free domain tests. Full player UI rendering, inventory handoff during real disconnects, guild eviction with multiple clients, and proximity fog appearance still require client acceptance testing.

For the website:

```powershell
.\website\scripts\local.ps1 -SyncAssets
```

See [website README](../../website/README.md) for fixture mode, renderer installation, native render smoke, and deployment. Local fixture sign-in is explicitly labeled and limited to loopback. Real OAuth is configured later with a new Eternia client and the website's `/auth/callback` URL; no Aetherhaven credentials are reused.

## Prepare the worlds

Builders configure the world in game with **`/e admin setup`**. Choose **Hub with housing**, set an arrival where you are standing, mark and register the public service plaza, and place the named service NPCs and world-select portal from **Hub services**. Only Prowl uses Prowl's model; the other five characters have their own race, costume, name and profession. Managed setup keeps their identity, interaction prompt and saved placement together.

Get the **Road Designer** item from World setup to shape public roads with spline nodes, width/material controls and a live grounded preview. Review and confirm with the held tool. Exact road columns register automatically. The rectangle selection remains a way to describe existing plazas or protect existing infrastructure, not the road construction workflow.

Use the same menu to register adventure destinations and safe arrivals. Housing can share the Hub. Worlds with housing enabled use Eternia's checked housing tools and disable ordinary hand placement/gathering and fluid/fire simulation. Removing that role restores saved prior world settings when an operator has not independently changed them.

Follow [world setup](world-setup.md), [managed NPCs and portals](managed-hub-services.md), [interface design](interface-design.md), and [customization](customization.md). For ready-made examples, use [the manual playground](manual-playground.md). No world, road, portal or NPC setup requires builders to edit JSON.

## Player flow

1. `/e menu` opens the Citadel service menu. Housing reissues the free plot deed and architect ledger without granting additional rights. Every new account receives one plot slot, one voluntary move credit, the starter house, a lamp, a porch, one palette, and one path style.
2. Use the deed in the housing world. Choose public/member/guild mode, preview the red/green grid, optionally use the overhead camera, and review then confirm. Claim rules run again at commit.
3. Stand in the plot and open `/e housing` or the architect ledger. Place the owned house, props/additions, palettes, and a path. The management block embedded in the house opens the ledger. Catalog structures are protected against harvesting for materials; use packing and customization actions.
4. The move action evacuates occupants to the configured hub, reserves a move credit, packs native content, and retains the same property slot. Restore from the deed; a larger owned size can contain the centered old contents. Failure retains snapshots and a recovery lock.
5. The mailbox handles offline messages and escrow attachments. The inventory desk deposits a held stack, stores it for mail/trade/listing, and claims durable deliveries into native inventory. The hub directory leads to the seller's house; checkout rechecks presence, stock, price revision, and funds.
6. Guild pages create/invite/manage members and roles. Guild-specific capabilities guard housing and shared conveniences. A claimed guild estate receives its starter hall, cobblestone style and one free voluntary move credit. Leaders can review purchased guild vouchers in the estate menu and confirm a donation to the named guild. `/e party create`, `invite <player>`, `accept <leader>`, `list`, and `leave` form activity groups of up to eight; invitations expire after 15 minutes. Party membership provides grouping and a live roster, not automatic shared combat rewards.
7. Season pages select one permanent pass to earn toward and claim free/paid rewards. Collection pages equip titles, an outfit/wearable, a following pet, and property pets when their content is owned.

The fog border is enabled by default, fades with viewer distance, and stays within one block including the particle envelope. It has no player toggle. The placement grid is a separate temporary visual. Login returns a player home only after **more than** 30 minutes away; shorter reconnects retain the native location.

[Guild communications](guild-communication.md) covers the persistent notice board, author edits, pinned announcements and `/e guildchat <message>`. [Community roads](guild-community-roads.md) covers straight, level road segments, member-owner easements, terrain restoration and recovery. Guild roads are protected infrastructure, never claim anchors. Remove an existing community road before claiming/restoring a plot across it; public-road overlap remains supported. Plot packing removes intersecting community road segments before preserving the personal house.

## Configure activities and purchases

`activity-xp.json` holds eligible adventure world names and exact native target-ID maps for `kills`, `mining`, and `harvesting`. Optional `activity-coins.json` has `KILL`, `MINE`, and `HARVEST` target-to-coin maps. Empty maps award nothing; an explicitly configured coin target may award coins with zero XP. Qualified awards are queued durably with their original amounts and stable receipts. Protect fresh natural-resource provenance; player-placed blocks do not earn mining XP or coins. Selected regrowing crops must use the `EterniaHarvestCrop` interaction. The sample permanent pass has 60,000 total XP and three 8,000-XP quests; five hours per week is a tuning target that needs measured gameplay rates.

The [discovery cache guide](discoveries.md) implements in-world housing tokens: place an authored cache, register its exact adventure-world position in `discoveries.json`, and choose a catalog reward. Each player can collect a stable cache ID once, including across restarts and world regeneration. Copying its physical block elsewhere grants nothing.

Product files live under data `Products/`. Use actual Tebex package IDs and immutable revisions, then map them in `tebex-packages.json`, for example `{"1234567":1}` after replacing the number with your package. Store presentation lives in `store-offers.json`: an array of `{id,revision,name,description,checkoutUrl}` matching registered products and HTTPS Tebex checkout pages. An empty file displays an honest unopened store.

Environment settings:

| Setting | Purpose |
|---|---|
| `ETERNIA_MODE` | `local` or `production`; production is the default |
| `ETERNIA_DATABASE_URL` | Production `jdbc:postgresql://host/database` |
| `ETERNIA_DATABASE_USER`, `ETERNIA_DATABASE_PASSWORD` | Separate game database credentials |
| `ETERNIA_BRIDGE_TOKEN` | At least 32 characters; must match website `GAME_BRIDGE_TOKEN` |
| `ETERNIA_BRIDGE_ADDRESS`, `ETERNIA_BRIDGE_PORT` | Private listener; defaults to `127.0.0.1:9010` |
| `ETERNIA_WEBSITE_URL` | Public HTTPS website, or loopback HTTP locally |
| `ETERNIA_TEBEX_WEBHOOK_SECRET` | Provider HMAC secret, held only by the game server |

Production startup requires PostgreSQL and never silently falls back to local or in-memory storage. The game migration and website migration own separate schemas. Publish the website on Railway only after setting its environment and persistent renderer storage. The raw `/webhooks/tebex` relay passes unchanged signed bytes to the private game endpoint. Both the signed callback and Tebex console delivery command must agree before anything is granted. See [commerce and activity details](commerce-and-activities.md).

Use the [PostgreSQL setup and operating plan](postgresql.md) to provision local game/web databases, run the real persistence tests, launch isolated database playtests and make backups without editing configuration files. Local mode also supports an explicitly configured PostgreSQL URL. File-backed saves are not automatically migrated.

## Operator tools and recovery

`/e admin status` lists configured content and unfinished operations. `/e admin inspect-recovery <operation UUID>` verifies snapshot hashes. `/e admin restore-source <operation UUID>` offers a confirmation to restore a supported interrupted source pack or roll back a customization. Other interrupted operation types remain locked until their documented recovery path is used; deleting authority records or issuing replacement tokens is not a recovery procedure. The old projection-only plot create/assign/remove commands are retired; they cannot bypass durable ownership.

`/e admin grant PLAYER:<uuid> <content-id> <UNLOCK|CAPABILITY|QUANTITY> <quantity> <receipt>` grants content for local tests or explicit support operations. `GUILD:<uuid>` grants to an existing guild. The receipt is durable, logged with the operator, and must be reused when retrying the same grant. A changed grant with the same receipt is rejected. These commands require Hytale's WorldEditor permission group. Never use an operator test grant as proof that a live Tebex payment was verified.

Read [native housing recovery](../native-housing-recovery.md) before testing interruptions. Existing prototype plots do not contain verified original terrain/provenance and are not automatically migrated by inventing it. Back up the original world and records before an explicit migration. Native component restrictions, ownership ambiguity inside mixed guild containers, and large guild snapshot cost require the stated checks before production rollout.

## Validation boundaries

The [local validation record](validation.md) lists the completed build, test, native-server and prefab-render checks, together with the remaining client and provider acceptance work.

Automated tests cover domain atomicity, replay and refunds, local restart persistence, permissions, plot graph/setbacks, border budgets, content schemas, skin preservation, snapshot tampering, parties, and bridge authorization. The optional PostgreSQL integration test reports skipped when an isolated database is absent. The website tests cover auth boundaries, CSRF, catalog revision import/export, render jobs, and raw webhook forwarding.

Native tests and renderer outputs are kept in ignored build/test-output directories. Actual client GUI interaction, final fog appearance/performance, a real new OAuth client, real Tebex test subscription/payment lifecycle, and Railway deployment remain distinct acceptance steps. Do not infer those have happened from passing fixture tests.
