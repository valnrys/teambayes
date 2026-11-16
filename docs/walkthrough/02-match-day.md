# Walkthrough 2: match day

This is a guided tour of the `:app` module, the Android app, one function at a time.
It's written for someone who knows engineering but is new to Kotlin and Android. Skim
section 1 for the shape of it, then dive into whichever section you need. Section 8
answers the questions that come up most.

It describes the code **as it is** at the end of milestone 2. Where the code and the
docs disagree, this follows the code. Every `File.kt:line` link jumps to the line. The
Android ideas each get a short entry in [learning.md](../learning.md), and the notes below point to
them.

---

## 1. The app in one page

**What it does.** It runs match day: pick who's playing, get three proposed splits into
Bibs and Non-bibs, adjust them by hand if you like, and confirm. It follows the original
web version's match-day flow closely, and every place it deliberately differs says why
in a code comment. There's no storage yet, so the players are 14 made-up names and
Confirm doesn't save anything. The app never touches the network.

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
  Confirm ✓ → "Saving games arrives in the next milestone" → back to ROSTER
```

Four ideas carry the whole thing:

- **One state value, one step at a time.** Everything on screen lives in one immutable
  `MatchDayUiState`, inside one `StateFlow`. Its `step` field says which screen shows.
  Every tap is a function on the ViewModel that builds the next state.
- **The fiddly rules are pure functions.** Who moves where, when teams are ready, and
  when to warn all live in `TeamEdit.kt`, which knows nothing about Android. The
  ViewModel calls in; the screen only draws.
- **The model comments; it never decides.** Quality, win chances and the uneven-teams
  warning are shown, but none of them stops you confirming.
- **The maths never runs on the main thread.** The main thread draws the screen, so the
  balancer runs on a background thread, and taps that would clash with it are either
  ignored or cancel it (section 6).

**How we know it's right.** On the laptop, 33 ViewModel tests drive whole match days,
including the races between taps and a running balancer, and 13 tests pin down the
editing rules. The engine gained 7 tests for `quality()`, including one that checks it
ranks splits exactly as `predictDraw` does. On a phone, 4 UI tests tap through the real
screens the way a person would. A timing test measures the balancer: on a Pixel 9 Pro,
18 players take a median of about 66 ms and 20 players about 270 ms. The permission
guard (section 2) fails the build on any unlisted permission, and its **mutation
checks** showed that adding INTERNET through `<uses-permission-sdk-23>`, or adding a
harmless VIBRATE, each failed the build. As with the engine, **every change was reviewed
adversarially by two independent AI reviewers before it was pushed** ([decisions.md](../decisions.md)).

---

## 2. The build and the guard

**What it's for:** turning Kotlin and XML into an installable app, with the same library
versions for everyone, and refusing to build an app that could go online. The only milestone 2
addition to the build itself is the bundled fonts.

### Modules, plugins and versions

[`settings.gradle.kts:19-20`](../../settings.gradle.kts#L19-L20) lists `:engine` (the maths from milestone 1) and `:app`.
The app depends on the engine ([`build.gradle.kts:38`](../../app/build.gradle.kts#L38)); the engine knows nothing about
Android. `FAIL_ON_PROJECT_REPOS` ([`settings.gradle.kts:10`](../../settings.gradle.kts#L10)) stops a module adding its
own download sources. *Android idea here: see [learning.md](../learning.md#an-app-module-and-its-build-file).*

Since Android Gradle Plugin (AGP) 9.0, Kotlin support is built into AGP, so the app
applies only AGP and the Compose compiler ([`build.gradle.kts:6-11`](../../app/build.gradle.kts#L6-L11)). The Compose
compiler ships with Kotlin, so its version is tied to Kotlin's
([`libs.versions.toml:50-51`](../../gradle/libs.versions.toml#L50-L51)). Every version lives in [`libs.versions.toml`](../../gradle/libs.versions.toml). Compose's
many libraries take their versions from one **BOM** ("bill of materials": a list of
versions known to work together, [`libs.versions.toml:9`](../../gradle/libs.versions.toml#L9)). Espresso is pinned by hand
because Compose's test library pulls in a version that crashes on newer Android
([`libs.versions.toml:22-23`](../../gradle/libs.versions.toml#L22-L23)).

[`build.gradle.kts:15-20`](../../app/build.gradle.kts#L15-L20) sets the SDK levels: `compileSdk` 37 (which Android APIs the
code can see), `targetSdk` 37 (which Android behaviour rules the app signs up for) and
`minSdk` 26 (the oldest Android it installs on, 8.0).

### The permission guard

**What it's for:** making it impossible to build an app that asks for INTERNET, or any
other permission nobody has looked at.

An app lists the permissions it wants in its manifest. Libraries have manifests too, and
the build **merges** them, so a library can add INTERNET without anyone editing our
files. **`VerifyPermissions`** ([`build.gradle.kts:82-138`](../../app/build.gradle.kts#L82-L138)) reads the merged result.
`verify()` ([`build.gradle.kts:97-114`](../../app/build.gradle.kts#L97-L114)) fails with a message naming each unexpected
permission, or copies the manifest through unchanged ([`build.gradle.kts:113`](../../app/build.gradle.kts#L113)).
`readPermissions()` ([`build.gradle.kts:120-137`](../../app/build.gradle.kts#L120-L137)) collects both `<uses-permission>` and
`<uses-permission-sdk-23>`, turns a nameless request into a failure
([`build.gradle.kts:134-135`](../../app/build.gradle.kts#L134-L135)), and refuses DOCTYPEs in the XML
([`build.gradle.kts:123-125`](../../app/build.gradle.kts#L123-L125)). `Permission` ([`build.gradle.kts:117`](../../app/build.gradle.kts#L117)) is a name plus
the tag it came from.

- **Why an allowlist?** A blocklist only stops what you thought of. The list has one
  entry ([`build.gradle.kts:147`](../../app/build.gradle.kts#L147)), `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which
  AndroidX adds to keep the app's own internal broadcasts private. It grants no network
  access.
- **Why inside the manifest pipeline?** The `onVariants` block
  ([`build.gradle.kts:142-153`](../../app/build.gradle.kts#L142-L153)) registers `verifyAllowedPermissionsDebug` and
  `verifyAllowedPermissionsRelease` as a **transform** of the merged manifest: it goes
  in, gets checked, and comes out. Building an APK, a Play bundle or installing all need
  that manifest, so none can skip the guard. A guard attached only to `./gradlew check`
  would never stop `assembleRelease`.
- **Why not `tools:node="remove"`?** That deletes a library's request during the merge,
  so the build would pass while hiding that a library wanted the network
  ([`AndroidManifest.xml:5-12`](../../app/src/main/AndroidManifest.xml#L5-L12)).

### The manifest choices

*Android idea here: see [learning.md](../learning.md#the-manifest-and-permissions).*

- **Backup off** ([`AndroidManifest.xml:21-23`](../../app/src/main/AndroidManifest.xml#L21-L23)): no player data goes to Google's cloud
  backup or phone-to-phone transfer. The app will offer its own export later.
- **Cleartext off** ([`AndroidManifest.xml:24`](../../app/src/main/AndroidManifest.xml#L24)): unencrypted HTTP is forbidden, a
  second lock on a door that has no key anyway.
- **The launcher entry** ([`AndroidManifest.xml:31-38`](../../app/src/main/AndroidManifest.xml#L31-L38)): `exported="true"` lets the
  launcher, which is another app, start us.
- **The emoji2 initializer, removed** ([`AndroidManifest.xml:47-56`](../../app/src/main/AndroidManifest.xml#L47-L56)): it can ask a
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

The window theme in `res/values/themes.xml` ([`themes.xml:8-10`](../../app/src/main/res/values/themes.xml#L8-L10)) and its `values-night`
twin ([`themes.xml:3-5`](../../app/src/main/res/values-night/themes.xml#L3-L5)) set the window background to the Compose background, so
there's no white flash before Compose draws.

---

## 4. File by file

Reading order: the entry point, the sample data, the pure rules, the ViewModel, then the
screens from top to bottom.

### `MainActivity.kt`: the front door

**What it's for:** the one thing Android launches. **`MainActivity`**
([`MainActivity.kt:13`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L13)) is the app's only Activity. `onCreate`
([`MainActivity.kt:15-24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L15-L24)) lets the app draw behind the system bars with
`enableEdgeToEdge()` ([`MainActivity.kt:18`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L18)), then hands the window to Compose with
`setContent` ([`MainActivity.kt:19-23`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L19-L23)), wrapping `MatchDayRoute` in the theme.
*Android idea here: see [learning.md](../learning.md#activity-and-setcontent).*

### `SamplePlayers.kt`: made-up players

**What it's for:** a roster worth searching before storage exists.

- **`Player`** ([`SamplePlayers.kt:15`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L15)): an id, a display name and the engine's
  `Rating`. The id is never shown, but it matters: the engine sorts by id, so it decides
  how ties break.
- **`SamplePlayers`** ([`SamplePlayers.kt:24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L24)) holds 14 invented first names
  ([`SamplePlayers.kt:26-30`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L26-L30)) and **`all`** ([`SamplePlayers.kt:33`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L33)), the 14 players
  the app starts with.
- **`make(count)`** ([`SamplePlayers.kt:42-49`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L42-L49)) builds `count` players, best first. The
  means come from the engine's `Prior.fromRankOrder`; every sigma is `SIGMA0`. Past 14,
  names repeat with a number ("Ottoline 2"), which the timing test uses for 18 and 20
  players.
- **`idFor(number)`** ([`SamplePlayers.kt:55`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/SamplePlayers.kt#L55)) gives `p01`, `p02` and so on. It uses
  `padStart`, not `String.format`, because `format` can use the phone's language for the
  digits.

### `TeamEdit.kt`: the hand-editing rules

**What it's for:** every fiddly rule about moving players, as pure functions: values in,
new values out, nothing changed in place, no Android. That makes it the easiest code in
the app to test. Team A is always the Bibs, team B the Non-bibs.

- **`Selection`** ([`TeamEdit.kt:23-32`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L23-L32)): how a set of teams was made, `GENERATED`,
  `EDITED` or `MANUAL`. The confirm step shows it as a badge, and once games are saved
  it will be stored with each one, so we can ask later whether the balancer's teams give
  closer games than hand-picked ones.
- **`WorkingCopy`** ([`TeamEdit.kt:44-53`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L44-L53)): teams being edited by hand, kept separate
  so the balancer's proposals stay as they were. It holds a mode, both teams and a pool.
  Its `init` refuses `GENERATED`, since a working copy is by definition edited or
  manual.
- **`fromSplit`** ([`TeamEdit.kt:56-57`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L56-L57)) starts editing a proposal (empty pool).
  **`fromRoster`** ([`TeamEdit.kt:60-61`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L60-L61)) starts the manual board with everyone in the
  pool, in tick order.
- **`move(playerId)`** ([`TeamEdit.kt:72-80`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L72-L80)) is the only way teams change by hand. On
  a proposal a player swaps sides; on the manual board they step round Pool → Bibs →
  Non-bibs → Pool. They join the end of their new list. An unknown id gives back the
  very same object, which the ViewModel uses to spot a move that did nothing. *Kotlin
  idea: `when (playerId) { in teamA -> ... }` branches on list membership.*
- **`nextPlace`** ([`TeamEdit.kt:86-91`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L86-L91)) says where a tap would send someone, for
  TalkBack's "move to Non-bibs". **`Place`** ([`TeamEdit.kt:94`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L94)) is the three board
  positions.
- **`isComplete()`** ([`TeamEdit.kt:97`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L97)): nobody in the pool and someone on each team.
- **`SizeGap`** and **`sizeWarning`** ([`TeamEdit.kt:105-109`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L105-L109)): returns "6 v 4" when
  the teams differ by more than one, otherwise `null`. It's advisory: it turns the
  confirm button amber but never blocks it.
- **`lockAfterTap`** ([`TeamEdit.kt:119-123`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L119-L123)): tapping a lock cycles unlocked → locked
  to this side → unlocked. If the player was locked to the *other* side (from another
  card), the tap moves the lock here.
- **`Meter`** ([`TeamEdit.kt:133`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L133)) is the model's view of one pair of teams: `quality`
  and the Bibs' win chance. **`meterFor`** ([`TeamEdit.kt:143-148`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamEdit.kt#L143-L148)) works it out with
  the engine, or returns `null` while a side is empty. It's a handful of sums, cheap
  enough to run straight after every tap on the main thread.

### `MatchDayViewModel.kt`: the step machine

**What it's for:** holding the whole match day as one state, deciding what every tap
does, and running the balancer without freezing anything.

#### Constants and small types

- **`OPTIONS = 3`** ([`MatchDayViewModel.kt:23-24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L23-L24)): how many proposals to show, as the
  original did. **`MAX_NAME_LENGTH = 60`** ([`MatchDayViewModel.kt:26-27`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L26-L27)) caps a new
  name. **`MIN_PLAYERS_MANUAL = 2`** ([`MatchDayViewModel.kt:29-30`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L29-L30)): one a side.
- **`Step`** ([`MatchDayViewModel.kt:38-50`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L38-L50)): `ROSTER`, `GENERATE`, `MANUAL`,
  `CONFIRM`. The original went on to record the result; those steps arrive with storage.
- **`SplitOption`** ([`MatchDayViewModel.kt:59`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L59)): one proposal, its teams in name order
  plus its `Meter`. **`ChosenTeams`** ([`MatchDayViewModel.kt:62`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L62)): the teams on the
  confirm step and how they were made.
- **`DiscardAction`** ([`MatchDayViewModel.kt:65-71`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L65-L71)): what "Discard" in the dialog
  will do, either show another proposal or re-generate. The dialog remembers the action,
  so one dialog serves both.

#### `MatchDayUiState`: everything on screen

**`MatchDayUiState`** ([`MatchDayViewModel.kt:99-117`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L99-L117)) is deliberately flat, one field
per thing the organiser can see or change, in the same shape as the original web
version's screen state. Fields for later steps sit unused until then. The ones worth
knowing: `selectedIds` keeps tick order; `generating` is true while the balancer runs;
`problem` and `notice` are string resource ids; `workingCopy` is `null` until someone
moves a player; `liveMeter` is the meter for the working copy; `discardPrompt` is set
while the dialog is open.

The derived values:

- **`namesById`**, **`ratingsById`**, **`visiblePlayers`**
  ([`MatchDayViewModel.kt:119-127`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L119-L127)) are worked out on first use with `by lazy`, so a
  state that never needs them never builds them. `visiblePlayers` filters by the search
  text, ignoring case.
- **`canMakeTeams`** ([`MatchDayViewModel.kt:135`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L135)): at least two ticked. **Both**
  roster buttons unlock at two, as in the original. The balancer needs four, so with two
  or three, Generate shows the engine's own reason ("needs at least 4 players. With
  fewer, make teams manually") rather than sitting greyed out with no explanation.
- **`canContinue`** ([`MatchDayViewModel.kt:142-145`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L142-L145)): on the generate or manual step,
  not generating, and either the working copy is complete or there's a proposal to take.

#### The class

**`MatchDayViewModel`** ([`MatchDayViewModel.kt:160-163`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L160-L163)) takes the players (the sample
players by default) and where to run the balancer (`Dispatchers.Default` by default;
tests swap in one they control). It implements `MatchDayActions`, the interface the
screen calls. The state starts with the roster sorted A to Z
([`MatchDayViewModel.kt:165`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L165)); `state` ([`MatchDayViewModel.kt:168`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L168)) is the read-only
view. `generateJob` ([`MatchDayViewModel.kt:171`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L171)) remembers the run in progress so it
can be cancelled. *Android idea here: see [learning.md](../learning.md#viewmodel-and-stateflow) and [learning.md](../learning.md#a-step-machine-in-one-stateflow).*

#### Roster actions

- **`toggleSelected`** ([`MatchDayViewModel.kt:180-189`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L180-L189)) ticks or unticks. It cancels
  any balancer run, because the roster just changed. Unticking also drops the player's
  lock, so it can't quietly return if they're ticked again.
- **`setSearch`** ([`MatchDayViewModel.kt:192`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L192)) and **`setNewPlayerName`**
  ([`MatchDayViewModel.kt:195-196`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L195-L196)) store the typed text; the name is cut to 60
  characters and any duplicate message clears.
- **`addPlayer`** ([`MatchDayViewModel.kt:207-232`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L207-L232)) trims the name, ignores a blank
  one, and refuses one already on the roster, ignoring case, so two "Sam"s can't be
  confused on the pitch. A new player starts as an average unknown (`MU0` ± `SIGMA0`),
  is ticked, and lives only in memory. **Surprise:** the id is the first *unused* one
  after the roster's size ([`MatchDayViewModel.kt:216-221`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L216-L221)), not simply size + 1, so an
  id can never repeat once players can be removed. It also cancels a run in progress,
  which wouldn't include the newcomer.
- **`generate`** ([`MatchDayViewModel.kt:235`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L235)) runs the balancer.
  **`makeTeamsManually`** ([`MatchDayViewModel.kt:238-253`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L238-L253)) opens the board with
  everyone in the pool and drops any old proposals.

#### Pick-a-split actions

- **`selectSplit(index)`** ([`MatchDayViewModel.kt:258-266`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L258-L266)) chooses a card. With hand
  edits it asks first, by setting `discardPrompt`. It ignores taps while generating.
- **`regenerate`** ([`MatchDayViewModel.kt:269-277`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L269-L277)) runs the balancer again with the
  current locks, asking first if there are edits.
- **`confirmDiscard`** ([`MatchDayViewModel.kt:280-287`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L280-L287)) drops the edits and does the
  remembered action. **`dismissDiscard`** ([`MatchDayViewModel.kt:290`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L290)) just closes the
  dialog.
- **`movePlayer`** ([`MatchDayViewModel.kt:307-322`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L307-L322)) moves one player. The first move
  on a proposal turns it into an `EDITED` working copy, and the meter is recomputed
  straight away. A move that changes nothing is ignored, so there's no EDITED badge for
  nothing ([`MatchDayViewModel.kt:315`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L315)). **Moving also clears that player's lock**
  ([`MatchDayViewModel.kt:319`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L319)). That's a deliberate divergence from the original,
  explained in the KDoc ([`MatchDayViewModel.kt:299-303`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L299-L303)): a lock means "keep them on
  this side", so after a move it would show a lock that no longer matches where they
  are.
- **`toggleLock`** ([`MatchDayViewModel.kt:325-327`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L325-L327)) applies `lockAfterTap`, ignored
  while generating.
- **`continueToConfirm`** ([`MatchDayViewModel.kt:330-342`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L330-L342)) takes the working copy if
  there is one, otherwise the chosen proposal, to the confirm step. It closes any open
  dialog.

#### Confirm and back

- **`confirm`** ([`MatchDayViewModel.kt:353-365`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L353-L365)) doesn't save yet. It returns to the
  roster with a notice ("Saving games arrives in the next milestone"), keeps the ticks
  so you don't re-tick everyone, and clears locks, since the roster has no lock controls
  and a hidden lock would act unseen. **`noticeShown`** ([`MatchDayViewModel.kt:368`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L368))
  clears the notice once the snackbar has shown it.
- **`back`** ([`MatchDayViewModel.kt:377-398`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L377-L398)) goes one step back. From the generate or
  manual step it drops your edits, as the original does, and also the proposals and locks, which the
  roster step can neither show nor change. From confirm it
  keeps them and returns to the board you came from, manual or generate.

#### The balancer

**`runBalancer`** ([`MatchDayViewModel.kt:409-447`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L409-L447)) builds the roster from the ticked
players and filters the locks to people who are playing ([`MatchDayViewModel.kt:416`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L416)),
a second line of defence. It cancels any earlier run, sets `generating`, and launches a
coroutine that runs `Balancer.balance(..., k = 3)` on the background dispatcher
([`MatchDayViewModel.kt:422-423`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L422-L423)). *Android idea here: see [learning.md](../learning.md#viewmodelscope-and-dispatchersdefault).*

When it finishes, an `Impossible` sets `problem` and leaves everything else alone, so
the old proposals stay. A `Splits` result moves to the generate step with fresh
proposals and no edits. **Surprise:** each team is re-sorted by name
([`MatchDayViewModel.kt:431-434`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L431-L434)). The engine lists players by id, which for the sample
players is best first, and name order gives nothing away on a shared screen.

**`cancelGenerating`** ([`MatchDayViewModel.kt:449-453`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L449-L453)) cancels the job and clears
`generating`. The balancer's maths never checks for cancellation, so the CPU work runs
on, but a cancelled coroutine can't publish its result: when `withContext` returns to
it, it throws instead.

**`Reason.message()`** ([`MatchDayViewModel.kt:457-465`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt#L457-L465)) maps each engine refusal to a
sentence. A duplicate id or a lock on an unknown player can't come from these screens,
so they share "Something went wrong".

### `MatchDayScreen.kt`: the frame

**What it's for:** picking which step to draw, and owning the things that sit on top of
every step.

- **`MatchDayActions`** ([`MatchDayScreen.kt:33-50`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L33-L50)) is an interface with one function
  per tap. Passing one interface, rather than a dozen lambdas, keeps each step's
  signature short. **`NoActions`** ([`MatchDayScreen.kt:53-70`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L53-L70)) does nothing, for
  previews.
- **`MatchDayRoute`** ([`MatchDayScreen.kt:78-83`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L78-L83)) is the only function that knows
  about the ViewModel. It collects the state, stopping while the app is in the
  background, and passes the state and the ViewModel (as `MatchDayActions`) down.
  *Android idea here: see [learning.md](../learning.md#state-hoisting).*
- **`MatchDayScreen`** ([`MatchDayScreen.kt:92-126`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L92-L126)) is stateless. `BackHandler`
  ([`MatchDayScreen.kt:96`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L96)) makes the phone's back gesture call `back()` on every step
  except the roster, where back leaves the app as usual. A `LaunchedEffect` keyed on the
  notice ([`MatchDayScreen.kt:98-106`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L98-L106)) shows each new notice in a snackbar, then
  reports it shown. The `Scaffold` measures `safeDrawing` insets (status bar, navigation
  bar, camera cutout and keyboard), and the `when` picks the step
  ([`MatchDayScreen.kt:110-121`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L110-L121)). The dialog sits on top when `discardPrompt` is set
  ([`MatchDayScreen.kt:123-125`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L123-L125)). *Android idea here: see [learning.md](../learning.md#composable-functions-and-recomposition).*
- **`DiscardDialog`** ([`MatchDayScreen.kt:134-148`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt#L134-L148)) is a standard Material
  `AlertDialog` with Cancel and a red Discard. Being standard, TalkBack announces it,
  and back or tapping outside counts as Cancel.

### `RosterStep.kt`: "Who's playing?"

**What it's for:** ticking players, searching, adding someone new, then choosing how to
make teams.

**`RosterStep`** ([`RosterStep.kt:49-106`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L49-L106)) is one `LazyColumn`, a list that only builds
the rows on screen, with the header, fields and buttons as single items so the whole
step scrolls as one page.

- **The search field** ([`RosterStep.kt:58-67`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L58-L67)) uses a real floating label rather than
  a placeholder, so it stays visible while you type and TalkBack reads it as the field's
  name.
- **The rows** use `key = { it.id }` ([`RosterStep.kt:69-77`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L69-L77)), so a tick stays on the
  right person while the search narrows the list. With no matches, a hint suggests
  adding someone ([`RosterStep.kt:78-80`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L78-L80)).
- **The buttons** ([`RosterStep.kt:83-102`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L83-L102)): "Make teams manually" and "Generate teams
  (n) →", which reads "Generating…" while it runs. Both need two ticks.

**`RosterHeader`** ([`RosterStep.kt:109-134`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L109-L134)) is the title plus an "n selected" pill.
The pill is a polite **live region** ([`RosterStep.kt:129-130`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L129-L130)): TalkBack reads the new
count after each tick, once it has finished reading the row.

**`PlayerRow`** ([`RosterStep.kt:143-155`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L143-L155)) makes the whole 52 dp row one target.
`toggleable(role = Role.Checkbox)` ([`RosterStep.kt:149`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L149)) turns the row into a single
checkbox for TalkBack, and the `Checkbox` inside only draws, so one tap is never handled
twice. **`RosterRowHeight`** ([`RosterStep.kt:39`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L39)) is 52 dp, a little taller than the
48 dp minimum, as in the original. *Android idea here: see [learning.md](../learning.md#semantics-for-talkback).*

**`NewPlayerField`** ([`RosterStep.kt:162-192`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L162-L192)) is a text field with an Add button. The
keyboard capitalises words and its Done key adds ([`RosterStep.kt:177-181`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L177-L181)). A
duplicate marks the field as an error with "Player 'Sam' already exists" underneath
([`RosterStep.kt:175-176`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt#L175-L176)), which TalkBack reads with the field. Add is disabled while
the field is blank.

### `PickSplitStep.kt`: "Pick a split"

**What it's for:** showing the three proposals, letting you choose one, adjust it, lock
players and re-generate.

**`PickSplitStep`** ([`PickSplitStep.kt:44-94`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L44-L94)) shows the heading, a note when the
search was sampled ([`PickSplitStep.kt:55`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L55)), a hint, any problem, then the cards. The
cards sit in a column marked `selectableGroup()` ([`PickSplitStep.kt:60-61`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L60-L61)), so
TalkBack treats them as one set of choices ("1 of 3"). Only the chosen card shows hand
edits; the others stay as proposed ([`PickSplitStep.kt:64-65`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L64-L65)). While the balancer
runs, nothing on the cards responds ([`PickSplitStep.kt:76-78`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L76-L78)). Below the cards: the
meter line while editing, "↻ Re-generate", and Back / Continue. *Android idea here: see
[learning.md](../learning.md#selectablegroup-and-radio-semantics).*

**`SplitCard`** ([`PickSplitStep.kt:103-174`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L103-L174)) is one proposal. It's `selectable` with
`Role.RadioButton` ([`PickSplitStep.kt:127-132`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L127-L132)), so the three cards behave like one
radio group, and the chosen one gets a 2 dp accent border. Inside: "Option 1", the amber
EDITED badge if edited, and "Quality 83%" in DM Mono, or "Quality —" while a side is
empty. A thin bar shows the same number but is hidden from TalkBack
([`PickSplitStep.kt:154-164`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L154-L164)), so it isn't read twice. **Win chances always add to
100** ([`PickSplitStep.kt:165-170`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L165-L170)): it rounds the Bibs' chance and gives the Non-bibs
the rest. The original rounded each side, which could show 51% and 50%.

**`SideColumn`** ([`PickSplitStep.kt:177-204`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L177-L204)) is one team: "BIBS · 52%", then a chip
per player. On the chosen card a chip moves its player ([`PickSplitStep.kt:197-198`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt#L197-L198));
on the others, `onMove` is `null`, so a tap falls through to the card and chooses it.
Every chip on every card has a lock.

### `ManualStep.kt`: "Make teams manually"

**What it's for:** building teams without the balancer. **`ManualStep`**
([`ManualStep.kt:25-59`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ManualStep.kt#L25-L59)) shows three columns, Pool, Bibs and Non-bibs
([`ManualStep.kt:39-55`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ManualStep.kt#L39-L55)), and each chip tap moves its player one place round. Each chip
tells TalkBack where it's going next, via `nextPlace`. The meter line appears once both
teams have someone. Continue unlocks when the pool is empty and both teams have someone,
whatever the meter says.

### `ConfirmStep.kt`: "Confirm teams"

**What it's for:** a last look before the game would be saved. **`ConfirmStep`**
([`ConfirmStep.kt:31-75`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L31-L75)) shows the heading with a GENERATED, EDITED or MANUAL badge
([`ConfirmStep.kt:47-48`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L47-L48)), the two teams as plain chips, and Back plus Confirm. If the
teams differ by more than one, the button turns amber and reads "Teams uneven (6 v 4) —
sure?" ([`ConfirmStep.kt:60-73`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L60-L73)). It's a nudge, not a lock: tapping it still confirms.
**`Selection.label()`** ([`ConfirmStep.kt:78-82`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt#L78-L82)) picks the badge word. There's no date
field yet, a documented divergence, because nothing is saved and a date would be thrown
away.

### `TeamComponents.kt`: the shared pieces

**What it's for:** the building blocks more than one step uses.

- **Small helpers** ([`TeamComponents.kt:51-70`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L51-L70)): `DisabledAlpha` (Material's 38%),
  `MinTouchTarget` (48 dp), `percent()` (0.523 → 52), and `label()` for a `Side` and a
  `Place`.
- **`StepHeading`** ([`TeamComponents.kt:77-84`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L77-L84)): a step title in the serif face,
  marked as a heading so TalkBack users can jump between steps. **`ProblemText`**
  ([`TeamComponents.kt:90-99`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L90-L99)): an error-coloured message in a polite live region, read
  out as soon as it appears. **`HintText`** ([`TeamComponents.kt:102-105`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L102-L105)): smaller
  muted help text.
- **`Badge`** ([`TeamComponents.kt:111-126`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L111-L126)): an outlined pill with an uppercase word,
  for EDITED and the confirm badge.
- **`TeamColumn`** ([`TeamComponents.kt:139-153`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L139-L153)): a header then one chip per player,
  or "—" if empty. The chip is a **slot**, a composable passed in by the caller, so one
  column serves proposals, the manual board and the confirm step without a pile of
  flags.
- **`PlayerChip`** ([`TeamComponents.kt:181-225`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L181-L225)) is one player, and the most careful
  piece of accessibility work in the app. Every chip is one TalkBack stop named
  "Ottoline, Bibs", so the side is spoken even where colour or position would show it
  ([`TeamComponents.kt:204-209`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L204-L209)). The visible name is hidden from TalkBack so it isn't
  read twice ([`TeamComponents.kt:217-218`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L217-L218)). A tappable chip is a button at least 48 dp
  tall whose `onClickLabel` makes TalkBack say "double-tap to move to Non-bibs" instead
  of the generic "double-tap to activate".
- **`LockToggle`** ([`TeamComponents.kt:235-261`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L235-L261)) is the 🔓/🔒 beside a chip: a separate
  48 dp target, so moving and locking can't be confused. It's a toggle with
  `Role.Switch` named "Lock Ottoline to Bibs" ([`TeamComponents.kt:242`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L242)); TalkBack adds
  "on" or "off". **Surprise:** emoji can't be tinted, so a locked padlock also sits on
  an accent-tinted circle, and the state shows in colour as well as shape. A disabled
  lock is drawn at 38%.
- **`MeterLine`** ([`TeamComponents.kt:279-292`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L279-L292)): "Model: 64% Bibs · quality 83%",
  naming whichever side the model favours, worked out exactly as on the cards so the two
  never disagree by a point. It's a polite live region, so TalkBack reads the new
  numbers after each move. Divergence: quality is a percentage, matching the cards, not
  a decimal like "0.83".
- **`ButtonRow`**, **`PrimaryButton`**, **`GhostButton`** and **`BackContinueRow`**
  ([`TeamComponents.kt:298-357`](../../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt#L298-L357)): buttons sharing the width equally, in the app's shape
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
(needs a phone). *Android idea here: see [learning.md](../learning.md#jvm-tests-vs-instrumented-tests).*

**`QualityTest.kt`** (engine, 7 tests): an even split scores exactly 1, including uneven
team sizes ([`QualityTest.kt:13-41`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L13-L41)); quality equals an oracle built only from
`predictDraw` ([`QualityTest.kt:21-34`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L21-L34)); worse splits score less
([`QualityTest.kt:43-53`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L43-L53)); swapping teams changes nothing ([`QualityTest.kt:55-60`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L55-L60));
every split of a 6-player roster ranks identically by `quality` and by `predictDraw`
([`QualityTest.kt:62-76`](../../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/QualityTest.kt#L62-L76)); empty teams are refused.

**`TeamEditTest.kt`** (13 tests) pins down the pure rules ([`TeamEditTest.kt:13-142`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/TeamEditTest.kt#L13-L142)):
swapping on a proposal, the manual cycle, that `move` never changes its input and
returns the same object for an unknown id, `nextPlace`, `isComplete`, the size warning
only above a gap of one, both lock rules, and the meter on empty, level and lopsided
teams.

**`MatchDayViewModelTest.kt`** (33 tests) drives whole match days on the laptop. **The
setup** ([`MatchDayViewModelTest.kt:36-49`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L36-L49)) gives the ViewModel an unconfined "main"
dispatcher, so its coroutine starts at once, and a standard "balancer" dispatcher that
runs nothing until the test calls `advanceUntilIdle()`. That freezes the ViewModel
mid-generate, where a test can poke at it. The tests cover the roster (order, search,
adding, duplicates, the 60-character cap), generating, editing, the discard dialog,
locks, the manual board, confirm and back.

**The race tests** check what happens when someone taps while the balancer runs. The
rule they enforce: a tap that changes *who's playing* cancels the run, because its
answer would be for the wrong people; a tap that changes *the teams on show* is ignored,
because the new proposals are about to replace them.

- Ticking someone mid-run cancels it, and no proposals ever arrive
  ([`MatchDayViewModelTest.kt:150-160`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L150-L160)).
- Adding a player mid-run cancels it, the stale answer never lands, and the next run
  includes the newcomer ([`MatchDayViewModelTest.kt:439-453`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L439-L453)).
- Continue while re-generating is ignored ([`MatchDayViewModelTest.kt:407-421`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L407-L421)), and so
  are moving, choosing a card and locking ([`MatchDayViewModelTest.kt:423-437`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L423-L437)).

**Why moving a player clears their lock** ([`MatchDayViewModelTest.kt:295-306`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L295-L306)). The
original web version left locks alone when you edited. But a lock means "keep them on
this side". If Ottoline is locked to the Bibs and you move her to the Non-bibs, the
original kept the Bibs lock, so the next re-generate would put her straight back, and
the chip in the Non-bibs column would show a lock that doesn't match. Clearing it keeps
what the screen shows and what re-generate does in step. The test locks two players,
moves one, and checks only the other's lock survives. Related tests check that unticking
drops a lock ([`MatchDayViewModelTest.kt:283-293`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L283-L293)), that a stale lock can't make
Generate fail after ticks change ([`MatchDayViewModelTest.kt:268-281`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L268-L281)), and that locks
which can't be met show the engine's reason and keep the old proposals
([`MatchDayViewModelTest.kt:308-320`](../../app/src/test/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModelTest.kt#L308-L320)).

**`MatchDayScreenTest.kt`** (4 tests, on a phone) drives the real screens by visible
text and by the names TalkBack reads ([`MatchDayScreenTest.kt:21-24`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L21-L24)). It finds a chip
by its TalkBack name plus its button role ([`MatchDayScreenTest.kt:34-40`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L34-L40)), which also
proves those names exist. It covers: generate then move a chip to get EDITED and the
meter ([`MatchDayScreenTest.kt:81-85`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L81-L85)); the discard dialog's Cancel and Discard
([`MatchDayScreenTest.kt:87-100`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L87-L100)); the manual board through to confirm
([`MatchDayScreenTest.kt:102-124`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L102-L124)); and the amber uneven button still confirming
([`MatchDayScreenTest.kt:126-140`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreenTest.kt#L126-L140)). It waits up to five seconds for results, because
the balancer runs on a real thread the test rule doesn't track.

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

- **No storage yet.** The roster is 14 sample players, a new player lives only in
  memory, and a ViewModel survives rotation but not Android closing the app in the
  background. Storage is the next milestone.
- **Confirm doesn't save.** It shows "Saving games arrives in the next milestone" and
  goes back to the roster. Recording the result and showing rating changes, which the
  original did next, come with storage, as does the date field.
- **No release shrinking.** R8, which strips unused code from release builds, is
  deferred to the release-signing work so both can be tested together ([decisions.md](../decisions.md)).
- **Timing.** On a Pixel 9 Pro, 18 players take a median of about 66 ms and 20 players
  about 270 ms. That's fine on a background thread behind "Generating…", but there's
  room to speed it up. The timing test asks for 5 splits while the app asks for 3; the
  cost is in trying every split, so that barely matters. With more than 100,000 possible
  splits (over 20 players, unless locks cut the count) the balancer samples, and the
  step says so.
- **The guard doesn't check the test APK** ([`build.gradle.kts:140-141`](../../app/build.gradle.kts#L140-L141)). It never
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
cards also stop responding (the buttons and locks visibly dim), so this is mostly belt and braces (section 6).

**Why pass one `MatchDayActions` interface instead of lambdas?** Sixteen actions as
separate lambdas would make every step's signature a wall of parameters. One interface
keeps them short, and previews pass `NoActions`.

**Why bundle the fonts instead of downloading them?** Downloadable fonts arrive through
a provider app over the network, which would break the offline promise by proxy.
Bundling costs about 480 KB and keeps the app self-contained.
