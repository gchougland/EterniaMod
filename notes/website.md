# Website (wave 1)

Branch `wave/website`. Live player count on the welcome page, "All sales are final" at checkout, `www.` redirect.

## SHARED EDITS

- `src/main/java/com/hexvane/eterniamod/runtime/GameBridgeServer.java` (owned for this endpoint): additive only.
  New field/setter `setOnlinePlayers(IntSupplier)` (default reads `Universe.get().getPlayerCount()`, confirmed in
  the installed 0.7.6 jar as `playersByUuid.size()`, safe off thread), and one route `GET /v1/status/players`
  returning `{source,online,updatedAt}` or 503 before the universe exists. No change to `EterniaRuntime` was needed.
- `src/test/java/com/hexvane/eterniamod/runtime/GameBridgeServerTest.java`: one new test method.
- No other shared Java files touched.

## CHANGELOG

- The website home page now shows how many adventurers are online right now.
- The Crown treasury now says clearly, before you pay, that Crowns are digital goods and all sales are final.
- The website now also works when you type www in front of the address (once the hosting step below is done).

## STATUS

- Welcome page: a small chip above the hero heading shows "42 adventurers online now" with a soft pulsing
  green dot (no pulse with reduced motion). Zero players reads "The realm is quiet. Be the first to arrive."
  An unreachable or unconfigured game reads "The realm is resting. Check back soon." with a grey dot. It
  refreshes every 30 seconds while the tab is visible, and right away when the tab comes back.
- Server: public `GET /api/v1/players-online` (mounted before sessions) returns `{status,online?,updatedAt}`.
  One shared cached answer: 30 s when online, 15 s when resting, concurrent requests coalesced, plus
  `Cache-Control: public, max-age=15`. Bridge errors never leak (no hostnames or messages).
- Game bridge: `GET /v1/status/players`, bearer protected like every other route, returns only a number.
- Checkout: the existing notice was rewritten and restyled (crown icon, gold edge): "All sales are final.
  Crowns are digital goods, so we cannot offer refunds except where the law requires it. Read the Tebex terms".
  Shown at the top of the treasury, above the package grid, and inside the purchase review right above
  "Continue to secure payment". Tebex terms link opens in a new tab.
- `www`: new `server/canonical-host.js` middleware, first in the app. A request whose Host is the `www.` twin
  of `PUBLIC_BASE_URL` (or the bare twin of a www base) gets 301 (GET/HEAD) or 308 (other methods) to
  `PUBLIC_BASE_URL` + same path and query. Other hosts (Railway health checks, IP addresses) are untouched.
  Before this, a `www` visit (if it reached the app) got a host only session cookie that the OAuth callback on
  the bare domain could not see, and every POST failed the origin check ("Unexpected request origin").
- Tests: website `npm test` 45 tests, 43 pass, 2 skipped (pre-existing Postgres skips), 6 new in
  `website/test/site-polish.test.js` (cache and coalescing, offline resting state and no leak, bearer and path to
  the game, wording incl. zero and singular, www redirect incl. method/path/health check, full app redirect
  before sessions). Java: `.\gradlew.bat --no-daemon build` green, `GameBridgeServerTest` 4/4 incl. the new one
  (401 without token, 503 before ready, exactly `source/online/updatedAt` keys, 405 on POST).
- Pictures in `notes/pictures/website/` (all looked at): `home-online-desktop.jpg`, `home-online-mobile.jpg`,
  `home-offline-desktop.jpg`, `store-sales-final.jpg`, `checkout-review-sales-final.jpg`,
  `checkout-review-mobile.jpg`. The checkout shots use stubbed package data in the local fixture site (fixture
  mode has no real packages); no real checkout or sign in was used. No horizontal scroll at 375 px.
- Local dev: `FIXTURE_PLAYERS_ONLINE` sets the fixture count (default 42; negative shows resting). Documented in
  `website/README.md`.

## DECISIONS

- Count comes from the game over the existing private bridge, never from the browser to the game. The website
  caches one shared value, so load on the game is at most one request per 15 to 30 s however many visitors.
- An unreachable game is shown as "resting", never as an error or as "0 online". A real zero gets its own warm line.
- The public JSON is always 200 so the page never has to tell errors apart; details stay in the server.
- `www` is handled by redirecting to the one canonical address instead of accepting both hosts, because sessions,
  the OAuth callback (`PUBLIC_BASE_URL/auth/callback`) and the CSRF origin check all require exactly one origin.
  No cookie `domain` change is needed after the redirect.
- The site is dark only (no light theme exists), so new pieces use the existing Citadel tokens only.

## REQUESTS

- **DNS and Railway (cannot be done in code), to fix www:**
  1. Railway, website service, Settings, Networking, Custom Domain: add `www.eternia-hytale.com` (keep
     `eternia-hytale.com`). Railway shows a CNAME target like `xxxx.up.railway.app`.
  2. At the DNS provider: add `CNAME  www  ->  <that Railway target>` (if behind Cloudflare, set it to DNS only
     at first so Railway can issue the certificate, then proxy if wanted with SSL mode Full). Remove any old
     A/AAAA/parking record for `www`.
  3. Wait for Railway to show the certificate as issued, then open `https://www.eternia-hytale.com/store`: it
     should land on `https://eternia-hytale.com/store`.
  4. Keep `PUBLIC_BASE_URL=https://eternia-hytale.com` (no www). The OAuth redirect URI stays
     `https://eternia-hytale.com/auth/callback`; no new OAuth or Tebex URL is needed.
- **Terms page:** the site has no rules or terms page of its own, so the notice links to the Tebex terms. If
  Eternia gets its own terms or server rules page, point the link there (one line in `web/js/treasury.js`,
  `purchaseNotice`).
- Deploy the website and the plugin together; until the plugin with `/v1/status/players` is live, the home
  page shows the resting state (harmless).
