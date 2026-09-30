# Player usability and local acceptance

## Finding and moving plots

Use **/e myplots** anywhere, or **Housing → My plots**, to see personal and guild properties. Each entry shows the world, exact X/Z bounds and durable state. Land is already claimed once the second claim confirmation succeeds; placing a building is a separate step. Escape never removes a confirmed claim.

**Locate plot** travels to an unobstructed yard position when used from a valid public travel point (including the Hub). It also works for an empty claimed plot. It does not require the premium home teleporter. Outside public travel range, the directory still provides coordinates.

**Move plot → Confirm move** reserves a move credit, evacuates occupants and packs the entire property. The confirmation displays the available credit count. Use the **Plot Deed** or **/e claim** to restore the packed property. There is no separate move-tool item. Guild actions recheck role permissions on the owning world thread. Moving/restoring operations are displayed explicitly and never silently deleted.

Escape retains an unconfirmed plot draft during the server session. Reopening the deed restores its scope, dimensions and coordinates, with confirmation reset. Cancel discards that draft and returns to My plots. It does not unclaim an existing property. Building and prop previews likewise resume through the ledger, housing menu or the same placement item; Cancel removes the actual preview session.

## Navigation and visits

- **/e quests** opens the active pass's quest journal directly; the ledger and service sidebar also link to Quests.
- The ledger's first entry opens the main menu. Navigation lists scroll when needed.
- Guild form values clear when the form changes meaning, including guild creation changing to invitations.
- Player shop travel uses the yard outside the prefab footprint, rather than the indoor login spawn. Visitors inside personal plots can use doors, including after reconnecting, but gain no container or building permissions. Guild estate doors retain their role checks.
- **Return from visit** in the market menu, or **/e shopreturn**, returns to the saved departure point while standing in the visited plot. Visitor rights clear on disconnect. World arrival checks load the relevant vertical sections before testing the floor and headroom.

## Native inventory saves

Whole-player cloning calls Hytale's legacy `Entity.clone` implementation. In the current server it fails because Player is registered as a component rather than in the legacy entity codec map. Transfers use the same immediate shallow-holder serialization approach as native PlayerSavingSystems, restricted to the verified DiskPlayerStorage provider. The disk save is acknowledged before releasing escrow rights. Receipt BSON is copied during serialization so later receipt cleanup cannot modify a queued write. Actual save failures are logged with their cause and retain recovery protection.

The isolated native smoke reproduces the Player clone failure, saves an inventory and receipt with a non-cloneable runtime component, modifies the live values, then reloads disk data to verify that the saved inventory and receipt stayed together.

## Catalog pictures

Native menus resolve images by stable entitlement ID under `Common/UI/Custom/EterniaMod/Catalog/<kind>/<id>/icon.png` and `screenshot.png`. Additions use the prop directory, matching their native ownership IDs. Missing custom images use the shared category artwork. Never put website URLs into native image fields.

Crown Store cards use icons, with screenshots in the purchase detail view. Housing choices and Collection entries use icons. Bundled houses and props include rendered prefab pictures, and Rootling uses its native model icon. Appearance items without an authored render use the Collection emblem.

In the website workshop, render an **icon** and **screenshot** for the exact content revision before exporting. The native bundle now includes completed images for those selected revisions. Merge both `Server/` and `Common/` into the asset pack and merge catalog indexes; rebuild before joining. Images are not loaded over HTTP by the Hytale client.

`website/scripts/render-bundled-catalog.js` regenerates missing bundled prefab pictures through a running local fixture website. Set `TEST_BASE_URL` if the fixture uses a different loopback port. `--force` rerenders existing pictures. This script creates local admin revisions and renders only; it does not publish content.

## Border effect

The old curtain and atlas have been removed. The new border emits small teal motes that rise and turn warm gold, using a single transparent diamond/glow sprite. Emission is continuous during each 1.2-second window, with randomized particle lifetimes overlapping between windows. Viewer proximity is scanned every 100 ms: newly approached segments start immediately on the next scan, while existing emitters retain their 1.2-second cooldown. Fade-in reaches full strength at 3% of particle life (about 50–70 ms), instead of 12%. Native spawn scheduling and network latency still affect the first visible mote. Particles remain under one block high. Ground sampling (including public paving), distance fade within eight blocks and a 32-emitter viewer budget remain enabled. Regenerate the texture and three strength variants with `scripts/generate-border-motes.py` (Pillow).

Native asset loading and bounds checks do not validate the client's final appearance. Check motion, corners and subtlety in the actual client after restarting and reconnecting.

## Packaging and road controls

Housing catalog actions now say **Place**. The Packaging Wand selects the plot of the aimed prop before falling back to the player's location. Actual prop custody and guild permissions still apply.

A failed cactus placement exposed a native initialization detail: Hytale adds `FarmingBlock.LastTickGameTime` when inserting a block. Snapshot comparisons ignore that scheduler timestamp and treat an otherwise empty FarmingBlock as equivalent to its omission on chunk reload; saved snapshots retain the original data. Block type, crop progress, inventories and all other component data remain subject to verification.

For an existing interrupted, unrecorded block-only prop placement, use the wand on your plot or **/e myplots → Manage → Recover prop**. Recovery verifies both saved hashes, the quantity reservation, permissions, plot bounds and the current world. It restores the previous ground and returns exactly the reserved quantity to the original build inventory. Edited objects, unproven entities and other unfinished operation types retain their recovery lock. The wand reports a recovery problem separately from an ownership denial. Recovery loads its affected columns and vertical sections asynchronously, then rechecks permissions and saved cells on the world thread. Its result stays on screen with Retry or Housing inventory actions. Failed comparisons are saved to recovery-check snapshots and logged; the menu does not silently return to My plots.

The Road Designer uses **CustomUIHud**, following Aetherhaven's PathToolStatusHud: clear `#ControlRows`, append `ToolHudHotkeyRow.ui` with `ToolHudHotkeyRows`, and populate plain `#KeyLabel.TextSpans` via `ToolKeybindDisplay`. The resolver, slots and row helper are copied from Aetherhaven with only package/resource namespace substitutions. There are no item Legend or native input-hint bindings. Like Aetherhaven, the resolver reads the local Hytale Settings.json when available and otherwise uses the defaults; it does not receive remote clients' Settings.json files. The client’s DisplayLegend setting has no effect on this custom HUD.

The claim grid refreshes while its page is open. The packaging wand stops drawing/clearing debug shapes whenever a custom page owns the screen, so holding it cannot erase a guild plot preview.

Travel is a destination picker at public portals and unlocked personal/guild teleporters. The generic main menu no longer includes Worlds. The picker shows loaded destinations with arrival points, excludes the current world, and offers Travel directly; there are no coordinate-only Details pages. `/e worlds` outside travel access reports the requirement without opening a dead-end screen. The travel service rechecks access when Travel is clicked.

Most menus now have a small house icon at the top right. It opens the main menu directly, using the normal page dismissal lifecycle so placement drafts are preserved. Its tooltip reads Main menu; no font glyph is required. Regenerate the three button states with `scripts/generate-home-icon.py`. Lists with a single page hide Previous, Next and the page count. Real parent navigation remains available, and the main menu itself does not carry a return-to-child Back button.


## Local play-through

1. Collect a delivery; store a held stack, attach it to a letter, send it, and reconnect to check that inventory is neither duplicated nor lost.
2. Open /e myplots for a claimed but unfurnished property. Locate it; review a move without confirming, then go Back. Confirm only when intentionally testing relocation.
3. Move a personal/guild claim preview, press Escape and reopen the deed. Confirm nothing. Cancel and reopen to verify the draft was discarded. Repeat Escape/resume/Cancel for house and prop previews.
4. Create a guild and verify the invite field starts empty. Test guild claim/move controls with an authorized and unauthorized role.
5. Visit Iris Wren's shop. Verify arrival outdoors, doors open, containers remain protected, purchases work, and /e shopreturn returns to the departure point.
6. Open Quests from the ledger and /e quests. Browse Crown Store pictures, housing icons and Collection entries. Equip Rootling and circle it to check body/head facing.
7. From the ledger, mail, Crown Store and a placement preview, click the top-right house icon. Confirm it opens the main menu; reopen the preview and verify it resumes. Check that a list of six or fewer choices has no pagination, while a longer list still pages. At a portal, select Travel and verify it actually moves you to another available world.
8. Walk toward each plot edge and corner from both sides, including bird's-eye height. Confirm continuous light motion, no rectangular fringe and no corner overshoot.

9. Place and package Cacti. For an older interrupted cactus placement, use Recover prop or the wand first, check the returned inventory quantity, then place it again. Verify Place labels and all six road keybindings, including one remapped key.

## Follow-up validation

The custom HUD keybinding helper/slot/row code was compared byte-for-byte with Aetherhaven after package/path substitutions. Ten focused regression tests pass. The isolated server loaded a staged copy of the affected player's cactus chunk and successfully restored its original ground, returned one reserved catalog quantity and unlocked the copied plot. Four-side boundary sampling, public paving, native housing/road/guild/inventory/playground checks pass.

The earlier complete regression run failed because the separately edited Prowl_PlayerRig no longer matched the original reference geometry/bounds. On September 29, 2026, the current appearance was approved and the references were updated without changing the artwork. All 215 Java tests (including PostgreSQL), 11 website tests, `verifyReleaseJar`, the viewer comparison and the complete isolated native smoke now pass. Client HUD appearance and grid animation still require an in-game visual check.

To repeat the optional retained-save diagnosis, stage a copied world under `build/<fixture>/world`, plus its `before.json`/`after.json` snapshot envelopes. Set `ETERNIA_WORKSPACE_ROOT` to the repository and `ETERNIA_RETAINED_PROP_FIXTURE` to that staged directory when running the isolated native smoke. It copies the staged world into the isolated server and mutates only that copy; never point it at live server data.

The navigation/border follow-up passes 20 focused UI and boundary tests and `verifyReleaseJar`. Isolated native smoke validates the home event binding, stale-home-event rejection, empty/single/multiple-page commands, and all particle assets. The later approved Prowl reference update also resolves the previously reported final bounds mismatch. The final icon and layout require a client visual check.
