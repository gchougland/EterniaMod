# Eternia website checkout and Crown delivery

The website uses Tebex Headless for its catalog and baskets, and Tebex.js for secure payment. Players browse on `https://eternia-hytale.com/store`; a separately designed Tebex storefront is unnecessary. A configured and approved Tebex project is still required. No payment card data enters Eternia.

## The four configured packages

These IDs and prices were read from Eternia's live public Headless catalog. The website always reads current prices from Tebex rather than hardcoding this table.

| Package | Package ID | Crowns delivered | Observed USD price |
| --- | --- | --- | --- |
| Pouch of Crowns | 7704919 | 500 | $5 |
| Satchel of Crowns | 7704922 | 1,100 | $10 |
| Chest of Crowns | 7704924 | 2,300 | $20 |
| Royal Treasury | 7704925 | 6,000 | $50 |

Website mappings live in `website/config/tebex-crowns.json`. Once published, a game product revision is immutable. To change a Crown amount, create a new revision and update the website mapping and server mapping together. Preserve old product definitions for existing receipts. Name and price come from the provider; the configured Crown total must match the game's active permanent currency benefit before checkout can begin.

## 1. Prepare the website on Railway

Deploy the updated `website/` application with its `config/` directory. Keep the existing PostgreSQL, session, Hytale OAuth, domain and renderer settings.

Add to the **website service**, not the PostgreSQL service:

```dotenv
TEBEX_PUBLIC_TOKEN=14mrr-8a755b8dabb29159454c076ab9d88208e0528bb6
TEBEX_CHECKOUT_ENABLED=false
TEBEX_CHECKOUT_ADMIN_ONLY=true
```

The public token is intentionally shareable. The catalog should appear at `/store` while payment buttons remain closed. `TEBEX_PRIVATE_KEY` is optional for the currently used Headless endpoints; if Tebex requires Basic authentication for your account, set that key only on Railway. Never put it in browser scripts. The Headless private key, game server key, and webhook signing secret are three different credentials.

Hytale OAuth must work before checkout. `PUBLIC_BASE_URL` should be `https://eternia-hytale.com`, with the approved callback `https://eternia-hytale.com/auth/callback`. Sign in through Hytale, and join the live game server once to create your game account. The UUID in `ADMIN_HYTALE_UUIDS` allows that profile to open `/admin/commerce`. This page shows package IDs, amounts and connection checks. Its status is not proof of successful payment delivery.

Use one website replica for now. Session state persists in PostgreSQL, while checkout preparation locks are process local. A timeout retries the same stored basket and inspects its packages before adding again. Do not scale checkout across multiple replicas until basket preparation has a shared lock.

## 2. Connect the website to Sparked Host

The PostgreSQL connection does not provide the website's game API connection. Ask Sparked Host for an **additional TCP port** for Eternia's HTTP bridge and an **HTTPS reverse proxy** to that port. Do not reuse Hytale's game port. The public endpoint must use HTTPS; do not expose bearer credentials over public HTTP.

On Sparked Host, set these environment variables or the matching entries in `mods/Hexvane_EterniaMod/eternia-server.properties`:

| Setting | Value |
| --- | --- |
| `ETERNIA_BRIDGE_ADDRESS` | `0.0.0.0` inside the hosting container |
| `ETERNIA_BRIDGE_PORT` | The additional TCP port Sparked actually allocates |
| `ETERNIA_BRIDGE_TOKEN` | A new random secret of at least 32 characters |
| `ETERNIA_WEBSITE_URL` | `https://eternia-hytale.com` |

In Railway's website service, set `GAME_BRIDGE_URL` to the actual HTTPS bridge address, with no `/v1` suffix, and set `GAME_BRIDGE_TOKEN` to exactly the same random secret. For example, `https://bridge.eternia-hytale.com` is a proposed hostname, not an address this implementation has provisioned. The proxy must preserve `Authorization`, `X-Signature`, request bodies and paths, and must not add browser login challenges to API requests.

## 3. Connect Tebex to the game server

Install the official Tebex Hytale plugin in the server's `mods/` directory and restart. In Tebex, open **Integrations → Game Servers**, create or select the Hytale game server, then copy its private server key. Enter in the **server console**:

```text
tebex secret YOUR_PRIVATE_GAME_SERVER_KEY
tebex info
```

Replace the placeholder privately in the console. This is not the Headless Public Token. [Official Hytale plugin setup](https://docs.tebex.io/creators/tebex-control-panel/game-servers/hytale)

For each of the four Crown packages, choose **Game Server Commands**, select that Hytale server, and add this initial console command exactly, without a leading slash:

```text
eternia-tebex {transaction} {packageId} {id} {purchaseQuantity}
```

The braces are Tebex variables; leave them unchanged. Set delivery to work while the player is offline because the command updates their stored account. These packages use **Only charge the customer once** and **Never remove the package**. No recurring or removal command is needed for Crown packs. Disable gifting for Crown packages and disable quantity changes for a consistent one-pack checkout. The website adds quantity one; the delivery code also safely handles a provider-confirmed purchase quantity.

## 4. Set up signed payment callbacks and the product files

In Tebex's webhook settings, use:

```text
https://eternia-hytale.com/webhooks/tebex
```

Copy the webhook signing secret into **Sparked Host** as `ETERNIA_TEBEX_WEBHOOK_SECRET`. This is neither the game server key nor the Headless private key. The website relays the unchanged signed request to the game server and does not need the signing secret.

Generate the ready-made files from the repository:

```powershell
cd website
npm run tebex:export
```

Output: `build/tebex-setup/` in the repository root.

1. Stop the game server and back up its current plugin data.
2. Configure the bridge token and webhook secret **before** copying nonempty package mappings. Startup deliberately rejects mapped purchases without those settings.
3. Upload the new `build/libs/EterniaMod-1.0.0.jar` to the server's `mods/` directory, replacing the previous Eternia jar. This build adds the private Crown delivery status endpoint used by the website.
4. Merge the four generated `Products/` files into `mods/Hexvane_EterniaMod/Products/`.
5. Merge the generated `tebex-packages.json` into `mods/Hexvane_EterniaMod/tebex-packages.json`. If the current file is `{}`, replace it. Preserve any other package mappings.
6. Restart the server, then retry Tebex's webhook validation. The endpoint echoes the signed validation event's ID.

Enable `payment.completed`, `payment.refunded`, `payment.dispute.opened` and `payment.dispute.lost` events. Subscription events are only needed when adding subscription products later. There are no `store-offers.json` changes required for this Headless Crown store; it uses active fulfillment mappings instead of static payment links.

## 5. Test without charging money

Keep the Tebex store private during testing, as Tebex requires for Test Mode. Turn on **Test Mode** under **Settings → Checkout** first. Keep Railway's `TEBEX_CHECKOUT_ADMIN_ONLY=true`, then set `TEBEX_CHECKOUT_ENABLED=true` and redeploy to allow checkout only for your configured administrator profiles. Do not open real payments until delivery has been verified. When testing is finished, turn off Tebex Test Mode before setting `TEBEX_CHECKOUT_ADMIN_ONLY=false` to open purchases to all signed-in players.

1. Join Eternia with your real Hytale profile, then sign in to the website with the same profile.
2. Check `/admin/commerce`: all four Crown deliveries should be configured.
3. Open `/store`, choose the smallest pack and complete a **Tebex Test Mode payment**.
4. Verify both the signed payment callback and the console command arrive. Each alone intentionally grants nothing.
5. Confirm the website and game balance each increase by 500. Refreshing the return page must not add more Crowns.
6. Retry provider delivery for that same transaction and confirm the balance stays unchanged. Confirm refund handling in the provider's supported test flow before real purchases.
7. Check the remaining three packages against their expected quantities. Clear or restore the isolated test account's test grants before using it for real play.

**Use Test Mode checkout, not Manual Payments, for the full test.** Tebex documents that Test Mode payments send webhooks, while Manual Payments do not. A manual payment can test console dispatch but cannot satisfy Eternia's two-part verification. [Tebex package testing](https://docs.tebex.io/creators/tebex-control-panel/how-to-create-packages/how-to-test-a-package)

For Hytale, confirm the signed webhook product's `username.id` and the console `{id}` both contain the real game UUID. The existing fulfillment adapter intentionally rejects numeric platform IDs. Never bypass that check with the browser's supplied username or basket custom data.

Local tests and preview commands:

```text
npm test
node scripts/tebex-preview.js
```

The browser preview intercepts checkout responses and uses a fake Tebex.js adapter. It never creates a real basket or makes a payment. `LOCAL_FIXTURES=true` can display the real public catalog when the public token is configured, but cannot open real checkout. For genuine provider testing locally, use real Hytale OAuth with the approved localhost callback, local PostgreSQL, and an HTTPS webhook tunnel into the local website; keep the test game, accounts and bridge separate from production.

## Implementation boundaries

The website creates baskets server side, with the verified Hytale session identity, buyer address, a fixed return URL, one allowlisted package and quantity one. Tebex may require an additional Hytale authorization step. The continuation has a session-bound nonce and rechecks the selected profile. Baskets are owned by the session and reused for retries for up to 30 minutes. Players can clear a finished or abandoned checkout and begin another.

Tebex.js uses the official `https://js.tebex.io/v/1.js` script, dark colors matching the site, and a user-initiated launch. An HTTPS provider checkout link remains available if the script is blocked. The payment-complete browser event and return query parameters display information only. They cannot credit Crowns. Fulfillment remains the existing durable join of the signed webhook and the matching console command, with replay protection and refund accounting.

The catalog is cached for 30 seconds; final basket prices come from Tebex. Provider requests have a timeout and a response size limit, and private keys never enter browser responses. The game endpoint `/v1/store/crowns` exposes only active mapped, one-off, permanent Crown products to the authenticated website bridge. No currency write endpoint has been added.

Public catalog lookup, local automated checks and browser previews are distinct from live acceptance. This implementation does not provision Sparked ports, deploy Railway, approve OAuth, activate the Tebex store or complete an authenticated provider test payment on your behalf.

Validated locally on September 29, 2026: all 224 game tests and 19 website tests passed with the isolated PostgreSQL databases, with no skipped tests. The release jar passed packaging checks and loaded in Hytale's startup validation with all four generated product files. The real public catalog returned all four expected packages. Desktop and mobile browser checks covered purchase review, mocked Tebex.js launch, checkout reset, blocked script fallback, closed purchases and admin package IDs. Actual Hytale authorization through Tebex and a complete provider Test Mode payment remain hosted acceptance steps.

References: [Headless basket creation](https://docs.tebex.io/developers/headless-api/guides/baskets/create-a-basket), [Tebex.js checkout](https://docs.tebex.io/developers/tebex.js/checkout), [fulfillment and refunds](commerce-and-activities.md).
