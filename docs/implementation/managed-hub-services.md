# Managed hub NPCs and portal pads

Use `/e admin setup`, register the world and a public services plaza through **Register plaza**, then open **Hub services**. The player needs Hytale's current WorldEditor permission; each preview, confirmation, move, removal and recovery checks it again.

Choose a named character for the corresponding service. Minigames opens its Coming Soon surface. Choose **Place portal** for the physical world-selection pad; the pad opens destination selection and the travel service still checks the registered public portal and current destination permissions.

| Character | Profession | Appearance |
| --- | --- | --- |
| Prowl | Eternia Guide | Original Prowl model and texture, retargeted player rig |
| Bramble Hearthleaf | Housing Steward | Kweebec with builder cap, leaves and chest gear |
| Torren Ironbough | Guild Marshal | Trork chieftain with armor, tusks and braided beard |
| Nima Quicktail | Market Broker | Feran with fox coat, poncho and top fur |
| Lyra Starweave | Royal Quartermaster | Elf with styled hair, green shirt and boots |
| Pip Coppercap | Games Master | Goblin with hermit hat, coat and beard |

Names and professions appear above each character on one line, for example **Prowl [Eternia Guide]**. Avoid newlines in native nameplates: this client renders them as an unsupported glyph. The native **Use** interaction displays the player's current bound key and a translated “Speak with…” hint. Roles also explicitly request the native prompt; no key name is embedded in the text. Managed NPCs created by older versions update their model, nameplate and persistent interaction hint as their entities load. Existing positions, service roles and managed UUIDs remain intact. Opening the character's management page retries an interrupted presentation refresh.

Aim at level solid ground within twelve blocks. Without a target, setup uses suitable ground three blocks ahead. The NPC faces the builder. A confirmation shows the world and exact coordinates; it expires after ninety seconds. Services need an empty, dry 3 × 3 footprint with three blocks of clearance, entirely inside one registered public plaza. Public roads, guild roads, unfinished paving, player or guild plots, housing reservations, other managed services and the world's arrival point are excluded. Register a plaza larger than the pad so the arrival can stand beside it.

**Manage** lists only services created through this menu in the current world. Stand near the saved location so its terrain and entities are loaded. An NPC can move to another valid location in the same world while retaining its identity. Portal pads can be removed and placed again. Removing a service leaves the plaza and destinations registered. Removing the last plaza containing a managed service, placing a road over it, or moving an arrival into its footprint is refused; move or remove the service first.

Unfinished changes appear as **Finish setup**. A durable record is written before native changes. NPCs carry a persistent service tag and the record's UUID before they enter the world. Pads have an inventory-free block tag, and all nine cells must match the supported portal geometry before removal. Native saves finish before the record is acknowledged. An interrupted move protects both recorded positions. Recovery uses the recorded identity and positions; it refuses mismatched entities, unrelated blocks and unloaded data. It does not adopt manually spawned NPCs or automatically respawn missing services at startup.

The registry lives under the plugin data directory's `managed-hub-services/`. Retain the complete directory and native world data in backups. Corrupt or missing authority files cause setup initialization to fail instead of inventing an empty registry. Ordinary builders never need to edit these files.

Implementation: [character catalog](../../src/main/java/com/hexvane/eterniamod/setup/HubNpcIdentity.java), [ManagedHubServices](../../src/main/java/com/hexvane/eterniamod/setup/ManagedHubServices.java), [ManagedHubRegistry](../../src/main/java/com/hexvane/eterniamod/setup/ManagedHubRegistry.java), [NPC tag](../../src/main/java/com/hexvane/eterniamod/setup/ManagedNpcTag.java), [portal tag](../../src/main/java/com/hexvane/eterniamod/setup/ManagedPortalTag.java). New service types require a shipped NPC role, a typed character catalog entry, and a live service interaction. They cannot supply arbitrary NPC asset IDs through the setup menu. Bump `HubNpcIdentity.REVISION` when changing shipped appearances so existing managed characters refresh on load. `ensureNpc` and `ensurePortal` are guarded, idempotent operator APIs used by the local playground.

Validation includes pure registry state, stale-confirmation and corruption tests. The guarded `ManagedHubServices.nativeSmoke` acceptance runs only in the disposable local smoke world; it exercises permissions, tagged NPC spawn/move/removal, native disk reload, portal placement/removal and interrupted placement recovery. It does not replace a connected-client check of aiming, menu layout or NPC interaction presentation.
