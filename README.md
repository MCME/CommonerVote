# CommonerVote

In-game voting that turns guests into Commoners on the MCME Minecraft server.

Team members vote for guests whose building they have seen. Each vote counts for a share of a promotion: by default
five votes make a Commoner, and a staff vote counts double. Votes last a limited time, 100 days by default. When a guest
reaches 100 %, the plugin promotes them through LuckPerms, welcomes them and announces it.

Everything is done with Paper dialogs and chat. Version 2.0 is a rewrite for Paper 26.2 and Java 25; the
[changelog](CHANGELOG.md) lists what changed since 1.2.9.

## What it does

**For guests**
- The XP bar shows a guest's progress towards Commoner. It is a progress meter only for players without
  `commonervote.exempt`; everyone with `commonervote.exempt` gains XP as normal.
- When a team member votes, the guest sees a note in the action bar ("A team member voted for you · 80% to Commoner").
  It never names the voter, and it reaches the guest on any server of the network.
- `/vote` explains how it works and shows how far the guest is, with a progress bar and, when a link is set, a
  "Building guide" button.
- At 100 % the guest becomes a Commoner and gets a welcome dialog with a link to the rules, and the whole network sees
  an announcement. A guest whom an admin promotes while they are offline gets the welcome dialog at their next join, on
  any server.

**For the team** (`commonervote.vote`)
- `/vote` lists the guests online on your server, closest to 100 % first. A click opens that guest's vote dialog.
- The vote dialog shows the guest's progress, an optional reason (up to 150 characters) and the buttons to cast,
  renew or withdraw a vote.
- There is at most one vote per voter per guest. Voting again renews the vote and restarts its clock.
- `/vote <player>` opens the same dialog by name, in any case, also for a guest who is offline or on another server.
  Voting for yourself, for a Commoner or higher, or for a name nobody has used is refused with a chat line.

**For admins**
- `/vote review` lists everyone with valid votes who is still a guest: not promoted, and without `commonervote.exempt`,
  which LuckPerms answers for a player who is offline (at most 50, closest to 100 % first). The detail dialog shows
  each valid vote: the voter, whether it was a staff vote, its age, when it ends and the reason. From there an admin
  can promote the player now or clear their votes, each after an "Are you sure?".
- `/vote config` edits the [settings](#settings) in game and saves them for every server that shares the database.

## Requirements

- Paper 26.2 and Java 25.
- [LuckPerms](https://luckperms.net/), which is required. The plugin finds players by name through it, checks the
  groups a guest is in before promoting, and reads `commonervote.exempt` for players who are offline.
- A MySQL or MariaDB database. Paper ships the MySQL driver, and the plugin bundles none (the jar contains no
  libraries). The database must exist already; the plugin creates its tables.
- Optional: a Velocity proxy with `bungee-plugin-message-channel = true` in `velocity.toml`, for the
  [cross-server updates](#cross-server-updates).

## Installation

1. Put the jar in `plugins/` and start the server. The first start writes `plugins/CommonerVote/config.yml`, whose
   database section holds placeholders.
2. Set the database in `config.yml` (see [config.yml](#configyml)) and restart.
3. Set up permissions in LuckPerms (see [Permissions](#permissions)). At the least:
   - give `commonervote.vote` to the people who may vote, and `commonervote.staff` to staff;
   - give `commonervote.exempt` to the Commoner group and to every rank above it. This is how the plugin knows a player
     is already past the vote.
4. Check the [settings](#settings) with `/vote config`.

If the database is down when the server starts, the plugin keeps retrying once a minute. While it is down, a player who
uses a vote command is told "The vote database is unreachable. Try again in a minute." and the log gets a WARNING line
for each failure, with a stack trace at most once a minute. Until the server has read the [settings](#settings) once,
nobody is promoted and `/vote config` does not open, since the defaults may not be what is stored.

## Commands

`/vote` is a Brigadier command, and `/commonervote:vote` works as well. A subcommand you may not use is not available to
you.

| Command | Who | What it does |
|---|---|---|
| `/vote` | players | With `commonervote.vote`: the list of guests online on this server. Without it: your own progress (guests) or "You're already a Commoner." (Commoners and higher ranks) |
| `/vote <player>` | `commonervote.vote` | The vote dialog for a player |
| `/vote withdraw <player>` | `commonervote.vote` | Withdraws your vote for a player, with a chat line as confirmation |
| `/vote score` | players | Your own progress |
| `/vote score <player>` | `commonervote.score.other` or `commonervote.review` | A player's progress, in chat |
| `/vote review [player]` | `commonervote.review` | The candidates, or the votes for one player. The console gets a plain-text version |
| `/vote clear <player>` | `commonervote.clear` | Deletes every vote for a player, after a confirmation (the console clears at once) |
| `/vote promote <player>` | `commonervote.promote` | Promotes a player now, after a confirmation (the console promotes at once) |
| `/vote config` | `commonervote.config` | The settings dialogs (players only) |
| `/vote help` | anyone | The subcommands you may use |

The dialogs are for players. From the console, the commands that need a player answer with the help lines.

## Permissions

| Node | Grants | Default |
|---|---|---|
| `commonervote.vote` | Cast, renew and withdraw votes; the team's guest list | op |
| `commonervote.staff` | The vote counts as a staff vote (`staffWeight`). Includes `commonervote.vote` | op |
| `commonervote.exempt` | Already a Commoner or higher: cannot receive votes, is never auto-promoted, and keeps normal XP | op |
| `commonervote.score` | Kept for compatibility. Seeing your own progress no longer needs it | true |
| `commonervote.score.other` | `/vote score <player>` | op |
| `commonervote.review` | The review dialogs and `/vote review`; also `/vote score <player>` | op |
| `commonervote.clear` | Clearing a player's votes | op |
| `commonervote.config` | The settings dialogs | op |
| `commonervote.promote` | Promoting a player by hand ("Promote now" and `/vote promote`) | op |
| `commonervote.*` | Everything above except `commonervote.exempt` | op |

Dialog buttons check the permission again when they are clicked, so taking a permission away takes effect at once.

## Settings

The settings are changed in game with `/vote config`. They are stored in the database and shared by every server on that
database: every other server picks up a change within about a minute, and the server that saves uses it at once. The
first page holds the numbers and switches, and "Texts and links…" opens the second page. A value that is out of range,
an empty group or command, or a promotion command without `<player>` is refused with a chat line, and nothing is
saved.

| Setting | Default | Notes |
|---|---|---|
| `votesNeeded` | `5` | 1–20. Changing it rescales every stored vote, so each vote keeps its worth in whole votes: 2 of 5 becomes 2 of 3 when you lower it to 3 |
| `validityDays` | `100` | 1–365. A vote older than this stops counting; it is not deleted |
| `staffWeight` | `2` | 1–5. What a staff vote counts for, in votes. Only votes cast after a change use the new value |
| `teamWeight` | `1` | 1–5. What any other vote counts for, in votes. Only votes cast after a change use the new value |
| `autoPromote` | `true` | Promote a guest as soon as they reach 100 %. When off, only an admin promotes |
| `xpBar` | `true` | Show a guest's progress on the XP bar. When off, nobody's XP is touched |
| `broadcastNetwork` | `true` | Announce a promotion to every player on the network. When off, or without a proxy channel, only this server hears it |
| `commonerGroup` | `commoner` | The LuckPerms group a promotion gives; it fills `<group>` in the promotion command |
| `promotableGroups` | empty | Comma-separated LuckPerms groups that do not block an automatic promotion (see below) |
| `promotionCommand` | `lp user <player> parent set <group>` | Run from the console on a promotion. `<player>` is replaced by the player's UUID, which LuckPerms accepts in place of a name. Must contain `<player>` |
| `rulesUrl` | `https://www.mcmiddleearth.com/help/terms` | The "Read the rules" button in the welcome dialog |
| `guideUrl` | empty | The "Building guide" button in the guest dialog; the button is hidden when this is empty |
| `guestInfoText` | see below | The explanation a guest sees on `/vote` |
| `welcomeText` | see below | The body of the welcome dialog |
| `announceText` | see below | The network announcement |

The three texts are [MiniMessage](https://docs.advntr.dev/minimessage/format.html) and may use `<player>`,
`<votes_needed>` and `<validity_days>`. The other text boxes hold at most 256 characters, and these three at most
1,024. The defaults:

```text
guestInfoText: Team members vote for guests whose building they've seen. <yellow><votes_needed> votes</yellow> make you a Commoner. Votes last <validity_days> days.
welcomeText:   The team voted you up. You're now a <aqua>Commoner</aqua> of Middle-earth.<newline>Please read the rules before you start.
announceText:  <gold>★ <player> <yellow>has been promoted to <aqua>Commoner<yellow>!
```

### Automatic promotion

`lp user <player> parent set <group>` replaces all of a player's groups, so promoting someone who holds a badge or a role
would take it away. The plugin therefore promotes a player (automatically, and with "Promote now") only when their
direct LuckPerms groups are `default` plus the groups in `promotableGroups`. For anyone else the promotion is refused.
The review list marks such a player ("Auto-promotion blocked: has group …"), the log gets one INFO line per player per
session, and "Promote now" tells the admin which group blocks it. The admin can then use LuckPerms by hand, or add a
harmless group to `promotableGroups`.

The command names the player by UUID (`lp user 3f8e1c2a-5b7d-4e9f-a1c3-2d4b6f8a0e1c parent set commoner`), never by
name: a name can be out of date, and a renamed guest's old name may belong to someone else by now, whom the guard never
checked. The messages, the welcome and the announcement still use the player's name.

An automatic promotion also needs `autoPromote` on, the guest online on the server that notices (on join, after a
vote, or after an update from another server), and the guest not to hold `commonervote.exempt`.

## Cross-server updates

With a proxy channel, a vote for a guest who is on another server reaches them through the proxy's BungeeCord plugin
messages (`ForwardToPlayer`, subchannel `CommonerVote`): their action bar note appears, their XP bar refreshes, and they
are checked for promotion. The promotion announcement uses `MessageRaw` to `ALL`. Both need a player online on the
sending server to carry the message; when nobody is online, the guest is brought up to date when they next join.

Set `useBungee: false` in `config.yml` on a single server. Updates then stay on the server, and the announcement is a
plain broadcast.

## config.yml

Only the database connection and `useBungee` live in `config.yml`; everything else is a [setting](#settings).

```yaml
database:
  dbName: commonervote
  user: commonervote
  password: change-me
  ip: localhost
  port: 3306
useBungee: true
```

A fresh install writes this file with placeholder values (`development` as the database name, user and password) that
you must replace. The plugin connects without SSL, so keep the database on `localhost` or a private network. The
account needs `SELECT`, `INSERT`, `UPDATE`, `DELETE` and `CREATE` on the database.

## Database

The plugin creates its tables with `CREATE TABLE IF NOT EXISTS` on startup.

| Table | Columns | Holds |
|---|---|---|
| `commonervote_votes` | `voter VARCHAR(50)`, `recipient VARCHAR(50)`, `timestamp BIGINT(20)`, `weight DOUBLE`, `reason VARCHAR(150)` | One row per vote: UUID strings, the time in epoch milliseconds, and the weight as a share of a promotion (a team vote is `teamWeight / votesNeeded`, a staff vote `staffWeight / votesNeeded`). The reason is `''` when empty, never NULL. Expired votes stay as history |
| `commonervote_settings` | `name VARCHAR(64) PRIMARY KEY`, `value TEXT NOT NULL` | The [settings](#settings), one row each. Missing rows are filled with the defaults on startup |
| `commonervote_promotions` | `player VARCHAR(36) PRIMARY KEY`, `promoted_at BIGINT NOT NULL`, `source VARCHAR(16) NOT NULL`, `welcomed BOOLEAN NOT NULL` | Who has been promoted, and whether they have seen the welcome. `source` is `auto`, `manual`, or `detected` (a Commoner with votes who was promoted outside the plugin) |

A player's score is the sum of the weights of their valid votes, those whose `timestamp` lies within the last
`validityDays` days. Rows leave `commonervote_votes` only when a voter withdraws, a voter renews or casts again, or an
admin clears a player's votes.

The votes table is exactly the one version 1.2.9 uses, with no extra column or index, so 1.2.9 can still read and write
it, and the plugin can be rolled back.

## Building

You need JDK 25 and Maven.

```sh
mvn -B verify
```

This compiles the plugin, runs the tests (JUnit, Mockito and MockBukkit, with H2 in MariaDB mode for the SQL) and writes
the jar to `target/`. Nothing is shaded. The jar follows MCME's naming convention,
`CommonerVote-<version>-<DEV|SNAPSHOT|RELEASE>-<commit id>.jar`, for example `CommonerVote-2.0.0-DEV-1a2b3c4.jar`. The
kind defaults to `DEV`; pass `-Dbuild.type=RELEASE` for a release build. The commit id is that of the current HEAD, so
build after committing. In a linked git worktree, pass it yourself with
`-Dgit.commit.id.abbrev=$(git rev-parse --short=7 HEAD)`.

GitHub Actions runs the same `mvn -B verify` on every push to `master`, `development` and `revision/**`, and on pull
requests.

## Upgrading from 1.x

- Replace the jar and restart. The votes table and its rows are used as they are.
- Only `database` and `useBungee` are still read from `config.yml`. The other 1.x keys are ignored, and the startup log
  names the ones your file still has. The settings now live in the database: set them with `/vote config`. A server
  that finds no stored settings stores the defaults above, but only for the rows that are missing.
- **If your 1.x `votesNeeded` was not 5, set it before the first 2.0 start.** Existing weights were stored as
  `weight / votesNeeded`, and 2.0 ignores the 1.x `config.yml`, so it would start with 5 and change what every stored
  vote is worth. Insert your old value first (the example uses 7):

  ```sql
  CREATE TABLE IF NOT EXISTS commonervote_settings (name VARCHAR(64) PRIMARY KEY, value TEXT NOT NULL);
  INSERT INTO commonervote_settings (name, value) VALUES ('votesNeeded', '7');
  ```
- `commonervote.promote` is new. Grant it to whoever should use "Promote now".
- The `apply` command, the `applicationNeeded` and `allowMultipleVotes` options, and file storage are gone.
- `/vote review <player> -voter` (the list of what one voter voted for) is gone.
- There is one vote lifetime, `validityDays`, for every server that shares the database.
