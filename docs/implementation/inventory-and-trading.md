# Inventory, mail, shops and direct trades

This documents the implemented Java services and native menu adapters. Run the two-player local checks below before treating the in-game experience as release-tested. Unit tests exercise the domain and custody protocol; they do not launch a Hytale client.

## Player entry points

Use `/e menu` (also `/eternia menu`). Select **Player shops** or **Mailbox** in the menu navigation. The configured hub shop NPC opens the same directory. There are no separate `/mail`, `/shop` or `/trade` commands in this implementation.

**Player shops → Item desk** opens saved deliveries. Its navigation also contains **Stored items**, **My shop**, **Write a parcel**, **Trades** and **Shop directory**. These screens share the existing Citadel native UI assets. Closing a page prevents late background reads or travel completion from reopening it.

### Deposit and collect items

1. Hold the stack to transfer in the active hotbar slot.
2. Open **Stored items** and select **Deposit held stack**. The entire stack is removed from carried inventory, losslessly encoded, and saved in escrow.
3. Use the saved stack in a listing, mail attachment or trade. **Withdraw** moves it into the durable delivery inbox.
4. In **Deliveries**, select **Collect**. If carried inventory has insufficient room, the stack remains pending. Collection never drops overflow into the world.

The adapter rejects `Eternia_` items and stacks with the `EterniaBound` metadata key. Housing and collection entitlements remain account or guild inventory, separate from tradeable native item stacks. An item must round-trip through `ItemStack.CODEC` without changing its BSON representation. Partial shop purchases replace the captured stack quantity with the delivery's authoritative quantity while preserving other metadata.

### Mail

Open **Mailbox** to read messages, claim attachments and archive completed mail. **Prepare mail** opens the parcel screen with the entered recipient, subject and body. Select up to five previously deposited stacks, review the recipient and attachments, then confirm sending. Recipient names resolve to known server accounts using an exact, case-insensitive name lookup; ambiguous matches are rejected. UUIDs remain the underlying identity.

Limits are currently 20 unarchived received messages, five attachment stacks, a 100-character subject and a 2,000-character body. A full mailbox rejects the whole transaction and leaves the sender's stacks available. Claiming a message moves its attachments into **Deliveries**; collecting from **Deliveries** performs the native inventory handoff. Repeated claims cannot issue additional attachments. Mail can be archived only after its attachments have been claimed.

The domain also implements sent history, sender blocking and returning attachments. Those three actions are not exposed by the current native screens. Paid mailbox capacity is not implemented.

### Player shops

**My shop** lists the seller's available stacks and active listings. Choose **Set price**, enter a positive integer price in earned coins per item, review the stack and price, then publish. A listing requires an active personal plot with a placed house. It reserves the deposited stack; buyers may purchase part of it. Closing the listing returns remaining stock to the seller's delivery inbox.

The directory searches actual active listings. **Visit shop** resolves the seller's current native house entrance; it does not accept coordinates supplied by the browser or UI. Travel starts from a permitted public travel location or an unlocked house/guild teleporter. The destination must have a loaded, unobstructed location with solid floor, two air blocks, no fluid, and no protected road column. The seller can be offline.

At the seller's house, select a quantity and review the exact total. Both review and final confirmation check the buyer's current world, plot containment, and distance of at most 12 blocks from the configured house entrance. The domain then rechecks listing revision, remaining stock, seller house state and buyer coin balance in one transaction. A changed quote must be refreshed. A full carried inventory does not undo a completed purchase: its items remain in **Deliveries**.

The house entrance currently serves as the purchase location. A separate placeable shopkeeper prop, shop decoration requirement, custom opening hours and player-selected teleport coordinates are not implemented. Listings can remain visible while a house is packed or temporarily unavailable; visiting or purchasing then fails safely until the house is restored. Prices use the existing earned-coin ledger. These menus do not grant coins or expose premium currency.

### Direct trades and guild members

Open **Trades**, enter another known player's name, and select **Start trade**. Both participants can find the same open trade in their own desk. Guild members are listed as a convenience when the viewer belongs to a guild. A trade can contain up to 12 saved stacks per participant plus a nonnegative whole-number coin offer.

Select stacks and enter coins, then **Save draft offer**. This reserves the offered items and coins and clears both confirmations. **Confirm shown offer** reviews the currently saved offers. Both participants must confirm the same offer revision; an edit between confirmation clicks rejects the stale confirmation. Settlement transfers both sides and creates item deliveries atomically. An empty trade cannot settle. **Cancel trade** releases both offers. Open trades expire five minutes after creation; expiration also releases both offers. The menu refreshes expiration, and the runtime must also call `services.trades().expire()` periodically for players who close the screen.

This is a personal, two-player exchange. Players do not need to be near each other or online simultaneously, but both must confirm before the deadline. There is no guild treasury withdrawal, guild-owned item trade, proximity requirement, trade invitation notification or group trade in these screens. Guild membership never authorizes spending another player's items or coins.

## Guild permissions and parties

Guild membership and role checks remain in `GuildService`, not in browser fields or button visibility. The native guild menu exposes invitations, accepting an invitation, viewing members, assigning lower roles, editing custom roles, leaving and entering guild housing. Built-in roles cannot be overwritten. Assignments cannot grant peer/higher rank or capabilities the actor lacks; only the leader can edit custom roles. Transfer leadership before leaving as leader.

| Capability | Use in the current systems |
| --- | --- |
| `member.invite` | Checked by guild invitations. |
| `member.role.assign` | Checked by role assignments, including rank and capability limits. |
| `role.edit` | Leader-only custom role definitions. |
| `housing.claim`, `housing.move`, `housing.resize` | Leader-only estate lifecycle actions. |
| `inventory.deposit`, `inventory.withdraw`, `shop.manage`, `mail.attachments.claim`, `treasury.deposit`, `treasury.withdraw` | Defined role capabilities for guild-owned services. They do not turn the current personal item desk into a guild inventory or treasury adapter. |
| `convenience.use`, `housing.visit` | Guild housing/travel access, rechecked by the relevant native adapters. |

Activity parties are separate from guilds and are available through:

```text
/e party create
/e party invite PlayerName
/e party accept LeaderName
/e party list
/e party leave
```

A party has at most eight members and one leader. Only the leader invites. Invitations expire after 15 minutes and cannot be reused after leaving. A player can belong to only one party. When its leader leaves, leadership moves to the first remaining member; an empty party closes. `/e party list` shows member names, leader and current online status. Party membership does not grant housing, inventory, guild or currency privileges. Shared loot, party combat rules, matchmaking and party chat are not implemented by these commands.

## Native custody and restart recovery

The adapter and its protocol are in [NativeItemEscrow.java](../../src/main/java/com/hexvane/eterniamod/inventory/NativeItemEscrow.java), [InventoryReceipts.java](../../src/main/java/com/hexvane/eterniamod/inventory/InventoryReceipts.java), [NativeSaveBarrier.java](../../src/main/java/com/hexvane/eterniamod/inventory/NativeSaveBarrier.java) and [EscrowService.java](../../src/main/java/com/hexvane/eterniamod/domain/EscrowService.java).

1. Deposit prepares an unavailable domain record before native removal. Delivery changes `READY` to `HANDOFF` with a unique attempt before native insertion.
2. On the world thread, the adapter increments the player's custody revision and marks the mutation uncertain before calling the native container. Successful all-or-nothing mutation replaces that marker with an exact removal or insertion receipt.
3. It forces `Player.saveConfig(..., required=true)` with the inventory and `EterniaInventoryReceipts` component in the same player document. It waits at most 15 seconds using `Future.get`, which does **not** complete or cancel Hytale's storage future on timeout.
4. Only after confirmed native save does the domain raise its durable custody checkpoint and make the deposit available or mark the delivery `DELIVERED`. The in-memory receipt can then be removed.
5. Recovery examines the loaded holder during `PlayerConnectEvent`, before world addition. A matching persisted insertion marker completes its pending handoff. An absent marker on the current accepted document returns an uninserted handoff to `READY`. Repeated recovery uses the same attempt and cannot redeliver a completed handoff.

The implementation specifically verifies Hytale's `DiskPlayerStorageProvider.DiskPlayerStorage`: required saves serialize the holder before queueing, and `StorageManager` excludes same-path loads and writes until the actual write completes. `BsonUtil` writes a temporary document, keeps a `.bak`, then moves the new document into place. Its fallback can load an older backup. A loaded custody revision below the domain's last confirmed checkpoint therefore disconnects the player for review before their items enter the world. Other player storage providers are rejected until their guarantees are reviewed. Native transfers also require player saving to be enabled in the current world and pause during a server backup.

The reviewed SDK implementation does not explicitly fsync its player JSON writes. The guard detects an older native document when the independent domain checkpoint survives; it cannot guarantee recovery from simultaneous rollback of both stores or arbitrary manual edits. Back up and restore the domain store and Hytale player/world data together. Do not downgrade this protocol or remove the receipt component while transfers are pending.

An unexpected exception inside a native item mutation leaves an `uncertain` marker and durable quarantine rather than assuming nothing happened. Any unconfirmed post-mutation save/acknowledgement disconnects the session so it cannot continue moving uncertain items. A regular interrupted save may recover on reconnect. Quarantined or stale-backup accounts require operator reconciliation of the player document, checkpoint, escrow/deposit proof and delivery attempt. There is deliberately no public “retry anyway” or automatic item reimbursement command. Preserve evidence before repairing those records.

## Extending and checking this implementation

- New native item types normally need no catalog entry if their `ItemStack` codec is lossless and their transfer policy allows it. Keep binding checks in the adapter and authoritative `transferable` flag; do not bypass them from a new menu.
- New parcel or shop screens should call existing services with immutable receipts. A server-owned listing ID is insufficient by itself: purchases must still enforce physical presence and the quoted revision.
- A new player storage provider needs ordered required saves, an immutable serialized inventory-plus-marker snapshot, load-after-write exclusion, and defined fallback/rollback behavior. Add protocol tests before enabling it.
- Current lists use item IDs and quantities rather than full native item tooltips. Metadata is preserved in custody but is not fully presented during review; improve item previews before offering heavily customized equipment for player trading.
- Domain storage currently serializes transactions and uses scans for several listings/inboxes. Pagination exists in the UI; indexed repository queries and bounded cleanup jobs are follow-up scaling work.

For a local acceptance pass, use two real clients and a known transfer-safe stack: deposit/withdraw; send and claim a parcel; reject a full mailbox; list and buy a partial stack from the seller's house while the seller is offline; reject buying from elsewhere; cancel the remainder; change an offer after one participant confirms; settle a two-sided item/coin trade; let a trade expire; fill carried inventory before claiming; restart between a prepared handoff and its acknowledgement. Verify total item quantity and coin balances after each step. Existing coin balances must come from an explicitly configured gameplay source or an isolated test fixture, never a hidden UI grant.

The Hytale-free tests in `NativeSaveBarrierTest` prove a timeout does not release a queued reconnect load. `NativeCustodyProtocolTest` verifies restart recovery, stale-backup rejection, monotonic per-owner checkpoints and durable quarantine. Domain tests separately cover concurrent last-stock checkout, coin and quantity reservations, mail rollback, revision-confirmed trading and source-bound custody. Central Gradle verification and real client testing remain distinct checks.

Related: [commerce and activities](commerce-and-activities.md), [local development](../planning/local-development.md), [planned player systems](../planning/player-systems.md).
