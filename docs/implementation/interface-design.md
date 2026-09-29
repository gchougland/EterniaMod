# Citadel interfaces

Eternia's native menus use the same geometric medieval style as the website: deep green panels, ivory text, brass primary actions, and sage highlights. The source of the native style is `Common/UI/Custom/EterniaMod/Theme/Citadel.ui`. Do not add paper, wood, stone, noise or other detailed textures to these interfaces.

## Choose a layout for the task

- The main service shell has persistent navigation at the left. Housing and the welcome page use an illustrated overview with the next useful actions.
- Guild management separates the member or permission list from its editing fields. Assign roles by their names; generated storage keys stay hidden. The noticeboard has its own reading and writing surface.
- Mail places the inbox beside the letter reader or multiline composer. Sending attachments opens the parcel desk while preserving the recipient, subject and message.
- Trades show your offer and the other player's offer in separate panels. Updating an offer clears both confirmations, and the final action still rechecks the actual current offer.
- Seasons have visible Rewards and Quests tabs. Rewards are paired into free and paid lanes by level and reward index. Quest cards show a human title, the objective and required quantity, progress, and the XP reward. Completed quest XP is granted automatically by the normal activity service.
- Collections show the selected titles and outfit above Appearance, Pets and Owned items. The Crown Store has its own catalog and checkout page.
- Small selection flows use `ChoicePage`, while house and prop placement keep a narrow control panel so the world preview remains visible.

## Add a page or a control

The service shell is `ServicesPage.ui`; append one purpose-specific body beneath `#ServicesBody`. Each body preserves the common binding IDs for title, description, tabs, rows, status, three optional fields, and three footer actions. Its content region scrolls, so long collections and quest lists do not push actions off screen. Keep event routes server-owned and retain the existing confirmation and authorization checks.

Database work belongs on the shared service-menu executor, including Crown Store loading and checkout. `PremiumShopPage` renders a detached `PremiumShopSnapshot`; worker results return to the world thread only if the original page, player reference and request generation are still current. Preserve the review receipt when checkout or its following refresh fails: retrying that review must return an already committed order rather than purchase the item again.

Use 16 px text for normal labels and actions, 24 px titles, and 14 px secondary captions. Buttons should usually be 44–48 px high. `UiPresentation.buttonWidth` budgets each action label using conservative glyph widths and padding; shorten an action when its meaning remains clear instead of shrinking the type. Put longer explanations in the adjacent wrapping label. Choice pages show six entries per page. A row's height grows with its explanatory text. Arbitrary guild notice titles, prefab filenames and store categories use `WrappedSecondaryButtonStyle`, measured heights and scrolling lists instead of shortening their names. The plot-claim panel reserves enough vertical space for all movement, camera and confirmation controls.

Native label alignment values are `Start`, `Center` and `End`. For right-aligned label text use `HorizontalAlignment: End`; `Right` belongs to layout direction and fails the client's `LabelAlignment` parser. The Java markup audit checks literal label alignment declarations across the packaged UI files. Server command serialization alone does not parse or render those client documents.

Change dimensions with `UICommandBuilder.setObject("#Element.Anchor", anchor)`, using the native `Anchor`/`Value` types. Selectors such as `#Element.Anchor.Height` are not supported client markup properties. Replacing an Anchor must preserve existing sibling dimensions and offsets; `UiAnchors` contains the common row/button helpers. Native serialization checks verify their retained spacing.

Use catalog display names and native item translations. `UiPresentation.contentName`, `itemName` and `friendlyId` provide fallbacks for content without a display name. Never present an account UUID, entitlement key, role key or catalog key as the primary player label.

The geometric icons live in `Common/UI/Custom/EterniaMod/Icons`. Both SVG sources and native PNG renditions are generated from the same polygons by `scripts/generate-citadel-icons.py` (Python with Pillow). Run that script after changing the geometry. Native `.ui` files use the PNG renditions; the SVGs can also be used by the website. Existing names are `housing`, `guild`, `mail`, `season`, `collection`, `worlds` and `crown`.

## Verify changes

`CitadelPresentationTest` verifies shared layout bindings, Crown Store scroll regions and button widths, quest wording, display-name fallbacks and packaged geometric assets. `NativeUiSmoke.validate()` runs the actual choice-page builder inside the initialized smoke server and checks serialized command widths, six-entry pagination, disabled navigation at the start, and packaged UI documents. Run the regular Java suite and the isolated native smoke after editing page code or assets.

`PremiumShopSnapshotTest` covers immutable view data, repeatable quantities versus already-owned unlocks, and retrying a committed purchase after its snapshot refresh fails. Its build contract also prevents database reads from being added directly to the native render method.

These tests do not render the Hytale client. During a local playtest, open every service at the client UI scale you use, inspect long item names, page a large collection, read and compose a full letter, select a guild role, and inspect both locked and claimable season rewards. Check that text wraps without covering actions and that confirmation dialogs preserve the exact action being reviewed. Client rendering is the final visual acceptance check.
