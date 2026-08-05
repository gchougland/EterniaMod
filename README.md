# HytaleModTemplate

A starter Hytale mod project derived from the [Aetherhaven](../Aetherhaven) mod toolchain. Copy this folder, rename the placeholders below, and start building.

## Prerequisites

- **Java 25** (JetBrains Runtime recommended for hot reload during development)
- **IntelliJ IDEA** (Community Edition is fine) or another Java IDE
- **Hytale** installed via the launcher (for `runServer`)

## Quick start

1. Copy `HytaleModTemplate/` to a new folder for your mod.
2. Update the [rename checklist](#rename-checklist) below.
3. Build:

```bat
.\gradlew.bat build
```

4. Run the local dev server:

```bat
.\gradlew.bat runServer
```

On first run you may need to authorize the server. Use `runServerNoSync` when editing `src/main/resources` directly so post-exit asset sync does not overwrite your files:

```bat
.\gradlew.bat runServerNoSync
```

5. In-game, try `/templatemod ping` or `/tm ping`.

## Gradle tasks

| Task | Description |
|------|-------------|
| `build` | Compile and package the mod JAR |
| `runServer` | Start dev server; syncs assets back to source on exit |
| `runServerNoSync` | Same as `runServer` without post-exit asset sync |
| `verifyReleaseJar` | Fail if HytaleServer was accidentally bundled |
| `syncAssets` | Copy `build/resources/main` back to `src/main/resources` |

## Project layout

```
src/main/java/com/example/templatemod/   Java plugin code
src/main/resources/manifest.json           Mod manifest (Gradle token expansion)
src/main/resources/Common/                 Client/shared assets (models, UI, etc.)
src/main/resources/Server/TemplateMod/     Server-side mod data JSON
src/main/resources/Server/Item/Items/      Item definitions (add as needed)
src/main/resources/Server/Languages/       Localization (.lang files)
```

See [Aetherhaven](../Aetherhaven) for a full-featured reference mod using the same patterns.

## HStats setup

This template includes optional [hstats.dev](https://hstats.dev) metrics integration.

1. Register your mod at [hstats.dev](https://hstats.dev).
2. Set your mod UUID in `gradle.properties`:

```properties
hstats_mod_uuid=your-uuid-here
```

Or pass at build time:

```bat
set TEMPLATEMOD_HSTATS_MOD_UUID=your-uuid-here
.\gradlew.bat build
```

When copying this template for a new mod, rename the env var in `build.gradle.kts` (e.g. `TEMPLATEMOD_HSTATS_MOD_UUID` → `MYMOD_HSTATS_MOD_UUID`).

Leave `hstats_mod_uuid` empty to disable metrics (the plugin logs a skip message at startup).

**Note:** Only change the package name in `HStats.java` — do not modify the metrics-sending logic per HStats license.

## Rename checklist

When copying this template for a new mod, update:

| Placeholder | Files to update |
|-------------|-----------------|
| `TemplateMod` / `templatemod` | `settings.gradle.kts`, Java classes, lang file name/prefix, `Server/TemplateMod/` folder |
| `com.example.templatemod` | Java package, `plugin_main_entrypoint` in `gradle.properties` |
| `YourName` | `gradle.properties` (`plugin_group`, `plugin_author`) |
| Command prefix `/templatemod` | `TemplateModCommand.java` |
| `hstats_mod_uuid` | `gradle.properties` |
| `TEMPLATEMOD_HSTATS_MOD_UUID` | `build.gradle.kts` env var name |

## Optional build options

In `build.gradle.kts`, the `hytale { }` block supports:

- `addAssetsDependency = true` — attach `Assets.zip` for IDE browsing (very large)
- `updateChannel = "pre-release"` — target pre-release server builds

## Bundling dependencies

If your mod needs to ship third-party libraries in the release JAR, see Aetherhaven's `modEmbed` pattern in its `build.gradle.kts`. Do not merge `runtimeClasspath` — it can include HytaleServer.
