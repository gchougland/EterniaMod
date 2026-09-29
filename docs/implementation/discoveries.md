# Adventure discovery caches

Discovery caches implement the **find in-world token** acquisition path. Using an authored cache in an adventure world grants housing content directly to the player's owned collection. A physical cache block is the visual interaction point; its item metadata is never a reward claim. Free starter grants, season rewards and verified commerce grants remain separate acquisition sources.

## Native entry point

The shipped sample item/block is `Eternia_Discovery_Cache`, named **Adventure Discovery Cache**. It reuses the existing village book-shelf model and textures. Its block `Use` interaction is `EterniaDiscovery`; the interaction codec contains no discovery ID, player UUID, quantity or reward fields.

The adapter accepts only a player currently in `GameMode.Adventure`, in a world whose `housing-infrastructure.json` role is `adventure`. It reads the actual target block and the player's transform from the native world, resolves the configured cache at that exact world name and block position, requires its `markerBlockId` to match, and requires the player within six blocks of the block center. Hytale's base block interaction also validates reach.

On success, the Citadel result page says **Housing token collected** for a quantity reward or **Content unlocked** for an unlock/capability. No carried item must be kept or redeemed later. The reward already belongs to the account and appears in the relevant housing/collection screen. A repeated Use reports **Discovery already collected**. Other players may collect the same cache independently, so it is not consumed globally.

Registered caches reject ordinary player breaking and block damage, including unattributed world damage. A Creative administrator can remove or relocate the marker. Moving a block without updating its authored configuration makes it decorative and grants nothing. Protection applies to the matching configured marker, not an arbitrary replacement block at its old location.

## Author a cache

The plugin data file is `discoveries.json`. An absent file is created as an empty version-one registry. The packaged [example](../../src/main/resources/Server/EterniaMod/Discovery/discoveries.example.json) is documentation, not an automatically placed or enabled cache.

1. Choose an existing content reward. For example, `eternia:prop/aqua_lamp` is the shipped Aqua Lamp decoration and uses `QUANTITY`.
2. In a locally tested adventure world, place `Eternia_Discovery_Cache` using the Creative item catalog. Record the actual origin block's integer coordinates, including Y.
3. Ensure that world's exact server name is registered with the `adventure` role in `housing-infrastructure.json`.
4. Add an entry to plugin-data `discoveries.json`, replacing the example world and coordinates with the real placement, then restart the plugin/server. There is no live reload command.

```json
{
  "version": 1,
  "discoveries": [
    {
      "id": "first-light-cache",
      "world": "adventure",
      "x": 32,
      "y": 80,
      "z": 16,
      "markerBlockId": "Eternia_Discovery_Cache",
      "reward": {
        "contentId": "eternia:prop/aqua_lamp",
        "kind": "QUANTITY",
        "quantity": 1
      }
    }
  ]
}
```

This example location is illustrative. The implementation does not generate terrain, build a cache or teleport players to it.

Each entry has one reward. `QUANTITY` grants 1–10,000 units. `UNLOCK` and `CAPABILITY` require quantity 1 and have no expiry. Use the ownership kind expected by the consuming content: housing styles currently use `UNLOCK`, while placeable decoration stock uses `QUANTITY`. Defining a reward does not create its underlying prop, house, cosmetic or capability behavior; author that content through its existing catalog first.

Cache IDs must be unique lowercase letters/digits with dots, underscores or hyphens, at most 100 characters, beginning with a letter or digit. Two entries cannot share the same exact world and position. The file is capped at 1 MiB and 10,000 entries. Invalid configuration or a world without the adventure role prevents this adapter from starting.

Keep an ID stable when relocating the same cache. After anyone collects it, its content ID, kind and quantity are immutable in the durable authority; changing them is rejected for existing and new claimants. A different intended reward is a new discovery with a new ID. Reusing a fresh ID intentionally lets each player earn another reward, so ID changes are a content publishing decision, not a way to rename a cache.

## Durable authority

`services.discoveries().redeem(actor, definition, evidence)` is an internal trusted-game API. Do not expose it as a raw browser endpoint or call it from arbitrary command/item fields. The native adapter owns the definition lookup and evidence construction.

The receipt is `discovery:<stable-cache-id>:<authenticated-player-uuid>`. Grant creation, the immutable reward definition and the player's claim record commit in one domain transaction. The claim records the actual source world UUID, world name, target coordinates, native block ID and collection time. The world UUID is evidence, rather than part of the replay key: regenerating a world, copying the world under another instance UUID or restarting does not reset collection for the same cache ID.

Retries and concurrent Uses return the existing claim without another grant. A copied cache item used somewhere else fails the server position lookup. Forged physical item metadata has no input path into the configured reward. Losing a decorative item or removing the cache after collection does not remove the account reward. Revoking its source grant does not permit collecting that same discovery again.

The current feature has no random mob loot tables, tradable discovery vouchers, consumable token inventory, discovery map/journal, respawn timers or cache quest XP. Those can be added as separate adapters while retaining this receipt authority. This path grants to the individual player; there is no direct guild-owner reward selector.

## Bootstrap contract and verification

After the domain services, housing infrastructure and building/prop catalogs have loaded, construct:

```java
DiscoveryBootstrap discoveries = new DiscoveryBootstrap(
    plugin, plugin.getServices(), plugin.getInfrastructure(),
    plugin.getDataDirectory().resolve("discoveries.json"));
```

The constructor loads the file and registers the interaction and block-protection systems; it throws `IOException` for invalid configuration. Keep the instance and call `close()` on plugin shutdown. Entity systems and codecs use the plugin's registries; this adapter starts no background thread. A restart loads updated locations.

[DiscoveryTest](../../src/test/java/com/hexvane/eterniamod/discovery/DiscoveryTest.java) covers simultaneous collection, per-player isolation, restart/world-instance replay, moved or replaced markers, wrong role/mode, excessive distance, immutable rewards, duplicate coordinates/IDs and empty-default configuration. These are Hytale-free service/registry tests, not an in-game rendering test.

For native acceptance, use the sample at an actual configured location in a local adventure world. Collect in Adventure mode and verify the decoration count rises once. Use it again, reconnect and use it again; the count must stay unchanged. Have a second player collect it once. Copy the cache elsewhere and verify it grants nothing. Test a changed block, Creative mode and a world with the wrong role. Finally, place the earned Aqua Lamp from housing inventory to verify the grant matches the shipped prop catalog. Do not reset real account receipts to repeat a test; use isolated local accounts or a deliberately new test cache ID.

Related: [inventory and trading](inventory-and-trading.md), [commerce and activities](commerce-and-activities.md), [content authoring plan](../planning/content-authoring.md).
