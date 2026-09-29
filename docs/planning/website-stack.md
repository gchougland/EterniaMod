# Eternia website stack and deployment

Use Aetherhaven's website tools and deployment approach for a new Eternia website: JavaScript ES modules, Node.js, Express, npm with a committed lockfile, plain HTML/CSS/browser JavaScript, and a Railway service built with Nixpacks. Eternia gets its own pages, appearance, account features and commerce integrations. Reusing the stack does not mean reproducing Aetherhaven's community-prefab marketplace, moderation workflow, wiki, advertising or visual design.

Hytale login is already implemented in Aetherhaven and is the integration reference. Develop and test the website locally first, following [local development](local-development.md). The user will create a **new Hytale OAuth Client Application for Eternia after the website has an acceptable callback URL**. Prefer real OAuth locally if the provider permits the callback; otherwise use an approved HTTPS route or a small Railway staging deployment for that check. Login and account-dependent actions remain unavailable outside the isolated local fixture harness until configuration is complete and tested.

This document describes the planned Eternia implementation. The source audit below records what is present in Aetherhaven's local repository; it does not claim to have inspected live Railway settings or any secret values. No Aetherhaven credentials, sessions, account data or production configuration should be copied into Eternia.

## Verified Aetherhaven stack

| Layer | Source evidence | Eternia adaptation |
| --- | --- | --- |
| Language/runtime | [package.json][ah-package] declares `type: module`, Node `>=22.0.0`, and JavaScript entry point `server/index.js`. | Use JavaScript ES modules and Node. Pin the tested supported Node runtime during implementation rather than assuming the open-ended engine range identifies Aetherhaven's deployed version. |
| Web/API framework | [server/index.js][ah-server] imports Express, registers API/auth routes and serves `web/` using `express.static`. [package-lock.json][ah-lock] currently resolves Express 4.22.2. | One Express application serves Eternia's pages and same-origin API. Keep routes/services separated into modules as functionality grows. |
| Frontend | [web/index.html][ah-html], [web/app.js][ah-browser-js] and [web/styles.css][ah-css] are ordinary HTML, CSS and browser JavaScript. The package has no frontend compilation/build script. | Build Eternia's UI with those same tools. No React, Next.js, Vite or TypeScript migration is required for this plan. |
| Package manager/scripts | [package.json][ah-package] and [lockfile version 3][ah-lock]: `start` runs `node server/index.js`, `dev` runs `node --watch server/index.js`, and `test` runs `node --test`. The [README][ah-readme] documents npm installation. | Commit an independent Eternia `package-lock.json`; use `npm ci` for reproducible installation and the same start/dev/test conventions. |
| Railway build and start | [railway.toml][ah-railway] specifies `builder = "nixpacks"`, direct `node server/index.js` start, `/api/v1/health`, and a 100-second health-check timeout. | Use a separate Eternia Railway web service with the same build/start/health pattern and a service root pointing at the new website directory. Verify the selected builder/runtime during deployment. |
| Process/topology | [server/index.js][ah-server] reads `PORT`, defaults locally to 3847, trusts one reverse-proxy hop, and hosts API plus static site in one process. It handles SIGTERM/SIGINT with graceful server shutdown. | One web/API service and one listening port initially. Configure proxy trust for the actual Railway route, keep production cookies secure, and drain HTTP/database work on shutdown. |
| Application storage | [storage.js][ah-storage] persists manifests and submission metadata as JSON/files. It is not a PostgreSQL or SQLite implementation. | Eternia's planned shared PostgreSQL ownership/account ledger is a deliberate addition needed by its MMO transactions. Do not describe SQL as inherited Aetherhaven infrastructure. |
| Sessions | [sessionStore.js][ah-sessions] uses `express-session` plus `session-file-store` under `DATA_DIR/.sessions`, with 30-day rolling cookies, `httpOnly`, `sameSite=lax` and production `secure`. | Retain server-managed opaque sessions, but use durable PostgreSQL-backed session storage. Add and pin the SQL driver/session-store dependencies explicitly; Aetherhaven's current npm dependencies do not include them. Session duration is an Eternia policy to set intentionally. |
| Persistent files | [README][ah-readme] documents a Railway volume for viewer assets, including `/data/hytale-assets`. Session source also expects persistent `DATA_DIR` in production. | Provision a separate volume only for Eternia file data that requires one; store accounts, grants, transactions and sessions in PostgreSQL. Files written only to the service container are not durable storage. Do not reuse Aetherhaven's volume or data. |
| Login integration | [oauth.js][ah-oauth] performs provider discovery, authorization-code exchange with S256 PKCE and userinfo lookup; [server/index.js][ah-server] wires `/auth/login`, `/auth/callback`, `/auth/logout` and `/api/me`. | Adapt this established Hytale integration to the new client, server-side session model and verified Eternia account/profile mapping. Complete the identity and callback validation requirements in [player systems](player-systems.md). |
| Marketplace-specific libraries | [package.json][ah-package] includes `marked`, `multer`, `sharp` and `playwright-core`; [nixpacks.toml][ah-nixpacks] installs Chromium and native libraries for screenshots/image processing. [index.html][ah-html] imports Three.js for prefab viewing. | Do not install these features merely because they exist in Aetherhaven. The initial account/store site needs neither Chromium nor prefab-viewer assets. Add image/upload/3D dependencies only when an Eternia feature requires them. |

The Aetherhaven README references `docs/RailwayDeployment.md` and `docs/CommunityMarketplaceOAuthSetup.md`, but those files were not present in the inspected checkout. The linked source/configuration files above are the verified reference. No live service count, region, domain mapping, database or private network topology was inferred from those missing guides.

## Fresh website project

Create a new `website/` directory in EterniaMod during implementation:

```text
website/
  package.json
  package-lock.json
  railway.toml
  nixpacks.toml
  .env.example
  server/
    index.js
    config.js
    auth/
    routes/
    services/
    persistence/
  web/
    index.html
    account.html
    store.html
    seasons.html
    styles.css
    js/
    assets/
  test/
```

This structure is proposed, not already created. `.env.example` documents variable names with blank/example-only non-secret entries; real local `.env` files are ignored. Use Eternia's [Citadel theme](theme.md) and shared tokens for the website and native game UI, with flat geometric medieval artwork and consistent colors. Implement only the requested public information, account statistics/rank, owned content/pass views and Tebex purchase entry points, with guild views where authorized. The authoritative game/ownership services supply the displayed state.

The initial Railway configuration should preserve the known Aetherhaven pattern:

```toml
[build]
builder = "nixpacks"

[deploy]
startCommand = "node server/index.js"
healthcheckPath = "/api/v1/health"
healthcheckTimeout = 100
```

Set Railway's service root to `website/` and verify configuration discovery from that root. Use an appropriate pinned Node version and lockfile-driven install; no frontend transpilation step is needed. Start with a minimal Nixpacks configuration without Aetherhaven's Chromium, graphics and image-processing additions.

`/api/v1/health` reports minimal service health without exposing configuration values. Missing OAuth configuration must not crash the public-site deployment or make this endpoint fail. Distinguish public-site health from account/integration readiness: deployment can serve public pages before login is enabled, while readiness checks and account routes clearly report unavailable dependencies. Do not label an unconfigured login or disconnected account service as ready.

## Configuration contract

The following variable **names** are verified in Aetherhaven source. Their values for Eternia are supplied independently through local configuration or Railway Variables.

| Variable | Eternia purpose |
| --- | --- |
| `PORT` | Railway-provided listening port; use 3847 as the documented local default unless the project changes it. Bind to the service's accessible interface. |
| `NODE_ENV` | Explicit production/development behavior; production enables secure-cookie and strict-secret requirements. |
| `PUBLIC_BASE_URL` | Eternia's canonical external HTTPS origin. Set explicitly for the configured environment and derive only approved internal redirects from it. |
| `SESSION_SECRET` | Newly generated Eternia session-signing secret. Required for session-enabled deployment; never reuse Aetherhaven's value. |
| `HYTALE_OIDC_ISSUER` | Provider issuer. Aetherhaven source defaults to `https://connect.accounts.hytale.com`; validate discovery against the new client configuration during integration. |
| `HYTALE_OIDC_CLIENT_ID` | Identifier of the user's **new Eternia OAuth Client Application**. |
| `HYTALE_OIDC_CLIENT_SECRET` | The new application's server-side client secret. Keep it out of browser assets, URLs and logs. |
| `HYTALE_OIDC_REDIRECT_URI` | The exact registered Eternia callback, with path `/auth/callback`. Set explicitly when enabling the client and require it to match the registered URL. |
| `RAILWAY_PUBLIC_DOMAIN` | Railway-provided domain used by Aetherhaven as a fallback for its public origin; prefer explicit `PUBLIC_BASE_URL` once Eternia's stable origin is chosen. |
| `RAILWAY_ENVIRONMENT` | Railway-provided deployment context; Aetherhaven uses its presence when identifying production behavior. Do not treat its name alone as authorization. |
| `DATA_DIR` | Optional persistent file directory for supported Eternia file features. It does not point at the PostgreSQL database or an Aetherhaven data directory. |

Add `DATABASE_URL` as a **new Eternia configuration name** for the planned PostgreSQL integration, with a least-privilege database identity and validated TLS settings. The implementation also defines its own scoped game-service/bridge credentials and Tebex configuration; these are new integrations, not Aetherhaven marketplace admin API credentials. Supply secret values through the deployment secret mechanism and redact them from diagnostics.

Use the same variable names across staging and production with separate environment values. A staging login requires a callback actually registered for that environment. Do not assume arbitrary temporary Railway preview URLs are authorized OAuth callbacks, and do not reuse the Aetherhaven application's redirect registration.

## Service and persistence boundaries

The initial deployment consists of one Eternia Node/Express web/API service on Railway plus the planned PostgreSQL database service. A Railway persistent volume is optional for specific file artifacts; it is not the account ledger. Website sessions, account projections and operation receipts use appropriate durable tables and migrations. Reuse the ownership ledger's transactional boundaries from [the implementation plan](implementation-plan.md), with separate privileges for web reads, fulfillment and authoritative game mutations.

The Hytale server host is **not assumed to be Railway**. During integration, verify the actual routes between the game host, website/bridge and database. A host outside Railway cannot be assumed to resolve or reach Railway-private DNS. Use a routable authenticated TLS database endpoint or an authenticated HTTPS bridge as the agreed topology requires; exercise connectivity from the real game host and avoid exposing general administrator commands as the bridge API. Keep database credentials server-side and give each service only the permissions it needs.

The website must not read the game server's live JSON files or independently grant items by editing its filesystem. A successful web request initiates or reads durable operations through the planned service boundary. Account state remains traceable to the authoritative game and ownership records. If the game host is temporarily unavailable, show the projection's update time and queue eligible operations durably instead of fabricating completion.

PostgreSQL session storage makes sessions survive deployment and avoids importing Aetherhaven's file-store single-volume assumption. Start with one web instance; add replicas only after operation idempotency, session sharing and any file storage are proven. Back up PostgreSQL and any required file artifacts with a restore procedure appropriate to their shared records.

## Setup order and completion checks

1. **Prepare and test locally.** Create `website/`, independent package/lockfile and tests, public pages, the health route, local PostgreSQL/session adapters and environment validation. Run local browser/theme, account-fixture and payment-fixture checks. Prepare login/callback routes, but keep real sign-in and purchase actions gated until configuration is available. Public pages explain that account access is not yet enabled.
2. **Establish an acceptable callback origin.** Check whether the provider permits the selected localhost/loopback callback. If supported, use it for the first real-provider test; otherwise check an explicitly approved HTTPS route to the local site or deploy a small Railway staging preview. For Railway, configure the separate web service root/build/start/port, database and required files, independent secrets and HTTPS. Produce the exact callback URL for registration; arbitrary local/tunnel domains are not assumed supported. Routine feature tests remain local.
3. **User creates the new application.** Once that URL is ready, the user creates Eternia's Hytale OAuth Client Application and registers the exact callback and any required application metadata. This is the stated setup order; do not treat missing client credentials as a reason to leave the website preparation unfinished.
4. **Configure the new client.** Enter the new client ID/secret, issuer, canonical origin and redirect URI in the correct environment's private local settings or Railway Variables. Verify each local/staging/production callback registration. Never copy credentials or authorized redirect entries from Aetherhaven.
5. **Prove login before enabling it.** Test discovery, authorization-code/PKCE and state handling, callback validation, authoritative profile UUID mapping, session rotation, logout, canceled/failed login, invalid callbacks, expired state, reconnect and deployment session persistence. Ensure secrets/tokens do not reach client bundles or logs. A misconfigured environment continues to fail closed for account actions while public pages remain usable.
6. **Verify deployment and real integrations.** Once local checks pass, stage the same app on Railway if not already needed for login. Verify reverse-proxy cookies, the registered staging origin, real game-host/database/bridge TLS reachability, account projections and durable retry behavior. Exercise actual Tebex delivery identity/acknowledgement after local signature/replay/failure tests. Validate any guild purchase recipient against current guild authority. Confirm all displayed statistics, ranks and pass claims derive from authoritative records.
7. **Release with rollback prepared.** Run the Node test suite and relevant browser/integration checks, verify the Railway health check and graceful shutdown, rehearse database migrations/restore, and retain a deployable previous version. The production callback and client configuration must match the production origin before production login is enabled.

The result is an Eternia-specific account and store website built with Aetherhaven's established tools and Railway deployment method, with the explicitly planned PostgreSQL additions supporting MMO ownership and commerce requirements.

[ah-package]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/package.json
[ah-lock]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/package-lock.json
[ah-server]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/index.js
[ah-html]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/web/index.html
[ah-browser-js]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/web/app.js
[ah-css]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/web/styles.css
[ah-readme]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/README.md
[ah-railway]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/railway.toml
[ah-nixpacks]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/nixpacks.toml
[ah-storage]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/storage.js
[ah-sessions]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/sessionStore.js
[ah-oauth]: C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/oauth.js
