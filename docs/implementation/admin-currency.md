# Currency grants and starting Coins

New players receive **500 Coins** on their first authenticated game login. They receive no starting Crowns. Account creation and the Coin gift save together, so reconnecting, renaming a character or restarting the server cannot award it again. Accounts that already existed before this update do not receive a retroactive gift. An admin can use the commands below for those accounts.

## Commands

Use these in game after installing the updated mod:

```text
/e admin crowns me 500
/e admin coins me 500
/e admin coins PlayerName 1000
```

`gold` is an alias for `coins`. The commands add to the existing balance. They do not replace it. Use a positive whole amount, up to 2,147,483,647 per command.

You can target a player by their last known name or UUID, including while they are offline. They must have joined Eternia at least once. `me` targets the player running the command. In the server console, omit the leading slash and supply a player name or UUID instead of `me`.

Both commands require **`eternia.admin.currency`**. Server operators with full permissions and the server console can use them. Builder access and Creative mode do not grant this permission automatically.

## Retrying a grant

Each command creates a unique reference and prints it with the resulting balance. Every grant records the administrator, recipient, currency, amount and time in the database, and also writes a server log entry.

For a grant that might need retrying, supply your own reference:

```text
/e admin coins PlayerName 500 --receipt=welcome-adjustment-001
```

Repeating that exact grant with the same reference and administrator will not pay twice. Reusing the reference with a different amount, player or currency is rejected. Without `--receipt`, every command is a new grant. If a database failure prevents confirmation, the command provides its reference for a safe retry.

Reopen the menu to see updated balances. An existing Crown refund debt is settled before added Crowns become available to spend. These commands work in local and production mode and do not create Tebex payments or change packages.
