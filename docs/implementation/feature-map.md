# Feature map

This maps the requested feature set to the implementation and the operator work needed to use it. The original planning documents remain design history; use the implementation guides for actual setup and supported native content.

| Feature | Implemented entry point | Content or setup needed |
|---|---|---|
| Hub welcome and service NPCs | Six distinct named characters and professions; only the greeter uses Prowl; native Use prompt | Place or move services with the in-game Hub setup tools |
| World selection | World portal block, `/e worlds`, public portal and paid home/guild access checks | Create worlds and register portal columns and safe arrival points |
| Personal and guild claims | Ground grid, overhead view, two-step confirmation, durable global slots, public/guild anchor rules and size entitlements | Author roads; approve the public connector to a new guild estate |
| Plot boundaries | Default proximity fog with bounded particle height, distance fade and emission budget | Inspect its final appearance with the client near multiple plots |
| House styles and decorations | Native prefab catalogs, owned inventory, placement/pack tools, additions with house/road setbacks | Add a definition and prefab; grant the appropriate entitlement |
| Palettes and paths | Confirmed sparse native edits with saved terrain and rollback | Add palette/path JSON definitions |
| Furnished plot relocation | Original-terrain snapshots, native block/component/entity preservation, centered supported size upgrades | Keep the game data/snapshot volume persistent; follow recovery guide for interrupted operations |
| Guild custody | Original guild decoration source persists on member plots; forced pack separates guild content from personal content | Assign the appropriate builder/inventory permissions |
| Guild management | Persistent invitations, role capabilities, live roster presence, confirmed removal and leadership transfer | Create five-member guilds to qualify for estates |
| Guild communications | Persistent notices, author edits, pinned announcements, moderation and ephemeral guild chat | Use guild board permissions and the native guild menus |
| Community roads | Confirmed level segments, member plot easements, full-column protection and saved-ground removal | Authors need road permission; member owners approve their own road easements |
| Public road designer | Held item with live grounded spline preview, movable nodes, width/material controls and saved edits | WorldEditors get the Road Designer from World setup; committed curves register their exact columns |
| Parties | Persistent groups of eight, expiring invitations and online roster | Use `/e party`; shared combat rewards are separate gameplay content |
| Mail, item trading and player shops | Offline text mail, native inventory escrow, deliveries, two-party trade, hub shop directory and house checkout | Configure verified activity coin payouts and authored safe shop entrances |
| World-found housing tokens | Authored discovery cache use grants a durable per-player reward | Add exact cache positions and catalog rewards to `discoveries.json` |
| Permanent seasons | One active earning pass, permanent free/paid claim tracks, count/distinct quests and qualified gameplay XP | Add immutable pass definitions; tune rates with measured gameplay |
| Cosmetics and titles | Native skin composition, prefix/suffix display and collection selection | Add validated outfit, wearable and title definitions |
| Pets | One cosmetic follower, persistent property assignments and bounded nearby presentation | Add a reviewed native pet model and a quantity grant |
| Real-money products and supporters | Tebex signed callbacks joined with console receipts, immutable products, leased supporter access and source revocation | Configure real packages, callback fields and test subscription lifecycle |
| Premium currency and in-game store | Separate Crown wallet, category browse/confirmation, atomic price/debit/content delivery and refund balance recovery | Add CrownShop catalog revisions and connect real Tebex Crown top-up packages |
| Paid guild conveniences and size | Purchaser retains a guild voucher until a leader confirms the named guild; refunds revoke derived access | Map the two documented permanent guild voucher products |
| Website account and store | Hytale OIDC, account stats/collection/pass views, authenticated private game bridge and Tebex checkout links | Create Eternia's new OAuth client and production database after callback URL is ready |
| Admin prefab authoring | Local native 3D viewer, immutable imports, screenshot/transparent-icon jobs and deterministic native catalog archive | Sync native viewer assets, install Chromium and configure admin UUIDs |
| Shared visual style | Citadel's geometric green, brass and ivory styling across native menus and website | Keep shared theme tokens and native UI constants in sync |
| Manual local playground | Persistent village, six NPCs, seller house/listings, guild hall, claimable land, test allowances and real activity stations | Use `/e playground` with WorldEditor permission on a local server |

The catalog demonstrates each content mechanism with a small reusable assortment. The local playground supplies concrete examples for manual testing. Minigames remain **Coming Soon**; pet feeding/care remains future work. Community submissions are intentionally absent from the website.

The local fixture website labels synthetic account data. PostgreSQL has a separate real local test setup. Native acceptance exercises the installed Hytale server directly in isolated worlds. Live OAuth, provider payments, Railway deployment and client-side appearance require their corresponding configured environment or interactive game client.

See [local setup](README.md), [content authoring](content-authoring.md), [inventory and trading](inventory-and-trading.md), [commerce and activities](commerce-and-activities.md), [guild communication](guild-communication.md), [guild roads](guild-community-roads.md), [discoveries](discoveries.md), [customization](customization.md), and [native recovery](../native-housing-recovery.md).
