# Chat channels (wave 1, role: chat)

Branch `wave/chat`. Package `com.hexvane.eterniamod.chat`, lang `eternia_chat.lang`, data
`Server/EterniaMod/Chat/ChatSettings.json`, tests `src/test/java/com/hexvane/eterniamod/chat/*Chat*Test`.

## What players get

| Channel | Reaches | Short forms | Tag colour |
| --- | --- | --- | --- |
| Server (default) | everyone on the network, every world | `/e c s`, `all`, `global`, `@s` | `#c9d3cb` quiet grey |
| World | everyone in the sender's world | `/e c w`, `@w` | `#85c5b3` Citadel accent |
| Party | party members, any world | `/e c p`, `@p` | `#8fb8e8` sky |
| Guild | guild members with `guild.chat`, any world | `/e c g`, `@g` | `#d8b66b` Citadel gold |
| Nearby | same world, within 50 blocks (data) | `/e c n`, `local`, `l`, `@n` | `#a9d18e` leaf |

- `/e chat` (`/e c`, also `/e c help`): your channel, a coloured list of all five with their short command, and usage.
- `/e c <channel>`: switch plain chat. Party and Guild refuse with a friendly line when you have no party or guild.
- `/e c <channel> <message>`: one message without switching.
- `@<channel> <message>` typed as plain chat: one message without switching, every character kept. Added because
  the engine's command tokenizer rejects square brackets in any command text (`/e c w [north] gate` fails with the
  engine's own parse error; this also affects the existing `/e guildchat`). `@Bob hi` and `a@p.com` stay normal chat.
- Lines look like `[Party] Alice: see you at the gate`; the name takes the channel colour on every channel but Server.
- Hints after the message: nobody within 50 blocks, nobody else in this world, no party/guild member online.
  Party or Guild with no group: "nobody heard that", never a fallback to public chat.
- The choice is saved (`chat_channel` namespace, key = player UUID, field `channel`) and on the next login a player
  not on Server is reminded once which channel they are in.

## How it hooks in (verified on the installed 0.7.6 jar with javap)

- `PlayerChatEvent` (async, cancellable; `getTargets/setTargets`, `getFormatter/setFormatter`, `Formatter.format(PlayerRef,String)`).
  `GamePacketHandler` fills targets with every player in the universe who has not hidden the sender, dispatches the
  event, and if not cancelled formats once and sends to each target. Commands (`/`) never raise it.
- Listener at `EventPriority.LATE`: moderation at NORMAL or earlier can cancel first. Collections' LAST listener only
  sets its formatter when the default is still in place, so ours wins and calls the collection display name itself.
- Server channel: engine delivery untouched, only the formatter changes. Other channels: the event is cancelled and
  `ChatService` delivers itself (hints then follow the line, and private text can never leak to public chat).
- Work runs on the chat service's own two daemon threads (store read, party lookup), never the world or network thread.
  Guild delivery stays on the services executor as before.
- Rank prefix hook: `ChatFormat.speaker(PlayerRef)` is the only place a name is built for chat, on every channel.
- Mute hook: `ChatGate.install(Policy)`; `ChatGate.blockReason(uuid)` is asked by plain chat, one shot sends, `@`
  shortcuts and `/e guildchat`. A failing policy blocks rather than lets a muted player through.

## SHARED EDITS

1. `EterniaModPlugin.java` `setup()`: after the `CollectionBootstrap.register` line,
   `com.hexvane.eterniamod.chat.ChatBootstrap.register(this);`
2. `EterniaModPlugin.java` `shutdown()`: before `if (socialUi != null) socialUi.close();`,
   `com.hexvane.eterniamod.chat.ChatBootstrap.close();`
3. `command/EterniaModCommand.java`: after the guildchat line,
   `this.addSubCommand(new com.hexvane.eterniamod.chat.EterniaChatCommand());`
4. `domain/EterniaServices.java`: field `chatPreferences`, one constructor line
   `chatPreferences=new com.hexvane.eterniamod.chat.ChatPreferences(store);`, accessor `chatPreferences()`.
   (The store is private there; this is the "one accessor for a new service" pattern.)
5. `command/EterniaGuildChatCommand.java`: delivery, cooldown and formatting moved into `chat/GuildChannel`
   so the Guild channel and `/e guildchat` share one path and one look. The command keeps its name, argument,
   `GuildChatText` validation and permission group; it now just calls `GuildChannel.send`. Behaviour kept:
   two second cooldown, `guild.chat` checked for sender and readers at delivery, no fallback to public chat.
   Visible change: lines read `[Guild] Name: text` (gold) instead of `[Guild Name] Name: text`.

## CHANGELOG

- **Chat channels**: talk to everyone, your world, your party, your guild or players nearby. Type /e c to see them, /e c p to switch to party chat, or /e c p hello to send just one message.
- **Quick channel messages**: start a chat line with @ and a channel, like @p on my way, to send it there without switching.
- **Your channel is remembered**: the chat channel you pick stays the same next time you log in, and you get a reminder if it is not Server.

## STATUS

2026-11-07, chat channels (wave 1, chat). Server, World, Party, Guild, Nearby channels on plain chat, `/e chat`
(`/e c`) to switch or send once, `@p text` shortcuts, saved per player (`chat_channel`). Server keeps engine delivery
with an Eternia formatter; other channels cancel the event and deliver from `ChatService`. Guild chat and `/e guildchat`
share `chat/GuildChannel`. Mute hook `ChatGate`, rank prefix hook `ChatFormat.speaker`. Nearby radius in
`Server/EterniaMod/Chat/ChatSettings.json` (50). Verified with two headless bots on a 0.7.6 server across two worlds.
Not yet seen in the real client: tag colours and bold channel names in the chat box, the `/e c` list layout.
Live guild delivery was not exercised (no guild in the test world); its path is the old guild chat code, moved.

## DECISIONS

- **Default channel is Server.** The engine's own chat already reaches every world, so Server is what players expect
  from Hytale, and a new player has no party or guild. World was considered; on a network where most players sit in
  the Hub it would split conversation between Hub and adventure worlds without being asked. Changeable in
  `ChatSettings.json` (`DefaultChannel`); a group channel is refused as a default.
- **Every channel is tagged, Server included**, in a quiet grey, so a Server line and a World line never look alike.
  Private channels also colour the speaker's name.
- **Nearby radius 50 blocks**, three dimensional, same world only. Clamped to 4..512 if the data file is odd.
- **No fallback to public chat**: Party/Guild without a group refuse with a friendly line; switching to them is refused
  too, so a player is never silently talking to nobody.
- **`@channel` shortcut** in plain chat, because commands cannot carry `[` `]` (engine tokenizer), and it is faster.
  Only an exact channel name or alias followed by a space triggers it.
- **Guild lines say `[Guild]`**, not the guild's name, so every tag is short and the same shape.
- **Sender always sees their own line**, then any hint below it.

## REQUESTS

- Moderation: plug the mute check in with `ChatGate.install(uuid -> muted ? Optional.of(Message.translation(...)) : Optional.empty())`.
  The policy runs off the world thread and may read the store. If moderation also cancels `PlayerChatEvent` at NORMAL
  or earlier, chat respects that (it skips cancelled events).
- Ranks (later): add the prefix in `ChatFormat.speaker`.
- Coordinator: no `docs/STATUS.md` or `docs/DECISIONS.md` exist in this checkout; the text above is ready for them.
- Testing note for whoever runs `server_start` next: the packaged plugin defaults to production and refuses to boot
  without PostgreSQL. I wrote `ETERNIA_MODE=local` into the new run's `mods/Hexvane_EterniaMod/eternia-server.properties`
  right after `server_start` returned (setup reads it about five seconds later). An env or data-file option on
  `server_start` would make this less fragile.
- Gate note: `asset_validate` reports 0 errors and 35 warnings (brief said baseline 34); none of the 35 name a chat file.

## Verification

- `gradlew --no-daemon build`: green, 414 tests, 0 failures (22 new chat tests: routing per channel incl. nearby edge
  and cross world, sender once, persistence across logout and restart with `LocalFileStore`, unknown saved value,
  alias uniqueness, line structure and colours, settings clamping, gate, `@` parsing, every lang key present and no dashes).
- Live, release 0.7.6 server on 15522, bots Alice and Bob (all stopped afterwards):
  plain Server chat reached Bob with `[Server]`; `/e c p` without party refused; party create/invite/accept, `/e chat party`
  confirmation; plain chat on Party reached Bob; `/e c w let's go, now "really"` kept quotes and apostrophes;
  `/e c w [north]` hit the engine parse error (hence `@`); Bob teleported 400 blocks away: Nearby reached only Alice with
  the 50 block hint; Bob moved to a second world (`world add second`): World stayed with Alice (+ hint), Party and Server
  crossed worlds; `/e c` status list; `/e c g` without guild refused; Alice relogged and got the Party reminder;
  rebuilt and rechecked `@w ... [north] gate, ok?` delivered intact, `@p` without party refused, `@Bob hi` stayed Server chat.
- No UI page was made, so no `ui_render` pictures; `notes/pictures/chat/` is empty.
