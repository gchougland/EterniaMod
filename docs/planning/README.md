# Eternia implementation plan

Status: proposed design, based on source inspection on 2026-09-12. This is a plan, not a claim that the features or proposed content formats already exist.

Eternia should become a social MMO server with a shared housing world, an approachable hub, player commerce, guild neighborhoods, and permanent season passes. Build a small working example of every system, then expand through content definitions. Aetherhaven is a source of assets and proven interaction patterns; Eternia owns its rules, storage, identifiers, and progression.

## Read the plan

| Document | Purpose |
| --- | --- |
| [Implementation roadmap](implementation-plan.md) | Architecture, dependency order, work packages, scope, and acceptance gates |
| [Housing and guild specification](housing-and-guilds.md) | Exact claim rules, placement, moving, guild neighborhoods, departure handling, and permission menus |
| [Player systems](player-systems.md) | Hub, travel, mail, trading, collections, pets, season passes, Tebex, and website |
| [Content authoring guide](content-authoring.md) | How the planned system will accept additional houses, props, palettes, quests, rewards, NPCs, and shop products |
| [Website stack and setup](website-stack.md) | Aetherhaven's Node/Express/Railway tools, a separate Eternia website, and new Hytale OAuth client setup order |
| [Local development](local-development.md) | Local Hytale, website and database workflow, account/payment fixtures, and the small set of real-provider checks |
| [Shared theme](theme.md) | Citadel: matching medieval geometry, colors, typography and components for game GUIs and website; [design tokens](theme-tokens.json) |
| [Codebase audit](codebase-audit.md) | What Eternia already implements, what needs replacement, and Aetherhaven reference paths |
| [Integration research](integration-research.md) | Verified external integration facts and unresolved Hytale capabilities |

## Main recommendations

1. Keep Eternia's placement, preview, camera, packaging, and prefab authoring foundations. Replace their authority and persistence layers before opening housing to players.
2. Use one shared housing world with protected public roads and portals. Ordinary plots grow from public anchors; guild neighborhoods grow from a guild root and are anchors only for that guild.
3. Use the hub merchant as a searchable directory. The first release sends buyers to a safe public shop entrance at the seller's house; the seller can be offline. Keep checkout behind one service so buying directly from the hub remains an easy later product change.
4. Make the Housing Ledger the player's central housing menu and token workshop. Unlocks live on the account; tokens reference those records, so losing an item cannot lose a purchased house or duplicate a move credit.
5. Ship direct Tebex purchases first. Keep an explicit place for premium currency, but add it when premium-currency rewards and purchases are introduced. Earned trading currency is a separate balance.
6. Give each permanent season a free and paid reward track sharing the same XP. Players select one active season, can switch freely, and keep all progress forever.
7. Use Hytale OAuth/OIDC login and the Node/Express/Railway website tools already used in Aetherhaven. Build Eternia's own pages and account features. Once the website URL is ready, the owner creates a new OAuth Client Application for Eternia and configures its callback.
8. Develop and test locally by default. Use a local Hytale server/client, Node website, PostgreSQL and isolated identity/payment fixtures; use Railway staging for final hosting and real-provider checks that cannot be established locally.
9. Apply the Citadel theme across all screens: flat geometric medieval forms, pine/limestone/brass/sage, shared component states and vector source artwork. No detailed textures, parchment grain, wood/stone texture, noise, or photorealistic GUI backgrounds.
10. Show plot borders with a default-on, proximity-faded wispy fog curtain, never more than one block above the terrain. It has no player toggle and is separate from the placement grid and admin wireframes.

## Defaults used to make the plan concrete

These are recommendations that implementation can proceed around, not additional requirements attributed to the owner. They do not require a large player-facing settings system.

| Decision | Proposed default |
| --- | --- |
| Active ownership | One personal plot per account; one guild membership per account; one plot and one main house per guild |
| Personal plot shapes | 24 × 24 free; 32 × 32 paid. Support footprint definitions; launch additional personal shapes only after checking useful buildable space |
| Guild plot shapes | 48 × 48 or 36 × 64 free; 64 × 64 or 32 × 128 paid. Rectangles can rotate 90 degrees |
| Guild eligibility | Five distinct current member accounts to claim; dropping below five later does not automatically evict a guild |
| Guild root range | At most 240 horizontal blocks from a public hub portal, with a valid public road connection planned before committing |
| House setbacks | Five blocks from plot borders and road boundaries; additions count as part of the house |
| Placement distance | Horizontal distance between claim boundaries, not movable house walls; exact definition in the housing spec |
| Guild rule interpretation | Later clarification controls: attached member plots anchor other members of that guild and ignore public-road proximity, while public road protection remains absolute |
| Shop location | Hub search → visit house → buy from an offline-capable shop at its public entrance |
| Mailbox | Free functional mailbox for everyone. Paid appearance first; expanded attachment capacity is an optional later product |
| Login routing | More than 30 minutes offline → safe spawn inside the house; 30 minutes or less → normal last location |
| Season pacing example | 12-week reference cadence, 30 levels, 60,000 total XP; approximately 60 hours without quest bonuses; seasons never close |
| First paid rank | One monthly supporter subscription; display perks and cosmetics, with no combat or XP advantage |
| Deployment | One authoritative Hytale server process with multiple worlds; a new Node/Express website/API on Railway using npm and plain browser JS like Aetherhaven; PostgreSQL for durable MMO records is a deliberate addition |
| Development | Local components and deterministic fixtures first; real OAuth locally if a permitted callback is available, otherwise a small Railway staging check |
| Visual direction | Citadel shared theme; consistent geometric heraldry, pointed arches, restrained borders and flat colors |

## Decisions to revisit at the relevant milestone

The plan does not stop on these questions. Each has a default above or a contained feasibility check.

- Confirm the guild portal radius and rectangular shapes against the actual housing-world terrain. Equal area does not mean equal usable interior space.
- Validate the house-shop travel experience with players. If travel hurts trading too much, enable hub checkout through the same transaction service.
- Choose season presentation cadence and the number of reward tiers after measuring real gameplay XP. The five-hour target is a pacing target, not a weekly requirement or time gate.
- Confirm whether “unique Aetherhaven quests” means an optional Aetherhaven integration running on Eternia, or new Eternia-specific activities. Use a namespaced activity adapter either way.
- Prove supported full-skin and wearable rendering and Tebex fulfillment acknowledgement on the exact server build. Website OAuth follows the inspected Aetherhaven implementation; test the new Eternia client and the mapping between OAuth profile identity and in-game UUID before account purchases.
- Once a supported website URL/callback is available, the owner creates a separate Hytale OAuth Client Application. Prefer a permitted local callback for testing; otherwise use an approved temporary HTTPS route or Railway staging. Keep sign-in visibly unavailable outside the local fixture harness until configured; site implementation does not wait on this external setup step.
- Decide whether pet care and larger paid mailboxes add enough value to follow the initial pet and mailbox systems. Both are explicitly optional in the feature request.

## Scope boundary

The plan includes every requested feature or an explicit follow-up for tentative features. It does not add combat classes, a large quest campaign, a minigame implementation, a broad catalog of purchasable content, or cross-server trading. The minigame NPC ships with “Coming Soon”; the adapters make actual minigames and their pass objectives straightforward to add later.
