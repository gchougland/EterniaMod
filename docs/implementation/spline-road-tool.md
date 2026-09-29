# Road Designer

Public roads are authored with the **Road Designer item**. Open `/e admin setup` and choose **Road Designer**, equip the shovel, then shape the road directly on the ground. There is no corner selection or separate road registration step in this workflow.

The item follows Aetherhaven's direct node editing, ray selection, grounded width outline and native remappable key legend. Eternia uses an interpolating Catmull–Rom spline with automatic tangents, avoiding a separate tangent-rotation mode. Reference implementations are [Aetherhaven PathSplineUtil](C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/pathtool/PathSplineUtil.java), [PathToolInteractions](C:/Users/gchou/Documents/HytaleModding/Aetherhaven/src/main/java/com/hexvane/aetherhaven/pathtool/PathToolInteractions.java), and the [reference item](C:/Users/gchou/Documents/HytaleModding/Aetherhaven/subplugin-assets/PathDesigner/Server/Item/Items/Aetherhaven/Aetherhaven_Path_Tool.json). Their interaction design is adapted; Aetherhaven's plugin, options and saved roads are not copied.

## Controls

The shovel's persistent item HUD displays all six controls using native `HotkeyLabel` bindings, resolved on each player's client. It appears while the item is equipped, independently of the collapsible builder legend preference. The separate status HUD explains progress and blocked cells. The default mouse descriptions below are supplemented by native input names rather than hard-coded keyboard keys.

| Action | Input | Behavior |
| --- | --- | --- |
| Add a node | Secondary item action / right click | Aim at ground to extend the spline. Aim at an existing node to remove it. |
| Move a node | Primary item action / left click | Select its diamond, then aim at new ground and click again. |
| Undo node edit | Ability 1 | Restore the preceding add, move or removal. Up to 32 draft edits are retained. |
| Width and style | Ability 2 | Open a compact selector, then return to the live preview. |
| Review, then build | Use / block interact | First press freezes a gold review; a second press within 90 seconds builds and registers it. Editing nodes, width or style cancels that review. |
| Edit or undo a saved road | Pick block | Aim at an authored road and choose Edit curve or Review undo. Use then confirms removal. |

Green edges outline the exact grounded footprint. Red marks identify blocked cells, with a reason in the HUD. Ivory cells at a junction join an existing public road without changing or taking ownership of it. Width covers the swept curve, including rounded ends. Node diamonds are selectable from the server's view ray; item metadata and client-supplied coordinates grant no authority. The HUD is keyed separately from other mods' overlays.

## Checked construction

- Every click, preview, management action and commit requires Hytale's **WorldEditor** permission. This is an administrator tool; it does not consume player housing inventory.
- The configured world must have the Hub or Housing role. A road has 2–32 nodes, width 1–9, at most 96 blocks between neighboring nodes, at most 512 blocks of control-polygon length and at most 4,096 ground cells. These are bounded operation limits, not content configuration knobs.
- Each column finds dry, plain natural ground or a registered path block near the curve's interpolated height. Plants and rubble are ignored when finding that surface. Construction clears every occupied block above owned road columns up to the world ceiling, including grass, flowers, crops, bushes, rubble and higher objects. The complete contents are retained in undo snapshots. Multiblock objects must fit wholly inside the footprint; if an edge cuts through one, the preview asks the builder to widen or move the curve. Neighboring surface cells may differ by at most one block. The tool does not bridge water or level hills. Full-height sections load asynchronously; an orange/red loading explanation clears when the preview can inspect them. At most 32,768 extra overhead cells can be cleared in one operation; split larger work into shorter roads.
- Loaded blocks, fluids, component holders, creatures, dropped items, plot reservations and native plots are checked again before a write. Roads cannot cross plots, public plazas or guild roads. Short endpoint junctions can overlap existing public roads as read-only connection cells within `width / 2 + 5` blocks of an endpoint. Their actual surface height must match the grounded preview, and the shared-to-new ground slope must remain valid. Shared cells retain their original road's clearing/undo ownership; clean an older junction by editing that parent road. Interior crossings are rejected.
- Connection cells are recorded separately from owned terrain. They receive no new snapshot ownership or protection; editing, recovery and undo never overwrite them. At least one new ground cell is required. The earlier road cannot remove, move, repaint or unregister a junction retained by another active or pending road. First edit that branch to detach it, or remove it. Unrelated edits to the parent road remain allowed when the connection surface and protection stay the same.
- A saved road can be edited within its own exact footprint and clear surrounding terrain. Removed parts restore their original cells. Existing housing must keep its required public-road and portal anchors; undo or a node edit that would disconnect a claim is rejected.
- Public registration consists of contiguous row runs covering **only** swept columns. The empty interior of a curved road remains unprotected. Full vertical column protection applies to the road cells as usual.
- The area management menu cannot resize or unregister a spline's individual row runs. Use the item so physical terrain and registered protection change together. Legacy rectangular records remain available under Manage → Legacy records for recovery and undo.

Guild community roads retain their existing permission, consent and relocation workflow in [guild-community-roads.md](guild-community-roads.md). Personal house paths retain their checked housing customization workflow. [SplineGeometry.java](../../src/main/java/com/hexvane/eterniamod/pathtool/SplineGeometry.java) is independent of road ownership and can be reused by those adapters; this release's new spline item authors public roads.

## Persistence and recovery

The source is [SplineRoadService](../../src/main/java/com/hexvane/eterniamod/pathtool/SplineRoadService.java). Each review captures sparse surface/headroom rows plus occupied overhead cells, without copying empty sky. SHA-256-verified native snapshots retain their complete block data and explicit fluid-clearing contract; component or fluid-bearing ground is rejected before destruction. A curve's bounding rectangle is used only to express local coordinates, never to capture, overwrite or protect its empty interior.

The data directory contains immutable `spline-roads/roads-<uuid>.json` journal generations and an atomically replaced, forced-to-disk `current.pointer`. Native before/after files are in `housing-snapshots/`. Back up these together with `housing-infrastructure.json`, the game world and the account authority. Nodes, style, exact ground cells, original terrain and infrastructure area names are retained per active road. The journal stores pending changes before native writes; then it verifies and flushes native terrain, updates infrastructure and acknowledges the road version. No world write precedes the verified before-snapshot and pending journal.

| Interrupted step | Behavior |
| --- | --- |
| Review only | No world or protection changes. Unreferenced immutable snapshots are harmless. |
| Pending journal, before terrain | Exact changed columns remain locked; Manage lists the pending road. |
| During terrain write | Recover accepts only cells matching the saved old or new state, restores the old terrain, verifies it and flushes it. Unrecognized changes retain the lock. |
| After terrain, before infrastructure | Recover restores both the prior terrain and prior road registration. |
| After infrastructure, before acknowledgement | Recover recognizes either reviewed infrastructure version and restores the prior version. |
| Uncertain acknowledgement | An in-memory emergency projection preserves protection. Restart reloads the verified durable generation. |
| Missing pointer, corrupt generation or changed active road registration | Startup fails closed. Preserve files and restore a matching backup; do not reset the index to an empty road list. |

New claims and plot restores pause throughout the affected world while a spline operation is pending, preventing fresh claims from depending on partially registered roads. Existing plots remain usable. Recovery itself requires a fresh WorldEditor check and a second, explicit Use confirmation. A scoped native writer can bypass only the current road's exact columns and named road runs; it cannot bypass public plazas, other road records or guild protection.

## Adding styles and local verification

Road styles share [CustomizationCatalog](../../src/main/java/com/hexvane/eterniamod/customization/CustomizationCatalog.java). Add a JSON file under the server data directory's `PathStyles/` and restart:

```json
{"id":"cut_stone_road","name":"Cut stone","blockId":"Rock_Stone_Cobble"}
```

Use a verified native plain cube block ID without a block entity. For packaged content, add the definition to `src/main/resources/Server/EterniaMod/PathStyles/` and its filename to `catalog.index`. Saved roads retain the resolved block ID and their verified snapshots; deleting a style definition does not silently repaint existing roads.

The local playground calls `SplineRoadTool.ensureExample(world, actor)` before its first housing claim. It uses normal validation and creates the width-five road through `(3.5,23.5) → (3.5,65.5) → (3.5,105.5) → (30.5,125.5)` on floor Y=0. A stable world-specific receipt preserves later edits and deliberate removal. This helper requires the managed local playground and WorldEditor; local PostgreSQL is supported. It cannot author arbitrary production terrain.

Pure tests cover real curvature, swept width, exact rectangle compression, bounded inputs, ray picking, local example clearance, journal checksums, compare-and-swap revisions and missing-pointer recovery. The isolated native smoke exercises actual plant and rubble removal/restoration at heights 1, 2, 4 and 40, block painting, saved-node editing, an interrupted native write before infrastructure acknowledgement, fresh permission revocation, pending claim protection and exact terrain undo. The native smoke remains disabled outside its isolated local file-authority fixture. Client checks should additionally verify remapped inputs, node targeting and HUD fit at the player's UI scale; a headless server cannot validate their rendered appearance.

New road snapshots include the surface, its two headroom cells, and a sparse list of every occupied cell higher above the footprint. Edits retain previously cleared overhead cells so later undo can still restore their original contents. Confirmation rescans the complete column and refuses newly appeared objects that were absent from the reviewed mask. Edits cannot split a previously cleared multiblock between restored and retained terrain. Editing, removal and interrupted-write recovery retain this exact vegetation custody. Legacy versions without a clearance field continue using their original ground-only snapshots; editing a legacy road upgrades its next version. Existing untouched roads are not repainted automatically. To clear an existing road, use **Edit curve**, review and confirm it again.

Claim proximity now uses the saved spline centerline, and touching public roads connected to a Hub portal can anchor plots along their length. See [current housing behavior](housing-navigation-and-boundaries.md).
