# Moderation (wave 1): timed bans with escalation

Branch `wave/moderation`. Code in `com.hexvane.eterniamod.moderation`, text in `eternia_moderation.lang`,
the ladder in `Server/EterniaMod/Moderation/BanLadder.json`, tests in `ModerationServiceTest` (22 cases).

## What staff get

All under `/eternia admin` (alias `/e admin`), so the admin collection's `hytale:WorldEditor` group gates them,
and each one checks `SetupAccess.allowed` again when it runs (the console is always allowed).

| Command | Does |
| --- | --- |
| `ban <player> [time] [reason...]` | Bans. `time` is `30m`, `2h`, `7d`, `2w`, `1y`, `1d12h`, `3 days` or `perm`. Leave it out (`ban Bob` or `ban Bob griefing at spawn`) and the ladder decides. A first word that looks like a time but is not one (`2x`) is refused with a hint instead of becoming the reason. `auto` also means "use the ladder". |
| `unban <player> [note...]` | Lifts a running ban; the note goes into the history. |
| `baninfo <player>` (alias `banhistory`) | Current ban, bans so far, what the next ladder ban would be, last 8 history entries newest first. |
| `bans` | Everyone banned right now, newest first, 15 shown then "and N more". |

Tab completion: player names (online players plus names with a running ban) and ban lengths
(`30m 1h 6h 1d 3d 7d 30d perm`). Names resolve online players first, then a player UUID, then any name in the
account store (offline players who have joined before), then old ban records. Unknown names, ambiguous names,
banning yourself and banning someone already banned each get a short friendly line. Feedback uses translation
keys with the Citadel colours (brass headers, ivory body, sage notes, teal success, coral problems) and bold names.

## How refusal works (0.7.6, checked with javap against the installed jar)

- `AccessControlModule.get().registerAccessProvider(AccessProvider)`: the engine's own login gate. On
  `PlayerSetupConnectEvent` it asks every provider and cancels the connection with the first reason given
  (`SetupPacketHandler` then calls `disconnect(event.getReason())`). Our provider answers on the moderation thread.
  A fallback listener on `PlayerSetupConnectEvent` (`setCancelled` + `setReason`) is used only if the module is absent.
- An online player is removed with `ConnectionRegistry.get().disconnect(uuid, Message)` (what vanilla `/ban` uses).
- The refusal text is resolved on the server (`I18nModule.getMessage("en-US", key)`, English fallback built in)
  and sent as raw text, because the client has no mod translations yet when it is refused.

Example, as the bot received it: "You are banned from Eternia. / Reason: griefing the town square / Your ban ends in
2 days. / If you think this is a mistake, please contact the Eternia team." Permanent: "This ban is permanent."

## Saved data

- `moderation_ban`, key = player UUID: state (ACTIVE, LIFTED, EXPIRED), name, reason, by, byName, at, until
  (empty = permanent), offence, offences (total bans ever), events (history counter).
- `moderation_history`, key = `<uuid>:<8 digit sequence>`: kind (BAN, UNBAN, EXPIRE), at, by, byName, reason,
  length, offence. Expiry is recorded as done by "Eternia" at the moment the ban ended.
- Expiry is noticed at login, on `baninfo`/`bans`, and by a sweep every 60 s.

## SHARED EDITS

1. `EterniaModPlugin.setup()`: one line after the memories line:
   `com.hexvane.eterniamod.moderation.ModerationBootstrap.register(this);`
2. `EterniaModPlugin.shutdown()`: first line `com.hexvane.eterniamod.moderation.ModerationBootstrap.shutdown();`
3. `command/EterniaAdminCommand.java` constructor, after the existing `addSubCommand` line:
   `com.hexvane.eterniamod.moderation.ModerationCommands.addTo(this);` (adds ban, unban, baninfo, bans)
4. `domain/EterniaServices.java`: one accessor after `accounts()`:
   `public com.hexvane.eterniamod.persistence.TransactionalStore store(){return batched;}`
   (the batched store, so moderation writes share its transactions and view invalidation).
- No edit to `PlayerLifecycle`: the login check goes through the engine's access provider instead.

## CHANGELOG

- **Bans for staff**: staff can ban rule breakers for a set time or for good with `/eternia admin ban`, lift a ban with `unban`, and look up anyone's ban record with `baninfo` or see every ban with `bans`.
- **Fair escalation**: when staff give no time, repeat offences get longer bans: 1 day, then 3 days, 7 days, 30 days, then permanent.
- **Clear ban notice**: banned players see why they were banned and when they can return, and bans end on their own.

## STATUS

- Timed bans with an escalation ladder, unban, ban record and active ban list under `/eternia admin`. Login refusal
  through the engine's access provider; online players are removed with the same notice. Bans expire on their own
  (login, commands, 60 s sweep) and every ban, unban and expiry is kept with who and when.
- Verified live on 0.7.6 (port 15521, headless bots, insecure auth): non staff refused by the engine; staff ban
  with time and reason removed the online player with the full notice; that player was refused on reconnect; `bans`,
  `baninfo`, already banned, self ban, unreadable time (`2x`) and unknown name all answered; after `unban` the player
  joined; a ladder ban by lowercase name gave offence 2 = 3 days; an offline `perm` ban refused with "This ban is
  permanent". 22 unit tests cover parsing, ladder, expiry, history, restart and the refusal text.
- Not seen in the real client: chat colours and the disconnect screen layout of the multi line notice; tab completion
  (bots cannot ask for suggestions); a reason typed with quote marks (the command tokenizer may treat quotes specially).
- `server_start` cannot set `ETERNIA_MODE=local`, so the live run used the installed `HytaleServer.jar` launched by
  hand in the agent scratchpad on 127.0.0.1:15521 with `ETERNIA_MODE=local`, bridge on 15531, then stopped.

## DECISIONS

- **Ladder in data**: `Server/EterniaMod/Moderation/BanLadder.json` (`{"Steps":["1d","3d","7d","30d","perm"]}`);
  a server can override it with `ban-ladder.json` in the mod data folder (read at startup). Offences past the end keep
  the last step. A broken file falls back to the default with a warning.
- **Offence count = every earlier ban**, including timed bans staff chose and bans later lifted by unban. Simple and
  hard to game; staff who overturn a mistaken ban can see it in `baninfo`. Overrule if lifted bans should not count.
- **A running ban is not replaced**: banning someone already banned says so and asks to unban first, so a double
  command never counts two offences or silently shortens a ban.
- **Staff check** reuses the admin collection's `hytale:WorldEditor` group plus `SetupAccess.allowed` (builder tools
  permission), as setup tools do. Staff may ban other staff; only self bans are blocked.
- **Engine access provider** instead of a `PlayerLifecycle` hook: it is the engine's supported gate, it runs before
  the player is loaded, and the engine fails closed when a provider errors.
- **Reason after the time** is read from the typed line (the vanilla `/ban` approach); reasons are flattened to one
  line and cut at 200 characters. Times longer than ten years are refused (use `perm`).
- Moderation is separate from vanilla `/ban` (`bans.json`); both work side by side.

## REQUESTS

- The coordinator: please merge the four shared edits above.
- Hytale MCP: a way to pass environment variables (or seed a data file) to `server_start`, so EterniaMod can run
  `ETERNIA_MODE=local` under the MCP. Agents keep hitting this.
- Gate note: `asset_validate` shows 0 errors / 35 warnings and `ui_check_java` 0 errors / 115 warnings on this
  branch. None of them name a moderation file (this feature adds no UI and no item JSON). `main` at 21e9cad already
  shows the same 35 asset warnings, so the 34/113 baseline in the contract is a little stale.
