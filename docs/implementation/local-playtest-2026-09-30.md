# Local Hytale playtest, 30 September 2026

Tested the running client as Hexvane in the local playground using Computer Use. These results describe direct client observations, separate from automated checks. No disconnect or CustomUI error occurred during the checks below.

## Passed in the client

| Area | Observed result |
| --- | --- |
| Main menu | Sidebar labels fit, including Season passes. Both wallet amounts and distinct currency icons are visible. |
| Quest journal | Direct navigation works. Goals, counts, XP and Coin rewards are readable. Empty and partially filled progress bars looked clean. |
| Season passes | Foundations rewards have thumbnails and claimed states. Switching to Village Field Notes and back preserved Foundations progress at 3,400 XP. |
| Crown Store | Distinct category icons and the jacket preview appear. Buying Cacti deducted exactly 80 Crowns and displayed the success message. |
| Player shops | Listings show item icons, stock and Coin prices. Visiting Iris's shop arrived outdoors. Buying one Bread deducted 5 Coins, reduced stock from 19 to 18, and reported delivery to inventory. Bread was visible in inventory. |
| Mailbox | The sample welcome letter opens. Opening attachments retained the unsent letter's recipient, subject and body without disconnecting. Attempting to deposit with an empty hand gave a readable message. |
| Housing navigation | My plots identifies the existing personal plot and exposes Locate and Move. Locate from the activity world correctly explains that a Hub or public portal is required. |
| Collection | Equipped appearance and pet management pages open. The jacket and title entries have distinct icons. |
| Guild | Existing guild members and role templates display correctly. The invitation field is empty. No membership or permissions were changed. |
| Playground | Village, activity trials and harvest travel controls work without a setup failure. Returned to the village at the end. |

## Fixes made after testing

- Crown Store prices and balances now sit outside the scrolling description, above Confirm purchase.
- Crown Store item buttons have an explicit height so they do not collapse into thin strips.
- Season pages use their existing top tabs without repeating the same navigation below the rewards and quests.

The rebuilt jar passed package verification and all 15 selected presentation, content image and shop snapshot tests. Build output is in `build/local-playtest-check.log`. These changes still need a server restart and a fresh client visual check. The running session was not restarted for this test.

## Remaining checks

Computer Use handled menus, chat commands and inventory reliably, but camera movement and gameplay hotkeys did not respond reliably. This is a testing limitation, not a confirmed mod defect. The following are not verified by this session:

- Road tool keybindings, road placement and removal of plants or rubble.
- House and guild placement, cancellation, recovery and packaging.
- Plot borders during movement and the birds eye preview.
- Pet following and looking at the player during movement.
- Completing a quest, receiving its Coin reward and seeing the completion toast.
- Depositing an actual attachment stack, sending a parcel and collecting a pending delivery.

Further polish observations: some prop and pet thumbnails are dark at menu size. The Collection banner still uses the same general icon across its subpages. Harvest travel initially faces away from the crops. Some travel restrictions appear in chat behind the open menu.

## Local account changes

Bought one Cacti for 80 Crowns and one Bread for 5 Coins using local test balances. Coins changed from 1,995 to 1,990; Crowns changed from 2,520 to 2,440. Foundations is active again. No letter was sent, no plot was moved and no building was changed. The character was left at the example village.

## Quest rotation and portal follow-up

The final rebuilt plugin was started against the saved `run` playground with `runServerNoSync`. The final clean boot loaded the new assets. No worlds, plots or inventories were reset.

| Area | Observed result |
| --- | --- |
| Currency panel | Coins and Crowns fit inside the new dark, framed panel in the main menu and Travel. |
| Quest journal | Daily and weekly sections display clear goals, progress and rewards. The catalog contains 30 daily and 15 weekly definitions, with three of each active. |
| Guild portal | Entering the portal volume by teleport opened Travel without pressing the interaction key. Closing Travel while still inside did not immediately reopen it. Physical walking through the portal was not separately tested. |
| Plot border | The final ground ribbon has a narrow teal line, gold diamond details and small rising lights. A straight edge and a corner were inspected in the client. The two sides joined without extending past the corner. |

Visual testing caught and corrected two particle issues: the first emitter could expire before rendering, and the first texture scale made each rune eight blocks wide. The final emitter lasts long enough to render its burst and each tile spans one block.

The final test and package verification run passed: 259 tests total, 251 passed, 8 skipped, zero failures or errors. See `build/quest-polish-final-tests.log`. Automated coverage includes UTC daily and Monday weekly boundaries, one reward per quest period, delayed activity delivery, all 45 quest choices, native target variants, harvest cycle identity and the original Foundations migration.

Native combat and harvest completion, and audible completion sound, remain unverified in the client. The mapped harvest prompt appeared, but Computer Use did not reliably activate gameplay input. This does not establish a harvest failure. The follow-up made no purchases or plot changes and returned the character to the village.

## Portal theme and spacing follow-up

Checked the revised main menu, Crown Store and quest journal in the running client. Currency labels now have 18 pixels of horizontal padding and 12 pixels of vertical padding. The Crown Store header has a 16 pixel gap before the divider. Quest goals display the shortened wording, including "Defeat 8 undead" and "Mine 4 adamantite ore".

Public and guild portal items now use the shared Citadel arch effect. Inspected the existing guild portal from the front and the reverse side, including an oblique view against the hall. The teal, faceted center is translucent and the building remains visible through it. Gold gems and a rotating compass match the plot border. Corrected overlapping compass particles and changed the arch from simultaneous bursts to staggered continuous emission after the user observed blinking. The final arch and ground seal retain constant opacity while the interior and rising lights animate.

The complete test run passed with 260 tests total, 252 passed and 8 skipped. The added regression check verifies that quest wording changes preserve completion, XP and Coins and cannot change reward amounts. Logs: `build/portal-polish-tests.log` and `build/portal-polish-package.log`. The final particle assets were copied into the running development resources for visual checks and packaged without deleting that live resource directory. No purchases, buildings or inventories were changed during this follow-up.

## Collection grids and steady portal follow-up

Started the rebuilt plugin against the saved playground and inspected the Collection in the Hytale client. Pets and Decorations now use three column cards with 192 pixel preview areas, names and short status lines. Verified the Rootling card opens its management page, decoration counts remain combined (including available, packed and placed quantities), and Housing inventory opens from the decoration footer. Only the top Pets tab remains; the repeated bottom navigation was removed. No purchases, assignments, buildings or inventory quantities were changed.

Portal testing found that the current client alpha-tests model quads even with RequiresAlphaBlending, making the first model center opaque. The final arch and ground seal are fixed model geometry with crisp edges. The center remains a constant-opacity particle capped at one live copy, with a one hour lifetime. Inspected both sides of the guild portal and several compass animation cycles: the hall remains visible through the center and the arch does not brighten as particles overlap. The one hour replacement boundary was not observed. Both public and guild portal assets reference the same model and effect.

The complete test run passed: 260 tests total, 252 passed, 8 skipped, no failures or errors (`build/collection-grid-tests.log`). Final packaging and release verification passed (`build/collection-grid-package.log`); the packaged model, texture, center effect and card UI match their source files. The saved local playground remains running with the revised assets.


## Organized Collection, Prowl Shirt and Horse follow-up

Verified the eight separate Collection categories in the saved playground. Decorations fit four cards per row, with combined counts and large previews. Houses, Wardrobe and Mounts show their own content; the free mailbox service entitlement is hidden. Wardrobe shows actual jacket and shirt previews.

Claimed the first paid Foundations Prowl Shirt reward from the local account and equipped it. The initial ordinary model attachment did not render on the player, confirmed by the user. The replacement cosmetic equipment display renders the red shirt and P logo in the native character preview, without adding an armor inventory item or changing the displayed defense. After the user reported sleeve clipping, increased sleeve width and depth and matched the current shoulder hierarchy. Rotated the preview to inspect both sides. Unequipping restored the original purple outfit. The final character is left in that original outfit with the shirt available in Wardrobe. Full combat animation and all body variations were not tested.

Summoned Horse from the Mounts card and verified the player immediately rides it with the native Dismount prompt. The user pressed their mapped F key and confirmed the horse disappeared. The following screenshot also showed normal first person view without the horse. Computer Use itself did not reliably activate that gameplay input. Disconnect, teleport and death cleanup are implemented but not separately exercised in this playtest.

Automated checks passed: 264 tests total, 256 passed, 8 skipped, zero failures or errors. Coverage includes safe additive reward migration, preservation of prior claims, category classification, unchanged native clothing state and projection of only the displayed chest equipment. See `build/collection-organized-final-tests.log`. The final model and previews were packaged and checked against source bytes; see `build/collection-final-package.log`. The saved server remains running with the final Java code and hot reloaded sleeve assets. No purchases, buildings or plot changes were made in this follow-up.
