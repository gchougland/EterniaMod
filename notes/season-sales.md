# season-sales (wave 1)

Season passes and season store items can be bought only while their season runs. Owning stays forever.

## What was built

- **Sale window in the domain** (`domain/SeasonService.java`): `SaleState {UNSCHEDULED, UPCOMING, ON_SALE, ENDED}`,
  `SaleWindow(startsOn, endsOn)` (whole UTC days: opens 00:00 UTC on the first day, closes 00:00 UTC the day after the
  last day; either end may be open), `SaleStatus(seasonId, name, state, window)`. Stored in namespace
  `seasonsales_window` (key = season id), mutable while a season is live. No dates = `UNSCHEDULED` = always on sale,
  so Foundations and every existing pass behave exactly as before.
- **API for other views (gatehouse desk, Journal):**
  - `services.seasons().saleStatus(seasonId)` / `saleStatus(seasonId, Instant now)` -> `SaleStatus`
  - `services.seasons().purchasable(seasonId, now)` -> boolean
  - `services.seasons().saleStatuses(now)` -> `Map<seasonId, SaleStatus>` (all registered seasons, one transaction)
  - `services.seasons().now()` (authority clock), `SeasonService.seasonOfPaidEntitlement(contentId)`
  - Wording: `seasonsales/SeasonSaleText`: `badge(status, now)` ("Until 30 Jan", "Opens 1 Dec", "Season ended"),
    `note(status)` (one friendly sentence), `closedButton(state)`, `rejection(status)`.
- **How an offer is tied to a season:** a new optional `season` field on Crown items (`PremiumService.Item`, JSON
  `"season":"eternia:winter"` in `CrownShop/*.json`). An item whose benefit is a paid pass entitlement
  (`eternia:season_paid/...`) is tied to that season automatically, so the Premium track item needs nothing new.
  The content entry page (`/eternia prefab browse` -> Set up for website) now sets `season` to the chosen pass when
  an entry is both **sold** and **on a pass** (`ContentAuthoring.storeItem`). Items without a season are untouched
  and keep their stored form (the field is only written when present, so released revisions stay identical).
- **Server enforcement in game:** `PremiumService.purchase` checks the window inside the purchase transaction, after
  the idempotent retry lookup (a retried in-window order still returns after the close) and the "already owned" check.
  Rejections are `INVALID_STATE` with plain text, e.g. "Sorry, Winter has ended, so this is no longer for sale.
  Anything you already own stays yours."
- **Tebex:** `CommerceService.fulfill` never checks windows (documented on the method). A payment begun inside the
  window and confirmed after it is delivered; nothing is refunded or lost. Windows are checked when a checkout
  **starts or resumes** on the website, before any basket is created.
- **Crown Store (in game):** card badge `#SaleBadge` (gold kicker, right of the category), items no longer buyable
  sorted last, review shows a gold `#SaleNote`, the buy button reads "Coming soon" / "No longer sold" and is disabled.
  A purchase rejected by the server (stale page) keeps the review open, shows the reason in `#SaleNote` and refreshes
  the item state. Owned items always show "Owned".
- **Season designer** (`/eternia season`): date labels now "First day (UTC)" / "Last day (UTC)", a sale box under the
  dates shows "Always on sale" / "Coming soon" / "On sale now" / "Sale ended" / "Check the dates" with one line of
  detail, updated live as dates are typed (ValueChanged + partial update, no rebuild). Saving (draft or live) writes the
  window to the game, and the status line adds the sale state.
- **Startup:** `seasonsales/SeasonSaleSync` loads windows from plugin-data `Seasons/*.json` (`startsOn`/`endsOn`, if
  present) and then the designer drafts in `Authoring/Seasons/` (drafts win). Bad dates are logged and skipped.
- **Website:** `web/js/season-sale.js` (shared by pages and server, same UTC rule). Seasons page: banners get a
  "Coming soon" / "Available until 30 Jan" / "Season ended" tag unless the player owns Premium; the pass view gets a
  sale strip with a sentence; footnote and intro now say *owned* passes stay forever. Treasury: a Crown package may
  carry `"season":"eternia:<id>"` in `config/tebex-crowns.json`; `/api/store` returns `sale` per offer, cards show the
  badge and a disabled "Coming soon"/"No longer sold" button, and `POST /api/store/checkout` (+ `/resume`) refuses
  with 409 and a plain reason outside the window. An unknown season fails closed. No current package has a season.

## Verified

- `gradlew --no-daemon build` green (all tests, including new `SeasonSaleTest` (7) and `SeasonSaleSyncTest` (2)).
- `npm test` in `website/`: 41 pass, 2 skipped (postgres, as before); new `test/season-sale.test.js` (4).
- `asset_validate`: 0 errors (35 warnings, all pre-existing files). `ui_check_java`: 0 errors, no warnings on files I touched.
- `ui_render` with the commands the Java sends, pictures opened and judged, in `notes/pictures/season-sales/`:
  `store-catalog-states.png` (on sale, coming soon, plain, owned, ended), `store-review-coming-soon.png`,
  `store-review-rejected-after-end.png` (long name and long text), `designer-live-on-sale.png`, `designer-empty.png`.
- Not tried: the in-game client, a real Tebex payment, the website pages in a browser (logic is unit tested; the
  new CSS and badges have not been looked at), the live ValueChanged update in the designer.

## SHARED EDITS

- `src/main/java/com/hexvane/eterniamod/EterniaModPlugin.java`: one line in `setup()` right after `runtime = new ...EterniaRuntime(...)`:
  `com.hexvane.eterniamod.seasonsales.SeasonSaleSync.sync(getServices(), dataDirectory, warning -> getLogger().atWarning().log("%s", warning));`
- `src/main/java/com/hexvane/eterniamod/domain/PremiumService.java` (not in the owned list, but it is where Crown purchases
  happen): optional `season` on `Item` (old 7 argument constructor kept), window check in `purchase`, `seasonOf`, `sales`.
- `src/main/java/com/hexvane/eterniamod/authoring/ContentAuthoring.java`: `storeItem` passes `d.passId` as the item season and compares it.
- `website/server/app.js`: imports `publicSeasons` and passes `()=>publicSeasons(catalog)` as a 5th argument to `mountTebexCheckout`.
- `website/server/config.js`: allows an optional `season` (`eternia:<slug>`) on Crown package mappings.
- `website/web/styles.css`: 5 lines after `.banner-tag.gold` (sale badge, pass sale strip, crown package badge).
- UI documents touched: `PremiumCard.ui` (category row now holds `#SaleBadge`), `PremiumShopPage.ui` (`#SaleNote`),
  `SeasonDesignerPage.ui` (`#SaleBox`, `#SaleHeadline`, `#SaleDetail`, date labels, description 96 -> 76 px).

## CHANGELOG

- **Seasonal sales:** Season passes and season items in the Crown Store can now be bought only while their season runs. The store and the website show when each one opens or closes, and anything you already own stays yours forever.

## STATUS

- Season sale windows (2026-11-07, season-sales): Premium tracks and Crown items tied to a season sell only between the
  season's first and last day (UTC). Set with `/eternia season` dates; stored in `seasonsales_window`; read by
  `SeasonService.saleStatus/purchasable`. Enforced in `PremiumService.purchase` and the website checkout start; Tebex
  delivery never checks windows. Rendered offline only; not yet seen in the client or a browser.

## DECISIONS

- **Season dates are whole UTC days, last day included.** Same clock as quest resets; the server decides, never the
  player's timezone. "Available until 30 Jan" means through 23:59 UTC on 30 Jan.
- **No dates means always on sale.** Keeps Foundations and every existing pass working; the designer says "Always on sale".
- **Offers tie to a season by a `season` field on the Crown item**, plus automatically through a paid pass entitlement.
  The content entry flow sets it when an entry is both sold and placed on a pass. Gibson may prefer pass rewards that
  are also sold all year: then drop the `d.passId` argument in `ContentAuthoring.storeItem`.
- **Ended items stay listed, last, with "Season ended".** So players understand why something is gone and owners still
  see it as theirs. Revisit if the store fills up with old seasons (could hide unowned ended items after some weeks).
- **Tebex delivery never checks the window**; the check is at checkout start/resume. A payment begun in time always delivers.
- **Website seasonal Crown packages use the website's own season catalog** (dates uploaded by the designer) rather than a
  new game bridge field, so `GameBridgeServer` is untouched. An unknown season fails closed.
- **Player text stays in Java** like the rest of the Crown Store and designer; `eternia_seasonsales.lang` was not needed.

## REQUESTS

1. **gatehouse (owns `socialui/EterniaServicesPage.java`)**, Journal season pass view, `seasonView(...)`, the line that
   builds the pass subtitle. Replace
   `(current.paid() ? " Both tracks are yours." : " The premium pass unlocks the second track.")`
   with
   `(current.paid() ? " Both tracks are yours." : " The premium pass unlocks the second track. " + com.hexvane.eterniamod.seasonsales.SeasonSaleText.note(services.seasons().saleStatus(current.id())))`
   (`note` returns "" for passes with no dates, so nothing changes for Foundations; check the subtitle height with a long note).
   The Gatehouse desk can use `services.seasons().saleStatus(id)` and `SeasonSaleText.badge(status, services.seasons().now())`.
2. **coordinator / whoever owns `ui/ContentEntryPage.java`:** consider one sentence in `#PassNote` when an entry is sold
   and on a pass: "In the Crown Store it sells only while that season runs." (not done: not my file, height unchecked).
3. **docs:** `docs/implementation/content-authoring.md` says "Dates are shown on the website only; the game does not open
   or close passes by date." That is no longer true: dates now decide when Premium and season items can be bought. And
   `crown-store.md` could document the optional `"season"` field on Crown items and on `website/config/tebex-crowns.json` entries.
