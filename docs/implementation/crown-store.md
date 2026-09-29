# Crowns and the in-game store

Crowns are Eternia's non-transferable premium currency. Coins remain the separate earned currency used for player shops and item trades. Talk to **Lyra Starweave, Royal Quartermaster**, or choose **Crown Store** in the Eternia menu to browse categories, review an item and confirm its price. The balance and delivery update together. Purchased props appear in housing inventory, cosmetics and pets in Collection, and pass access on the corresponding season.

The bundled Crown catalog contains a small working assortment of furnishings, a pet, an outfit, a title, plot and travel upgrades, a moving charter, a paid pass track and a guild waygate charter. Prices are initial development values. Nothing in this catalog enables a real-money charge by itself.

## Try it locally

On a server running with `ETERNIA_MODE=local`, a WorldEditor can use `/e playground` and collect **Starter supplies**. This issues 5,000 example Crowns once to that profile. **More supplies** explicitly issues another local allowance after confirmation. These are marked local fixture credits, not fabricated Tebex payments. Production rejects the playground command's actions.

Try buying a decoration twice, an outfit once, a pet for following, a second pet for a property, the Foundations paid track, and a guild charter. An already-owned permanent unlock cannot be bought again. Quantity items remain purchasable. A guild leader redeems a purchased charter through the guild estate's upgrade menu; the guild keeps the resulting convenience if that leader later leaves.

## Connect the website treasury

Use the existing signed Tebex callback plus console receipt integration in [commerce and activities](commerce-and-activities.md). Define real Tebex Crown packages in plugin-data `Products/`. A Crown benefit uses:

```json
{"contentId":"eternia:currency/crowns","kind":"QUANTITY","quantity":500,"expiresWithSubscription":false}
```

The surrounding product's `packageId` must be your actual Tebex package ID and its immutable revision must match `tebex-packages.json`. Add the real HTTPS Tebex checkout URL to `store-offers.json`. The website treasury displays these offers and the signed-in account's current Crown balance from the private game bridge. It never accepts a client-supplied credit amount or grants currency on a checkout redirect. Configure and verify your own package, recipient UUID, callback and console delivery before opening paid top-ups.

Crowns can only be credited to players, cannot expire with a subscription, and are never deposited in mail, player shops or peer trades. Supporter subscriptions can still grant their existing separate time-limited benefits.

## Add an in-game product

Place a definition under plugin-data `CrownShop/` and restart. Bundled definitions additionally list their filenames in the resource directory's `catalog.index`.

```json
{
  "id":"eternia:store/garden_lantern", "revision":1,
  "name":"Garden Lantern", "description":"A warm light for your garden.",
  "category":"Furnishings", "price":120,
  "benefits":[{"contentId":"eternia:prop/garden_lantern","kind":"QUANTITY","quantity":1,"expiresWithSubscription":false}]
}
```

Create the referenced prop, building, collection item or capability first. Use `QUANTITY` for independently placeable props, pets and consumable charters; use `UNLOCK` or `CAPABILITY` for reusable access. The in-game product cannot grant Crowns or expiring subscription benefits. Released revisions are immutable: publish revision 2 to change the description, price or benefits. Only the latest revision is offered; a stale confirmation is rejected and must be reviewed again.

## Receipts, concurrency and refunds

Each Crown purchase atomically debits the wallet, records its server-owned order and grants all benefits. Retries of the same request return the original order. Independent concurrent requests cannot overspend. Quantity deliveries use normal ownership/provenance rules; guild charter redemption accepts an exact delivered Crown-order source as well as a verified direct Tebex voucher source.

A verified refund or dispute reverses a Crown top-up once. If its Crowns were already spent, the wallet records the amount owed and blocks further spending until later credits restore it. Previously placed homes and decorations are not automatically destroyed. The website and store display the outstanding amount. A refund before a delayed payment delivery blocks that delivery. Direct entitlement purchases retain the existing source-revocation behavior; a Crown top-up refund uses the wallet reversal policy instead.

The initial ledger uses the same transactional authority as housing and commerce, including PostgreSQL when configured. `PremiumServiceTest`, `CrownCatalogTest` and the PostgreSQL reconnect case cover delivery, duplicate requests, concurrent spending, immutable pricing, charter redemption and refund recovery. A real provider transaction and final client appearance still need operator acceptance.
