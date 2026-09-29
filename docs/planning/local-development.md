# Local development and testing

> Implementation update: server shutdown no longer runs `syncAssets`. Both `runServer` and `runServerNoSync` retain source resources. `syncAssets` is a manual import only; the original planning notes below describe the earlier behavior.

Build and test Eternia locally for as much of the work as possible. The normal development loop is a local Hytale server/client, the Node/Express website, local PostgreSQL, and deterministic fixtures for account and payment integrations. Railway staging is a final check of hosting and real-provider behavior that local fixtures cannot establish; it is not required for routine feature development or art/UI iteration.

This is a plan for the development environment. The new website, Compose file, seed tools, fixture providers and scripts below are **not implemented yet**. Existing Gradle task definitions are identified separately. No containers, databases, servers, tunnels or purchases were started while writing this document.

## Local topology and prerequisites

Run the Java/Hytale server and Node website directly on the development machine, using the same JavaScript ES-module, Express, npm and plain browser-JavaScript stack described in [website stack](website-stack.md). Use a local PostgreSQL container for the shared ledger and durable sessions. A native local PostgreSQL installation is an equivalent alternative if Docker is unavailable; keep its major version, extensions and migrations aligned with the eventual staging database.

The existing [build.gradle.kts](../../build.gradle.kts) targets Java 25 and configures JUnit 5. Use the Gradle wrapper, a matching JDK, the pinned Hytale server/client and the normal legitimate local Hytale authentication setup. Local development does not imply disabling the game's authentication. Pin the website's Node runtime and npm lockfile before relying on reproducible integration results.

At planning time, the default `node` on this machine reported 18.18.0; Aetherhaven's website declares Node 22 or newer. Select a tested supported runtime for Eternia before installing/running the future website. No runtime upgrade was performed for this plan.

Initially use separate endpoints for each local component:

| Component | Proposed local endpoint/data |
| --- | --- |
| Website/API | `http://127.0.0.1:3847`; same-origin pages and APIs |
| PostgreSQL | Loopback port 54329 forwarded to the container's 5432; database `eternia_local` |
| Development identity/payment fixture services | Separate loopback-only ports allocated by the development runner |
| Hytale server | Dedicated local development server directory/worlds with explicit local service/database configuration |
| Snapshot/blob storage | Dedicated ignored local data directory; never Aetherhaven's or production's data |

Keep the browser hostname consistent when testing cookies and callbacks: `localhost` and `127.0.0.1` are different origins. The chosen real-provider callback must exactly match its registration. Local endpoints are development defaults, not public deployment addresses.

## Proposed PostgreSQL setup

Implement `dev/compose.local.yml`, `dev/.env.local.example` and an ignored `dev/.env.local`. Use a pinned PostgreSQL image compatible with staging; the example deliberately requires that choice instead of selecting an unverified image version:

```yaml
name: eternia-local
services:
  postgres:
    image: ${ETERNIA_POSTGRES_IMAGE:?Set the tested pinned PostgreSQL image}
    environment:
      POSTGRES_DB: eternia_local
      POSTGRES_USER: eternia_local_bootstrap
      POSTGRES_PASSWORD: ${ETERNIA_LOCAL_DB_PASSWORD:?Set a local-only password}
    ports:
      - "127.0.0.1:54329:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U eternia_local_bootstrap -d eternia_local"]
      interval: 5s
      timeout: 3s
      retries: 12
volumes:
  postgres-data:
```

The volume mount shown is illustrative: verify the selected image's documented data-directory layout, especially when choosing its major version, and update the Compose definition accordingly before running it. Bootstrap migrations create restricted application/session identities; the website and game service should not routinely use the bootstrap administrator. Production and staging do not use these local credentials.

After those files and tools are implemented, the intended commands from the repository root are:

```powershell
docker compose --env-file dev/.env.local -f dev/compose.local.yml up -d postgres
```

In the future `website/` directory:

```powershell
npm ci
npm run db:migrate
npm run db:seed:local
npm test
npm run dev
```

These npm commands describe the **target script contract**. No Eternia website `package.json` or migration/seed script is assumed to exist today. `dev` uses Node watch mode; migration scripts invoke the shared, versioned SQL migration contract rather than maintaining a second incompatible website schema. The seed command checks a local environment/database marker before writing and is never part of production startup.

The fixture process can be started with a proposed `npm run dev:fixtures` script in another terminal. Stop local services normally while preserving the database volume between sessions. Resetting a test dataset should use a scoped local reset/reseed tool that verifies its target, not a command copied from production administration. A database reset must also reconcile local snapshot/blob fixtures so references remain valid.

## Hytale and art iteration

The existing Gradle configuration registers `runServerNoSync` when the Hytale plugin exposes a compatible Java execution task. It mirrors that task without its post-exit resource-copy hook. Confirm it is available on the pinned plugin setup, then use it for local iteration:

```powershell
.\gradlew.bat test
.\gradlew.bat runServerNoSync
```

These Gradle task names come from current configuration; their presence does not mean a useful feature test suite already exists or has passed. `runServerNoSync` inherits the ordinary server's working directory and arguments, so **it is not automatically an isolated test server**. Configure and verify a dedicated development server directory, development worlds, local database identity and snapshot path before starting it. The regular `runServer` task finalizes with `syncAssets`, which can copy build resources over files in `src/main/resources` when the server stops. Avoid that hook while editing source art or while another task edits assets.

Use the actual local Hytale client for behavior that screenshots or Java tests cannot prove:

- Prowl model, texture and parent animations; stationary NPC interactions and readable dialogue.
- Claim terrain grid, valid/invalid colors and reasons, five-block setbacks, road columns, quarter-turns and large guild footprints.
- Default-on wispy border fog: proximity fade, one-block maximum visible height including sprite size, slope following, road/portal gaps, adjacent seams, particle load and cleanup. Verify separately from toggleable admin outlines.
- Bird's-eye pan/zoom, control hints, and camera/preview cleanup on close, cancellation, world transfer and disconnect.
- House/porch/basement fitting, terrain carving, palette regions, roads, doors, beds, furniture and block-entity state.
- Placement, pickup and whole-property restoration across chunk boundaries, with containers and player/guild ownership intact.
- In-game management pages, housing inventory, mail/shop pages, titles, supported wearables and one follower versus property pets.

Use small representative prefabs and disposable authoring worlds. Preview website artwork in the local browser and Hytale art in the native local client; website styling is not evidence that a Hytale UI asset renders correctly. Check both surfaces against the same planned Eternia theme, at realistic screen sizes and text lengths. Local validation should add a second item of an existing content kind without Java edits, following [content authoring](content-authoring.md).

## Account fixtures without a shipping login bypass

Seed named, synthetic local accounts and states: a new player, an established player, a supporter, a guild leader/member/former member, a seller, an offline buyer, and an account with a partially completed archived pass. Use deterministic fixture IDs, ownership receipts and dates. Keep these records in the marked local database; do not import real player purchases or Aetherhaven account/session files.

Provide a **development-only identity provider/test harness**, rather than a public `login-as-user` route. It should exercise the normal website login/callback/session path with synthetic discovery, authorization, token and userinfo responses, including bad-state and expired-code cases. The harness uses independent local signing material and binds only to loopback. It establishes test identities, not proof of actual Hytale ownership.

Production startup must reject development-auth configuration and unapproved test issuers. Fixture servers/seed/reset tools belong outside the deployed server entry point and are excluded from the production deployment artifact. Test this boundary explicitly: a production-mode run with a fixture-provider setting must fail closed, and supplying a UUID/header/query parameter must never create a signed-in account. Do not ship a hidden bypass that becomes active through a request parameter or permissive environment fallback.

Session and authorization tests should cover login/logout, session rotation and expiry, rejected CSRF/callback state, malicious redirects, missing profile UUID, switched account identity, admin/guild permission changes, unauthenticated requests and replayed callbacks. Use the same database-backed session implementation intended for deployment. Distinguish a locally simulated successful login from a successful real Hytale login in test reports.

## Real Hytale OAuth while keeping work local

First build the local website skeleton and its callback route. The user then creates the **new Eternia Hytale OAuth Client Application** once an acceptable callback URL is available; Aetherhaven's client credentials and callback registration are not reused.

Check the provider's current registration rules before choosing the callback:

1. If the provider permits the chosen localhost/loopback callback for this application type, register that exact local URL and test against the real provider from the local website.
2. If a public HTTPS callback is required, optionally expose **only the local website** through a short-lived HTTPS tunnel and register its exact callback. Confirm that this provider and application accept the resulting domain; arbitrary tunnel or loopback callbacks are **not verified as supported by this plan**. The database, Hytale administration ports and fixture-provider endpoints stay private. Configure the public origin/proxy/cookies for the tunnel deliberately, then remove the tunnel registration when it is no longer needed.
3. If an acceptable local/tunnel callback cannot be registered, use a small Railway staging deployment for this final real-login check. Continue all independent account-page and authorization testing locally in the meantime.

Until the new client is configured, the normal website authentication path stays visibly unavailable outside the explicit local fixture harness. A local HTTPS proxy can test secure cookies and reverse-proxy behavior without claiming the Hytale provider accepts its certificate/domain. Production ultimately requires the actual production callback to be registered and exercised separately.

## Tebex fixtures and provider checks

Use synthetic purchases and locally signed webhook fixtures. Implement the signature computation and raw-body verification required by the **verified Tebex contract**, with a local-only fixture secret. Route fixtures through the same parser, signature verifier, normalization, fulfillment ledger and delivery service as production. Do not skip verification because the sender is local. Test malformed bodies, an incorrect signature, a correctly signed but unauthorized package, duplicate events, reordered events and multiple quantity units.

For the official plugin command path, exercise the local adapter with synthetic immutable purchase-line and recipient identities and test its acknowledgement/retry behavior. A mock demonstrates Eternia's handling, not that the real plugin supplies those fields. Keep the sole-grant-source/reconciliation rules in [player systems](player-systems.md#fulfillment-contract): command and webhook simulations must converge on one receipt ledger and cannot independently grant the same purchase.

The local checkout fixture represents pending/completed/canceled payments and receipts. It accepts no card data, invokes no live checkout, charges no money and sends no real player notifications. Fixtures need no production Tebex secret. Startup prevents fixture payment settings from being enabled in production.

After local fulfillment passes, run the provider's supported test delivery and the real supported Hytale plugin against a designated test account/environment. Verify actual event shape/signature, command placeholders, recipient identity, receipt acknowledgement, retries, provider timeouts and callback reachability. Use provider-supported test methods where available; do not claim all subscription/refund states have a sandbox or make a live purchase merely to finish a test. Record any state that cannot be exercised with a safe provider test as a remaining integration check. A browser success redirect is never grant authority.

## Testing ladder

| Stage | Run locally | Evidence before advancing |
| --- | --- | --- |
| 1. Domain and content | Java/JUnit rules, Node's test runner, catalog/schema checks and deterministic clock/event fixtures | Geometry and permissions, content references, reward keys and XP rules behave correctly without a Hytale session or provider account. |
| 2. Database integration | Actual local PostgreSQL, shared migrations, reservations/constraints, seed data and session store | Transactions and retry receipts survive rollback/restart; migrations are repeatable as designed; web and game services agree on the schema. |
| 3. Website | Local browser against real Express routes and local account/payment fixtures | Pages, accessibility, ownership/recipient presentation, stale/loading/error states and authorization work; no mock bypass reaches the production entry point. |
| 4. Native gameplay | Local Hytale client/server with representative content and database | UI/art, inventory custody, placement/camera, real event attribution, travel, pets and saved world state are validated in engine. |
| 5. Recovery and contention | Restart local services and inject failures at operation boundaries | The matrix below passes without duplicated rewards/items, vanished property or premature success. |
| 6. Real integrations | Real new OAuth client where callback rules permit local testing; supported Tebex/plugin test methods | Actual provider/adapter semantics are confirmed, and unexercised residual cases are recorded honestly. |
| 7. Deployment parity | Railway staging only after the preceding local checks | Nixpacks/runtime, TLS/proxy/cookies, health/start/shutdown, actual host connectivity, migrations and production-origin provider callbacks work. |

Do not rerun every layer for every cosmetic edit. Run checks appropriate to the changed behavior and broaden when failures or unresolved concerns justify it. For release artifacts, run the existing `verifyReleaseJar` task and check that it actually performed its inspection; the current task can log a skipped content check if the JAR tool is unavailable.

## Failure, restart and contention matrix

Use injectable clocks and fault points to exercise elapsed time and crash windows quickly, then perform a few real process-restart checks against the persisted state.

| Scenario | Local test | Required invariant |
| --- | --- | --- |
| Claim race | Two players confirm overlapping previews concurrently; disconnect one during commit | At most one legal claim; no lost token/eligibility; stale UI is revalidated. |
| Move/pack crash | Fail after reservation, durable snapshot, each world-edit phase, metadata commit and delivery | One recoverable property; source/destination contents and move credit settle once; no success before verified completion. |
| Guild departure | Advance to just before/at/after 48 hours, restart worker, rejoin or change permissions concurrently | Deadline and cancellation rules persist; player property returns once; guild-owned assets return to guild inventory. |
| Trade/mail crash | Concurrent purchases, full inventory, offline recipient, crash after debit and before delivery | Escrow/payment/item custody remains balanced; mailbox retry does not clone attachments or credit. |
| Pass/reward replay | Replay kill/harvest and claim events; change active season mid-flight; restart after grant | XP goes to its assigned season once; reward slot and paid entitlement are checked; old progress remains available. |
| Tebex delivery | Duplicate and reordered purchase/subscription/reversal events, timeout and retry | One grant per economic purchase unit; retries retain the promised offer revision; unrelated ownership remains intact. |
| Logout/home routing | Simulate short and greater-than-30-minute absences, missing/packed house, blocked spawn | Correct reconnect/home decision with a safe fallback, no teleport loop. |
| Database/bridge outage | Stop PostgreSQL or the local bridge while web/game remains open, then restore it | World ticks stay responsive; unauthorized/offline actions fail or queue durably; no fabricated completion. |
| Asset/chunk failure | Missing prefab revision, unavailable chunk, unsupported entity state, corrupted snapshot | Operation remains recoverable and explains failure; no silent partial placement or discarded content. |
| Restarted sessions/pets | Restart website/game, transfer worlds, reconnect twice | Sessions follow policy; one follower/owned pet instance remains; previews/cameras do not leak. |
| Restore drill | Restore a local database backup with its matching snapshot/blob set | IDs, ownership counts and references agree; replayed recovery jobs neither duplicate nor lose records. |

## Data separation and final staging scope

Use distinct local, staging and production databases, service identities, signing keys, OAuth configuration, webhook secrets and snapshot paths. Display the current environment prominently in local/admin tooling. Seed/reset/fault-injection scripts must check their target environment and local-data marker before mutation. Never select an environment by matching only a convenient database name or by accepting a browser-supplied flag.

Local backups and fixtures belong to the local project and contain synthetic data. Keep authentication values and tokens out of source, fixture recordings and logs. If an integration response is saved as a fixture, redact it and replace identity/transaction material with synthetic data while preserving the fields under test.

Railway staging remains necessary for the differences that local tests cannot establish: the actual Nixpacks build and runtime, service `PORT`/health checks, deployment signals, HTTPS/proxy and secure cookies, externally reachable OAuth/webhook callbacks, managed PostgreSQL TLS/permissions and route availability from the **real Hytale server host**. That host is not assumed to run on Railway or reach Railway-private DNS. Recheck these deployment boundaries with the already locally tested application, then release; do not rebuild the feature-development workflow around hosted staging.
