# Changelog

All notable changes to CommonerVote are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - unreleased

A rewrite for Paper 26.2 and Java 25. The votes table is unchanged, so every existing vote carries over.

### Added
- Six dialogs, built with Paper's Dialog API:
  - for the team, the list of guests online on the server and the vote dialog (cast, renew or withdraw, with an optional
    reason);
  - for guests, a "Becoming a Commoner" dialog with their progress, and a welcome dialog at the promotion;
  - for admins, the review dialogs (the candidates, and the votes for one player) and the settings dialogs.
- Shared settings, kept in the new `commonervote_settings` table and edited in game with `/vote config`
  (`commonervote.config`). Every server on the same database uses them.
- "Promote now" in the review dialog and `/vote promote <player>`, behind the new permission `commonervote.promote`.
  Each asks a player for confirmation first.
- A LuckPerms guard on every promotion: a player with a direct group other than `default` and the groups in the
  `promotableGroups` setting is not promoted, so the promotion command can no longer demote a ranked player. The review
  list marks such a player, the log gets one INFO line per player and session, and "Promote now" tells the admin which
  group blocks it.
- An action-bar note for a guest who was voted for, on any server of the network. It never names the voter.
- A network-wide promotion announcement, and a welcome dialog for a guest who was promoted while offline, shown at
  their next join.
- The `commonervote_promotions` table, which records who was promoted and whether they have seen the welcome.
- Permission-aware tab completion, and plain-text `review`, `clear` and `promote` for the console.
- `/vote review` without a name, which lists the candidates.
- Automated tests, and a GitHub Actions build that runs `mvn -B verify`.

### Changed
- The XP bar is a progress meter only for players without `commonervote.exempt`. Everyone with `commonervote.exempt`
  gains XP as normal.
- `/vote help` lists only the subcommands you may use, and no longer crashes from the console.
- There is one vote lifetime for the whole network, `validityDays` (100 days by default), instead of one per server.
- Expired votes are kept in the table as history and only stop counting. Before, `/vote score` and `/vote review`
  deleted them.
- `/vote` is a Brigadier command, with permission-aware tab completion, and `/commonervote:vote` works too. Player
  names are found in any case and through LuckPerms, so a player who has only been on another server is found as well.
- Settings now live in the database (`/vote config`). `config.yml` only keeps the `database` section and `useBungee`:
  the other 1.2.9 keys are ignored, and the startup log names the ones still in the file.
- Changing `votesNeeded` rescales every stored vote, so each keeps its worth in whole votes.
- `<player>` in the promotion command is replaced by the player's UUID, which LuckPerms accepts, instead of their name.
  A name can be out of date, and a renamed guest's old name could point the command at somebody else.
- A failure of the database tells the player "The vote database is unreachable. Try again in a minute." and logs a
  WARNING line with a stack trace at most once a minute.
- Database work runs on one dedicated thread, over a fresh connection for each piece of work, with the MySQL driver that
  Paper provides.
- Requires Paper 26.2, Java 25 and LuckPerms (`depend`).
- Jars are named `CommonerVote-<version>-<DEV|SNAPSHOT|RELEASE>-<commit id>.jar`.

### Fixed
- A `-voter` review dropped the last recipient, and showed nothing when there was only one; the option is gone (see
  Removed).
- Reasons lost their spaces ("great builder" became "greatbuilder").
- `/vote score` and `/vote review` ran a database DELETE on the main thread, which could stall the server.
- Servers with different vote lifetimes deleted votes that the other servers still counted.
- The keep-alive task wrote three log lines every minute, and it raced with the queries over one shared connection
  ("Packets out of order" errors).
- Nobody gained XP: every XP gain of every player was cancelled, whatever the `useXpBar` option said.
- `/vote help` from the console threw a ClassCastException.
- A fresh install crashed on every join and command, because the jar shipped no `config.yml`.
- The plugin declared `commonervote.voter` while the code checked `commonervote.vote`.
- Two quick votes could promote a guest twice, because LuckPerms applies the promotion command late. The command also
  ran without a look at the player's other groups, and `parent set` then dropped them.
- Looking up an offline player could block the main thread, and a player who had only been on another server was
  reported as unknown.
- Sending a message through the proxy while nobody was online threw a NullPointerException.
- `/commonervote:vote` printed the usage instead of running.
- Scores showed raw decimals such as `33.33333333333333%`.
- Values set with `/vote config` were saved with a trailing space, which broke numbers such as the vote lifetime.
- File storage gave a normal vote a staff vote's weight and could not read back its own file; it is gone (see Removed).

### Removed
- File storage (`votes.yml`); the votes are always in the database.
- The `apply` command and its `commonervote.apply` permission, and the `applicationNeeded`, `allowMultipleVotes` and
  `useDatabase` options.
- `/vote review <player> -voter` (the list of what one voter voted for).
- The PluginUtils dependency, and the bundled MariaDB driver.
- The Votifier `loadbefore` entry and `load: STARTUP` in `plugin.yml`.
- The `commonervote.voter` permission, which nothing checked.
