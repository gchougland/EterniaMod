# Citadel: Eternia's shared interface theme

Status: implemented styling, **2026-09-13**. Citadel gives the website and in-game menus the same medieval identity through simple geometry and clear typography. The latest refinement adds fine borders, restrained gradients, self-hosted Google Fonts on the website and a Cinzel game wordmark. See [implementation and validation](../implementation/citadel-styling.md). The later rollout notes below retain the original design rationale; no website deployment is implied.

Use the [local development workflow](local-development.md) to review the website locally and the actual Hytale menus in a development world. The [planning overview](README.md) connects this theme to the feature specifications. The interactive website preview is a visual review aid; native UI support is established by testing `.ui` files in the pinned game build.

The planning preview contains game-menu and website views with local selection/placement-feedback interactions. It was checked in a local browser at desktop and narrow 320/360-pixel widths, with the mobile overflow corrected; its script passed a syntax check and produced no observed browser script errors. The token JSON parses. These checks do not constitute a native Hytale rendering test.

## Visual language

Use deep ink backgrounds, pine panels, warm limestone text, brass primary actions, and sage interaction accents. Medieval character comes from a pointed arch around a featured illustration, a short banner notch, restrained chamfered corners, diamond separators, and a simple heraldic crest. Use one main motif per component. Keep dense trading, guild-permission, and inventory views mostly rectangular so labels and columns remain easy to scan.

Interface surfaces use clean geometric fills, restrained gradients and fine inset rims. Do not add photographs, paper grain, parchment, wood, stone, metal textures, noise, ornate filigree or textured text. Brass is a color role, not a shiny material effect. Leave space around ornament and keep it outside text, hit targets, focus indicators and progress measurements.

The theme is shared across the two surfaces; their layouts serve their respective input methods. A desktop website can use a persistent sidebar, while a native placement menu must preserve a useful view of the world. Consistent names, icons, button states, status labels, and content hierarchy matter more than identical pixel placement.

The [in-world plot boundary effect](housing-and-guilds.md#default-plot-boundary-fog) uses faint limestone/sage wisps in a ground-hugging curtain at most one block high. It appears automatically near borders without a player toggle. Keep the effect sparse and soft through particle opacity/motion; GUI panels and website artwork retain flat geometric fills. Red/green placement validity remains a separate temporary grid with textual reasons.

## Palette and semantic roles

| Token | Color | Use |
| --- | --- | --- |
| `canvas` | `#101E22` | Page canvas, input wells, solid separation around focus rings |
| `panel` | `#192D31` | Main panels, dialogs, cards |
| `raised` | `#233C3E` | Selected rows, raised groups, secondary hover fills |
| `border` | `#45615D` | Decorative separators and nonessential panel edges |
| `text` | `#F0E8D5` | Primary text and neutral icons |
| `muted` | `#B5C3B8` | Secondary text, helper copy, readable disabled labels |
| `primary` | `#D8B66B` | Main action fill, selected marker, restrained heraldic detail |
| `onPrimary` | `#142528` | Text and icons on brass or solid danger actions |
| `primaryHover` | `#E8CA87` | Hovered primary action |
| `accent` | `#85C5B3` | Essential input outlines, focus, navigational accents |
| `success` | `#91C98B` | Valid placement, delivered state, success label |
| `danger` | `#E69085` | Invalid placement, error, destructive action |
| `warning` | `#E0BC73` | Pending changes, grace-period notices, caution |

The JSON separates named colors from semantic roles so a later implementation can change a shared role without editing every menu. Feature definitions refer to roles such as `stateDanger`; they do not invent new hex colors for each season or guild. Player-selected heraldry and item art can vary within their own content area without recoloring the navigation and controls.

## Type, spacing, and geometry

Website display headings use self-hosted `Cinzel, Georgia, serif`. Body text and controls use self-hosted `Source Sans 3` with a system sans-serif fallback. Use sentence case for actions and sentences; short category labels may use restrained uppercase. Do not set body text in an imitation handwritten or blackletter face.

| Role | Website size | Candidate native UI size | Treatment |
| --- | --- | --- | --- |
| Page display | 44 px | 28 units | Serif on web; one short title |
| Heading | 28 px | 24 units | Section or dialog title |
| Section | 22 px | 20 units | Semibold grouping label |
| Body | 16 px | 16 units | Regular; web line height 1.5 |
| Control label | 14 px | 16 units | Semibold; concise action |
| Caption | 14 px | 14 units | Secondary information, never essential tiny text |

Native sizes are starting values to validate at supported client resolutions and UI scales. Use a supported Hytale font; a suitable native display face is optional after testing. Do not assume Georgia, web fonts, CSS line-height, or a web pixel-to-native-unit equivalence. Keep the shared hierarchy when native font metrics require a different size.

Use the spacing scale **4, 8, 12, 16, 24, 32, 48**. Typical panel padding is 24, compact panel padding 16, control gaps 8, component gaps 12, and section gaps 32. Start with 44-high buttons and at least 44-by-44 hit areas; a compact 36-high visual control still needs a 44-high hit area. Allow text wrapping and expansion instead of shrinking long translations or names.

Use 8-unit panel chamfers and 4-unit control chamfers. Pointed arches belong on a large content frame, crest, or hero feature; avoid repeated arch outlines around every field. Website geometry can use CSS and code-native SVG. For native Hytale UI, use verified primitives or lossless PNGs exported from the same clean vector sources. Native SVG support is not assumed.

Icons share a 24-unit viewbox and a 1.75-unit stroke, with 16/20/24/32 display sizes. Use simple house, shield, envelope, compass, paw, chest, title-ribbon, and calendar/book silhouettes. Match optical weight and stroke endings. Every actionable icon has an accessible name or visible label; an unfamiliar symbol never carries an action alone.

## Controls and states

| Component/state | Visual behavior |
| --- | --- |
| Primary action | Brass fill with dark `onPrimary` text; hover uses `primaryHover`; press returns to brass with a thin dark inset indicator |
| Secondary action | Panel fill, limestone text, sage outline; hover uses raised fill and brass outline; press uses canvas fill |
| Destructive action | Danger text/outline on panel; deliberate hover may use danger fill with dark text; destructive confirmation still uses specific wording |
| Focus | Two-unit sage ring with three-unit offset and solid canvas separation; remains visible beside a brass action and outside clipped decoration |
| Selected | Raised fill plus brass marker and a check/Selected label; do not rely on the fill change alone |
| Disabled | Panel fill, muted readable label, decorative border, and a visible reason where needed; no misleading hover response or active handler |
| Loading | Retain the action label or name the operation; show a simple progress mark and prevent repeat submission; do not replace the entire panel |
| Input | Canvas fill, limestone value, muted helper/placeholder, sage boundary; error adds danger outline plus message and icon |
| Success/warning/error | Colored icon and short text label with relevant next action; keep the main explanation in limestone or the verified status color |

Buttons should say “Claim plot,” “Visit shop,” “Buy 3 for 120 coins,” or “Claim reward.” Keep cost, recipient, permanence, subscription interval, and consequences adjacent to the action where the feature requires them. The theme does not replace the purchase, permission, or placement validation contracts.

Website transitions may last about 120 ms; reduce to zero with reduced-motion preferences. Native state changes can be immediate. Avoid ambient particles, parallax, animated textures, pulsing gold, or movement that suggests an unavailable action is urgent.

## Matching screen components

| Shared component | In-game use | Website use |
| --- | --- | --- |
| Crest and service heading | Housing Ledger, guild menu, greeter | Header, account navigation |
| Category tabs | Styles, palettes, props, additions | Collection, store categories |
| Item card | Asset preview, owned count, fit result | Product preview, ownership, price/terms |
| Status strip | Placement reasons, saved/moving state | Account, delivery, plot status |
| Member row | Presence, role, permitted actions | Guild summary where authorized |
| Permission group | Plain action labels and explicit toggles | Future authorized guild-management view |
| Reward track | Free/paid rows sharing level progress | Current and archived pass progress |
| Message row | Sender, unread marker, attachments | Counts/status only under the initial website scope |
| Confirm dialog | Claim, move, purchase, role change | Account-sensitive action or checkout handoff |

A dialog has one clear title, a short explanation, a scrollable body when needed, and a stable action row. Keep cancel/back in a consistent position. Placement grids use success/danger colors together with a validity label and rule reasons; protected road cells have a distinct patterned geometry or outline so the map is readable without color.

Store, pass, and housing illustrations sit in flat frames with consistent padding and a clean background. Price chips and ownership labels use the same semantic styles. Paid/free distinctions use text and a small icon, with equal legibility on both tracks.

## Accessibility and measured contrast

The website targets at least 4.5:1 for ordinary text and 3:1 for large text. Essential graphical controls and state indicators target at least 3:1 against adjacent colors. These thresholds follow [W3C text contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) and [W3C non-text contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html). Apply the same legibility targets to native menus; a palette calculation is not a claim of complete accessibility conformance.

The following ratios were calculated from the exact opaque hex colors using sRGB relative luminance. Values are rounded for display; the underlying thresholds must be checked without rounding during implementation.

| Pair | Contrast |
| --- | --- |
| Limestone text / panel | 11.78:1 |
| Limestone text / raised | 9.63:1 |
| Muted text / raised | 6.41:1 |
| Dark action text / brass | 8.17:1 |
| Dark action text / brass hover | 9.98:1 |
| Sage / raised | 5.95:1 |
| Danger / raised | 4.86:1 |
| Decorative border / raised | 1.75:1 |

`border` is intentionally quiet and must not be the sole visible boundary of an active input, checkbox, selection, or other essential control. Use sage or another verified semantic color for those boundaries. Brass-filled controls use dark text, not limestone. Focus around a brass action needs the canvas separation defined above.

Use icons plus text for valid/invalid, online/offline, selected/unselected, and free/paid states. Website implementation needs keyboard operation, visible focus, semantic labels, useful live status messages, and correct dialog focus entry/return. Test native keyboard/controller behaviors supported by the game rather than claiming web semantics apply there. Check narrow website widths, 200% browser zoom, long names, long translations, native UI scaling, and missing item art.

## Content-art guidance

Author interface ornaments and symbols as clean vector geometry. Keep source files editable, use a small number of flat fills, and export native assets without sharpening, photographic shadows, paper texture, or compression artifacts. Keep labels as live UI text instead of embedding words into an image. Transparent PNG exports preserve the vector style when the native UI requires raster assets.

Product cards may show the actual isolated game item, character, pet, or house so players can recognize what they receive. Place it in the flat Citadel frame; do not turn an entire in-game UI screenshot, photographed object, parchment placard, or textured environment into a button or panel. If a screenshot is needed to explain a build, treat it as clearly bounded content, never interface chrome. Optional illustrations use simple flat shapes and limited colors, not detailed painted textures.

## Minimal native migration

The current placement pages already define `TextButtonStyle` states and explicit hex label colors, while their page/title chrome imports shared `$C` styles. This makes an incremental styling layer practical, but it does not establish every proposed shape or font as a native feature. [Current placement styles](/C:/Users/gchou/Documents/HytaleModding/EterniaMod/src/main/resources/Common/UI/Custom/EterniaMod/BuildingPlacementPage.ui:1), [Current title/container use](/C:/Users/gchou/Documents/HytaleModding/EterniaMod/src/main/resources/Common/UI/Custom/EterniaMod/BuildingPlacementPage.ui:105).

1. Add a proposed Eternia-owned shared style file at `Common/UI/Custom/EterniaMod/Theme/Citadel.ui`. Map token roles to supported `Background`, `TextColor`, font-size, and button-state properties. Keep the JSON as a design source until an explicit validated build generator is implemented.
2. Migrate one placement page first: title/body styles, flat panel chrome, primary/secondary buttons, and icon states. Replace textured shared decoration only where Eternia owns the page; keep existing element IDs, event bindings, anchors, and placement behavior.
3. Export the few arch/banner/icon assets needed by native UI from the same vector source used by the website. Test scaling and transparency in-game. Use a plain flat shape fallback if a decorative shape cannot render cleanly.
4. Apply the verified styles to building placement, prop placement, pickup, and prefab browsing, then new feature menus. Avoid globally editing the engine's `Common.ui` or restyling unrelated interfaces.
5. Review the same representative cards, states, and typography locally in the browser and the game. Confirm disabled controls, focus/selection, long text, safe action areas, and useful world visibility before expanding.

A future build step may emit website CSS variables and native `.ui` fragments from the token reference. Its generator, supported property mapping, and validation are implementation work; the JSON is not directly loadable as a Hytale menu.
