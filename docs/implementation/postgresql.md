# PostgreSQL setup and operating plan

PostgreSQL holds Eternia's durable MMO records: accounts, guilds, property ownership, balances, entitlements, season progress, mailbox and item escrow, payment receipts, and operation journals. It does not replace Hytale's world files. Terrain, native players/entities, housing snapshots and their manifests still need disk storage and coordinated backups. Website prefab revisions, render-job metadata and login sessions use a separate database; native assets and rendered PNG files remain on disk.

The game is the authority for player records. The website reads them through the authenticated game bridge and has no database rights to change them. The initial game adapter serializes domain transactions with a PostgreSQL advisory lock and checks record revisions. This favors correctness for one game server; concurrency tests passing locally do not establish performance under production load. Network latency, connection overhead and large housing snapshots must be measured before launch.

## Local setup without editing configuration files

From the repository root in PowerShell:

```powershell
.\scripts\postgres-local.ps1 Setup
.\scripts\postgres-local.ps1 Test
```

The helper uses the [official PostgreSQL Windows binary distribution](https://www.postgresql.org/download/windows/) supplied by [EDB](https://www.enterprisedb.com/download-postgresql-binaries). The pinned portable version is 18.6. It downloads/extracts inside ignored `.local/postgres/`, generates credentials, enables SCRAM password authentication, and listens only at `127.0.0.1:55432`. It installs no Windows service and does not change system PATH. Setup can be repeated without resetting the database or credentials. Do not remove `.local/postgres/data` or its matching credentials when upgrading binaries.

Four databases have separate, non-superuser accounts:

| Database | Purpose |
| --- | --- |
| `eternia_game_dev` | Manual game playtesting |
| `eternia_web_dev` | Normal website sessions, catalog and render metadata |
| `eternia_game_test` | Automated game persistence tests |
| `eternia_web_test` | Automated website persistence tests |

Application roles cannot connect to each other's databases. The helper keeps the administration account separate and uses it only for local provisioning. Generated credentials are in ignored `.local/postgres/credentials.json`; the commands read that file automatically. No secrets need to be pasted into game chat, source files or these docs.

The test command runs Java tests and jar verification, then website tests against the real test databases. It explicitly enables database writes and reruns Gradle tests so a previous cached/skipped result cannot substitute for an actual PostgreSQL run. The game checks migrations, reconnect/restart behavior, rollback, receipt replay, concurrent credits and stale revisions. The website checks immutable imports, job persistence, rollback and actual HTTP sessions across application restart, including current admin permissions. Test databases contain only generated test data and do not share authority with development playtests.

## Playtesting and website

```powershell
.\scripts\postgres-local.ps1 Game
```

Connect to **`127.0.0.1:5524`**. This uses the `run-postgres/` world and plugin data directory, `eternia_game_dev`, and authenticated multiplayer. A first run needs game-server `auth login device` and `auth persistence Encrypted` in its console, with `auth select <number>` if requested. This authentication is separate from website OAuth. The script leaves ordinary `runServer`/`run/` and `runServerLocal`/`run-local/` file saves intact. Do not point PostgreSQL at an existing furnished world and assume its file-backed ownership has been transferred.

In another terminal:

```powershell
.\scripts\postgres-local.ps1 Web
```

Open **http://127.0.0.1:3848**. The helper applies the website migration and starts the actual PostgreSQL/session implementation, with private bridge port 9011. There is no fixture sign-in on this website; real sign-in still needs Eternia's new OAuth application. The existing fixture site on port 3847 remains useful for visual and prefab-render tests before that application exists. Website test coverage can exercise persistent sessions now without inventing a live Hytale identity.

The helper supplies environment values only to its process and children, then restores them when it returns. `ETERNIA_MODE=local` enables local testing restrictions and loopback website URLs. Without `ETERNIA_DATABASE_URL`, local mode uses the file store; an explicit valid JDBC PostgreSQL URL selects the real database. Production always requires a valid PostgreSQL URL and never falls back to a local file when a database fails.

## Start, stop and back up

```powershell
.\scripts\postgres-local.ps1 Status
.\scripts\postgres-local.ps1 Start
.\scripts\postgres-local.ps1 Backup
.\scripts\postgres-local.ps1 Stop
```

The database remains running after setup/test until stopped or Windows exits. Stop game and website processes before stopping their database. Backup creates timestamped custom-format dumps of the two development databases under `.local/postgres/backups/`; it never replaces an earlier backup. Also preserve matching world files, housing snapshots, infrastructure/setup records, website assets and render output. For a consistent initial deployment backup, pause writes by stopping the game/website, dump their databases, and copy their disk state before restarting them.

Use `pgsql/bin/pg_restore.exe --list <dump>` to inspect a backup. Restore only into a newly created empty verification database first, then check migrations, record counts, representative accounts, ownership and snapshot references before considering a recovery successful. PostgreSQL documents the supported [custom-format dump and restore workflow](https://www.postgresql.org/docs/18/backup-dump.html). Do not use a destructive restore command against a running development or production database.

## Deployment and migration

1. Finish local database, multi-player and recovery acceptance. Keep this setup independent of real Tebex fulfillment.
2. Choose the game host and database region together. The game database should be close to the Hytale server, with private networking or verified TLS. The website can use Railway with its own database account and persistent assets/render storage. A shared PostgreSQL instance can host separate game and website databases, provided access remains isolated.
3. Provision production databases, credentials, backup retention and a tested restore procedure. Use provider environment settings for secrets, not game chat or repository files. Register the new OAuth application when the callback URL is ready and wire the authenticated bridge.
4. Move existing file-backed state only through an explicit migration with a maintenance window, backups, counts/revision checks and a rollback plan. No file-to-PostgreSQL authority migration is currently implemented. A fresh production launch can initialize a fresh database; transferring an existing populated server requires that migration work first.
5. Verify real login, trades/mail, furnished moves, guild departures, payment replay/refunds and restart recovery on the target hosting arrangement before opening it to players.

No Railway service, paid database, live OAuth registration or live purchase is created by the local helper. Its portable binaries, databases, backups and secrets belong to this workstation.
