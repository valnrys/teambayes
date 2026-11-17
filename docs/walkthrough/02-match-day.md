# Walkthrough 2: match day

This is a guided tour of the `:app` module, the Android app, one function at a time.
It's written for someone who knows engineering but is new to Kotlin and Android. Skim
section 1 for the shape of it, then dive into whichever section you need. Section 8
answers the questions that come up most.

It describes the code **as it is** now. Milestone 3 added storage: the players now come
from the phone, and Confirm saves the game and goes on to record the result. Those parts
are in [walkthrough 3](03-saving-games.md); this one covers match day from the roster to the confirm step.
Where the code and the docs disagree, this follows the code. Every `File.kt:line` link
jumps to the line. The Android ideas each get a short entry in [learning.md](../learning.md), and the
notes below point to them.

---

## 1. The app in one page

**What it does.** It runs match day: pick who's playing, get three proposed splits into
Bibs and Non-bibs, adjust them by hand if you like, and confirm. It follows the original
web version's match-day flow closely, and every place it deliberately differs says why
in a code comment. Since milestone 3 the players are the group's own, stored on the
phone, and Confirm saves the game (walkthrough 3). The app never touches the network.

**The flow.**

```
  tap the icon ─► MainActivity ─► TeamBayesTheme ─► MatchDayRoute
                                                         │ watches MatchDayViewModel.state
                                                         ▼
                                     MatchDayScreen: one step at a time
                                                         │
  ┌────────────────── ROSTER  "Who's playing?" ──────────┴──────────────┐
  │ Generate teams (n) →                           Make teams manually  │
  ▼                                                                     ▼
  Balancer.balance(k = 3) on a background thread         MANUAL  Pool → Bibs → Non-bibs
  │                                                                     │
  ▼                                                                     │
  GENERATE  "Pick a split": 3 cards, quality and win %                  │
  │   tap a chip on the chosen card → EDITED copy, live meter           │
  │   lock a chip → kept on that side by "Re-generate"                  │
  │   other card or re-generate with edits → "Discard your edits?"      │
  ▼                                                                     ▼
  CONFIRM  "Confirm teams" ◄─────────────────── Continue → ─────────────┘
  │   GENERATED / EDITED / MANUAL badge; amber button if uneven
  ▼
  Confirm ✓ → game saved → record result → done              (walkthrough 3)
```

Five ideas carry the whole thing:

- **One state value, one step at a time.** Everything on screen lives in one immutable
  `MatchDayUiState`, inside one `StateFlow`. Its `step` field says which screen shows.
  Every tap is a function on the ViewModel that builds the next state.
- **The fiddly rules are pure functions.** Who moves where, when teams are ready, and
  when to warn all live in `TeamEdit.kt`, which knows nothing about Android. The
  ViewModel calls in; the screen only draws.
- **The model comments; it never decides.** Quality, win chances and the uneven-teams
  warning are shown, but none of them stops you confirming.
- **The buttons stay put.** Every step has a scrolling body and a footer pinned to the
  bottom of the screen, holding that step's main actions, so they never scroll out of
  reach. The footer steps aside only while the keyboard is open.
- **The maths never runs on the main thread.** The main thread draws the screen, so the
  balancer runs on a background thread, and taps that would clash with it are either
  ignored or cancel it (section 6).

**How we know it's right.** On the laptop, the ViewModel tests (92 of them since
milestone 3) drive whole match days, including the races between taps and a running
balancer, and 13 tests pin down the editing rules. The engine gained 7 tests for
`quality()`, including one that checks it ranks splits exactly as `predictDraw` does. On
a phone, 7 UI tests tap through the real screens the way a person would. A timing test
measures the balancer: on a Pixel 9 Pro, 18 players take a median of about 65 ms, and 20
players between about 180 and 280 ms depending on the run. The permission guard (section
2) fails the build on any unlisted permission, and its **mutation checks** showed that
adding INTERNET through `<uses-permission-sdk-23>`, or adding a harmless VIBRATE, each
failed the build. As with the engine, **every change was reviewed adversarially by two
independent AI reviewers before it was pushed** ([decisions.md](../decisions.md)).

---

## 2. The build and the guard

**What it's for:** turning Kotlin and XML into an installable app, with the same library
versions for everyone, and refusing to build an app that could go online. Nothing here
changed in the rebuild except the fonts.

### Modules, plugins and versions

[`settings.gradle.kts:19-20`](../../settings.gradle.kts#L19-L20) lists `:engine` (the maths from milestone 1) and `:app`.
The app depends on the engine ([`build.gradle.kts:51`](../../app/build.gradle.kts#L51)); the engine knows nothing about
Android. `FAIL_ON_PROJECT_REPOS` ([`settings.gradle.kts:10`](../../settings.gradle.kts#L10)) stops a module adding its
own download sources. *Android idea here: see [learning.md](../learning.md#an-app-module-and-its-build-file).*

Since Android Gradle Plugin (AGP) 9.0, Kotlin support is built into AGP, so the app
applies only AGP and the Compose compiler ([`build.gradle.kts:6-19`](../../app/build.gradle.kts#L6-L19)). The Compose
compiler ships with Kotlin, so its version is tied to Kotlin's
([`libs.versions.toml:68-69`](../../gradle/libs.versions.toml#L68-L69)). Every version lives in [`libs.versions.toml`](../../gradle/libs.versions.toml). Compose's
many libraries take their versions from one **BOM** ("bill of materials": a list of
versions known to work together, [`libs.versions.toml:9`](../../gradle/libs.versions.toml#L9)). Espresso is pinned by hand
because Compose's test library pulls in a version that crashes on newer Android
([`libs.versions.toml:33-34`](../../gradle/libs.versions.toml#L33-L34)).

[`build.gradle.kts:23-28`](../../app/build.gradle.kts#L23-L28) sets the SDK levels: `compileSdk` 37 (which Android APIs the
code can see), `targetSdk` 37 (which Android behaviour rules the app signs up for) and
`minSdk` 26 (the oldest Android it installs on, 8.0).

### The permission guard

**What it's for:** making it impossible to build an app that asks for INTERNET, or any
other permission nobody has looked at.

An app lists the permissions it wants in its manifest. Libraries have manifests too, and
the build **merges** them, so a library can add INTERNET without anyone editing our
files. **`VerifyPermissions`** ([`build.gradle.kts:108-164`](../../app/build.gradle.kts#L108-L164)) reads the merged result.
`verify()` ([`build.gradle.kts:123-140`](../../app/build.gradle.kts#L123-L140)) fails with a message naming each unexpected
permission, or copies the manifest through unchanged ([`build.gradle.kts:139`](../../app/build.gradle.kts#L139)).
`readPermissions()` ([`build.gradle.kts:146-163`](../../app/build.gradle.kts#L146-L163)) collects both `<uses-permission>` and
`<uses-permission-sdk-23>`, turns a nameless request into a failure
([`build.gradle.kts:160-161`](../../app/build.gradle.kts#L160-L161)), and refuses DOCTYPEs in the XML
([`build.gradle.kts:149-151`](../../app/build.gradle.kts#L149-L151)). `Permission` ([`build.gradle.kts:143`](../../app/build.gradle.kts#L143)) is a name plus
the tag it came from.

- **Why an allowlist?** A blocklist only stops what you thought of. The list has one
  entry ([`build.gradle.kts:173`](../../app/build.gradle.kts#L173)), `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which
  AndroidX adds to keep the app's own internal broadcasts private. It grants no network
  access.
- **Why inside the manifest pipeline?** The `onVariants` block
  ([`build.gradle.kts:168-179`](../../app/build.gradle.kts#L168-L179)) registers `verifyAllowedPermissionsDebug` and
  `verifyAllowedPermissionsRelease` as a **transform** of the merged manifest: it goes
  in, gets checked, and comes out. Building an APK, a Play bundle or installing all need
  that manifest, so none can skip the guard. A guard attached only to `./gradlew check`
  would never stop `assembleRelease`.
- **Why not `tools:node="remove"`?** That deletes a library's request during the merge,
  so the build would pass while hiding that a library wanted the network
  ([`AndroidManifest.xml:5-12`](../../app/src/main/AndroidManifest.xml#L5-L12)).

### The manifest choices

*Android idea here: see [learning.md](../learning.md#the-manifest-and-permissions).*

- **Backup off** ([`AndroidManifest.xml:22-24`](../../app/src/main/AndroidManifest.xml#L22-L24)): no player data goes to Google's cloud
  backup or phone-to-phone transfer. The app will offer its own export later.
- **Cleartext off** ([`AndroidManifest.xml:25`](../../app/src/main/AndroidManifest.xml#L25)): unencrypted HTTP is forbidden, a
  second lock on a door that has no key anyway.
- **The launcher entry** ([`AndroidManifest.xml:32-39`](../../app/src/main/AndroidManifest.xml#L32-L39)): `exported="true"` lets the
  launcher, which is another app, start us.
- **The emoji2 initializer, removed** ([`AndroidManifest.xml:48-57`](../../app/src/main/AndroidManifest.xml#L48-L57)): it can ask a
  font-provider app to download an emoji font, which is network activity by proxy. This
  *is* a `tools:node="remove"`, but for a start-up hook, in plain sight.
- **Fonts are bundled**, for the same reason. The five font files live in `res/font` and
  their OFL licence text is in [NOTICE](../../NOTICE). Android's "downloadable fonts" would fetch them
  through a provider app at run time.

---

## 3. The theme

**What it's for:** one place that decides how everything looks: colours, fonts and
corner shapes. The palette, fonts and shapes follow the original web version, with
contrast raised where its colours were too faint to read.

### The palette

The app is **dark-first**: the dark scheme was designed first and the light one derived
from it ([`Theme.kt:14-23`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L14-L23)). It still follows the phone's light or dark setting, and it
still ignores Android 12's wallpaper-based "dynamic colour", so it looks the same on
every phone.

**`DarkColors`** ([`Theme.kt:57-87`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L57-L87)) is a near-black blue background (`#0B0E13`),
slightly lighter cards (`surfaceContainerLow`, `#111720`), raised cards a step lighter
again (`surfaceContainerHigh`), and a sky-blue accent (`#38BDF8`) with near-black text
on it. **`LightColors`** ([`Theme.kt:25-55`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L25-L55)) mirrors it with a deeper blue accent. Each
colour comes with an "on" partner, the colour for text or icons drawn on top of it.

### Contrast: why muted text is lighter than the original

WCAG, the web accessibility standard, asks for at least **4.5:1** contrast for normal
text and **3:1** for borders and controls. Two of the original's colours fell short as
text, so they were lightened:

| Colour | Original | Now | Why |
|---|---|---|---|
| Muted text (`onSurfaceVariant`) | `#5B7896` | `#7B97B4` | The original is 4.2:1 on the background and 3.6:1 on raised cards. The new one is about 5.4:1 even on raised cards. |
| Error and the red Discard | `#EF4444` | `#F87171` | The original is 4.3:1 on raised cards; the lighter red is about 5.9:1 ([`Theme.kt:112`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L112)). |

The old muted shade still has two jobs that don't involve reading it. It's the dark
theme's **`outline`**, for control borders such as the Ghost buttons, where 3:1 is
enough: it reaches at least 3.2:1 on every dark surface ([`Theme.kt:80`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L80)). Dividers use
the subtler `outlineVariant`. And it lives on as **`mutedFaint`**, for decoration, never
text.

### Extra colours: `TeamBayesColors`

Material 3 has no slot for "amber warning" or "win green", so **`TeamBayesColors`**
([`Theme.kt:100-107`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L100-L107)) holds them: `amber` (the EDITED badge and the uneven warning),
`green` and `red` (results), `teamNeutral` and `mutedFaint`. There's a dark set and a
light set ([`Theme.kt:109-123`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L109-L123)). Only `amber` and `red` are used so far; the others are
ready for the results screens.

They reach the screen through a **CompositionLocal** called `LocalTeamBayesColors`
([`Theme.kt:125`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L125)), a value that any composable inside the theme can read without it
being passed down by hand. The extension property `MaterialTheme.teamBayesColors`
([`Theme.kt:128-129`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L128-L129)) makes reading it look like reading any other theme colour.
*Android idea here: see [learning.md](../learning.md#a-compositionlocal-for-extra-colours).*

**`TeamBayesTheme`** ([`Theme.kt:137-152`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L137-L152)) provides the right `TeamBayesColors`, then
wraps everything in `MaterialTheme` with the colour scheme, typography and shapes.
*Android idea here: see [learning.md](../learning.md#material-3-theming).*

### Fonts: `Type.kt`

Three families, all bundled under the SIL Open Font License ([`Type.kt:21`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt#L21)):

- **`InstrumentSerif`** ([`Type.kt:24-27`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt#L24-L27)) for headings. It has one weight, so there's
  a regular and an italic file.
- **`DmSans`** ([`Type.kt:30-44`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt#L30-L44)) for body text and labels. It's a single *variable*
  font file, one file that holds every weight. Each `Font` entry sets the file's `wght`
  axis to the weight it stands for.
- **`DmMono`** ([`Type.kt:47-50`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt#L47-L50)) for numbers, so percentages line up and don't jiggle
  as they change.

*Android idea here: see [learning.md](../learning.md#bundled-fonts-and-fontfamily).*

**`TeamBayesMaterialTypography`** ([`Type.kt:104-121`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt#L104-L121)) maps them onto Material's slots:
serif for display, headline and title styles; DM Sans for body and label styles.
**`TeamBayesTypography`** ([`Type.kt:59-76`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt#L59-L76)) adds what Material has no slot for:
`numbers` (DM Mono), `numbersLarge` (not used yet) and `sectionLabel`.
**`SectionLabel`** ([`Type.kt:79-87`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt#L79-L87)) is a composable because Compose text styles can't
uppercase text: it uppercases the words itself and colours them muted. `serif()` and
`sans()` ([`Type.kt:89-102`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt#L89-L102)) are small helpers that build the styles.

### Shapes: `Shape.kt`

**`TeamBayesShapes`** ([`Shape.kt:7-15`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Shape.kt#L7-L15)): cards have 16 dp corners, buttons 14 dp
corners and a 52 dp minimum height, chips are full pills. **`TeamBayesMaterialShapes`**
([`Shape.kt:19-25`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Shape.kt#L19-L25)) hands the same values to Material, so cards and chips pick them up
by default. Buttons default to a pill, so the app's button helpers pass the button shape
explicitly.

### Layout: a pinned footer on every step

Each step sits in the same frame, **`StepLayout`** in `TeamComponents.kt`
([`TeamComponents.kt:376-429`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L376-L429)): a body that takes all the spare height and scrolls on
its own, a thin divider in the subtle `outlineVariant` colour, and a footer pinned to
the bottom with that step's main controls. The original kept its roster this way, with
the player list scrolling in its own box while the buttons stayed put. **While the
keyboard is open, the footer hides**, so typing never squeezes the body into a sliver.
The Scaffold's `safeDrawing` insets include the keyboard, so the body then ends just
above it, and the buttons come back when the keyboard closes. No step needs
`imePadding`. Every body starts with its heading 16 dp from the top, so the steps line
up as you move between them.

The window theme in `res/values/themes.xml` ([`themes.xml:8-10`](../../app/src/main/res/values/themes.xml#L8-L10)) and its `values-night`
twin ([`themes.xml:3-5`](../../app/src/main/res/values-night/themes.xml#L3-L5)) set the window background to the Compose background, so
there's no white flash before Compose draws.

---

## 4. File by file

Reading order: the entry point, the sample data, the pure rules, the ViewModel, then the
screens from top to bottom.

### `MainActivity.kt`: the front door

**What it's for:** the one thing Android launches. **`MainActivity`**
([`MainActivity.kt:15`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L15)) is the app's only Activity. `onCreate`
([`MainActivity.kt:17-27`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L17-L27)) lets the app draw behind the system bars with
`enableEdgeToEdge()` ([`MainActivity.kt:21`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L21)), then hands the window to Compose with
`setContent` ([`MainActivity.kt:22-26`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L22-L26)), wrapping `TeamBayesApp` in the theme. Since
milestone 3 it first fetches the app's shared storage from the `Application`
([`MainActivity.kt:19`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L19)), and `TeamBayesApp` chooses between first run and match day
(walkthrough 3). *Android idea here: see [learning.md](../learning.md#activity-and-setcontent).*

### `SamplePlayers.kt`: made-up players

**What it's for:** made-up players for tests, previews and first run's debug-only "Load
sample players" ([`SamplePlayers.kt:17-20`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L17-L20)). Since milestone 3 the app's roster comes
from storage.

- **`Player`** ([`SamplePlayers.kt:15`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L15)): an id, a display name and the engine's
  `Rating`. The id is never shown, but it matters: the engine sorts by id, so it decides
  how ties break.
- **`SamplePlayers`** ([`SamplePlayers.kt:25`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L25)) holds 14 invented first names
  ([`SamplePlayers.kt:27-31`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L27-L31)) and **`all`** ([`SamplePlayers.kt:34`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L34)), all 14 of them.
- **`make(count)`** ([`SamplePlayers.kt:43-50`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L43-L50)) builds `count` players, best first. The
  means come from the engine's `Prior.fromRankOrder`; every sigma is `SIGMA0`. Past 14,
  names repeat with a number ("Ottoline 2"), which the timing test uses for 18 and 20
  players.
- **`idFor(number)`** ([`SamplePlayers.kt:56`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L56)), private, gives `p01`, `p02` and so on.
  It uses `padStart`, not `String.format`, because `format` can use the phone's language
  for the digits.

### `TeamEdit.kt`: the hand-editing rules

**What it's for:** every fiddly rule about moving players, as pure functions: values in,
new values out, nothing changed in place, no Android. That makes it the easiest code in
the app to test. Team A is always the Bibs, team B the Non-bibs.

- **`Selection`**: how a set of teams was made, `GENERATED`, `EDITED` or `MANUAL`. Since
  milestone 3 it lives in the storage layer ([`Games.kt:15-24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt#L15-L24)), so the badge on the
  confirm step is exactly what's saved with the game, and one day we can ask whether the
  balancer's teams give closer games than hand-picked ones.
- **`WorkingCopy`** ([`TeamEdit.kt:31-40`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L31-L40)): teams being edited by hand, kept separate
  so the balancer's proposals stay as they were. It holds a mode, both teams and a pool.
  Its `init` refuses `GENERATED`, since a working copy is by definition edited or
  manual.
- **`fromSplit`** ([`TeamEdit.kt:43-44`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L43-L44)) starts editing a proposal (empty pool).
  **`fromRoster`** ([`TeamEdit.kt:47-48`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L47-L48)) starts the manual board with everyone in the
  pool, in tick order.
- **`move(playerId)`** ([`TeamEdit.kt:59-67`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L59-L67)) is the only way teams change by hand. On
  a proposal a player swaps sides; on the manual board they step round Pool → Bibs →
  Non-bibs → Pool. They join the end of their new list. An unknown id gives back the
  very same object, which the ViewModel uses to spot a move that did nothing. *Kotlin
  idea: `when (playerId) { in teamA -> ... }` branches on list membership.*
- **`nextPlace`** ([`TeamEdit.kt:73-78`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L73-L78)) says where a tap would send someone, for
  TalkBack's "move to Non-bibs". **`Place`** ([`TeamEdit.kt:81`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L81)) is the three board
  positions.
- **`isComplete()`** ([`TeamEdit.kt:84`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L84)): nobody in the pool and someone on each team.
- **`SizeGap`** and **`sizeWarning`** ([`TeamEdit.kt:92-96`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L92-L96)): returns "6 v 4" when the
  teams differ by more than one, otherwise `null`. It's advisory: it turns the confirm
  button amber but never blocks it.
- **`lockAfterTap`** ([`TeamEdit.kt:106-110`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L106-L110)): tapping a lock cycles unlocked → locked
  to this side → unlocked. If the player was locked to the *other* side (from another
  card), the tap moves the lock here.
- **`Meter`** ([`TeamEdit.kt:120`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L120)) is the model's view of one pair of teams: `quality`
  and the Bibs' win chance. **`meterFor`** ([`TeamEdit.kt:130-135`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L130-L135)) works it out with
  the engine, or returns `null` while a side is empty. It's a handful of sums, cheap
  enough to run straight after every tap on the main thread.

### `MatchDayViewModel.kt`: the step machine

**What it's for:** holding the whole match day as one state, deciding what every tap
does, and running the balancer without freezing anything.

#### Constants and small types

- **`OPTIONS = 3`** ([`MatchDayViewModel.kt:53-54`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L53-L54)): how many proposals to show, as the
  original did. **`MAX_NAME_LENGTH = 60`** ([`MatchDayViewModel.kt:56-57`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L56-L57)) caps a new
  name. **`MIN_PLAYERS_MANUAL = 2`** ([`MatchDayViewModel.kt:59-60`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L59-L60)): one a side.
- **`Step`** ([`MatchDayViewModel.kt:66-84`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L66-L84)): `ROSTER`, `GENERATE`, `MANUAL`,
  `CONFIRM`, then `RECORD` and `DONE`, which milestone 3 added (walkthrough 3).
- **`SplitOption`** ([`MatchDayViewModel.kt:93`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L93)): one proposal, its teams in name order
  plus its `Meter`. **`ChosenTeams`** ([`MatchDayViewModel.kt:96`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L96)): the teams on the
  confirm step and how they were made.
- **`DiscardAction`** ([`MatchDayViewModel.kt:99-105`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L99-L105)): what "Discard" in the dialog
  will do, either show another proposal or re-generate. The dialog remembers the action,
  so one dialog serves both.

#### `MatchDayUiState`: everything on screen

**`MatchDayUiState`** ([`MatchDayViewModel.kt:215-245`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L215-L245)) is deliberately flat, one field
per thing the organiser can see or change, in the same shape as the original web
version's screen state. Fields for later steps sit unused until then. Milestone 3 added
fields for loading, the placement sheet, the date and the stored game (walkthrough 3).
The ones that matter here: `selectedIds` keeps tick order; `generating` is true while
the balancer runs; `problem` and `notice` are string resource ids; `workingCopy` is
`null` until someone moves a player; `liveMeter` is the meter for the working copy;
`discardPrompt` is set while the dialog is open.

The derived values:

- **`namesById`**, **`ratingsById`**, **`visiblePlayers`**
  ([`MatchDayViewModel.kt:247-258`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L247-L258)) are worked out on first use with `by lazy`, so a
  state that never needs them never builds them. `visiblePlayers` filters by the search
  text, ignoring case.
- **`canMakeTeams`** ([`MatchDayViewModel.kt:274`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L274)): at least two ticked. **Both**
  roster buttons unlock at two, as in the original. The balancer needs four, so with two
  or three, Generate shows the engine's own reason ("needs at least 4 players. With
  fewer, make teams manually") rather than sitting greyed out with no explanation.
- **`canContinue`** ([`MatchDayViewModel.kt:281-284`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L281-L284)): on the generate or manual step,
  not generating, and either the working copy is complete or there's a proposal to take.

#### The class

**`MatchDayViewModel`** ([`MatchDayViewModel.kt:340-347`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L340-L347)) takes the three repositories,
a `SavedStateHandle`, where to run the balancer (`Dispatchers.Default` by default; tests
swap in one they control) and a clock. Walkthrough 3 covers the storage side. It
implements `MatchDayActions`, the interface the screen calls. The state starts empty and
loading ([`MatchDayViewModel.kt:352`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L352)) until the stored players arrive; `state`
([`MatchDayViewModel.kt:355`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L355)) is the read-only view. `generateJob`
([`MatchDayViewModel.kt:371`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L371)) remembers the run in progress so it can be cancelled.
*Android idea here: see [learning.md](../learning.md#viewmodel-and-stateflow) and [learning.md](../learning.md#a-step-machine-in-one-stateflow).*

#### Roster actions

- **`toggleSelected`** ([`MatchDayViewModel.kt:502-511`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L502-L511)) ticks or unticks. It cancels
  any balancer run, because the roster just changed. Unticking also drops the player's
  lock, so it can't quietly return if they're ticked again.
- **`setSearch`** ([`MatchDayViewModel.kt:514`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L514)) and **`setNewPlayerName`**
  ([`MatchDayViewModel.kt:517-518`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L517-L518)) store the typed text; the name is cut to 60
  characters and any duplicate message clears.
- **`addPlayer`** ([`MatchDayViewModel.kt:532-543`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L532-L543)) normalises the name, ignores a
  blank one, and refuses one already taken, ignoring case, so two "Sam"s can't be
  confused on the pitch. Since milestone 3 it doesn't add anyone straight away: it opens
  "Where do they fit?", so the newcomer is placed in the ranking and saved with a
  sensible starting rating (walkthrough 3).
- **`generate`** ([`MatchDayViewModel.kt:609`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L609)) runs the balancer.
  **`makeTeamsManually`** ([`MatchDayViewModel.kt:612-627`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L612-L627)) opens the board with
  everyone in the pool and drops any old proposals.

#### Pick-a-split actions

- **`selectSplit(index)`** ([`MatchDayViewModel.kt:632-640`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L632-L640)) chooses a card. With hand
  edits it asks first, by setting `discardPrompt`. It ignores taps while generating.
- **`regenerate`** ([`MatchDayViewModel.kt:643-651`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L643-L651)) runs the balancer again with the
  current locks, asking first if there are edits.
- **`confirmDiscard`** ([`MatchDayViewModel.kt:654-661`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L654-L661)) drops the edits and does the
  remembered action. **`dismissDiscard`** ([`MatchDayViewModel.kt:664`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L664)) just closes the
  dialog.
- **`movePlayer`** ([`MatchDayViewModel.kt:681-696`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L681-L696)) moves one player. The first move
  on a proposal turns it into an `EDITED` working copy, and the meter is recomputed
  straight away. A move that changes nothing is ignored, so there's no EDITED badge for
  nothing ([`MatchDayViewModel.kt:689`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L689)). **Moving also clears that player's lock**
  ([`MatchDayViewModel.kt:693`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L693)). That's a deliberate divergence from the original,
  explained in the KDoc ([`MatchDayViewModel.kt:673-677`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L673-L677)): a lock means "keep them on
  this side", so after a move it would show a lock that no longer matches where they
  are.
- **`toggleLock`** ([`MatchDayViewModel.kt:699-701`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L699-L701)) applies `lockAfterTap`, ignored
  while generating.
- **`continueToConfirm`** ([`MatchDayViewModel.kt:704-716`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L704-L716)) takes the working copy if
  there is one, otherwise the chosen proposal, to the confirm step. It closes any open
  dialog.

#### Confirm and back

- **`confirm`** ([`MatchDayViewModel.kt:743-789`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L743-L789)) saves the teams as the pending game
  and goes straight to recording the result (walkthrough 3). The proposals, edits and
  locks are done with, so they're dropped; the ticks stay. **`noticeShown`**
  ([`MatchDayViewModel.kt:792`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L792)) clears the notice once the snackbar has shown it.
- **`back`** ([`MatchDayViewModel.kt:807-832`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L807-L832)) goes one step back. From the generate or
  manual step it drops edits, proposals and locks, as the original does. From confirm it
  keeps them and returns to the board you came from, manual or generate. From the record
  step it goes to the roster (walkthrough 3).

#### The balancer

**`runBalancer`** ([`MatchDayViewModel.kt:1106-1144`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1106-L1144)) builds the roster from the ticked
players and filters the locks to people who are playing ([`MatchDayViewModel.kt:1113`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1113)),
a second line of defence. It cancels any earlier run, sets `generating`, and launches a
coroutine that runs `Balancer.balance(..., k = 3)` on the background dispatcher
([`MatchDayViewModel.kt:1119-1120`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1119-L1120)). *Android idea here: see [learning.md](../learning.md#viewmodelscope-and-dispatchersdefault).*

When it finishes, an `Impossible` sets `problem` and leaves everything else alone, so
the old proposals stay. A `Splits` result moves to the generate step with fresh
proposals and no edits. **Surprise:** each team is re-sorted by name
([`MatchDayViewModel.kt:1128-1131`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1128-L1131)). The engine lists players by id, which means
nothing to a person; name order is easy to scan and gives nothing away on a shared
screen.

**`cancelGenerating`** ([`MatchDayViewModel.kt:1146-1150`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1146-L1150)) cancels the job and clears
`generating`. The balancer's maths never checks for cancellation, so the CPU work runs
on, but a cancelled coroutine can't publish its result: when `withContext` returns to
it, it throws instead.

**`Reason.message()`** ([`MatchDayViewModel.kt:1211-1219`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L1211-L1219)) maps each engine refusal to
a sentence. A duplicate id or a lock on an unknown player can't come from these screens,
so they share "Something went wrong".

### `MatchDayScreen.kt`: the frame

**What it's for:** picking which step to draw, and owning the things that sit on top of
every step.

- **`MatchDayActions`** ([`MatchDayScreen.kt:36-66`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L36-L66)) is an interface with one function
  per tap. Passing one interface, rather than a dozen lambdas, keeps each step's
  signature short. **`NoActions`** ([`MatchDayScreen.kt:69-99`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L69-L99)) does nothing, for
  previews.
- **`MatchDayRoute`** ([`MatchDayScreen.kt:111-119`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L111-L119)) is the only function that knows
  about the ViewModel. Since milestone 3 it takes the app's container and builds the
  ViewModel through a factory. It collects the state, stopping while the app is in the
  background, and passes the state and the ViewModel (as `MatchDayActions`) down.
  *Android idea here: see [learning.md](../learning.md#state-hoisting).*
- **`MatchDayScreen`** ([`MatchDayScreen.kt:129-179`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L129-L179)) is stateless. `BackHandler`
  ([`MatchDayScreen.kt:133`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L133)) makes the phone's back gesture call `back()` on every step
  except the roster, where back leaves the app as usual. A `LaunchedEffect` keyed on the
  notice ([`MatchDayScreen.kt:135-143`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L135-L143)) shows each new notice in a snackbar, then
  reports it shown. The `Scaffold` measures `safeDrawing` insets (status bar, navigation
  bar, camera cutout and keyboard), and the `when` picks the step
  ([`MatchDayScreen.kt:147-161`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L147-L161)). Until the stored players load, it draws nothing
  ([`MatchDayScreen.kt:152`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L152)). The "Where do they fit?" sheet and two dialogs, "Discard
  your edits?" and "Discard this unfinished match day?", sit on top when needed
  ([`MatchDayScreen.kt:163-178`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L163-L178)). *Android idea here: see [learning.md](../learning.md#composable-functions-and-recomposition).*
- **`DiscardDialog`** ([`MatchDayScreen.kt:188-202`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L188-L202)) is a standard Material
  `AlertDialog` with a question, Cancel and a red Discard. Being standard, TalkBack
  announces it, and back or tapping outside counts as Cancel.

### `RosterStep.kt`: "Who's playing?"

**What it's for:** ticking players, searching, adding someone new, then choosing how to
make teams.

**`RosterStep`** ([`RosterStep.kt:61-133`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L61-L133)) puts everything except the buttons in one
scrolling `LazyColumn` ([`RosterStep.kt:67-109`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L67-L109)), a list that only builds the rows on
screen: the title and count, the search field, the player rows and, last, "+ New
player", which sits under the list as in the original. Scrolling it all as one means
even a short screen, such as landscape at a large font size, can reach every part. The
footer ([`RosterStep.kt:111-131`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L111-L131)) holds only any problem and the two buttons, "Make
teams manually" and "Generate teams (n) →" (which reads "Generating…" while it runs), so
they're always in reach except while the keyboard is open. Both buttons need two ticks.

- **The search field** ([`RosterStep.kt:78-92`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L78-L92)) uses a real floating label rather than
  a placeholder, so it stays visible while you type and TalkBack reads it as the field's
  name. The list filters as you type, so the keyboard's Search key only has to close the
  keyboard, which brings the buttons back ([`RosterStep.kt:86-88`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L86-L88)).
- **The rows** use `key = { it.id }` ([`RosterStep.kt:94-102`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L94-L102)), so a tick stays on the
  right person while the search narrows the list. With no matches, a hint suggests
  adding someone ([`RosterStep.kt:103-105`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L103-L105)). The list's test tag, **`ROSTER_LIST_TAG`**
  ([`RosterStep.kt:48`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L48)), lets the UI tests find it and scroll it.
- **"+ New player"** is the list's last item ([`RosterStep.kt:106-108`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L106-L108)), so you scroll
  to the bottom of the roster to add someone. **Surprise:** the header, search field and
  new-player field each have a fixed item key, like the rows. Without one, an item is
  identified by its position, so as a search or an Add changed the rows above, the field
  would become a "different" item, lose focus, and close the keyboard mid-typing.

**`RosterHeader`** ([`RosterStep.kt:172-197`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L172-L197)) is the title plus an "n selected" pill.
The pill is a polite **live region** ([`RosterStep.kt:192-193`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L192-L193)): TalkBack reads the new
count after each tick, once it has finished reading the row.

**`PlayerRow`** ([`RosterStep.kt:206-218`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L206-L218)) makes the whole 52 dp row one target.
`toggleable(role = Role.Checkbox)` ([`RosterStep.kt:212`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L212)) turns the row into a single
checkbox for TalkBack, and the `Checkbox` inside only draws, so one tap is never handled
twice. **`RosterRowHeight`** ([`RosterStep.kt:45`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L45)) is 52 dp, a little taller than the
48 dp minimum, as in the original. *Android idea here: see [learning.md](../learning.md#semantics-for-talkback).*

**`NewPlayerField`** ([`RosterStep.kt:226-263`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L226-L263)) is a text field with an Add button. The
keyboard capitalises words, and its Done key opens "Where do they fit?", or just closes
the keyboard when the field is blank ([`RosterStep.kt:244-252`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L244-L252)). A duplicate marks the
field as an error with "Player 'Sam' already exists" underneath
([`RosterStep.kt:242-243`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L242-L243)), which TalkBack reads with the field. Add is disabled while
the field is blank. When the keyboard opens for it, the footer hides and the list ends
just above the keyboard, so the field stays in view as you type.

### `PickSplitStep.kt`: "Pick a split"

**What it's for:** showing the three proposals, letting you choose one, adjust it, lock
players and re-generate.

**`PickSplitStep`** ([`PickSplitStep.kt:49-67`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L49-L67)) puts the cards in the scrolling body
and pins a footer ([`PickSplitStep.kt:55-65`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L55-L65)): the meter line, "↻ Re-generate", and
Back / Continue. The meter line is always there, blank until the first move, so the
footer doesn't jump when the numbers arrive. Pinning the meter and Re-generate goes
slightly beyond the original, which pinned only its roster buttons. It's deliberate: you
can move a chip and watch the numbers change without scrolling down to find them
([`PickSplitStep.kt:46-47`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L46-L47)).

**`PickSplitBody`** ([`PickSplitStep.kt:70-109`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L70-L109)) is the scrolling part: the heading, a
note when the search was sampled ([`PickSplitStep.kt:80`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L80)), a hint, any problem, then
the cards. The cards sit in a column marked `selectableGroup()`
([`PickSplitStep.kt:85-86`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L85-L86)), so TalkBack treats them as one set of choices ("1 of 3").
Only the chosen card shows hand edits; the others stay as proposed
([`PickSplitStep.kt:89-90`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L89-L90)). While the balancer runs, nothing on the cards responds
([`PickSplitStep.kt:101-103`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L101-L103)). *Android idea here: see [learning.md](../learning.md#selectablegroup-and-radio-semantics).*

**`SplitCard`** ([`PickSplitStep.kt:118-189`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L118-L189)) is one proposal. It's `selectable` with
`Role.RadioButton` ([`PickSplitStep.kt:142-147`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L142-L147)), so the three cards behave like one
radio group, and the chosen one gets a 2 dp accent border. Inside: "Option 1", the amber
EDITED badge if edited, and "Quality 83%" in DM Mono, or "Quality —" while a side is
empty. A thin bar shows the same number but is hidden from TalkBack
([`PickSplitStep.kt:169-179`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L169-L179)), so it isn't read twice. **Win chances always add to
100** ([`PickSplitStep.kt:180-185`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L180-L185)): it rounds the Bibs' chance and gives the Non-bibs
the rest. The original rounded each side, which could show 51% and 50%.

**`SideColumn`** ([`PickSplitStep.kt:192-219`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L192-L219)) is one team: "BIBS · 52%", then a chip
per player. On the chosen card a chip moves its player ([`PickSplitStep.kt:212-213`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L212-L213));
on the others, `onMove` is `null`, so a tap falls through to the card and chooses it.
Every chip on every card has a lock.

### `ManualStep.kt`: "Make teams manually"

**What it's for:** building teams without the balancer. **`ManualStep`**
([`ManualStep.kt:28-39`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ManualStep.kt#L28-L39)) pins the meter line and Back / Continue in its footer.
**`ManualBoard`** ([`ManualStep.kt:42-72`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ManualStep.kt#L42-L72)) is the scrolling part: the heading, a hint,
and three columns, Pool, Bibs and Non-bibs ([`ManualStep.kt:54-70`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ManualStep.kt#L54-L70)). Each chip tap
moves its player one place round, and each chip tells TalkBack where it's going next,
via `nextPlace`. The meter line in the footer stays blank until both teams have someone.
Continue unlocks when the pool is empty and both teams have someone, whatever the meter
says.

### `ConfirmStep.kt`: "Confirm teams"

**What it's for:** a last look before the game would be saved. **`ConfirmStep`**
([`ConfirmStep.kt:54-85`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L54-L85)) pins Back and Confirm in its footer. If the teams differ by
more than one, the button turns amber and reads "Teams uneven (6 v 4) — sure?"
([`ConfirmStep.kt:69-81`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L69-L81)). It's a nudge, not a lock: tapping it still confirms.
**`ConfirmSummary`** ([`ConfirmStep.kt:88-117`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L88-L117)) is the scrolling part: the heading with
a GENERATED, EDITED or MANUAL badge ([`ConfirmStep.kt:102-103`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L102-L103)) and the two teams as
plain chips. **`Selection.label()`** ([`ConfirmStep.kt:189-193`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L189-L193)) picks the badge word.
Since milestone 3 the step also shows the date and Confirm saves the game; walkthrough 3
covers both.

### `TeamComponents.kt`: the shared pieces

**What it's for:** the building blocks more than one step uses.

- **Small helpers** ([`TeamComponents.kt:60-79`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L60-L79)): `DisabledAlpha` (Material's 38%),
  `MinTouchTarget` (48 dp), `percent()` (0.523 → 52), and `label()` for a `Side` and a
  `Place`.
- **`StepHeading`** ([`TeamComponents.kt:86-93`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L86-L93)): a step title in the serif face,
  marked as a heading so TalkBack users can jump between steps. **`ProblemText`**
  ([`TeamComponents.kt:99-108`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L99-L108)): an error-coloured message in a polite live region,
  read out as soon as it appears. **`HintText`** ([`TeamComponents.kt:111-114`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L111-L114)):
  smaller muted help text.
- **`Badge`** ([`TeamComponents.kt:120-135`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L120-L135)): an outlined pill with an uppercase word,
  for EDITED and the confirm badge.
- **`TeamColumn`** ([`TeamComponents.kt:148-162`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L148-L162)): a header then one chip per player,
  or "—" if empty. The chip is a **slot**, a composable passed in by the caller, so one
  column serves proposals, the manual board and the confirm step without a pile of
  flags.
- **`PlayerChip`** ([`TeamComponents.kt:190-234`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L190-L234)) is one player, and the most careful
  piece of accessibility work in the app. Every chip is one TalkBack stop named
  "Ottoline, Bibs", so the side is spoken even where colour or position would show it
  ([`TeamComponents.kt:213-218`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L213-L218)). The visible name is hidden from TalkBack so it isn't
  read twice ([`TeamComponents.kt:226-227`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L226-L227)). A tappable chip is a button at least 48 dp
  tall whose `onClickLabel` makes TalkBack say "double-tap to move to Non-bibs" instead
  of the generic "double-tap to activate".
- **`LockToggle`** ([`TeamComponents.kt:244-270`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L244-L270)) is the 🔓/🔒 beside a chip: a separate
  48 dp target, so moving and locking can't be confused. It's a toggle with
  `Role.Switch` named "Lock Ottoline to Bibs" ([`TeamComponents.kt:251`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L251)); TalkBack adds
  "on" or "off". **Surprise:** emoji can't be tinted, so a locked padlock also sits on
  an accent-tinted circle, and the state shows in colour as well as shape. A disabled
  lock is drawn at 38%.
- **`MeterLine`** ([`TeamComponents.kt:291-309`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L291-L309)): "Model: 64% Bibs · quality 83%",
  naming whichever side the model favours, worked out exactly as on the cards so the two
  never disagree by a point. It's a polite live region, so TalkBack reads the new
  numbers after each move. **Surprise:** it's always one `Text`, whose words are simply
  empty while there's no meter ([`TeamComponents.kt:295-308`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L295-L308)). The line keeps its
  height, so the pinned footer doesn't jump when the first move brings the numbers in,
  and because it's the same node throughout, TalkBack hears one live region change
  rather than a new node appearing. Divergence: quality is a percentage, matching the
  cards, not a decimal like "0.83".
- **`StepLayout(contentPadding, body, footer)`** ([`TeamComponents.kt:376-429`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L376-L429)) is the
  frame every step uses. The body is wrapped in a `Box` ([`TeamComponents.kt:411-417`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L411-L417))
  with `Modifier.weight(1f)`, which gives it whatever height the footer leaves, so the
  one `LazyColumn` or `verticalScroll` column inside it scrolls in that space.
  `clipToBounds()` stops scrolled content drawing over the footer. Body and footer are
  each marked `isTraversalGroup`, so TalkBack reads the whole body before moving to the
  footer. The divider and footer, a column of controls spaced 10 dp apart, are drawn
  only while `WindowInsets.isImeVisible` is false ([`TeamComponents.kt:418-427`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L418-L427)): when
  the keyboard opens they hide, and since the Scaffold's padding includes the keyboard,
  the body ends just above it. `isImeVisible` is still marked experimental, hence the
  `@OptIn`. *Android idea here: see [learning.md](../learning.md#modifierweight-and-scrolling-one-part-of-a-screen).*
- **`ButtonRow`**, **`PrimaryButton`**, **`GhostButton`** and **`BackContinueRow`**
  ([`TeamComponents.kt:315-374`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L315-L374)): buttons sharing the width equally, in the app's shape
  and 52 dp height, so long labels wrap instead of pushing the other button off screen.

### The resources

[`strings.xml`](../../app/src/main/res/values/strings.xml) holds every word, following the original's wording. The TalkBack-only
names (chip names, "move to", "Lock … to …") are strings too, so they can be translated
with everything else. The padlocks are strings so the emoji live in one place.

---

## 5. The engine's `quality()`

**What it's for:** a number for "how even is this split?" that reads the same for any
roster.

A raw draw chance means little on its own: 0.21 might be excellent for 14 uncertain
players and poor for 4 well-known ones. So **`quality(teamA, teamB)`**
([`ThurstoneMostellerFull.kt:170-177`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L170-L177)) divides the real draw chance by the best this
game could possibly have: the same players, team sizes and uncertainty, but with both
teams' total skill exactly level. The draw chance peaks at a level gap, so every real
split scores between 0 and 1, and "93%" means "93% of the best possible" whatever the
roster. The result is clamped to 0–1 to absorb rounding.

**Why it ranks splits exactly as the balancer does.** Every split of one roster has the
same players, so the same total uncertainty and player count, so the same denominator.
Dividing by a constant can't change the order. The balancer keeps maximising
`predictDraw`, and the screen shows `quality`, and the two can never disagree about
which split is best.

**Why not the original's number?** The original web version showed TrueSkill's "match
quality". TrueSkill is patented, and TeamBayes is built only from openskill's openly
licensed maths, so this measure is made from the model's own draw prediction instead
([`ThurstoneMostellerFull.kt:160-164`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L160-L164)). It plays the same role: 1.0 is a perfect match.

**`DrawTerms`** ([`ThurstoneMostellerFull.kt:187-202`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L187-L202)) is a small private class holding
everything a draw chance needs, worked out once: each team's summed mu, the draw margin
and the spread. Its **`drawChance(totalA, totalB)`**
([`ThurstoneMostellerFull.kt:199-201`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L199-L201)) is openskill's expression term for term, so
passing the real totals gives the same bits as before. **`drawTerms()`**
([`ThurstoneMostellerFull.kt:204-217`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L204-L217)) builds it, with the "player count enters twice"
quirk from milestone 1 intact. **`predictDraw`** ([`ThurstoneMostellerFull.kt:131-135`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L131-L135))
now calls `drawChance(muA, muB)`; **`quality`** calls it twice, once with the real
totals and once with team B's total moved to match team A's
([`ThurstoneMostellerFull.kt:175`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt#L175)). Because both halves go through the same expression,
a level split gives exactly 1.0. **`WengLin.quality`** ([`WengLin.kt:37-38`](../../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/WengLin.kt#L37-L38)) is the
front-door version that works out beta for you.

---

## 6. The tests

Run them with `./gradlew test` (laptop) and `./gradlew :app:connectedDebugAndroidTest`
(needs an emulator or a spare phone, never one holding real games; see walkthrough 3).
*Android idea here: see [learning.md](../learning.md#jvm-tests-vs-instrumented-tests).*

**`QualityTest.kt`** (engine, 7 tests): an even split scores exactly 1, including uneven
team sizes ([`QualityTest.kt:13-41`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L13-L41)); quality equals an oracle built only from
`predictDraw` ([`QualityTest.kt:21-34`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L21-L34)); worse splits score less
([`QualityTest.kt:43-53`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L43-L53)); swapping teams changes nothing ([`QualityTest.kt:55-60`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L55-L60));
every split of a 6-player roster ranks identically by `quality` and by `predictDraw`
([`QualityTest.kt:62-76`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L62-L76)); empty teams are refused.

**`TeamEditTest.kt`** (13 tests) pins down the pure rules ([`TeamEditTest.kt:14-143`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/TeamEditTest.kt#L14-L143)):
swapping on a proposal, the manual cycle, that `move` never changes its input and
returns the same object for an unknown id, `nextPlace`, `isComplete`, the size warning
only above a gap of one, both lock rules, and the meter on empty, level and lopsided
teams.

**`MatchDayViewModelTest.kt`** (92 tests since milestone 3) drives whole match days on
the laptop, on in-memory fake repositories. **The setup**
([`MatchDayViewModelTest.kt:60-73`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L60-L73)) gives the ViewModel an unconfined "main"
dispatcher, so its coroutine starts at once, and a standard "balancer" dispatcher that
runs nothing until the test calls `advanceUntilIdle()`. That freezes the ViewModel
mid-generate, where a test can poke at it. The tests here cover the roster (order,
search, adding, duplicates, the 60-character cap), generating, editing, the discard
dialog, locks, the manual board, confirm and back; walkthrough 3 covers the ones for
saving, recording and resuming.

**The race tests** check what happens when someone taps while the balancer runs. The
rule they enforce: a tap that changes *who's playing* cancels the run, because its
answer would be for the wrong people; a tap that changes *the teams on show* is ignored,
because the new proposals are about to replace them.

- Ticking someone mid-run cancels it, and no proposals ever arrive
  ([`MatchDayViewModelTest.kt:402-412`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L402-L412)).
- Adding a player mid-run cancels it, the stale answer never lands, and the next run
  includes the newcomer ([`MatchDayViewModelTest.kt:793-808`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L793-L808)).
- Continue while re-generating is ignored ([`MatchDayViewModelTest.kt:761-775`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L761-L775)), and so
  are moving, choosing a card and locking ([`MatchDayViewModelTest.kt:777-791`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L777-L791)).

**Why moving a player clears their lock** ([`MatchDayViewModelTest.kt:547-558`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L547-L558)). The
original web version left locks alone when you edited. But a lock means "keep them on
this side". If Ottoline is locked to the Bibs and you move her to the Non-bibs, the
original kept the Bibs lock, so the next re-generate would put her straight back, and
the chip in the Non-bibs column would show a lock that doesn't match. Clearing it keeps
what the screen shows and what re-generate does in step. The test locks two players,
moves one, and checks only the other's lock survives. Related tests check that unticking
drops a lock ([`MatchDayViewModelTest.kt:535-545`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L535-L545)), that a stale lock can't make
Generate fail after ticks change ([`MatchDayViewModelTest.kt:520-533`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L520-L533)), and that locks
which can't be met show the engine's reason and keep the old proposals
([`MatchDayViewModelTest.kt:560-572`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L560-L572)).

**`MatchDayScreenTest.kt`** (7 tests, on a phone) drives the real screens by visible
text and by the names TalkBack reads ([`MatchDayScreenTest.kt:36-42`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L36-L42)). It finds a chip
by its TalkBack name plus its button role ([`MatchDayScreenTest.kt:59-65`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L59-L65)), which also
proves those names exist. Because the roster list is lazy, a row below the fold doesn't
exist until it's scrolled to, so `tickFour` scrolls the list itself, found by
`ROSTER_LIST_TAG`, with `performScrollToNode`, then taps each name and checks it's
ticked straight away, while the row is certainly still on screen
([`MatchDayScreenTest.kt:77-88`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L77-L88)). Pinned buttons are on screen whenever the keyboard is
closed, so `tap` just clicks them ([`MatchDayScreenTest.kt:90-96`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L90-L96)). It covers: typing
in the search field shows only the matching player ([`MatchDayScreenTest.kt:119-129`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L119-L129));
generate then move a chip to get EDITED and the meter
([`MatchDayScreenTest.kt:131-135`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L131-L135)); the discard dialog's Cancel and Discard
([`MatchDayScreenTest.kt:137-150`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L137-L150)); the manual board through to confirm
([`MatchDayScreenTest.kt:152-179`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L152-L179)); the amber uneven button still confirming
([`MatchDayScreenTest.kt:181-196`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L181-L196)); and, since milestone 3, placing a new player and a
whole match day through to the rating movement. It waits up to five seconds for results,
because the balancer runs on a real thread the test rule doesn't track.

**`BalancerTimingTest.kt`** (2 tests, on a phone) runs the balancer twice to warm up,
then seven measured times, and logs the median and maximum to logcat
([`BalancerTimingTest.kt:48-66`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/BalancerTimingTest.kt#L48-L66)). 18 players must have a median under 200 ms
([`BalancerTimingTest.kt:28-33`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/BalancerTimingTest.kt#L28-L33)); 20 players is logged only
([`BalancerTimingTest.kt:35-39`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/BalancerTimingTest.kt#L35-L39)). **Why skip the limit on emulators?** An emulator's
speed depends on the laptop running it. `isEmulator()` ([`BalancerTimingTest.kt:41-46`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/BalancerTimingTest.kt#L41-L46))
spots one from its build strings, and the test measures and logs *before* the skip, so
an emulator still records its numbers.

---

## 7. Known gaps

These are deliberate for now.

- **Storage arrived in milestone 3.** Players, games, recording the result and surviving
  process death are covered, with their own gaps, in [walkthrough 3](03-saving-games.md#7-known-gaps).
- **No release shrinking.** R8, which strips unused code from release builds, is
  deferred to the release-signing work so both can be tested together ([decisions.md](../decisions.md)).
- **Timing varies.** On a Pixel 9 Pro, 18 players take a median of about 65 ms. 20
  players took between about 180 and 280 ms across runs: a phone's speed shifts with its
  temperature, power state and what else is running. That's fine on a background thread
  behind "Generating…", but there's room to speed it up. The timing test asks for 5
  splits while the app asks for 3; the cost is in trying every split, so that barely
  matters. With more than 100,000 possible splits (over 20 players, unless locks cut the
  count) the balancer samples, and the step says so.
- **The guard doesn't check the test APK** ([`build.gradle.kts:166-167`](../../app/build.gradle.kts#L166-L167)). It never
  ships to users, but a test library could add a permission there unnoticed.

---

## 8. Questions you might ask

**Why copy the original web version so closely?** It's what the group already knows on
match day, and its flow was tested on real nights. Copying it means the app earns trust
by feeling familiar. Where it differs, a code comment says why, so a divergence is a
decision, not drift.

**What does "Quality 83%" mean?** This split's chance of a draw is 83% of the best
possible for these players. It's the same ranking the balancer uses, rescaled so 100%
always means "as even as it gets" (section 5).

**Why show win chances at all?** The owner chose to, though they aren't well calibrated
yet: the ratings start as guesses from a ranking, and the model has known biases, such
as treating team strength as a sum ([model.md](../model.md)). They're shown as commentary and never
gate anything.

**Why "Bibs" and "Non-bibs" instead of Team A and B?** That's how the teams are told
apart on the pitch, and what the original called them. In the code, team A is always the
Bibs.

**Why is Generate enabled with only two or three players?** So it can explain itself. A
greyed-out button gives no reason; tapping it gives the engine's own sentence, which
also suggests making teams by hand.

**What if I tap things while it's generating?** Changing who's playing cancels the run.
Changing the teams on show is ignored until the new proposals land. The buttons and
cards also grey out, so this is mostly belt and braces (section 6).

**Why pass one `MatchDayActions` interface instead of lambdas?** Sixteen actions as
separate lambdas would make every step's signature a wall of parameters. One interface
keeps them short, and previews pass `NoActions`.

**Why are the buttons pinned to the bottom?** On a phone, 14 players pushed Generate off
the bottom of the screen, so you had to scroll past everyone to reach it. The original
keeps its actions visible below a scrolling list, so the app does the same. On "Pick a
split" it goes slightly further and pins the meter line and Re-generate too, so you can
watch the numbers while you move chips. The one exception is typing: while the keyboard
is open the footer hides, so the field you're typing in keeps its room.

**Why bundle the fonts instead of downloading them?** Downloadable fonts arrive through
a provider app over the network, which would break the offline promise by proxy.
Bundling costs about 480 KB and keeps the app self-contained.
