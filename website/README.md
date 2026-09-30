# Eternia website

Local Node/Express account pages and an administrator-only content workshop. JavaScript ES modules, npm lockfile, Citadel styling, PostgreSQL sessions/catalog/jobs, Hytale OIDC and a game-service HTTP adapter. No community uploads, marketplace moderation, or live purchases are included.

## Local start

Use Node 22 or newer (tested with Node 24). From this directory:

On this Windows workspace, the one-command setup/test/start flow is `powershell -ExecutionPolicy Bypass -File scripts/local.ps1 -SyncAssets`. It finds Node 22+ (including the bundled Codex Node when PATH still points at Node 18), installs locked dependencies, preserves an existing local environment file, generates the theme, synchronizes viewer assets, runs tests and starts the local site. Omit `-SyncAssets` after the first sync. To exercise the real browser and render pipeline against the running site, run `powershell -ExecutionPolicy Bypass -File scripts/local.ps1 -Smoke` in another terminal. Browser smoke writes synthetic admin revisions and outputs under ignored `test-output/`.

```powershell
npm ci
Copy-Item dev/local.env.example dev/local.env
npm run dev:fixtures
```

Open http://127.0.0.1:3847 and select **Use local test account**. This explicit mode binds to loopback, uses synthetic account data and an isolated file-backed authoring catalog, and rejects production startup. Its sessions are in memory and do not survive restart. Normal startup requires PostgreSQL and a new session secret; it never accepts player UUID headers as authentication. The local test-login route is not registered outside fixture mode.

Run `npm test`. `npm run sync:theme` regenerates web colors from the shared planning tokens. The local fixture catalog/jobs persist under `data/local-fixtures`; there is no game ownership write path in this site.

## Admin authoring and renders

Sign in as an administrator and open **Content workshop**. Select a definition JSON and, for a house/prop/addition, a native prefab JSON. Validate, import, select the immutable revision, and queue screenshot or transparent-icon PNG output. The example pair in `dev/chair.*.json` is a renderer demonstration, not launch content. Actual Eternia prefabs are under the parent mod's resource directory.

For native textures/models:

```powershell
npm run sync:hytale-assets
npm run renderer:install
```

The sync script reads local Hytale assets and Eternia resources, writes only a viewer copy, and never edits the original packs. Override input with `HYTALE_ASSETS_SRC` or `ETERNIA_ASSETS_SRC`; `OUT_DIR` selects output. Generated Common art remains ignored. Do not publish proprietary native assets as a public download; asset and viewer routes require admin authorization. `CHROMIUM_PATH` may point to an existing local Chrome/Chromium executable.

The renderer launches one isolated browser context per job, uses a short-lived internal header, and blocks all browser requests except the local renderer's required routes. It cannot fetch arbitrary URLs supplied in content. Jobs and output paths use server-generated UUIDs. Pending jobs resume after restart; interrupted jobs return to queued. Failed jobs expose Retry. Render errors remain failures, not successful empty placeholders. Imported entities still require native Hytale validation; browser rendering does not establish game placement or snapshot safety. Version import does not publish to game catalogs or grant ownership.

Run `powershell -ExecutionPolicy Bypass -File scripts/local.ps1 -NativeSmoke` against the running fixture website to import and render the actual `House.prefab.json` (729 blocks), `Potion Shelf.prefab.json` (three positioned item entities), and `Aqua Lamp.prefab.json` (nine blocks including native trapdoor states). It writes six PNGs, a workshop screenshot, a report, and a deterministic native catalog archive under ignored `test-output/`. Every run creates synthetic local authoring revisions; it does not change the game catalogs.

`python scripts/inspect-native-output.py` (Python with Pillow) checks the exported tar entries and both native indexes, compares all prefab JSON payloads against the mod source, verifies manifest hashes, and validates image sizes and transparent icon alpha. Export batches have a 64 MB prefab payload limit.

**Native catalog download.** Select a house, prop or addition revision and click **Download native catalog**. The CSRF-protected administrator API `POST /api/admin/exports/native` also accepts `{contentKeys:["revision-uuid", ...]}` for a bundle of up to 100 selected revisions. The `.tar.gz` contains game-compatible `Server/EterniaMod/Buildings` or `Props` definitions, stable `catalog.index` entries, prefab files under `Server/Prefabs/EterniaAuthored`, and an SHA-256 manifest. Identical revisions produce identical archive bytes. Merge the index entries into existing indexes and run the local native catalog checks before rebuilding; downloading is not publication.

The authoring definition must use `id: "eternia:house/<catalog_id>"`, `eternia:prop/<catalog_id>`, or `eternia:addition/<catalog_id>` (lowercase letters, digits, underscores or hyphens). Its `displayName` becomes the native literal label. Put native placement metadata in `native`: `plotAnchorOffset` (default `[0,0,0]`), `rotationYaw` (`None`, `Ninety`, `OneEighty`, `TwoSeventy`), and for houses the required `managementBlockLocalPos` and `spawnLocalPos` three-integer vectors plus `housingKind` (`personal` or `guild`). Additions map to the native Props catalog with `category: "addition"` and entitlement `eternia:prop/<catalog_id>`. Other authoring content kinds retain JSON export until their game-specific exporter is implemented. A manifest flag identifies prefabs containing entities that still need the native placement adapter to accept those components.

## PostgreSQL and production

For an isolated local PostgreSQL instance, use the repository's [database helper and guide](../docs/implementation/postgresql.md). `scripts/postgres-local.ps1 Web` from the repository root starts the actual database-backed site on port 3848; the existing fixture site remains on port 3847. Local non-production websites bind to loopback. Real sign-in still requires the new OAuth application.

Copy `.env.example` to ignored `.env`, configure a database URL and unique secret, then run `npm run db:migrate` and `npm start`. The migration owns **eternia_web** only: sessions, immutable content revisions and render-job metadata. Game/account/entitlement tables belong to the game service. Configure least-privilege database access and TLS appropriate to the deployed endpoint.

Set `ADMIN_HYTALE_UUIDS` to authorized Hytale game-profile UUIDs. They are compared against verified profile identity after OIDC validation. The new Eternia OAuth client uses `HYTALE_OIDC_ISSUER`, `HYTALE_OIDC_CLIENT_ID`, `HYTALE_OIDC_CLIENT_SECRET`, and the exact `HYTALE_OIDC_REDIRECT_URI`. Create the new application only after a supported local/tunnel or deployed callback URL is ready. Local callback support is not assumed. Missing OAuth credentials keep sign-in unavailable without inventing live account data. Login verifies state, PKCE, nonce, ID-token signature/issuer/audience and matching userinfo subject/profile UUID. Mutations require session CSRF tokens and same-origin requests.

Set `GAME_BRIDGE_URL` and `GAME_BRIDGE_TOKEN` for authenticated server-to-server reads:

- `GET /v1/players/{uuid}/overview` returns `{updatedAt,account,stats,owned,seasons,housing}`.
- `GET /v1/store/offers` returns `{offers:[{id,name,description,checkoutUrl}]}`.

Without a configured/reachable bridge, pages show an unavailable state. This website does not fake purchases, claim game rewards, or trust a browser checkout redirect. Store links are restricted to HTTPS Tebex domains. Game-side permission and recipient checks remain authoritative.

The store page is now the **Crown treasury**: players buy configured Crown packages on Tebex and spend their balance in Lyra's in-game Crown Store. Account and treasury views show the premium balance separately from earned trade coins. See [Crowns and product authoring](../docs/implementation/crown-store.md). Local `/e playground` allowances exercise real in-game purchases without a provider charge.

**Tebex ingress.** Register the public website route `/webhooks/tebex` as the provider webhook endpoint. It forwards the unchanged signed request bytes and `X-Signature` to `GAME_BRIDGE_URL/v1/commerce/tebex`, adding only the configured bridge bearer credential. The game verifies the Tebex HMAC and durable fulfillment rules; the website never holds the Tebex webhook secret or grants rewards. This public provider route is mounted before browser sessions and CSRF, limits bodies to 1 MB and concurrent requests to eight, times out the upstream after eight seconds, and returns only provider protocol fields. Missing bridge configuration returns retryable unavailability even in fixture mode. Use routable TLS for the deployed private bridge and keep its bearer token out of browser code. Local tests use signed synthetic fixtures and a local receiver, with no live payment.

Railway files select Nixpacks, a single Node service, `PORT`, and `/api/v1/health`. Set service root to `website/`. Chromium is now required for the requested admin rendering feature. Set `CHROMIUM_PATH` to its actual runtime location, or install Playwright Chromium during deployment and preserve its browser path. Mount separate persistent storage for `DATA_DIR/renders` and viewer assets; PostgreSQL metadata alone does not preserve PNG files. Run one renderer service instance until a distributed job claim/lease is implemented. No deployment was performed by these source changes.

## Provenance

`web/prefab-viewer/` and the asset-catalog sync algorithm adapt the user's Aetherhaven implementation. They preserve native block/model/rotation support without importing community submissions, user data, credentials, branding or layouts. Three.js is served from the pinned local npm package, so rendering needs no public CDN. The shared Citadel colors are generated from `docs/planning/theme-tokens.json`.

The website now self-hosts Google Fonts **Cinzel** and **Source Sans 3**, with their OFL files in `web/fonts`. Run `node scripts/theme-preview.js` for an isolated desktop/mobile style preview using the actual CSS and native patch images. See [Citadel styling](../docs/implementation/citadel-styling.md) for the game font limitation and regeneration instructions.

## Welcome page artwork

The public landing page uses the shared Citadel colors and locally hosted Cinzel / Source Sans 3 fonts. It introduces homes, guild neighborhoods, and permanent season passes, and labels the server as in development. It does not advertise an unconfigured game address or bypass Hytale login.

- `web/js/landing.js`: page sections and copy.
- `web/media/eternia-valley.svg`: original editable vector landscape. Houses, trees, and the hall are reusable SVG symbols. This is an illustration, not an in game screenshot.
- `web/icons/eternia.svg`: gateway emblem and favicon used by public and admin pages.
- `web/media/guild-build/`: 48 transparent WebP frames of the actual native guild hall assembling from foundation to roof, with its entrance toward the camera. The sequence totals about 1.9 MB. It loads only near its section, with four requests at a time. Reduced motion, data saving, and loading failures use the completed hall image.
- `web/js/guild-build.js`: scroll progress, reversible assembly, and completed view control. The public page does not need WebGL or download Hytale viewer assets.
- `web/styles.css`: responsive landing styles under the Eternia valley comment. Existing account and checkout layouts retain their own styles.

To refresh only the guild hall's native game artwork, run `node scripts/render-bundled-catalog.js --force --id=founders_hall` against a local fixture website with viewer assets installed. Set `TEST_BASE_URL` if it uses a different local port. The renderer uses the definition's `frontFacing` value, matching the admin viewer. Eternia Guild Hall is authored facing South; its in game default rotation faces the sample village.

Run `node scripts/bake-guild-build.js` against the same local fixture site to regenerate the website construction animation. This logs in to the local authoring fixture, loads the actual prefab through the native viewer, and captures block groups settling in height order. Frame 47 is also the still preview. This script does not publish content or require Hytale OAuth.

Run `node scripts/landing-smoke.js` with `TEST_BASE_URL` set to the running local site to check desktop, tablet, and phone layouts, image loading, navigation, and browser errors. Screenshots go to ignored `test-output/`. Run `node scripts/guild-build-smoke.js` for forward/reverse scrolling, the completed view control, reduced motion, and header emblem checks. The existing `browser-smoke.js` still checks the account pages and administrator prefab rendering.

Railway serves this artwork with the existing website deployment. No new service, secret, font provider, or OAuth claim is needed. These local edits reach the public site only after deploying the updated website code.
