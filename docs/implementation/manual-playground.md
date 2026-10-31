# Playtest Eternia in game

Start your normal local server and connect as usual. `runServer` uses the existing `run/` universe on `localhost:5520`. `runServerLocal` uses the separate `run-local/` universe on `localhost:5523`. Both use authenticated game connections. The playground is an in-game command; it does not require another server task or any hand-edited JSON.

Use **`/e playground`** as a WorldEditor. The first **Visit village** creates `eternia_playground` and `eternia_trials`. Creation refuses existing worlds with those names unless their saved UUIDs match Eternia's playground marker. The village supports housing in the same world as its public services. If your server already has a Hub, the village is an additional residential destination and your Hub stays assigned as before.

Revisiting does not rebuild modified roads or houses. The worlds, shop stock and your local purchases persist through restarts. Keep the local authority/database and native world saves together.

## Suggested route

### Solo guild placement and the Architect's Ledger

On servers running with `ETERNIA_MODE=local`, a guild with one member can claim and restore its estate. Production still requires five members. Guild claim permissions, road connections, portal distance, plot borders, paid sizes and the one estate limit still apply. You do not need to add fake members to your existing guild.

Open `/e menu`, choose **Architect's Ledger** at the top, then **Guild estate → Choose plot**. This opens the guild estate preview directly. Use a clear area within five blocks of a public road centerline. A portal connection is not required. The example guild's estate belongs to that example guild, so your guild needs its own clear site. After claiming, stand inside your estate and choose **Housing inventory** to place the guild hall.

The Ledger has separate illustrated cards for plots, housing inventory, customization, claiming, guild estates and Home Waygate. A physical ledger item opens the same page. An unfinished placement still resumes when reopening the Ledger.

**Home Waygate** is a travel unlock, not a decoration or block. Stand inside your personal plot after buying it, then choose **Architect's Ledger → Use Waygate**. Guild Waygate charters must first be redeemed for the guild and require its convenience permission. Public portals also open the destination picker.

**Guild Waygate Portal:** Redeem a Guild Waygate Charter through **Guild estate → Review vouchers**. Stand on the guild estate and open **Housing inventory → Guild decorations → Guild Waygate Portal → Place**. The unlock includes one portal decoration per source; existing guild unlocks receive it when their estate inventory opens. Only one portal can be placed on the estate at a time. The glowing pad opens destination choices when a permitted member interacts with it. It does not make a public portal or a housing anchor. The original menu travel option remains available.

Place and pack permissions follow the guild's decoration and build inventory permissions. Use requires the guild's active Waygate unlock and the member's convenience permission, checked again when selecting a destination. The Packaging Wand returns the portal to guild custody for placement elsewhere on the estate. Moving the entire guild plot preserves it. Refunding the unlock disables the portal and revokes its unused quantity.

Portal verification: 20 focused checks passed, including grant backfill, repeated inventory opens, packing reservations and refund propagation. The new portal asset loaded in the isolated native server. The full native run stopped on the same Windows save replacement error before reaching the portal placement fixture, so in-world appearance, interaction and packing still need an in-game check.

**Get Crowns** closes the store and posts a clickable `/store` link in chat using `ETERNIA_WEBSITE_URL`. Local mode defaults to `http://127.0.0.1:3847`. Set that variable to your website's address if testing against another website instance. No browser is opened automatically.

Collection's **Owned items** groups matching content into one row. Its available count totals only active, unspent quantities. Expired or revoked purchases do not increase it. Individual pet assignments and packed decorations retain their own identities and contents.

Validation on 30 September 2026: the release jar and 22 focused checks passed, including solo guild placement, production member requirements, collection totals and the Crown catalog. Two isolated native smoke attempts started successfully but stopped on Windows `AccessDeniedException` while atomically replacing their disposable `local-authority.bin` files. Neither attempt completed the full native suite. The new Ledger layout still needs a client visual check after restarting the local server.

### Walkthrough

1. **Collect Starter supplies** in `/e playground`: 5,000 Crowns, 2,000 trading coins, extra housing props, the housing tools and a real item parcel. This allowance is issued once per profile. **More supplies** grants an additional explicitly requested local allowance.
2. **Visit all six NPCs.** Inspect their different appearances, names and professions. Confirm the native interaction prompt shows your bound Use key. Only Prowl uses the Prowl model.
3. **Shop with Lyra.** Buy a decoration, outfit, pet, title or housing convenience with Crowns. Use Collection to equip an outfit/title or choose your follower. Buy another pet to assign to your property. Test the purchase confirmation and insufficient-balance behavior.
4. **Open Nima's player-shop directory.** Iris Wren sells iron bars and bread from a furnished house east of the village lane. Visit the house, purchase with coins, then collect the native stack through Item deliveries. These are actual market listings with finite stock and a real house destination.
5. **Claim your own home.** The west side of the lane around `(-19, 34)` has room for a free 24 × 24 plot near the plaza. Use the plot deed, review the grounded validity grid, confirm, then place your free house with the housing tools. Your one global home slot still applies; an existing home is retained and must be moved normally.
6. **Try customization.** Place a lamp, shelf and porch, change the block palette, make a path, pick up a prop and move the furnished plot. Approach the border to inspect the short default wispy fog. Purchase another moving charter if needed.
7. **Test mail and trading.** Read Iris Wren's letter and claim its iron-bar parcel. Collect the delivery, hold a stack, and deposit it for a letter or your own listing. For the two-party trade and live party flows, join with a second authenticated player; the offline examples cannot accept a live trade.
8. **Try guild management.** The village includes the Lantern Company's guild hall. **Guild workshop** optionally creates your own five-member test guild if you are not already in one. You lead it; four clearly named offline examples satisfy the normal minimum. Claim an estate, inspect roles, donate a purchased guild charter and test the guild inventory. Existing guild membership is never replaced.
9. **Visit Activity trials.** Collect the sword and pickaxe from Item deliveries and use **Trial controls** to find mining/crop stations or spawn a training skeleton. Use **Adventure mode** for XP. **Practice chapter** selects Village Field Notes: defeat three mobs, mine four resource types and harvest three crops. Its paid track costs 100 test Crowns. Follow its visible goals and claim both reward tracks; the normal source checks remain active. Use the discovery bookshelf at `(52, 1, 20)` to collect its Aqua Lamp token, then interact again to check that it cannot grant twice. Foundations retains its longer progression.
10. **Try the builder workflows.** Open World setup or use `/e admin roadtool` to get the Road Designer item. Add and move grounded spline nodes, change width/material, review, commit, edit and remove a road. Use the managed Hub service placement tools to move or add NPCs and a world-select portal in a registered public plaza.

The local setup intentionally uses a small reusable assortment rather than a launch-sized content library. Minigames remain Coming Soon. Mail/market item deliveries, housing claims, purchases and native placements use the real services. Local currency/item issuance is explicitly labeled fixture issuance. Actual website OAuth and Tebex charges require your own configured application and products; no live payment is generated by the playground.

If a setup operation fails, the command reports the cause and the server logs its details. Fix the reported obstruction or unfinished native operation and retry. Do not erase native saves while keeping authority records or copy old snapshots over a live authority. Use the existing recovery tools for interrupted housing or road operations.

### Crop setup recovery (0.6.5)

The old “original flat ground was changed” error could actually mean its block section had unloaded while the village was being built. Setup now loads both native columns and vertical sections, loads the trials immediately before preparing crops, and distinguishes unloaded ground from a real obstruction. Empty native location-cache holders and plain-soil rotation do not count as a build. Restart the server and choose **Visit village** in `/e playground` again; do not erase the saved worlds or manually replace intact soil. Genuine occupied ground is retained and the message identifies the actual block/state. The native acceptance check also unloads/reloads the trial world from disk and retries the partial setup.

For the new frames, fonts, local preview and manual client checks, see [Citadel styling](citadel-styling.md).

## September polish playtest

1. Talk to Prowl and select **Get starter toolkit**. You receive a Plot Deed, Architect's Ledger, and Packaging Wand. Ask again to replace missing tools. Tools already carried are not duplicated, and a full inventory leaves the remaining tools available to collect later.
2. Equip a following pet, walk forward, turn a corner, and rotate while standing still. The pet follows from its current position and settles nearby. Turning the camera no longer makes it orbit to a fixed side.
3. Open **Season passes** in the main sidebar, then the quest journal. Check the selected label, full button hit area, and progress bars at your normal UI scale. Browse Iris Wren's listings and item deliveries to see the native item icons.
4. From the ledger, open **Customize**, choose your plot and path style, then a width from one to three blocks. Start far enough from your house to leave one empty block beside the entire path. Review and confirm the result. Remove the old path before choosing another width. Place a prop nearby to check the clearance and rounded bends.
5. Example villages use **Eternia Guild Hall**, adapted from the Stormwind hall, which faces its entrance toward the village.

The hall's catalog ID is `founders_hall`. The adapter preserves native decorative doors and the sign; Aetherhaven quest boards, smoke emitters, POI marker, and unsupported loose display entities are removed. The Eternia management block is inside the hall.


## Housing and travel menu follow-up

- Open Housing inventory while outside every plot. The personal Decorations and House styles cards remain available. Guild members also see separate Guild decorations and Guild houses cards. Clicking Place outside the appropriate plot explains where to stand without creating a placement session or consuming an item.
- Personal items remain visible while standing on a guild estate. They can only be placed on the personal plot. Guild inventory placement still requires the existing build and inventory permissions.
- Collection now has a Decorations tab. Owned items and Decorations scroll through the entire list. Each item has one row with separate available, packed, and placed counts. Packing does not create extra available copies.
- Build & Furnish uses the Potion Shelf icon. Housing categories use distinct house, shelf, guild, palette, and plot art. Travel uses illustrated destination cards with direct Travel buttons, Back, and Home.
- New Guild Waygate portals have no stone platform. To update an existing raised portal, recover it with the Packaging Wand and place it again. Its original stone base is removed by normal recovery; a strictly recognized old portal snapshot is converted on replacement. Unexpected saved blocks, entities, fluids, or components stop conversion for review. No new entitlement or item is minted.
- Place a decoration while looking east, then slightly north or south of east. The initial preview appears three blocks ahead on the nearby surface. The forward arrow follows the nearest cardinal direction of the player's look, with perpendicular left and right movement. **In front of me** moves the preview back ahead of the player.
- Turn on Bird's eye view during prop placement. Check pan, zoom buttons, zoom slider, and movement arrows. Arrows follow the overhead screen in this view. Escape, Home, Cancel, and successful placement restore the player camera. Escape keeps the existing placement draft available when reopening housing tools.

Automated verification for this follow-up covers the full unit suite and release packaging. It includes cardinal direction boundaries, packed/placed item counts, and safe conversion of original Waygate snapshots. These changes still need a live client visual check after restarting the updated mod.


## Daily quests, weekly quests and border check

1. Activate Foundations and open Quests. There are three daily and three weekly goals. Each card names the exact target, amount, XP and Coin reward.
2. Use Adventure mode. Skeleton variants and ghouls advance an undead goal; cows do not. Combat trials also count in the managed local village. Production activity belongs in an adventure world or an explicitly enabled world.
3. Harvest ripe crops by interacting or using a sickle. Each successful regrowth cycle counts once. Player placed ore never pays mining XP or progresses ore quests.
4. Complete a current goal. Check for its toast, completion sound, Coin credit and XP. Reopen the menu and reconnect to confirm no second payment.
5. Daily goals change at 00:00 UTC. Weekly goals change Monday at 00:00 UTC. Changing the in game time has no effect. The automated clock tests cover both boundaries without changing the computer clock.
6. Enter your placed guild portal. Travel opens automatically. Close it while standing inside: it stays closed until you leave and enter again. Visitors without guild travel permission cannot use it.
7. Look at the top right wallet in the main menu and Travel. Both balances sit inside a dark framed panel.
8. Approach a plot corner. A turquoise ground line with gold runes appears promptly, with small rising lights. Its segments meet at the corner without extending beyond it and fade with distance. Check all sides and road crossings.

See [all 45 Foundations quests](quest-catalog.md) and [authoring recurring goals](content-authoring.md#daily-and-weekly-quest-pools).


## Collections, clothing and mounts

Open Collection and browse Houses, Decorations, Materials, Titles, Wardrobe, Pets, Mounts and Upgrades. Cards fit four across. The mailbox is accessed from the main menu and does not appear as a collectible.

For Prowl Shirt, open Foundations rewards with its paid track unlocked and claim the first paid reward. Equip it from Collection, Wardrobe. Check the front logo and sleeves while moving, then unequip and check that your usual outfit returns. Existing pass claims stay claimed and the new shirt remains claimable for eligible players.

Open Collection, Mounts in an open space. Horse is available to everyone. Summon it and you should immediately ride it. Use your mapped dismount key and the horse should disappear. Also try reconnecting, world travel and a blocked indoor location. Summoning should never change nearby blocks or leave a permanent horse behind.
