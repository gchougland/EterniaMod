# Local validation record

Checks recorded September 12–13, 2026 Pacific, with the installed Hytale 0.6.5 server and Java 25. The latest polish acceptance is below; earlier entries retain their original scope and counts. Live provider and client acceptance remain separate.

## Client-reported UI and Prowl corrections — September 13, 2026 evening

The resource audit compared source, build output and the previous release jar before changes: all **159 non-manifest resources matched byte-for-byte**, with no missing or extra assets. The sole manifest difference was expected build-time metadata expansion. The initial shutdown sync had recopied the completed resources rather than losing the polish work. A later rewritten Prowl rig was saved separately and regenerated; the final source/build/jar comparison again reports 159 matching resource files. Recovery copies are retained in ignored `.local/resource-audit/20260913-203848/`.

Fixed `QuestRow.ui`'s invalid `HorizontalAlignment: Right` to `End`. Replaced all 23 unsupported nested `Anchor.Height`/`Anchor.Width` commands with complete native Anchor objects, retaining each template's dimensions and spacing. Native UI checks now inspect those encoded objects, and resource tests reject invalid alignment values and nested Anchor selectors. This addresses errors that the earlier server-only serialization check did not detect.

Prowl's rig now includes inherited parent shape offsets, restoring original body proportions without scaling the model or changing its source geometry/texture. The previous proof omitted the same offsets as the broken generator. Corrected proofs cover fixed anatomical coordinates, independent transforms, Idle/Walk pose samples, eye displacement and visible eyelid dimensions. The [viewer comparison](../../website/test-output/prowl-rig-comparison.png) was rendered and visually inspected; it places the original, earlier broken rig and corrected rig at the same camera and scale. The native parser check uses the documented visible-descendant reference described in the [rig guide](prowl-rig.md).

All six nameplates now use one line, such as `Prowl [Eternia Guide]`. Managed identity revision 2 refreshes existing characters while preserving their UUIDs and locations. Native migration and saved-nameplate checks passed.

The final combined run passed **200 Java tests with no skips, failures or errors**, real PostgreSQL checks, release-jar verification and the full isolated native acceptance sequence in approximately 58 seconds. The log is `build/ui-prowl-final-validation.log`, including `ETERNIA_NATIVE_PROWL_BOUNDS_PASS`, `ETERNIA_NATIVE_UI_PASS` and `ETERNIA_NATIVE_SMOKE_PASS`. `python scripts/rig-prowl.py --check` and the viewer comparison also passed after packaging. Final resource hashes are recorded in `build/ui-prowl-resource-verification.json`.

`runServer --dry-run` confirmed that `syncAssets` is no longer a shutdown finalizer; the task remains an explicit manual import. The task graph is recorded in `build/shutdown-sync-dry-run.log`. Restart the server and reconnect to load the corrected client resources. Native checks and the viewer render do not replace an in-game check of animation blending and UI layout.

## NPC, road, store and playtesting polish — September 13, 2026

The final Java suite passed **196 tests with no skips, failures or errors**, including five real PostgreSQL integration tests. Release verification passed for `build/libs/EterniaMod-1.0.0.jar` (approximately 2.7 MB, without HytaleServer). Crown checks cover concurrent spending, receipt replay, catalogue delivery, immutable prices, refunded top-ups and owed balances, guild-charter provenance, reconnect persistence, and retry after a successful purchase followed by a failed balance refresh. Store database work runs on the menu executor; the game thread renders detached snapshots.

The final combined Java, release-jar and isolated native run completed successfully in approximately 53 seconds (the world assertions took approximately 31 seconds). It checks the same persistent examples created by `/e playground`, rather than a separate mock of the village. The final log is `build/polish-final.log`; the preceding native run is retained in `build/polish-native.log`.

- All six named Hub characters loaded their distinct models and native interaction hints. Managed identity migration, nameplates, tagged placement and native save/reload passed. Only the greeter uses Prowl.
- Road Designer loaded its item and six native input bindings, painted and edited a real curve, left its empty interior unprotected, joined another road without owning its terrain, refused removal of the supporting road, and restored terrain on branch undo. Permission revocation, pending-operation locks and interrupted-write recovery passed.
- The manual village created its plaza, NPCs, managed portal, curved road, furnished seller's house, guild hall and two finite native-item listings. Repeating setup retained houses and stock. The two example houses were actually placed and their shop arrival was checked.
- The native trial crop retained its farming state, completed a harvest and reset, regrew using native elapsed-game-time handling, stayed mature beyond its hold duration, preserved its generation on disk and completed a second harvest. This probe does not manufacture player XP.
- Native Citadel UI command serialization verified paginated choices, enabled/disabled navigation and full action-label widths. The existing personal/guild housing, palette, decorative entity custody, relocation and road consent checks also passed.

`python scripts/rig-prowl.py --check` reproduced all 15 generated rig/clip/proof files. Every one of Prowl's 40 original shapes and its UVs is retained, with bind-pose world vertex error below `1e-9`. The original Prowl model/texture and Aetherhaven files were not edited.

The website passed **11 tests with real PostgreSQL and no skips or failures**. The Crown treasury browser check passed at 1440×1000 and 390×844, checking fixture balance, unconfigured checkout messaging, horizontal overflow and browser exceptions. Its desktop and mobile screenshots are in `website/test-output/crown-store-*.png`; logs are in `build/polish-web-tests.log`. No real checkout or OAuth login was performed.

Use the [manual playtesting route](manual-playground.md), [Road Designer guide](spline-road-tool.md), [NPC guide](managed-hub-services.md), [Crown store guide](crown-store.md) and [interface guide](interface-design.md). Connected-client checks remain necessary for actual GUI rendering at the player's UI scale, aiming, Prowl's animation appearance, remapped prompts, inventory delivery, border fog and multi-player interactions. Server assertions and layout calculations do not verify those visuals.

## Game build and automated checks

Run from the repository root:

```powershell
.\gradlew.bat --gradle-user-home C:/Users/gchou/.gradle test verifyReleaseJar runServerLocal -PnativeSmoke --offline --no-daemon
```

The combined run completed successfully. JUnit reported **133 tests: 132 passed, one skipped, zero failures or errors**. The skipped PostgreSQL integration test requires an explicitly isolated database. `verifyReleaseJar` passed for `build/libs/EterniaMod-1.0.0.jar` (approximately 2.3 MB), including the check that it does not bundle HytaleServer.

The installed native server also passed the following acceptance sequence in a fresh local world:

- Applied the housing world policy, checked placement/gathering/fluid restrictions, and restored the prior policy after removing the housing role.
- Claimed a 24×24 personal plot, placed the actual starter house, applied a palette, and placed, removed and reused a path. Duplicate preview confirmation was rejected.
- Picked up and replaced the potion shelf with its three decorative item entities. Their stable UUIDs and provenance survived packing and restoration into a centered 32×32 plot two blocks higher. The voluntary move consumed exactly one credit.
- Created a five-member guild, claimed its 48×48 estate, placed its actual hall, assigned an Architect role, and placed a member house with a guild-owned shelf.
- Built, removed and rebuilt guild roads. A member plot required its owner's consent; revoking consent invalidated an existing preview. Forced member packing removed the intersecting road and restored its original terrain.
- Forced the member's plot separation, retained the guild shelf and its three entities in guild custody, and restored only the member's property at a public plot. The guild hall and member's free move credit remained intact.
- Finished with no unfinished world-mutation journals.

The native assertion sequence took approximately 25.6 seconds; the guild claim/hall stage took approximately 4.3 seconds. These are local acceptance measurements, not a claim of performance under concurrent players. The combined output is retained in ignored `build/final-validation.log`, with `ETERNIA_NATIVE_ROAD_SMOKE_PASS`, `ETERNIA_NATIVE_GUILD_SMOKE_PASS` and `ETERNIA_NATIVE_SMOKE_PASS` markers.

## Website and actual prefab renders

`node --test` in `website/` passed **all nine tests**, covering authentication/CSRF boundaries, admin imports and immutable revisions, render validation, deterministic exports and raw Tebex webhook forwarding.

The local renderer completed six actual PNG jobs: a screenshot and transparent icon for each of the native house (729 blocks), potion shelf (one block and three item entities), and aqua lamp (nine blocks with native trapdoor states). Exported prefab JSON and manifest hashes matched their source; icon alpha and dimensions were inspected. Native game textures belong to the rendered objects; the surrounding Citadel interface uses the flat geometric theme.

Artifacts are kept in ignored `website/test-output/`, including `native-render-report.json`, the six `native-*-screenshot.png` / `native-*-icon.png` files, and `eternia-native-catalog.tar.gz`. Desktop account/admin and mobile season pages were also inspected.

The local website was running at `http://127.0.0.1:3847` with the explicit fixture account **Local Explorer**, and its health endpoint returned `ok`. See the [website guide](../../website/README.md) to restart it, synchronize native assets, or connect it to the local game bridge.

## Remaining acceptance

### In-game setup acceptance — September 13, 2026

The final implementation passed **159 Java tests and 11 website tests with no skips, failures or errors**, with actual PostgreSQL integration enabled, and release-jar verification. The guarded native server run then passed personal/guild housing and custody, guild roads, public road paving, and managed NPC/portal acceptance. Logs are retained in ignored `build/final-setup-validation.log` and `build/final-setup-native.log`.

Native paving checks verified permission revocation at commit, refusal to overwrite protected roads, actual cobblestone placement, duplicate-confirmation rejection, persisted pending-operation claim protection and exact original-terrain recovery. Native managed-service checks placed, moved and removed a tagged Prowl NPC; rejected stale confirmations and mismatched identities; placed and removed the nine-cell portal; and completed an interrupted portal placement. Native disk reload verified NPC UUID/tag/role/position and all portal cells/fillers/tag. These passed alongside the existing furnished housing and guild custody checks in the same isolated run.

The separate PostgreSQL playtest also booted on `127.0.0.1:5524`, migrated `eternia_game_dev`, persisted its six initial domain records, and served the authenticated bridge health endpoint on port 9011. No `local-authority.bin` was created in `run-postgres`. It was stopped cleanly after verification; the local PostgreSQL cluster remains running. Its log is `build/postgres-game-startup.log`. The normal file-backed `run/` and `run-local/` worlds were not switched to PostgreSQL or assigned new infrastructure.

Connected-client presentation, aiming, menu navigation and multi-builder interaction remain playtesting steps. The in-game setup flow is documented in the [world and Hub guide](world-setup.md) and [managed services guide](managed-hub-services.md).

### PostgreSQL follow-up — September 13, 2026

The project-local PostgreSQL 18.6 cluster was provisioned on loopback port 55432 with four independent game/web development/test databases. The database-enabled run passed **139 Java tests and 11 website tests, with no skips, failures or errors**, plus release-jar verification. The real database tests cover rollback, migration/reconnect, concurrent credits and receipt replay, immutable web imports/jobs, and HTTP session persistence across application restart.

An independent privilege audit confirmed all four application roles are non-superusers without create-database/create-role privileges. Each can connect to its own database; all twelve cross-database connection attempts were denied. A full clean PostgreSQL stop/start preserved the exact game test record count and payload fingerprint. A custom-format backup restored into a newly created temporary verification database with the same records and migration history; that temporary database was removed after verification.

The normal PostgreSQL website also started on `127.0.0.1:3848`, applied its real migration, returned healthy status and reported fixtures disabled and OAuth unconfigured. It was stopped after verification. The database remains available through the [local PostgreSQL helper](postgresql.md). Results are retained in ignored `build/postgres-validation.log`, `build/postgres-web-startup.log` and `.local/postgres/` verification artifacts. The final in-game setup acceptance entry above covers the later changes.

### Local connection follow-up

After a client playtest exposed the launch defaults, both development entry points were corrected and booted against the installed server. `runServerLocal` now starts in `AUTHENTICATED` mode on `127.0.0.1:5523` and successfully loaded the existing encrypted server authentication. Previously, its offline connection mode caused the native `offlineModeSingleplayerOnly` rejection even after successful server OAuth login. `runServer` also loaded stored authentication and booted on port 5520 after its development environment default was changed to local file storage. Its prior startup failure required production PostgreSQL configuration. Explicit production mode and the packaged plugin's production default remain intact.

The startup results are retained in ignored `build/connection-runServerLocal.log` and `build/connection-runServer.log`. Verification servers were stopped afterward. These checks confirm server startup and authentication configuration; an actual client join still needs the player to reconnect. See the [connection instructions](README.md#run-locally).

### Client, persistence and provider acceptance

- Use the actual Hytale client to check every GUI, overhead camera, proximity fog appearance and performance, travel, NPC interactions, native inventory delivery, cosmetics, pets and guild chat with multiple players.
- Exercise real disconnects, process termination and the 48-hour guild-departure worker with multiple clients. Receipt/recovery tests and a native forced-pack run do not cover every interruption timing.
- Measure PostgreSQL/database-connection and world-snapshot latency under representative multiplayer load. Isolated PostgreSQL integration, role isolation, clean restart and backup-restore checks have now passed.
- Register Eternia's new OAuth application once its callback URL is ready. Verify actual login with it, then validate Tebex test products, subscription identity and the complete signed callback/console delivery lifecycle.
- Configure Railway's own environment, persistent render storage and private game bridge before deployment. No live deployment, OAuth registration or purchase was performed by these local checks.

Use the [setup guide](README.md), [feature map](feature-map.md), [community road guide](guild-community-roads.md), [inventory and trading guide](inventory-and-trading.md), and [native recovery guide](../native-housing-recovery.md) for the relevant procedures and limits.
