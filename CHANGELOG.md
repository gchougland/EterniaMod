# Changelog

## [1.0.0] - Unreleased

### Added

- **Hub plots** — Admins can mark plot areas and assign them to players, including assigning the plot you are standing in. Use `/eternia plots show` to toggle plot outlines.
- **Building placement** — Use a building item in your plot to place and move a preview before building.
- **House** — First test building available for hub plots.
- **Building records shelf** — Use the management block inside a placed house to pick the building back up into an item (with confirmation).
- **Props** — Place decorations like the Aqua Lamp in your plot using a prop item.
- **Packaging wand** — Use the wand to see props in your plot and pack them back into items.
- Prop placement shows a red highlight when the spot is blocked or outside your plot.
- **Prefab browser** — Use `/eternia prefab browse` to browse server prefabs and create props or buildings from them.
- Props and buildings now place prefab entities (NPCs, items, etc.) and remove them when packaged or picked up.

### Changed

- Prop highlight and packaging detection use a padded bounding box along your look direction instead of requiring a direct hit on prop blocks.
- Picking up a building only removes prefab blocks that are still unchanged; blocks you placed inside (e.g. a furnace) are kept.
- Terrain replaced by a building is saved and restored when you pick the building back up.
