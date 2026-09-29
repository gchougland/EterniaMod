# Eternia Mod

A Hytale server plugin and companion website for Eternia's housing, guilds, trading, permanent seasons, cosmetics, and account services.

Start with the [implementation and local setup guide](docs/implementation/README.md), [feature map](docs/implementation/feature-map.md), [content authoring guide](docs/implementation/content-authoring.md), and [website guide](website/README.md).

Builders and admins: see [in-game Hub, housing and NPC setup](docs/implementation/world-setup.md). Housing can share the Hub world. For a real local database, use the [PostgreSQL setup and testing guide](docs/implementation/postgresql.md).

**Playtest the populated examples:** use `/e playground` as a WorldEditor on your local server. It creates a persistent village with six distinct NPCs, actual shop listings and furnished houses, plus activity stations and a short practice pass. Collect test supplies to try the [Crown Store](docs/implementation/crown-store.md). See the [manual testing route](docs/implementation/manual-playground.md). Builders can get the spline road item directly with `/e admin roadtool`.

```powershell
.\gradlew.bat test verifyReleaseJar
.\gradlew.bat runServerLocal
```

Connect to **`127.0.0.1:5523`** when using `runServerLocal`; plain `localhost` uses the game's default port instead. This task uses normal Hytale authentication, durable local storage in `run-local/`, and never copies test assets back into source. Existing server authentication saved in that directory is reused.

The regular `runServer` and `runServerNoSync` tasks also default to local file storage, while preserving an explicitly set `ETERNIA_MODE`. They keep their existing `run/` world and normal game port (5520 by default, so `localhost` works). The packaged plugin still defaults to production and requires PostgreSQL when launched outside these development tasks.

Server shutdown never copies build resources back into source. `syncAssets` is manual only, for deliberately importing in-game asset edits; it can overwrite newer source changes. Compare and preserve those edits before running it.

`runServerLocal -PnativeSmoke` uses offline authentication in a fresh directory under `build/native-smoke/` and runs headless native housing acceptance checks before shutting down; it is not a client playtest server. Both tasks require the installed Hytale server and Java 25. See the guide for the existing Gradle cache override on this machine.

The website has an admin-only prefab workshop with a native 3D viewer, screenshot/transparent-icon rendering, and native catalog export. Local fixtures work before registering Eternia's new Hytale OAuth client. Production needs its own OAuth credentials, PostgreSQL, a private game bridge, and configured Tebex products.

## Implementation planning

The [original implementation plan](docs/planning/README.md) records the feature decisions and reference research. The implementation guides describe the current code and its validation boundaries.
