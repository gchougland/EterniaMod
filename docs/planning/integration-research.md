# Eternia integration research

Verified: **2026-09-12**, including the user's correction and inspection of Aetherhaven's existing website OAuth source. This document records externally and locally verified capabilities and the remaining integration checks. Proposed Eternia architecture is explicitly labeled below; it is not a claim that these systems already exist in this repository. Recheck provider documentation against the server build and Tebex plugin release selected for implementation.

## Tebex plugin and website store

Tebex maintains an official Hytale plugin. Its installation guide describes a JAR in the server's `mods` directory, store authentication through a secret, automated command execution, and package checkout links. The guide documents `tebex checkout <id>` and `tebex sendlink <username> <id>`, and describes fulfillment checks occurring every two minutes by default. These are integration entry points, not an Eternia purchase ledger. [Official Hytale integration guide](https://docs.tebex.io/creators/tebex-control-panel/game-servers/hytale).

The current official repository also documents a `/buy` store browser, cart, and checkout through a QR code or chat link. The browser can be disabled to show a webstore link. Its package thumbnails are built into an asset pack; the README describes a restart after the first startup and rebuilding assets when thumbnails change. The repository describes delivery when the player next comes online. Verify these features in the actual plugin release before wiring the Store NPC to them. [Official Tebex-Hytale repository](https://github.com/tebexio/Tebex-Hytale).

For a custom website, Tebex's Headless API supports fetching listings, creating baskets, and starting checkout. It can work with Tebex.js for embedded checkout; some endpoints may require account permissions or provider approval. This supports an Eternia account website with Tebex checkout without implementing a payment processor. [Headless API introduction](https://docs.tebex.io/developers/headless-api/introduction.md).

Basket authorization is provider-dependent: the API returns the authorization options available for that store type. This is a checkout flow. The documentation does not establish that its callback is a general-purpose Hytale identity assertion that Eternia may use to open a private account session. [Basket authorization](https://docs.tebex.io/developers/headless-api/guides/baskets/authorize-a-basket).

**Proposed implementation:** have the Hub Store NPC open the official plugin store or a checkout link. Resolve every product into an Eternia catalog offer. Keep gameplay currency separate from any future premium currency. Permanent housing styles, titles, pets, pass ownership, plot-size entitlements, and time-bound supporter access all pass through one entitlement service. Neither a checkout return URL nor a browser message grants a reward.

**Integration checks before implementation:** confirm the selected Hytale store's immutable recipient identifier; inspect plugin command substitutions and transaction references; prove an offline purchase survives a server restart; and test purchase quantity, repeat delivery, refund, and subscription expiration. Do not publish example fulfillment commands containing invented Tebex placeholders.

## Fulfillment, subscription events, and reconciliation

Tebex webhooks report completed payments, refunds, disputes, and recurring-payment state changes. They contain a webhook ID. Non-2xx responses cause retries. The documented signature uses `X-Signature` and HMAC-SHA256 over a SHA256 hash of the original request body; reparsing and serializing JSON can change the signature input. Cancellation-requested occurs before subscription end; recurring-payment-ended marks the point where the subscribed product should be revoked. [Tebex webhook documentation](https://docs.tebex.io/developers/webhooks/overview).

**Proposed implementation:** persist verified provider events before acknowledging them, deduplicate deliveries, and apply grants with a unique purchase/product/recipient grant identity. Persist the source transaction separately from the webhook-event identity: several events can describe one purchase. Route plugin commands and webhooks through the same ledger, or make one the sole grant source and the other a notification/reconciliation source. Two independent reward-grant paths would duplicate purchases.

Supporter benefits should have an explicit validity interval and a durable renewal history. Cancellation should stop the next renewal without prematurely deleting the paid interval. Refund handling should reverse or suspend the affected entitlement according to the published product rules while preserving player builds for recovery. Treat consumable move tokens differently from permanent unlocks. A refund or supporter expiration must not silently destroy a house.

Reconciliation should repair missing grants and expose unresolvable recipient mappings to administrators. The website should show pending, fulfilled, failed, or reversed delivery status from Eternia's ledger, not infer success from checkout navigation.

## Hytale website identity boundary

**Eternia will use Hytale website OAuth.** The owner will create a new OAuth Client Application for Eternia after the website URL is available. Local source inspection confirms the dedicated website OIDC integration already used by Aetherhaven.

Aetherhaven's website configures `https://connect.accounts.hytale.com` as its default issuer and `/auth/callback` as its callback path. Its OIDC helper discovers provider endpoints, generates PKCE with S256, requests an authorization code with the `openid hytale:profile` scopes, exchanges that code using HTTP Basic client authentication and the PKCE verifier, and retrieves UserInfo with the access token. These are verified source behaviors, not a claim that this research exercised a live login. [Issuer and callback configuration](C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/index.js:130), [OIDC discovery and authorization flow](C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/oauth.js:17).

The reference server exposes `/auth/login`, `/auth/callback`, `/auth/logout`, and `/api/me`. It keeps OAuth state and the PKCE verifier in the browser session, checks callback state, obtains UserInfo, then stores the returned profile in that session. Reuse this integration pattern and the same website tooling/Railway stack; Eternia's pages, visual design, account features, and commerce behavior remain its own. [Existing website auth routes](C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/index.js:1925), [Eternia website stack and deployment plan](website-stack.md).

The Server Provider Authentication Guide documents a separate `hytale-server` client and `auth:server` scope for server provisioning and sessions. Eternia website login uses its own newly registered application and the website issuer above; server-provider credentials do not substitute for website-client credentials. [Hytale Server Provider Authentication Guide](https://support.hytale.com/hc/en-us/articles/45328341414043-Server-Provider-Authentication-Guide).

Tebex's login webhook lets a store consult an operator endpoint to accept or deny a login attempt using supplied username and request information. It is an integration hook, not independent proof of Hytale account ownership for the account dashboard. [Tebex login webhooks](https://docs.tebex.io/developers/webhooks/login-webhooks).

**Setup sequence:** build and test Eternia's website locally first. Establish a provider-supported callback URL, preferring a permitted local callback or approved HTTPS route to the local site; use Railway staging when needed. Local/tunnel callback acceptance must be checked, not assumed. The user then creates Eternia's OAuth Client Application with that site's exact callback URL. Configure issuer, client ID, client secret and redirect URI privately for the selected environment. Enable real sign-in and complete a test login only after those values are supplied. Until then, use the isolated local fixture harness for development and show login-not-configured elsewhere. Production requires its own registered HTTPS callback. See [local testing](local-development.md). No application creation or secret inspection was performed for this plan.

**Identity mapping must be proved before purchases:** Aetherhaven's `playerIdentity.js` distinguishes `profile.uuid` from `sub` and includes both in its favorites identity lookup. That compatibility helper is a reference to investigate, not permission to merge arbitrary account IDs in Eternia. Persist the authenticated issuer/subject and the provider's game-profile UUID as distinct fields. Confirm which claim corresponds to the UUID observed in Eternia's authenticated game session, then bind inventory, purchases, housing, and pass progress to that verified game identity. A browser-supplied UUID/header or displayed username cannot establish ownership. [Aetherhaven identity handling](C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/playerIdentity.js:13), [Profile UUID used by the existing account endpoint](C:/Users/gchou/Documents/HytaleModding/Aetherhaven/community-marketplace/server/index.js:1962).

The Eternia callback must require a present, valid, short-lived, one-use state and its associated PKCE verifier; reject replayed callbacks and mismatched identity claims; validate the provider response according to OIDC; rotate the authenticated browser session; and support session expiration/logout. Keep provider tokens and client secrets on the server. Preserve the `PlayerIdentityProvider` boundary for implementation and testing, with Hytale OIDC as the planned provider. The referenced `docs/CommunityMarketplaceOAuthSetup.md` was not present in the inspected Aetherhaven checkout, so the current code and the new Eternia deployment instructions are the concrete references.

## Skins, wearables, and model support

Hytale's official model schema exposes server model assets in `Server/Models`, references common `.blockymodel` and texture assets, and includes attachments, animation sets, hitboxes, and camera settings. This establishes an asset representation for custom models and attachments; it does not prove every desired player-cosmetic equip flow works on Eternia's pinned build. [Official model asset reference](https://docs.hytale.com/assets/models/).

The official Java API exposes `CosmeticsModule.createModel`, `getRegistry`, `parseSkinFromJson`, and `validateSkin`. The protocol's `PlayerSkin` contains separate clothing/accessory fields including overtop, pants, shoes, gloves, and head/face accessories. These are useful implementation surfaces, not a promise that a server can add globally owned items to the official account character creator. [CosmeticsModule API](https://docs.hytale.com/api/com/hypixel/hytale/server/core/cosmetics/CosmeticsModule), [PlayerSkin API](https://docs.hytale.com/api/com/hypixel/hytale/protocol/PlayerSkin).

**Proposed capability proof:** implement one Eternia wearable and one full appearance preset in a staging server. Verify third-person and first-person rendering, armor combinations, animation, reconnects, world transfers, unequip, and restoration of the original appearance. Preserve normal collision and movement behavior for cosmetic purchases. Keep entitlement ownership separate from the rendering adapter. Scope purchased cosmetics to Eternia unless an official cross-server entitlement mechanism is later confirmed.

Do not make paid cosmetic catalog expansion depend on the prototype until both the asset-loading and player-application paths are proven against the installed server. If only full models work initially, document that limit and defer wearable sales rather than silently substituting a different product.

## Product rules that affect this plan

Hytale's Server Operator Policies, version 1.1 effective January 13, 2026, require truthful purchase terms and refund disclosure. For Teen/Mature servers, gameplay-affecting purchases must be disclosed. Paid plot capacity, teleport convenience, and mailbox capacity should therefore be reviewed as functional purchases rather than automatically labeled cosmetics. All Ages servers cannot offer paid random items. [Server Operator Policies, sections 4-5](https://hytale.com/server-policies).

The policy's additional Listed Server rules require compatibility with official player-owned cosmetics, constrain theme overrides, and prohibit misleading official-looking cosmetics or blocking official cosmetics to force purchases. The same page says the Listed Server program is not yet available. Preserve original appearances and official cosmetic compatibility in the design, and recheck applicable terms before store launch. [Server Operator Policies, introduction and section 7](https://hytale.com/server-policies).

## Darktale reference: verified features only

The developer-authored CurseForge page for **Darktale's Territory Warfare** describes a Faction Finder for browsing factions and diplomacy, My Faction for faction details, rank customization, and per-member city permissions. It explicitly distinguishes allied interaction with doors/beds from benches/chests. The page contains rank-customization screenshots. Its visible last-update date is April 28, 2026. [Darktale author's project description](https://www.curseforge.com/hytale/mods/darktales-territory-warfare).

The rank screenshots could not be fetched during this research. The exact permission list, inheritance rules, promotion hierarchy, audit-log behavior, and menu controls have **not been verified**. Do not attribute an invented guild menu to Darktale. The usable inspiration is a separate faction-management UI, customizable ranks, and explicit action-level interaction permissions. Eternia's eventual permission matrix and guild-house management screens are its own design.

## Required proofs before dependent feature work

| Proof | Success condition |
| --- | --- |
| Tebex recipient identity | A test purchase maps to the same verified game UUID before and after a display-name change. |
| Delivery ledger | Duplicate notifications and command retries create one grant; an interrupted grant resumes without loss. |
| Supporter lifecycle | Renewal extends access; cancellation preserves the current interval; end-of-term removes only time-bound benefits. |
| Website identity | Eternia's new OAuth application signs in through the deployed callback; state/PKCE replay fails; issuer/subject and game-profile UUID map to the correct authenticated game account. |
| Cosmetic adapter | A sample wearable and model survive reconnect/world changes and unequip restores the original appearance. |
| Provider compatibility | The pinned Hytale build and selected Tebex release pass the integration smoke tests in staging. |

These checks belong in the implementation milestones. No payments, external accounts, plugin installation, or runtime code were changed during this research.
