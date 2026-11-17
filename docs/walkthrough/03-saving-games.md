# Walkthrough 3: saving games

This is a guided tour of milestone 3: the app now keeps its players and games on the
phone. It's written for someone who knows engineering but is new to Android. Skim
section 1 for the shape of it, then dive into whichever part you need. Section 8 answers
the questions that come up most.

It describes the code **as it is** at the end of milestone 3. Where the code and the
docs disagree, this follows the code. Every `File.kt:line` link jumps to the line. The
match-day screens themselves are in [walkthrough 2](02-match-day.md); this one covers what storage added
to them. The Android ideas each get a short entry in [learning.md](../learning.md#milestone-3-saving-games).

---

## 1. The milestone in one page

**What it does.** The first time the app opens, it asks for the group's players and a
rough best-to-worst order, and stores them. From then on, match day works on those
stored players. Confirm saves the game as *pending*; you tally goals as they go in;
**Finalise** turns it into a *final* game, which moves everyone's rating; and the done
step shows by how much. If Android kills the app at any point, reopening it brings you
back where you were, goals and all.

**The flow.**

```
  first launch, no players stored
        │
        ▼
  FIRST RUN   "Add your players" → "Put them in rough order" → Save players ✓
        │     names best → worst → starting mu per player → players table
        ▼
  MATCH DAY   the roster comes from storage, each player rated by replaying every final game
        │     + New player → "Where do they fit?" → saved with a starting mu between neighbours
        ▼
  pick a split, or make teams manually            (walkthrough 2)
        │
        ▼
  CONFIRM     the date (today by default) → Confirm ✓
        │     one games row, status pending, pending_slot 1, plus an appearance per player
        ▼
  RECORD      "+" and "−" per player, each tap stored at once
        │     "Adjust score" for an own goal or an unknown scorer
        ▼
  Finalise result ✓   the games row becomes final (pending_slot NULL)
        │
        ▼
  DONE        the result, and each player's Skill movement → Start another match day

  app killed anywhere ──► reopened: the saved screen state brings the step back and
                          storage brings back the goals; or the roster shows
                          "Unfinished match day" with Discard and Resume →
```

Four ideas carry the whole thing:

- **Store the facts, work out the numbers.** The database holds players (with the
  starting skill they joined with) and games. Ratings are never stored: they're rebuilt
  by replaying every final game, as milestone 1's engine was designed for.
- **Every tap is a write.** A goal is stored the moment it's tapped. The screen shows
  the change at once and the database catches up a moment later, in tap order.
- **One unfinished game at a time,** and the database itself enforces it, not just the
  code.
- **Nothing is lost when Android kills the app.** The screen's position goes into a
  small bundle Android keeps for us (`SavedStateHandle`); everything else comes back
  from the database.

**How we know it's right.** On the laptop, 167 app tests run in seconds. 92 of them
drive the match-day ViewModel through whole games, including races between taps and slow
writes, and Android killing the app on every step. 20 cover first run, and 18 the result
rules ported from the original web version. On a phone or emulator, 38 instrumented
tests run the real database: the repositories, the migration harness, first run, a whole
match day from Generate to the rating movement, and a test that resumes a stored game
and recreates the activity. None of them touches the phone's real database (section 6).
As before, **every change was reviewed adversarially by two independent AI reviewers
before it was pushed** ([decisions.md](../decisions.md)).

---

## 2. The database

**What it's for:** keeping players and games on the phone, safely, in a form that can
grow with the app without ever losing a user's history.

### Room 3, KSP and the bundled SQLite

The app uses **Room**, Google's library on top of SQLite, the small database built into
every phone. You describe tables as Kotlin classes and queries as annotated functions,
and Room writes the SQL plumbing for you. This is **Room 3** (package `androidx.room3`),
the coroutine-first version ([`libs.versions.toml:18-20`](../../gradle/libs.versions.toml#L18-L20)).

Room writes that code at build time with **KSP**, Kotlin Symbol Processing: a compiler
plugin that reads annotations such as `@Entity` and `@Dao` and generates Kotlin from
them ([`libs.versions.toml:21-23`](../../gradle/libs.versions.toml#L21-L23)). The app applies both plugins
([`build.gradle.kts:11-12`](../../app/build.gradle.kts#L11-L12)), and the dependencies add Room's runtime, the SQLite driver
and Room's code generator as `ksp(...)` ([`build.gradle.kts:67-70`](../../app/build.gradle.kts#L67-L70)). *Android idea
here: see [learning.md](../learning.md#ksp-code-that-writes-code).*

The **bundled SQLite driver** ships the app's own copy of SQLite instead of using the
phone's ([`libs.versions.toml:24-26`](../../gradle/libs.versions.toml#L24-L26)). Different Android versions carry different
SQLite versions, so bundling makes every phone behave the same.
**`TeamBayesDatabase.create`** ([`TeamBayesDatabase.kt:55-60`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/TeamBayesDatabase.kt#L55-L60)) opens the database file
with that driver ([`TeamBayesDatabase.kt:57`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/TeamBayesDatabase.kt#L57)), registers the migrations, and runs
queries on `Dispatchers.IO`, the thread pool meant for waiting on disks
([`TeamBayesDatabase.kt:59`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/TeamBayesDatabase.kt#L59)).

### The three tables

**`Entities.kt`** describes the tables. *Android idea here: see [learning.md](../learning.md#room-entities-daos-and-the-database).*

| Table | Holds | Key columns |
|---|---|---|
| `players` ([`Entities.kt:19-29`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt#L19-L29)) | One row per player | `id` (a UUID), `name` (unique), `active`, `starting_mu`, `created_at` |
| `games` ([`Entities.kt:47-69`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt#L47-L69)) | One row per game | `played_at`, `status` (`pending` or `final`), `pending_slot`, `winner`, `score_a`/`score_b`, `selection`, `created_at` |
| `appearances` ([`Entities.kt:77-101`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt#L77-L101)) | Who played in which game | `game_id`, `player_id`, `team` (`a` or `b`), `goals` |

Times are epoch milliseconds, the unit the engine's `Game` already uses. `selection`
records how the teams were made (`generated`, `edited` or `manual`), so one day the app
can ask whether the balancer's teams give closer games than hand-picked ones.

The **foreign keys** on `appearances` ([`Entities.kt:71-94`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt#L71-L94)) protect history: deleting
a game deletes its appearances with it (a *cascade*), but a player who has appearances
can't be deleted at all. **`GameWithAppearances`** ([`Entities.kt:104-108`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt#L104-L108)) loads a
game and its appearances together.

### Why ratings are never stored

A player row holds only `starting_mu`, the skill estimate they joined with. Their
current rating is worked out by replaying every final game in order, from everyone's
starting rating ([`Ratings.kt:19-27`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Ratings.kt#L19-L27)). So editing, deleting or back-dating a game can
never leave a stale number behind, and a migration only ever has to carry players and
games across ([`TeamBayesDatabase.kt:39`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/TeamBayesDatabase.kt#L39)). Replaying is cheap: each game is a few dozen
sums.

### The `pending_slot` trick

There must never be two unfinished games. The obvious rule, "unique where status is
pending", is a *partial index*, which Room can't declare. So `games` has an extra
column, `pending_slot`, with a unique index ([`Entities.kt:50`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt#L50)): it's `1` while a game
is pending and `NULL` once it's final ([`Entities.kt:41-45`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt#L41-L45)). SQLite treats every
`NULL` as different from every other, so any number of final games fit, but only one row
can hold a `1`. Finalising sets it back to `NULL` in the same statement that marks the
game final ([`Daos.kt:53-58`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Daos.kt#L53-L58)). A test inserts a second pending row behind the
repository's back and checks the database itself refuses it.

### The DAOs and transactions

A **DAO** (data access object) is an interface of queries, and Room generates its body.
**`PlayerDao`** ([`Daos.kt:10-24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Daos.kt#L10-L24)) and **`GameDao`** ([`Daos.kt:27-66`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Daos.kt#L27-L66)) are the only
code that writes SQL. Two details matter:

- **Queries that return `Flow`** ([`Daos.kt:12-13`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Daos.kt#L12-L13), [`Daos.kt:29-35`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Daos.kt#L29-L35)) don't just
  answer once: Room re-runs them whenever a table they read changes, and the new answer
  flows to whoever is watching. That's how a new player appears on the roster by
  themselves. *Android idea here: see [learning.md](../learning.md#flow-from-a-database).*
- **Deleting is narrow on purpose.** `deletePendingGame` only deletes a game whose
  status is `pending` ([`Daos.kt:60-65`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Daos.kt#L60-L65)), so no bug can erase a finished game this way.

Every write in the repositories runs inside **`withWriteTransaction`**: a group of
statements that happens completely or not at all. Saving a game, for example, checks
there's no pending game, checks every player exists, inserts the game and inserts its
appearances, as one unit ([`Games.kt:174-187`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L174-L187)). If anything fails half-way, nothing is
saved. *Android idea here: see [learning.md](../learning.md#transactions).*

### Exported schemas and the migration harness

**What it's for:** making sure a future version of the app can change the tables without
losing anyone's games.

Room writes each schema version as JSON into `app/schemas/`
([`build.gradle.kts:15-19`](../../app/build.gradle.kts#L15-L19)), and those files are committed. Version 1 is [`1.json`](../../app/schemas/io.github.valnrys.teambayes.data.db.TeamBayesDatabase/1.json).
The rules for changing the schema sit in the database's KDoc
([`TeamBayesDatabase.kt:23-40`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/TeamBayesDatabase.kt#L23-L40)):

1. Bump **`SCHEMA_VERSION`** ([`TeamBayesDatabase.kt:15`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/TeamBayesDatabase.kt#L15)).
2. Write a `Migration` and add it to **`ALL_MIGRATIONS`** ([`TeamBayesDatabase.kt:21`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/TeamBayesDatabase.kt#L21)),
   the one list used by both the app and the tests.
3. Build, so Room writes the new JSON, and commit it.
4. Add a migration test.

**`MigrationTest`** (4 tests) then guards every future version. It reads the exported
JSON files ([`MigrationTest.kt:53-60`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/data/MigrationTest.kt#L53-L60)) and checks that every version can be created,
that the versions run 1 to n with one migration per step, that the newest file matches
`SCHEMA_VERSION`, and that a pending game and a final game seeded in the *oldest* schema
survive migration to the newest ([`MigrationTest.kt:93-125`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/data/MigrationTest.kt#L93-L125)). Nothing there needs
editing when the schema changes.

**The no-destructive-migration check.** Room has an escape hatch,
`fallbackToDestructiveMigration`, which wipes the database when no migration exists. It
would silently delete every game. **`NoDestructiveMigrationTest`**
([`NoDestructiveMigrationTest.kt:13-24`](../../app/src/test/kotlin/io/github/valnrys/teambayes/data/NoDestructiveMigrationTest.kt#L13-L24)) is a plain laptop test that searches the app's
source for it and fails the build if it appears.

### Name normalisation

Two people typing "Sam  Smith" and "sam smith" mean the same player. **`normaliseName`**
([`Names.kt:18-19`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Names.kt#L18-L19)) puts every name into one form before it's stored or compared:
Unicode NFC (so an accent typed as a separate mark becomes the single accented letter),
no spaces at the ends, and any run of spaces inside turned into one. Case is kept as
typed but ignored when comparing.

The database's own uniqueness check uses SQLite's `NOCASE`, which only folds A to Z
([`Entities.kt:15-18`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt#L15-L18)), so "Émile" and "émile" would slip past it. The repository
therefore also compares in Kotlin, inside the same transaction ([`Players.kt:85-89`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt#L85-L89)).

### `Mapping.kt`: rows to app types

The tables store short text codes. **`Mapping.kt`** turns them into Kotlin types and
back, so nothing outside the data package ever sees `'a'` or `'pending'`. An unknown
code is an error, not a guess ([`Mapping.kt:28-33`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Mapping.kt#L28-L33)). **`toPendingGame`**
([`Mapping.kt:53-65`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Mapping.kt#L53-L65)) treats the scores as an override only when both are set
([`Mapping.kt:55`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Mapping.kt#L55)); **`toFinalGame`** ([`Mapping.kt:68-81`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Mapping.kt#L68-L81)) insists a final game has a
winner and a score; **`newGameRow`** ([`Mapping.kt:83-98`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Mapping.kt#L83-L98)) builds a fresh pending row
with `pending_slot` 1. These are plain functions, so laptop tests check them.

---

## 3. The repositories

**What it's for:** one door per kind of data. Screens talk to repositories, never to the
database, so the rules live in one place and tests can swap in fakes.

### `PlayerRepository`

**`PlayerRepository`** ([`Players.kt:34-43`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt#L34-L43)) has three members: the `players` flow
(everyone, alphabetical), `addPlayersInOrder` for first run, and `addPlayer` for one
player with a starting mu chosen by the caller. **`StoredPlayer`**
([`Players.kt:25-31`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt#L25-L31)) is a player as stored.

**`RoomPlayerRepository`** ([`Players.kt:54-104`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt#L54-L104)) is the real one. Its clock and id
maker are constructor parameters, so tests can make them predictable.

- **`addPlayersInOrder`** ([`Players.kt:65-69`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt#L65-L69)) cleans the names and gives them evenly
  spaced starting mus with the engine's `Prior.fromRankOrder`, best first.
- **`addPlayer`** ([`Players.kt:71-76`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt#L71-L76)) refuses a non-finite mu and returns the new id.
- **`insertAll`** ([`Players.kt:82-100`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt#L82-L100)) adds the whole list in one transaction, or
  nobody: a duplicate anywhere throws **`DuplicateNameException`** ([`Players.kt:46`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt#L46))
  and nothing is saved.

### `GameRepository`

**`GameRepository`** ([`Games.kt:87-137`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L87-L137)) is the life of a game: `savePending`,
`setGoals`, `setScoreOverride`, `finalise` and `discardPending`, plus two flows,
`pendingGame` and `finalGames`. **`PendingGame`** ([`Games.kt:45-53`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L45-L53)) and
**`FinalGame`** ([`Games.kt:62-72`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L62-L72)) are what they hand out, with the engine's own
`Outcome` for the winner. **`Selection`** ([`Games.kt:15-24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L15-L24)) moved here from match
day, so the badge on the confirm step is exactly what's saved.

**`RoomGameRepository`** ([`Games.kt:145-222`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L145-L222)):

- **`savePending`** ([`Games.kt:164-188`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L164-L188)) refuses empty teams, a player listed twice
  and unknown players, and throws **`PendingGameExistsException`** if a game is already
  waiting. Everyone starts on 0 goals.
- **`setGoals`** ([`Games.kt:190-197`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L190-L197)) updates one appearance, and checks exactly one
  row changed, so a goal for someone not in the game is an error.
- **`setScoreOverride`** ([`Games.kt:199-205`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L199-L205)) stores a typed score, or clears it with
  `null`.
- **`finalise`** ([`Games.kt:207-214`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L207-L214)) stores the winner and score it's given. It
  doesn't check that the winner matches the score: deciding the winner is the screen's
  job (section 5).
- **`discardPending`** ([`Games.kt:216-221`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L216-L221)) deletes the pending game, and the cascade
  takes its appearances.

**Surprise:** `finalGames` drops repeats ([`Games.kt:157-162`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L157-L162)). Every goal tap changes
`appearances`, which re-runs the final-games query even though no final game changed.
`distinctUntilChanged` stops that from triggering a pointless replay of every rating.

### `RatingsRepository`: replaying on demand

**`RatingsRepository`** ([`Ratings.kt:28-76`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Ratings.kt#L28-L76)) works ratings out from the other two.

- **`roster`** ([`Ratings.kt:39-42`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Ratings.kt#L39-L42)) is the one flow screens should watch. It combines
  players and final games and replays once per change, on a background thread
  (`flowOn`), giving every player with their current rating. **`ratings`**
  ([`Ratings.kt:45-46`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Ratings.kt#L45-L46)) is the same thing as a map.
- **`ratingChange(gameId)`** ([`Ratings.kt:58-62`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Ratings.kt#L58-L62)) gives each player's rating just
  before and just after one game, for the done step. It reads them from the replay's
  history ([`Ratings.kt:128-142`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Ratings.kt#L128-L142)), so a game entered late but dated earlier still shows
  the right movement, unaffected by games played after it.
- **`ratingsBefore`** ([`Ratings.kt:71-75`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Ratings.kt#L71-L75)) gives everyone's ratings just before a
  game. Only tests use it so far.

The replay itself is in plain functions ([`Ratings.kt:78-142`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Ratings.kt#L78-L142)), kept apart from flows
and threads so laptop tests can check them directly.

### `Skill`: the number people see

**`skillOf`** ([`Skill.kt:14`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Skill.kt#L14)) turns a rating into the number shown on the done step:
`round((mu − 2 × sigma) × 40)`. Subtracting two sigmas makes it cautious, so a player we
know little about can't shoot up on one lucky game, and the 40 makes it read like a
skill level. A brand-new average player is about 333. The formula matches the original
web version ([`Skill.kt:6-13`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Skill.kt#L6-L13)).

### `AppContainer` and `TeamBayesApplication`: wiring without Hilt

**`AppContainer`** ([`AppContainer.kt:21-29`](../../app/src/main/kotlin/io/github/valnrys/teambayes/AppContainer.kt#L21-L29)) builds the database and the three
repositories on top of it. That's the whole object graph: four objects. Many Android
apps use a dependency-injection library such as Hilt for this; here it would be more
machinery than the problem.

**`TeamBayesApplication`** ([`TeamBayesApplication.kt:13-26`](../../app/src/main/kotlin/io/github/valnrys/teambayes/TeamBayesApplication.kt#L13-L26)) is the app's
`Application`, an object Android creates once, before any screen, and keeps for the life
of the process. It builds the container in `onCreate`. The manifest names it
([`AndroidManifest.xml:21`](../../app/src/main/AndroidManifest.xml#L21)), and **`MainActivity`** fetches the container from it
([`MainActivity.kt:19`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L19)). It's `open`, with an overridable **`createContainer`**
([`TeamBayesApplication.kt:25`](../../app/src/main/kotlin/io/github/valnrys/teambayes/TeamBayesApplication.kt#L25)), for one reason: the instrumented tests swap in an
in-memory database (section 6). *Android idea here: see [learning.md](../learning.md#the-application-class).*

**`TeamBayesApp`** ([`TeamBayesApp.kt:37-54`](../../app/src/main/kotlin/io/github/valnrys/teambayes/TeamBayesApp.kt#L37-L54)) is the top of the screen tree. The first
time it's shown it reads the stored players once: none means first run, some means match
day ([`TeamBayesApp.kt:40-45`](../../app/src/main/kotlin/io/github/valnrys/teambayes/TeamBayesApp.kt#L40-L45)). It doesn't keep watching, so adding a player on match
day can't flip the screen. The choice is remembered with `rememberSaveable`
([`TeamBayesApp.kt:39`](../../app/src/main/kotlin/io/github/valnrys/teambayes/TeamBayesApp.kt#L39)), so it survives rotation and process death. While the database
answers, which takes milliseconds, nothing is drawn. There's no navigation library: two
screens and one move between them don't need one ([`TeamBayesApp.kt:25-28`](../../app/src/main/kotlin/io/github/valnrys/teambayes/TeamBayesApp.kt#L25-L28)).

---

## 4. First run

**What it's for:** getting the group's players into the app with a sensible starting
point, so the first teams aren't a blind guess.

### `FirstRunViewModel.kt`

**`FirstRunUiState`** ([`FirstRunViewModel.kt:49-70`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L49-L70)) holds the step (`ADD` or `RANK`),
the names (best first, once ranked), the typed draft, a duplicate warning, and three
save fields: `saving`, `saved` and `saveProblem`. Three derived values:

- **`busy`** ([`FirstRunViewModel.kt:63`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L63)) is true from the tap on Save until the screen
  goes, so nothing flashes back to enabled while first run closes.
- **`canContinue`** needs 2 players (**`MIN_PLAYERS_TO_CONTINUE`**,
  [`FirstRunViewModel.kt:21`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L21)); **`belowMatchDayMinimum`** is true under 4
  (**`MIN_PLAYERS_FOR_MATCH_DAY`**, [`FirstRunViewModel.kt:24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L24)), which shows a hint
  that match day needs four to generate teams.

**`FirstRunViewModel`** ([`FirstRunViewModel.kt:102-105`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L102-L105)) takes the repository and a
**`SavedStateHandle`**. Its starting state is read from that handle
([`FirstRunViewModel.kt:107-114`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L107-L114)), and the private **`change`** function
([`FirstRunViewModel.kt:120-127`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L120-L127)) sets every new state *and* writes the step, names,
draft and `saved` flag back to it. So if Android kills the app mid-typing, the names
come back. *Android idea here: see [learning.md](../learning.md#savedstatehandle-in-depth).*

The actions, all through `change`:

- **`setDraft`** ([`FirstRunViewModel.kt:130-131`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L130-L131)) caps the name at 60 characters.
  **`addPlayer`** ([`FirstRunViewModel.kt:139-148`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L139-L148)) normalises the name, ignores a
  blank one and refuses a duplicate, ignoring case. **`removePlayer`**
  ([`FirstRunViewModel.kt:151-152`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L151-L152)) takes one off.
- **`loadSamplePlayers`** ([`FirstRunViewModel.kt:158-159`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L158-L159)) fills the list with the 14
  made-up names. Only debug builds show the button (below).
- **`next`** ([`FirstRunViewModel.kt:162-164`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L162-L164)) and **`back`**
  ([`FirstRunViewModel.kt:167-169`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L167-L169)) move between the two steps; back keeps the ranking
  and is ignored while busy.
- **`move(from, to)`** ([`FirstRunViewModel.kt:176-181`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L176-L181)) is the only way the order
  changes. Dragging and TalkBack's "Move up" and "Move down" all call it.
- **`save`** ([`FirstRunViewModel.kt:193-214`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L193-L214)) stores the names best first and sets
  `saved`. **Surprise:** if storage says a name already exists *and every name* is
  already stored, an earlier save must have gone through before the app was killed, so
  that counts as success ([`FirstRunViewModel.kt:201-207`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt#L201-L207)). If only some exist, you're
  sent back to step 1 to fix the name.

### The `saved` flag

**`FirstRunRoute`** ([`FirstRunScreen.kt:30-50`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunScreen.kt#L30-L50)) builds the ViewModel through a factory
that hands it a `SavedStateHandle` ([`FirstRunScreen.kt:38-42`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunScreen.kt#L38-L42)), and moves on to match
day when the state says `saved` ([`FirstRunScreen.kt:44-48`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunScreen.kt#L44-L48)), not when a callback
fires. **Why:** if the activity is recreated while a save is running, a callback
captured by the old screen would be lost. A flag in the state, which is also in the
saved bundle, is seen by whichever screen is showing when the save lands.
`rememberUpdatedState` lets the effect call the latest `onDone` without restarting.

**`FirstRunScreen`** ([`FirstRunScreen.kt:56-72`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunScreen.kt#L56-L72)) is stateless: it draws the step and
makes the phone's back gesture go back a step on the ranking screen.

### `AddPlayersStep.kt`

**`AddPlayersStep`** ([`AddPlayersStep.kt:54-107`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/AddPlayersStep.kt#L54-L107)) is one scrolling list: a heading and
explanation, the name field, and the names so far, each with a remove button named
"Remove Ottoline" for TalkBack ([`AddPlayersStep.kt:155-176`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/AddPlayersStep.kt#L155-L176)). **Next →** is pinned
below with the hint about match day's four-player minimum
([`AddPlayersStep.kt:91-105`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/AddPlayersStep.kt#L91-L105)). The name field has a stable key, so it keeps focus as
the list grows. The keyboard's Done adds the name, or just closes the keyboard when the
field is blank ([`AddPlayersStep.kt:135-139`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/AddPlayersStep.kt#L135-L139)).

**Debug-only sample players** ([`AddPlayersStep.kt:72-81`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/AddPlayersStep.kt#L72-L81)). "Load sample players" sits
behind `BuildConfig.DEBUG`, a constant the build sets to false for release builds
([`build.gradle.kts:44-46`](../../app/build.gradle.kts#L44-L46)). The compiler drops the whole block, so the button can't
reach a real user and fill their list with made-up people.

### `RankPlayersStep.kt`: dragging, and TalkBack

**`RankPlayersStep`** ([`RankPlayersStep.kt:63-127`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/RankPlayersStep.kt#L63-L127)) shows the names as numbered cards
to drag into order, best at the top, with Back and **Save players ✓** pinned below.

**The drag library.** Dragging comes from [Reorderable](https://github.com/Calvin-LL/Reorderable), a small Apache-2.0 library
([`libs.versions.toml:27-28`](../../gradle/libs.versions.toml#L27-L28), [`build.gradle.kts:64-65`](../../app/build.gradle.kts#L64-L65)). It reports "the item at
position 5 moved to position 3", and the screen passes that to `move`
([`RankPlayersStep.kt:70-72`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/RankPlayersStep.kt#L70-L72)). **Surprise:** the heading is also an item in the list,
so the library's positions are one more than the ranking's, which
**`ITEMS_ABOVE_PLAYERS`** ([`RankPlayersStep.kt:50`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/RankPlayersStep.kt#L50)) corrects. A long press on the
handle picks a row up ([`RankPlayersStep.kt:186-196`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/RankPlayersStep.kt#L186-L196)), so brushing past while scrolling
never moves anyone.

**TalkBack actions.** A blind user can't drag. **`RankRow`**
([`RankPlayersStep.kt:146-200`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/RankPlayersStep.kt#L146-L200)) makes each row one stop ("3. Ottoline") with two
**custom actions**, "Move up" and "Move down", found in TalkBack's actions menu
([`RankPlayersStep.kt:166-171`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/RankPlayersStep.kt#L166-L171)). They call the same `move`, so every way of reordering
behaves the same. An action is left out where it can't apply, such as "Move up" at the
top. The drag handle is hidden from TalkBack, since it would be a second stop that does
nothing a TalkBack user can use ([`RankPlayersStep.kt:191-193`](../../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/RankPlayersStep.kt#L191-L193)). While saving,
dragging, the actions and Back are all off, so the list can't change under the save.

---

## 5. Match day changes

**What it's for:** match day on real, stored players, through to a saved result. The
flow from roster to confirm is as in walkthrough 2; this section covers what's new.

### Where the roster comes from

**`MatchDayViewModel`** now takes the three repositories, a `SavedStateHandle`, the
balancer's dispatcher and a clock ([`MatchDayViewModel.kt:340-347`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L340-L347)). Its `init`
([`MatchDayViewModel.kt:389-422`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L389-L422)) starts three watchers:

1. **The roster** ([`MatchDayViewModel.kt:394-402`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L394-L402)): `ratingsRepository.roster`,
   filtered to active players and sorted by name. Inactive players' names are kept
   aside, so a stored game that includes one still shows a name.
2. **The pending game** ([`MatchDayViewModel.kt:404-413`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L404-L413)), with its date in the phone's
   time zone, for the "Unfinished match day" card.
3. **The saved-state mirror** ([`MatchDayViewModel.kt:416-421`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L416-L421)), covered below.

Until the players have loaded, `loading` is true and the screen draws nothing
([`MatchDayScreen.kt:152`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L152)), for a few milliseconds. A flash of an empty roster would be
worse. Each new roster also re-works the meters, in case a rating moved
([`MatchDayViewModel.kt:451-459`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L451-L459)).

### "+ New player" and the placement sheet

A new player needs a starting rating, and "average" is a poor guess for someone the
organiser knows is good. So **`addPlayer`** ([`MatchDayViewModel.kt:532-543`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L532-L543)) doesn't
save straight away. It checks the name (normalised, ignoring case, against every stored
player, inactive ones too) and opens **"Where do they fit?"**, with the newcomer in the
middle of the ranking.

**`PlacementSheet`** ([`PlacementSheet.kt:70-121`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PlacementSheet.kt#L70-L121)) is a bottom sheet listing everyone
best first by current mu (**`ranking`**, [`MatchDayViewModel.kt:264-266`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L264-L266)), with the new
player highlighted among them. Only they move: drag the handle, or use TalkBack's "Move
up" and "Move down" ([`PlacementSheet.kt:209-236`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PlacementSheet.kt#L209-L236)). It uses the same drag library as
first run. **Surprises:** the sheet can't be swiped down, because dragging a row down
would fight the sheet's own swipe-to-close ([`PlacementSheet.kt:84`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PlacementSheet.kt#L84)); and while saving
it refuses to close ([`PlacementSheet.kt:76-80`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PlacementSheet.kt#L76-L80)). After a TalkBack move, the list
scrolls to keep the new player in view, but never during a drag, when the library is
already scrolling ([`PlacementSheet.kt:141-151`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PlacementSheet.kt#L141-L151)).

**`startingMuAt`** ([`MatchDayViewModel.kt:296-299`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L296-L299)) turns the position into a starting
mu with the engine's `Prior.insertBetween`: the midpoint of the two neighbours, or one
step beyond either end.

**`saveNewPlayer`** ([`MatchDayViewModel.kt:572-606`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L572-L606)) stores them, ticks them and stops
any balancer run. **Surprise: the optimistic "awaiting storage" insert.** Saving returns
before the players flow reports the new player. If the screen waited, the player would
be ticked but missing from the roster for a moment, and Generate or Make teams manually
would meet an id with no name. So the new player goes into **`awaitingStorage`**
([`MatchDayViewModel.kt:360-368`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L360-L368)) and is shown from there at once
([`MatchDayViewModel.kt:582-588`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L582-L588)), and each roster update removes anyone storage has
now reported ([`MatchDayViewModel.kt:395`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L395)).

### The date, and why it's stored at local noon

The confirm step now shows the date, today by default. **`DateRow`**
([`ConfirmStep.kt:128-169`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L128-L169)) opens Material's date picker, limited to today and earlier
by **`UpToToday`** ([`ConfirmStep.kt:171-181`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L171-L181)): games are replayed in date order, so a
future date would count after games really played before it. **`setPlayDate`**
([`MatchDayViewModel.kt:725-729`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L725-L729)) also ignores a future day, and any change while
saving. Both use one "today", from the ViewModel's clock: the state carries it as
**`today`** ([`MatchDayViewModel.kt:235`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L235)), refreshed when you reach the confirm step
([`MatchDayViewModel.kt:714`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L714)), and the picker is built from it, so the screen and the
ViewModel can never disagree about which days are allowed, even at midnight or in a test
with a fixed clock. **Surprise:** the picker speaks in milliseconds at midnight UTC, so
the day is converted to and from that in UTC, never the phone's time zone; otherwise it
could show the day before ([`ConfirmStep.kt:184-186`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L184-L186)).

What's stored is a third moment: **local noon** on that day, from **`toPlayedAt`**
([`MatchDayViewModel.kt:301-310`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L301-L310)). Midnight would be fragile: read an hour to the west,
midnight is the previous day, and on a daylight-saving night midnight can fall in the
skipped hour. Noon is twelve hours from either edge, so the day always reads back the
same, and clock changes, which happen at night, never move it.

### Confirm saves the game

**`confirm`** ([`MatchDayViewModel.kt:743-789`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L743-L789)) saves the chosen teams as the pending
game, dated at local noon, with the badge's selection, then goes straight to the record
step, as the original does. The proposals, edits and locks are dropped; the ticks stay.
If a game is already pending, nothing is saved and a message says so, teams intact. The
save runs under the same write lock as discarding ([`MatchDayViewModel.kt:751-760`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L751-L760)), so
a discard still running can't make this save hit "a pending game already exists".

### The record step

**`Recording`** ([`MatchDayViewModel.kt:133-159`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L133-L159)) is the game being played: its id,
both teams, each player's goals, and the adjust-score fields. **`RecordStep`**
([`RecordStep.kt:72-118`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RecordStep.kt#L72-L118)) lays it out as the original did: three result tiles, two
score boxes, "Adjust score", then a goal row per player.

**The derivation, ported from the original** ([`MatchResult.kt:7-20`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchResult.kt#L7-L20)). The organiser
never picks a winner. They tally goals; each team's score is its sum (**`sumGoals`**,
[`MatchResult.kt:34-35`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchResult.kt#L34-L35)); the winner follows from the scores (**`winnerFromScores`**,
[`MatchResult.kt:38-42`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchResult.kt#L38-L42)). **`deriveResult`** ([`MatchResult.kt:57-68`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchResult.kt#L57-L68)) puts that
together. **`canFinalise`** ([`MatchResult.kt:78-79`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchResult.kt#L78-L79)) blocks an untouched 0–0, which
would record a draw nobody played, until a goal is tallied or the score is adjusted.

**The override.** A tally can't show an own goal or a goal nobody's sure about.
**`toggleAdjustScore`** ([`MatchDayViewModel.kt:927-938`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L927-L938)) switches to typed scores,
filling both boxes with the tally so editing starts from the right numbers.
**`setTypedScore`** ([`MatchDayViewModel.kt:944-956`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L944-L956)) keeps only the digits 0 to 9
(other scripts' digits would pass `isDigit`), at most two of them. A blank box counts as
0 (**`scoreFromInput`**, [`MatchResult.kt:48`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchResult.kt#L48)) but stays blank on screen. The winner
then follows the typed score.

**Every tap is stored.** **`changeGoals`** ([`MatchDayViewModel.kt:913-920`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L913-L920)) updates
the screen and then calls **`store`** ([`MatchDayViewModel.kt:1046-1065`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1046-L1065)), which queues
the write.

- **The write lock.** All record-step writes go through one `Mutex`
  ([`MatchDayViewModel.kt:373-378`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L373-L378)), so they happen one at a time in tap order: two
  quick taps on "+" store 1 then 2, never 2 then 1. Finalise, confirm, discard and
  resume use the same lock, so each waits for the writes before it.
- **Failure and re-read.** If a write fails, the screen shows a goal storage doesn't
  have. So `store` shows "Couldn't save that change" and, once the queue is empty,
  re-reads the game from storage (**`resyncRecording`**,
  [`MatchDayViewModel.kt:1068-1079`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1068-L1079)). Waiting for an empty queue means a later tap
  isn't wiped out by a read taken before it was stored. **`showStored`**
  ([`MatchDayViewModel.kt:1081-1095`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1081-L1095)) then puts storage's goals and score on screen, or
  closes the record step if the game has gone.

**`finalise`** ([`MatchDayViewModel.kt:970-1012`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L970-L1012)) doesn't trust the screen. It waits
its turn on the lock, so every earlier tap has been stored or has failed, then reads the
stored game back and compares the *whole* recording with what's on screen: every
player's goals and the adjusted score, if any (**`Recording.matches`**,
[`MatchDayViewModel.kt:149-159`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L149-L159); [`MatchDayViewModel.kt:976-987`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L976-L987)). A typed score is
compared as a number, so a blank box and "0" match. If anything differs, even where the
result would come out the same, nothing is saved: the screen is put right from storage,
they're told, and Finalise is ready again for the numbers they can now see. Otherwise it
stores the result worked out from storage, works out the movement and moves to the done
step. If the movement can't be worked out, the result is still safe and the done step
says so.

**Accessibility on the record step.** The three result tiles are one TalkBack stop with
one sentence, "Result: Bibs winning, 2–1", in a polite live region, so each goal that
changes the result is announced ([`RecordStep.kt:129-175`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RecordStep.kt#L129-L175)). Each goal row reads
"Ottoline, 2 goals", and its 48 dp buttons are named "Add a goal for Ottoline" and
"Remove a goal from Ottoline" ([`RecordStep.kt:263-295`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RecordStep.kt#L263-L295)). At 0 the "−" stays on screen
but disabled, so focus never jumps. Below 480 dp wide, the two teams' lists stack
instead of sitting side by side ([`RecordStep.kt:55-56`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RecordStep.kt#L55-L56)), a documented divergence from
the original.

### The done step and movement

**`movementsFrom`** ([`MatchResult.kt:95-97`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchResult.kt#L95-L97)) turns `ratingChange` into
**`Movement`**s: Skill after minus Skill before, biggest rise first, ties by name.
**`DoneStep`** ([`DoneStep.kt:45-81`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/DoneStep.kt#L45-L81)) shows the result card and one row per player: "▲
+14" in green, "▼ −9" in red, or "0" ([`DoneStep.kt:102-139`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/DoneStep.kt#L102-L139)). TalkBack hears words,
not arrows: "Ottoline, Skill up 14". **Start another match day**
([`MatchDayViewModel.kt:1018-1033`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1018-L1033)) clears the ticks for a fresh start. The original's
"View full Rankings →" button waits for a Rankings screen ([`DoneStep.kt:42-43`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/DoneStep.kt#L42-L43)).

### The unfinished-game card

While a game is pending, the roster shows **`PendingGameCard`**
([`RosterStep.kt:142-169`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L142-L169)): "Unfinished match day", its date, **Discard** and **Resume
→**.

- **`resumeUnfinished`** ([`MatchDayViewModel.kt:844-859`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L844-L859)) is ignored while the discard
  question is open. Otherwise it reads the pending game under the write lock, so a goal
  tapped just before Back is counted, and opens the record step with every stored goal
  and any typed score. **Divergence:** the original restarts the tally on resume,
  because it stored nothing until the end.
- **`discardUnfinished`** ([`MatchDayViewModel.kt:867-869`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L867-L869)) asks first, "Discard this
  unfinished match day?". **Divergence:** the original discards at once, but a delete
  can't be undone and the button sits beside Resume.
- **`confirmDiscardUnfinished`** ([`MatchDayViewModel.kt:882-905`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L882-L905)) hides the card and
  clears the ticks at once, then deletes in the background. Ticks made meanwhile are
  kept. When the delete lands it clears the game from the screen state straight away,
  rather than waiting for storage to report it, so the card can't flash back; if the
  delete fails, the card comes back with a message.

Back from the record step ([`MatchDayViewModel.kt:807-832`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L807-L832)) lands on the roster, where
the card offers the game again. The original had no way back from recording, but nothing
is lost: every goal is already stored.

### `SavedStateHandle`: surviving process death

A ViewModel survives rotation, but not Android killing the app in the background.
**`MatchDaySavedState.kt`** handles that ([`MatchDaySavedState.kt:8-22`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDaySavedState.kt#L8-L22)).

- **`SavedMatchDay`** ([`MatchDaySavedState.kt:40-55`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDaySavedState.kt#L40-L55)) is the part of the state worth
  keeping: the step, ticks, proposals (ids only), locks, the working copy, the confirm
  step's teams and date, the search and new-player text, the record step's adjusted score
  (whether it's on and the typed boxes), and which game the record or done step was
  showing. Ratings, stored players' names and goals are left out: they come back from
  storage, so they can't go stale, and the bundle stays small.
- **`writeTo`** ([`MatchDaySavedState.kt:57-83`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDaySavedState.kt#L57-L83)) and **`readFrom`**
  ([`MatchDaySavedState.kt:87-113`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDaySavedState.kt#L87-L113)) store each part under its own key, because a
  `Bundle` holds only simple types. A date goes in as its day number.
- **The mirror** ([`MatchDayViewModel.kt:416-421`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L416-L421)) writes the state on every change,
  but only once loading has finished, so the empty starting state never overwrites a
  match day waiting to be restored.
- **`restore`** ([`MatchDaySavedState.kt:172-211`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDaySavedState.kt#L172-L211)) rebuilds the screen once players
  have loaded, re-working every meter from today's ratings. It checks first: if the
  saved teams mention someone who has gone, or a step is missing what it needs (a
  confirm step without the board Back returns to), you land on the roster with your
  ticks rather than a crash ([`MatchDaySavedState.kt:186-199`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDaySavedState.kt#L186-L199)).
- **The record and done steps** need their game read back first, so `init` does that
  before showing anything (**`readResultStep`**, [`MatchDayViewModel.kt:476-493`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L476-L493)). If
  the game has gone, finalised or discarded elsewhere, you land on the roster.

*Android idea here: see [learning.md](../learning.md#savedstatehandle-in-depth) and [learning.md](../learning.md#remembersaveable).*

---

## 6. Testing

**What it's for:** proving storage keeps what it should, and that no test can ever touch
a real user's data.

### On the laptop: fakes with gates

**`InMemoryRepositories.kt`** holds fake repositories that keep everything in memory and
follow the real rules match day relies on: unique names, one pending game
([`InMemoryRepositories.kt:22-24`](../../app/src/test/kotlin/io/github/valnrys/teambayes/fakes/InMemoryRepositories.kt#L22-L24)). They also record what they were asked to do, and
they have **gates**: `CompletableDeferred` values that make a write wait until the test
opens them ([`InMemoryRepositories.kt:91-110`](../../app/src/test/kotlin/io/github/valnrys/teambayes/fakes/InMemoryRepositories.kt#L91-L110)), plus switches that make a write fail.
That lets a test act *while* a save is still running, which is exactly where race bugs
live. Some of the race tests:

- `backThenResume_whileAGoalIsBeingStored_showsTheStoredCount`: tap a goal, go Back and
  Resume while it's still being written, and the resumed screen shows the stored count.
- `discard_hidesTheCardAtOnceAndKeepsTicksMadeMeanwhile`: ticks made during a slow
  delete survive it.
- `confirm_waitsForADiscardStillRunning`: Confirm during a slow discard waits for it,
  rather than failing with "already a pending game".
- `finalise_refusesWhenStorageDiffersFromTheScreen_thenSavesWhatsStoredOnTheNextTap`,
  and `finalise_whileAdjusted_refusesIfAStoredGoalDiffersEvenThoughTheResultMatches`: a
  failed goal write under a typed score leaves the result the same, but Finalise still
  refuses.

**Process death on the laptop.** The `processDeath_…` tests build a second ViewModel
from the *same* `SavedStateHandle` and repositories as the first, which is what Android
does after killing the app. Each step is covered, including the record step coming back
with every goal and a cleared score box still blank.

### On a phone: the in-memory test runner

Instrumented tests run inside a real app on a device. Without care, they'd run against
the phone's real database: a test that discards "any pending game" would delete the
organiser's real one.

**`TeamBayesTestRunner`** ([`TeamBayesTestRunner.kt:33-36`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/TeamBayesTestRunner.kt#L33-L36)) prevents that. It's
registered as the test runner in the build ([`build.gradle.kts:31-33`](../../app/build.gradle.kts#L31-L33)) and starts the
app with **`TestTeamBayesApplication`** ([`TeamBayesTestRunner.kt:39-50`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/TeamBayesTestRunner.kt#L39-L50)) instead of
the real one. That subclass overrides `createContainer` to build an in-memory database,
which exists only while the tests run. **`resetContainer`**
([`TeamBayesTestRunner.kt:46-49`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/TeamBayesTestRunner.kt#L46-L49)) swaps in a fresh, empty one, so tests that launch the
real activity start clean. The other tests never go through the app's container: the match-day screen
tests build their own with **`inMemoryContainer`** ([`TestContainers.kt:13-18`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/TestContainers.kt#L13-L18)), `FirstRunScreenTest` uses a fake
repository, the repository tests use `DatabaseTest` (below), and `MigrationTest` works on a throwaway
file of its own, `migration-test.db`, deleted before each test. The runner's KDoc lists
how each kind of test gets its data ([`TeamBayesTestRunner.kt:14-21`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/TeamBayesTestRunner.kt#L14-L21)). The production
app carries no test switch; it only lets a subclass build the container. *Android idea
here: see [learning.md](../learning.md#a-custom-test-runner).*

The repository tests share **`DatabaseTest`** ([`DatabaseTestSupport.kt:15-39`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/data/DatabaseTestSupport.kt#L15-L39)), which
opens a fresh in-memory database before each test with a predictable clock and ids, and
closes it after. **`GameRepositoryTest`** (11 tests) includes
`theSchemaAlsoRefusesASecondPendingGame`, which goes behind the repository to check the
`pending_slot` index itself.

**`ResumeMatchDayTest`** ([`ResumeMatchDayTest.kt:40-102`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/ResumeMatchDayTest.kt#L40-L102)) launches the real activity
with a pending game in storage, resumes it from the card, adds a goal, recreates the
activity, and checks every goal is there, on screen and in storage. **Note:**
`recreate()` is a configuration change, like a rotation, so the ViewModel survives it
([`ResumeMatchDayTest.kt:31-34`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/ResumeMatchDayTest.kt#L31-L34)). Real process death is covered by the laptop tests
above. **`AppStartTest`** ([`AppStartTest.kt:36-57`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/AppStartTest.kt#L36-L57)) runs first run into match day;
**`MatchDayScreenTest`** (7 tests) ends with a whole match day, from Generate to the
rating movement.

### Why `leaveApksInstalledAfterRun` matters

By default, `connectedDebugAndroidTest` uninstalls the app when it finishes, and
**uninstalling deletes everything the app has stored**: on a phone in real use, every
player and every game. `android.injected.androidTest.leaveApksInstalledAfterRun=true`
([`gradle.properties:5-10`](../../gradle.properties#L5-L10)) keeps it installed.

### The emulator, not your phone

The in-memory runner and the setting above are two layers of protection, not permission.
Installing the debug build over the real app, or a future test that slips past both,
could still cost real data ([`TeamBayesTestRunner.kt:26-28`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/TeamBayesTestRunner.kt#L26-L28)). **Run instrumented tests
on an emulator or a spare phone, never on the phone that holds the group's games.** The
timing test's limit still only applies on a physical phone (walkthrough 2).

---

## 7. Known gaps

- **No players screen.** You can't rename, deactivate or delete a player yet. The
  `active` column is ready for it.
- **No game history.** Final games can't be viewed, edited or deleted, though replay
  makes editing safe once there's a screen for it.
- **No Rankings screen,** so the done step has no "View full Rankings →".
- **No export yet.** Backup is off on purpose, so the games live only on this phone.
  Uninstalling the app deletes them. The JSON export comes later.
- **The replay grows with history.** Every change replays every final game. That's
  milliseconds for years of weekly games, but it isn't free.
- **Release shrinking is still deferred** to the release-signing work, and the
  permission guard still doesn't check the test APK.

---

## 8. Questions you might ask

**Why not store ratings? Wouldn't that be faster?** Slightly. But a stored rating can
drift out of step with the games it came from, and every fix to an old game would need
every later rating recomputed by hand. Replaying makes the games the only truth, and a
replay is a few dozen sums per game.

**Why Room rather than a simple file?** Games, players and appearances are related
records that must stay consistent: one pending game, no orphaned appearances,
all-or-nothing saves. That's what a database is for. Room adds compile-time checks of
every query, flows that update the screen, and a tested path for future schema changes.

**What happens if the phone dies mid-game?** Every goal was stored the moment it was
tapped. Reopen the app and you're back on the record step, or the roster shows the
unfinished game with Resume →.

**Why noon?** A day stored as midnight can read back as the previous day in a time zone
to the west, or fall into a skipped daylight-saving hour. Noon is twelve hours from both
edges.

**Why can't I just pick the winner?** Because a tapped tally and a separately chosen
winner can disagree, and on a dark pitch they will. The winner always follows the score.
For own goals, adjust the score.

**Why does Resume keep the goals when the original web version doesn't?** The original
stored nothing until the end, so it had nothing to resume. Here each goal is already
stored, and throwing them away would be the surprise.

**Why no Hilt or other dependency-injection library?** There are four objects to wire.
`AppContainer` does it in a handful of lines, and the ViewModel factories are a few
more. A library would add a code generator and new concepts for no gain yet.

**Can I run the tests on my phone?** Please don't, if it holds real games. The tests use
an in-memory database and the app isn't uninstalled afterwards, but an emulator costs
nothing and risks nothing.
