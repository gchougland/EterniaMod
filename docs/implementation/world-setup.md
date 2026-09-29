# In-game world and Hub setup

Use `/e admin setup` in the world you want to configure. You can assign world roles, set arrival points, build and register roads, reserve public plazas, and place/manage the Hub NPCs and world portal without editing JSON. The menu puts **Hub services** and **Road Designer** on its first page.

## Your existing Hub with housing

You do not need a separate housing world. Stay in your existing world, open setup, choose **World role → Hub with player and guild housing**, and confirm. This keeps the same terrain and world name. A world called `default` can be your Hub; it does not need to be renamed.

A first-time setup sequence:

1. Choose **Hub with player and guild housing** and confirm the world role.
2. Stand on a clear public arrival pad and select **Set arrival**. Review and confirm the coordinates. The pad needs solid ground and two empty, dry blocks above it.
3. Reserve a public services plaza: stand at its first corner and choose **Plaza start**, then walk to the opposite corner and choose **Plaza end**. Open setup and choose **Register plaza**, then confirm. Select enough space for six NPCs, the portal, and walking room; each managed service needs a clear 3×3 footprint entirely inside the plaza and off any road.
4. Choose **Hub services** to place the six NPCs and a physical world-select portal. Aim at the desired level ground. Each placement shows the location before confirmation.
5. Choose **Road Designer**, equip the item, and add spline nodes on the ground outside the plaza toward the residential area. Select and move node diamonds to adjust the curve. A grounded width preview shows valid and blocked cells. Use reviews the road; a second Use confirms physical construction and registration together. The native key legend follows remapped controls; Ability 2 opens the compact width/style selector.
6. Aim at a saved spline road and use the item's Manage action to reopen its nodes or review undo. Use **Manage areas** in setup to inspect, resize, or unregister public plazas and legacy registrations. Changes that would strand a claim, damage a retained road junction, or remove a managed service's plaza are refused.
7. Test `/e claim` outside the plaza, within five blocks of a registered road centerline connected to the plaza/portal (or a connected public house anchor). Review the red/green plot preview and confirm. `/e housing` opens house placement and management.
8. Add neighborhood portal plazas and additional roads as the Hub expands. Guild estates and guild-member neighborhoods can use the same world.

Plaza corner selection includes both marked blocks horizontally and applies protection at every height; registration does not alter its physical blocks. Spline roads protect only their exact swept columns, leaving curved interiors clear. Roads can cross claimed plot footprints only when they were already registered before the claim; setup refuses to change protection inside an existing claim. Portal plazas cannot be claimed, so they keep public services clear.

In a Hub with housing enabled, public world travel works near registered portal/plaza areas. Farther from those areas, personal and guild teleporter entitlements still apply. The Hub role remains the return/fallback destination. A player who already owns a plot in another world uses the normal furnished move flow to relocate; changing a world role does not move existing houses or create another property slot.

## Commands for repeatable setup

The menu is sufficient. These in-game commands are useful when you want descriptive area names:

| Command | Action |
| --- | --- |
| `/e admin setup` | Open the setup menu for the current world |
| `/e admin setup world hub-housing` | Review making the current world the Hub with housing |
| `/e admin setup world hub` | Review a Hub without housing |
| `/e admin setup world housing` | Review a separate housing world |
| `/e admin setup world adventure` | Review an adventure world |
| `/e admin setup arrival` | Review a safe arrival at your current position |
| `/e admin setup corner 1` | Mark the first horizontal corner under your feet |
| `/e admin setup corner 2` | Mark the opposite corner in the same world |
| `/e admin setup show` | Display the selected outline for 20 seconds |
| `/e admin setup road main-road` | Legacy registration for a road already built; new roads use the Road Designer item |
| `/e admin setup portal welcome-plaza` | Review registering/resizing the selected public plaza with this name |
| `/e admin setup list` | List named infrastructure areas and their management actions |
| `/e admin setup remove main-road` | Review unregistering an area; its physical blocks remain |
| `/e admin setup confirm` | Confirm your current, unexpired review |

Area names use lowercase letters, numbers, hyphens or underscores, start with a letter, and contain at most 48 characters. The menu generates a stable name when no custom name is supplied. To resize, mark new corners and use the same name. Reviews expire after five minutes, and corner selections after twenty. Crossing worlds invalidates their use; changing corners cancels the pending review. Another administrator changing infrastructure requires a fresh review.

For another adventure or authoring world, native Hytale commands provide `/world add <name>` and `/tp world <name>`. Creation uses the configured default generator/storage; teleportation needs the world's native spawn to be available. Once there, use Eternia setup to assign its role and safe arrival. You can then select it through Eternia's world portal. Keep only one configured Hub; the setup menu refuses to silently replace an existing Hub in another world.

## Builder access and ongoing road work

Setup requires current Hytale WorldEditor permission, including inherited administrator access. An administrator can grant the native group using `/perm user group add <player-uuid> hytale:WorldEditor`, and revoke it with `/perm user group remove <player-uuid> hytale:WorldEditor`. These are native permission commands and do not require editing a permissions file. WorldEditor is a powerful native editing role; assign it to trusted builders. Every setup confirmation rechecks the permission, even if a menu was opened earlier.

**The Road Designer works after housing is enabled.** It uses Eternia's checked world adapter and does not require turning protections off. Smooth splines support 2–32 nodes, width 1–9 and up to 4,096 surface cells. Each cell follows dry plain ground with two clear blocks above it; steep jumps, plots, durable reservations, public plazas, guild roads, complex blocks, inventories and fluids are rejected. Endpoint junctions may join another public road using read-only shared cells. Interior crossings are rejected. See [spline-road-tool.md](spline-road-tool.md) for controls, limits and content authoring.

The item's Manage action provides saved-node editing, reviewed undo and recovery. Undo restores the road's own original terrain and removes its registration together; existing claims and branch junctions must remain valid. Recovery checks durable before/after snapshots, while unfinished road columns stay protected and new claims wait. Legacy rectangular paving remains recoverable under Manage → Legacy records. Do not delete recovery files to unlock an area.

Housing-enabled worlds apply their native construction policy throughout the world: hand block placement/gathering, fluid buckets and fluid/fire simulation are disabled. NPC services, public portals, player house/prop/palette/path tools, and the checked administrative paving tool remain usable. These setup tools do not provide a general terrain sculptor or arbitrary structure editor. Author larger decorative Hub structures in a separate editor world and use your trusted content/prefab workflow. Raw native WorldEditor operations can bypass block-event protections; do not treat unrestricted editor commands as safe tools around live player plots.

## Hub NPCs and portal management

Open **Hub services** on the first setup page. The available services are:

| Service | Role |
| --- | --- |
| Greeter / welcome | `Eternia_Greeter` |
| Personal housing | `Eternia_Housing` |
| Guild management and estate services | `Eternia_Guild` |
| Player shop directory | `Eternia_Shop` |
| Real-money store link | `Eternia_Store` |
| Minigames: Coming Soon | `Eternia_Minigame` |

Each service has its own named character and appearance; only the welcome greeter is Prowl. Names and professions appear above them, and the native Use prompt respects key rebinding. They are stationary, invulnerable, and wired to the correct menus. Managed placements are persisted and tagged with stable identities. Use **Hub services → Manage** to list, move, remove, or finish interrupted setup. NPC moves preserve identity; move a portal by removing and placing it again. Managed service footprints must remain inside registered public plazas, so setup will refuse to unregister a plaza they still use. See the [character roster and managed service guide](managed-hub-services.md).

The same menu places the physical `Eternia_World_Portal` with its world-selection interaction. It must be inside a registered public plaza. A destination must have both a loaded world and a safe configured arrival. Installing the mod does not automatically create NPCs, portals, roads, or world assignments.

For manual authoring in a development world, native `/npc spawn Eternia_Greeter` and the other listed role IDs remain available. They spawn at your current player position and are not adopted by Eternia's managed-service list. Do not add `--frozen`; the role already stays still and its interaction logic needs to run. Prefer the managed menu for live Hub services. See [content authoring](content-authoring.md) for changing assets without changing service code.

## Persistence and checks

Setup writes its own infrastructure file atomically and increments a revision. Stable named areas retain their identities across restarts. Older version-one files with `roads`/`portals` arrays still load; their existing rectangles receive deterministic legacy names. Preview and confirmation check current permissions, world identity, saved reservations, active native claims, unfinished operations, managed-service footprints, and the road/portal connectivity of existing claims. External file changes are detected rather than overwritten.

The regular `runServer` saves under `run/mods/Hexvane_EterniaMod/`; `runServerLocal` uses the separate `run-local/mods/Hexvane_EterniaMod/`. The optional PostgreSQL playtest uses `run-postgres/mods/Hexvane_EterniaMod/`. Infrastructure, managed services, native snapshots, and world files belong to that server's data and backups. No JSON editing is required for the setup workflow above.

See [customization and world policy](customization.md) for the gameplay policy and saved settings, and [native recovery](../native-housing-recovery.md) before testing interruptions.
