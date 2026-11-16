# Walkthrough 2: the first screen

This is a guided tour of the `:app` module, the Android app, one function at a time.
It's written for someone who knows engineering but is new to Kotlin and Android. Skim
section 1 for the shape of it, read section 2 for how the build is put together, dive
into section 3 when you want to know what a line is doing, and use section 6 when
something seems odd.

It describes the code **as it is** at the end of milestone 2. Where the code and the
docs disagree, this follows the code. Every `File.kt:line` link jumps to the line. The
Android ideas each get a short entry in [learning.md](../learning.md), and the notes below point to
them.

---

## 1. The app in one page

**What it does.** One screen, "Tonight". It shows ten made-up players, all ticked. You
untick whoever isn't coming, tap **Balance**, and two team cards appear with each side's
win chance. **Try another** flips through the five most even splits. There's no storage
yet, and the app never touches the network.

**What happens when you tap the icon.**

```
  tap the TeamBayes icon
            │
            ▼
   MainActivity.onCreate           the app's one Activity (a window Android gives us)
            │  enableEdgeToEdge(), then setContent { ... }
            ▼
   TeamBayesTheme                  pitch-green colours, light or dark
            │
            ▼
   TonightRoute                    fetches the TonightViewModel, watches its state
            │
            ▼
   TonightScreen(state) ◄──────────────── every new state redraws it ─────┐
            │  tap "Balance"                                              │
            ▼                                                             │
   TonightViewModel.balance()      result = Balancing  ───────────────────┤
            │  launch on viewModelScope (main thread)                     │
            ▼                                                             │
   withContext(Dispatchers.Default)                                       │
       Balancer.balance(roster, k = 5)     on a background thread         │
            │                                                             │
            ▼                                                             │
   result = Teams(splits, index 0)  or  Problem(message) ─────────────────┘
```

Three ideas carry the whole thing:

- **The state is one value.** Everything the screen draws lives in one immutable
  `TonightUiState`. The ViewModel replaces it; the screen redraws from it.
- **The screen owns nothing.** `TonightScreen` takes the state plus three callbacks.
  That makes it trivial to preview and test.
- **The maths never runs on the main thread.** The main thread draws the screen, so the
  balancer runs on a background thread and the screen keeps responding.

**How we know it's right.** JVM unit tests check the ViewModel on the laptop, including
the awkward cases: ticking someone mid-balance, and tapping Balance twice (section 4).
On a phone, a UI test taps through the real screen, and a timing test measures the
balancer. On a Pixel 9 Pro, 18 players take a median of about 66 ms and 20 players about
270 ms. The permission guard (section 2) fails the build on any unlisted permission, and
it had its own **mutation checks**: adding INTERNET through `<uses-permission-sdk-23>`,
and adding a harmless VIBRATE, each made the build fail. As with the engine, **every
change was reviewed adversarially by two independent AI reviewers before it was pushed**
([decisions.md](../decisions.md)).

---

## 2. The build

**What it's for:** turning Kotlin and XML into an installable app, with the same library
versions for everyone, and refusing to build an app that could go online.

### Two modules

[`settings.gradle.kts:19-20`](../../settings.gradle.kts#L19-L20) lists `:engine` (the maths from milestone 1) and `:app`
(this). The app depends on the engine ([`build.gradle.kts:38`](../../app/build.gradle.kts#L38)); the engine knows
nothing about Android. `FAIL_ON_PROJECT_REPOS` ([`settings.gradle.kts:10`](../../settings.gradle.kts#L10)) stops a
module adding its own download sources, so every library comes from Google's repository
or Maven Central (build plugins can also come from the Gradle Plugin Portal). *Android idea here: an app module, see [learning.md](../learning.md#an-app-module-and-its-build-file).*

### Built-in Kotlin

Since Android Gradle Plugin (AGP) 9.0, Kotlin support is part of AGP itself, so the app
applies only two plugins: AGP and the Compose compiler ([`build.gradle.kts:6-11`](../../app/build.gradle.kts#L6-L11)). The
Compose compiler ships with Kotlin, so its version is tied to Kotlin's
([`libs.versions.toml:50-51`](../../gradle/libs.versions.toml#L50-L51)). The root build file declares all four plugins with
`apply false` ([`build.gradle.kts:1-6`](../../build.gradle.kts#L1-L6)), which loads each one once, at one version, for
every module. The app compiles to Java 17 bytecode, like the engine
([`build.gradle.kts:26-30`](../../app/build.gradle.kts#L26-L30)).

### The version catalogue and the Compose BOM

Every version lives in [`libs.versions.toml`](../../gradle/libs.versions.toml), each with a link to its release notes.
Compose is many libraries that must agree, so the catalogue names one **BOM** ("bill of
materials": a list of versions known to work together) at [`libs.versions.toml:9`](../../gradle/libs.versions.toml#L9), and
the Compose libraries below it have no version of their own. **Surprise:** Espresso, a
test library, is pinned to 3.7.0 by hand because Compose's test library pulls in 3.5.0,
which crashes on newer Android ([`libs.versions.toml:22-23`](../../gradle/libs.versions.toml#L22-L23)). [`gradle.properties:2`](../../gradle.properties#L2)
gives Gradle 4 GB of memory; the Android and Kotlin compilers together run out with the
default.

### SDK levels

[`build.gradle.kts:15-20`](../../app/build.gradle.kts#L15-L20) sets three numbers that are easy to mix up:

| Setting | Value | Means |
|---|---|---|
| `compileSdk` | 37 | Which Android APIs the code can see when it compiles. |
| `targetSdk` | 37 | Which Android behaviour rules the app has signed up for. |
| `minSdk` | 26 | The oldest Android it installs on (8.0). This lets one adaptive-icon file cover every phone. |

### The permission guard

**What it's for:** making it impossible to build an app that asks for INTERNET, or any
other permission nobody has looked at.

On Android, an app must list the permissions it wants in its manifest (the app's
declaration file, covered below). Libraries have manifests too, and the build **merges**
them all into one. So a library can add INTERNET without anyone editing our files. The
guard reads the final merged manifest and fails the build if it finds anything
unexpected.

**`VerifyPermissions`** ([`build.gradle.kts:82-138`](../../app/build.gradle.kts#L82-L138)) is a Gradle task with three
inputs: the merged manifest, where to copy it, and the allowed list
([`build.gradle.kts:84-95`](../../app/build.gradle.kts#L84-L95)).

- **`verify()`** ([`build.gradle.kts:97-114`](../../app/build.gradle.kts#L97-L114)) logs every permission it found, fails
  with a message naming each unexpected one and how to trace which library added it, and
  otherwise copies the manifest through unchanged ([`build.gradle.kts:113`](../../app/build.gradle.kts#L113)).
- **`readPermissions()`** ([`build.gradle.kts:120-137`](../../app/build.gradle.kts#L120-L137)) collects both
  `<uses-permission>` and `<uses-permission-sdk-23>` (the same request, limited to
  Android 6.0 and later). Checking only the first would leave a back door. A request
  with no name is turned into `<missing android:name>`, which fails too
  ([`build.gradle.kts:134-135`](../../app/build.gradle.kts#L134-L135)). The XML parser is locked down to refuse DOCTYPEs,
  which closes off a classic XML attack ([`build.gradle.kts:123-125`](../../app/build.gradle.kts#L123-L125)).
- **`Permission`** ([`build.gradle.kts:117`](../../app/build.gradle.kts#L117)) is a name plus the tag it came from, for
  the error message.

**Why an allowlist?** A blocklist only stops what you thought of. An allowlist fails on
anything new, so a person has to audit it and add it on purpose. The list has exactly
one entry ([`build.gradle.kts:147`](../../app/build.gradle.kts#L147)):
`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. AndroidX's core library adds
it to keep the app's own internal broadcasts private to the app. It grants no network
access.

**Why inside the manifest pipeline?** The `onVariants` block
([`build.gradle.kts:142-153`](../../app/build.gradle.kts#L142-L153)) registers one task per build variant
(`verifyAllowedPermissionsDebug` and `verifyAllowedPermissionsRelease`) and plugs it in
as a **transform** of `MERGED_MANIFEST`: the manifest goes in, gets checked, and comes
out the other side. Every later step that needs the manifest, such as
building an APK, a Play bundle, or installing, therefore has to run the guard first. A
guard attached only to `./gradlew check` would never stop `assembleRelease`.

**Why not `tools:node="remove"`?** The usual Android trick is to add
`tools:node="remove"` for INTERNET in your own manifest, which deletes a library's
request during the merge. That would make the build pass while hiding the fact that a
library wanted the network. The comment at [`AndroidManifest.xml:5-12`](../../app/src/main/AndroidManifest.xml#L5-L12) records this
choice.

### The manifest choices

[`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) is the app's declaration to Android: its icon, name, theme,
which screen opens from the launcher, and what it's allowed to do. *Android idea here:
see [learning.md](../learning.md#the-manifest-and-permissions).*

- **Backup off** ([`AndroidManifest.xml:21-23`](../../app/src/main/AndroidManifest.xml#L21-L23)). `allowBackup="false"` keeps player
  data out of Google's cloud backup. `dataExtractionRules` does the same for Android
  12+, covering both cloud backup and phone-to-phone transfer, and
  `fullBackupContent="false"` covers Android 11 and older. The app will offer its own
  export later, which the user controls.
- **Cleartext off** ([`AndroidManifest.xml:24`](../../app/src/main/AndroidManifest.xml#L24)). `usesCleartextTraffic="false"` forbids
  unencrypted HTTP. The app has no network permission anyway, so this is a second lock
  on the same door.
- **The launcher entry** ([`AndroidManifest.xml:31-38`](../../app/src/main/AndroidManifest.xml#L31-L38)). The `MAIN` / `LAUNCHER` intent
  filter is what puts the icon in the app drawer. `exported="true"` is required for
  that: the launcher is another app, and it has to be allowed to start us.
- **The emoji2 initializer, removed** ([`AndroidManifest.xml:47-56`](../../app/src/main/AndroidManifest.xml#L47-L56)). Compose pulls in
  `androidx.emoji2`, whose start-up code can ask a font-provider app to download an
  emoji font. That's network activity by proxy. The manifest redeclares the start-up
  provider only so it can reach that one entry and remove it. **Note:** this *is* a
  `tools:node="remove"`, but for a start-up hook, in plain sight, not to hide a
  permission request.

---

## 3. File by file

Reading order: the entry point, the theme, the sample data, the ViewModel, the screen,
then the resources.

### `MainActivity.kt`: the front door

**What it's for:** the one thing Android launches. It sets up the window and hands over
to Compose.

**`MainActivity`** ([`MainActivity.kt:13`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L13)) is the app's only Activity. Every future
screen will be a Compose function inside it, not a new Activity.

**`onCreate`** ([`MainActivity.kt:15-24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L15-L24)) runs when Android creates the Activity,
including again after a rotation. Two things happen:

- **`enableEdgeToEdge()`** ([`MainActivity.kt:18`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L18)) lets the app draw behind the status
  bar and navigation bar. **The catch:** each screen must then keep its content clear of
  them itself, which `TonightScreen` does with insets (below).
- **`setContent { ... }`** ([`MainActivity.kt:19-23`](../../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt#L19-L23)) swaps the old XML-layout world
  for Compose: whatever you call inside is the UI.

*Android idea here: see [learning.md](../learning.md#activity-and-setcontent).*

### `Theme.kt`: the colours

**What it's for:** one place that decides how everything looks, light or dark.

**`LightColors` and `DarkColors`** ([`Theme.kt:13-36`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L13-L36), [`Theme.kt:38-61`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L38-L61)) are Material
3 colour schemes. Each colour comes with an "on" partner, the colour for text or icons
drawn on top of it, chosen for readable contrast. Any colour not listed falls back to
Material's default (the error red, for example).

**`TeamBayesTheme`** ([`Theme.kt:69-78`](../../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt#L69-L78)) wraps everything in `MaterialTheme` with the
right scheme. `darkTheme` defaults to the phone's setting but can be forced, which is
how the dark preview works. **Note:** it deliberately ignores Android 12's
wallpaper-based "dynamic colour", so the app looks the same on every phone. *Android
idea here: see [learning.md](../learning.md#material-3-theming).*

### `SamplePlayers.kt`: made-up players

**What it's for:** something to balance before storage exists.

**`TonightPlayer`** ([`SamplePlayers.kt:15`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/SamplePlayers.kt#L15)) is an id, a display name and the engine's
`Rating`. The id matters more than it looks: the engine sorts by id, so it decides how
ties break.

**`SamplePlayers`** ([`SamplePlayers.kt:24`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/SamplePlayers.kt#L24)) is an `object` holding ten invented first
names and **`tonight`** ([`SamplePlayers.kt:32`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/SamplePlayers.kt#L32)), the ten players the app starts with.

**`make(count)`** ([`SamplePlayers.kt:41-53`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/SamplePlayers.kt#L41-L53)) builds `count` players, best first. The
means come from the engine's `Prior.fromRankOrder`, and every sigma is `SIGMA0`, exactly
as a real first-run ranking would give. Past ten, names repeat with a number ("Ottoline
2"); the timing test uses this for 18- and 20-player rosters. **Surprise:** the id is
built with `padStart`, not `String.format` ([`SamplePlayers.kt:47-48`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/SamplePlayers.kt#L47-L48)), because
`format` can use the phone's language for digits. Zero-padding (`p01`...`p20`) makes
text order match list order.

### `TonightViewModel.kt`: the brains of the screen

**What it's for:** holding the screen's state, surviving rotations, and running the
balancer without freezing anything.

#### The state types

**`KEPT_SPLITS = 5`** ([`TonightViewModel.kt:22`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L22)) is how many splits we ask for, so
"Try another" has something to cycle through.

**`TonightResult`** ([`TonightViewModel.kt:25-58`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L25-L58)) is a `sealed interface` for the
bottom half of the screen, with four cases:

- **`Idle`** and **`Balancing`** ([`TonightViewModel.kt:28-31`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L28-L31)) carry no data, so
  they're `data object`s: one instance each, with a readable `toString`.
- **`Teams(splits, index)`** ([`TonightViewModel.kt:42-54`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L42-L54)) holds all the kept splits
  and which one is on show. Its `init` refuses an empty list or an out-of-range index
  ([`TonightViewModel.kt:44-47`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L44-L47)), so `split` and `total`
  ([`TonightViewModel.kt:50-53`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L50-L53)) can never fail. **Why keep the splits in here?** Then
  the screen state is the only copy, and nothing can drift out of step with it.
- **`Problem(message)`** ([`TonightViewModel.kt:57`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L57)) holds a string *resource id*, not
  the text. `@StringRes` makes the compiler check it's really one. The screen looks up
  the words, so they could be translated.

**`TonightUiState`** ([`TonightViewModel.kt:66-73`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L66-L73)) is everything the screen draws: all
players, the ticked ids, and the result. `playingCount` is just `ticked.size`.

#### The ViewModel

**`TonightViewModel`** ([`TonightViewModel.kt:91-95`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L91-L95)) takes the players, who starts
ticked (everyone, by default), and **where to run the balancer**, defaulting to
`Dispatchers.Default`. That third parameter exists for the tests, which swap in a
dispatcher they control. **Surprise:** because every parameter has a default, Kotlin
also generates a no-argument constructor, which is what `viewModel()` in the screen uses
to create it. *Android idea here: see [learning.md](../learning.md#viewmodel-and-stateflow).*

**`_state` and `state`** ([`TonightViewModel.kt:97-100`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L97-L100)): the private, writable
`MutableStateFlow` and its public, read-only view. Only the ViewModel can change the
state; anyone can watch it.

**`balanceJob`** ([`TonightViewModel.kt:103`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L103)) remembers the balancing in progress, so
it can be cancelled.

**`toggle(playerId)`** ([`TonightViewModel.kt:106-112`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L106-L112)) cancels any balancing, flips
the player in or out of `ticked`, and resets the result to `Idle`. Teams for yesterday's
roster are wrong teams, so they go. `update { }` swaps in a new state built from the
current one in a single step.

**`balance()`** ([`TonightViewModel.kt:119-143`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L119-L143)), step by step:

1. Build the roster from the ticked players, in display order
   ([`TonightViewModel.kt:120-123`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L120-L123)).
2. Cancel any earlier run and show `Balancing` ([`TonightViewModel.kt:125-126`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L125-L126)).
3. Launch a coroutine on `viewModelScope`, which hops to the background dispatcher for
   the one slow call ([`TonightViewModel.kt:127-128`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L127-L128)). *Android idea here: see
   [learning.md](../learning.md#viewmodelscope-and-dispatchersdefault).*
4. Turn the engine's answer into a `TonightResult` ([`TonightViewModel.kt:129-140`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L129-L140)) and
   publish it ([`TonightViewModel.kt:141`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L141)). An `Impossible` becomes a `Problem`. An
   empty `Splits` list, which the engine never returns, also becomes a `Problem` rather
   than crashing `Teams`'s check.

**Surprise, about cancellation.** `Balancer.balance` is plain maths and never checks
whether it's been cancelled, so cancelling doesn't stop the CPU work. What it does stop
is the *result*: when `withContext` returns to a cancelled coroutine, it throws instead
of handing back the answer, so step 4 never runs. A stale result can't overwrite newer
state. That's what the tests in section 4 pin down.

**`reroll()`** ([`TonightViewModel.kt:146-151`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L146-L151)) moves `index` on by one, wrapping from
the last split to the first. If teams aren't on show, `return@update current` leaves the
state untouched ([`TonightViewModel.kt:148`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L148)). *Kotlin idea: `as?` is "cast, or null if
it's the wrong type", and `?:` supplies what to do with the null.*

**`Reason.message()`** ([`TonightViewModel.kt:155-163`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt#L155-L163)) maps each engine refusal to a
sentence. Too few and too many get their own messages. Locks get one too, though the app
has no locks yet. A duplicate id or a lock on an unknown player can't come from this
screen, so they share "Something went wrong". *Kotlin idea: this is an extension
function, a function added to someone else's type, here the engine's `Reason`.*

### `TonightScreen.kt`: what you see

**What it's for:** drawing the state and reporting taps. No logic, no storage.

**`MinTouchTarget = 48.dp`** ([`TonightScreen.kt:43`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L43)) is the smallest comfortable tap
size from Android's accessibility guidance. A `dp` is a density-independent pixel,
roughly the same physical size on every screen.

**`TonightRoute(viewModel)`** ([`TonightScreen.kt:52-62`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L52-L62)) is the only function here
that knows about the ViewModel. `viewModel()` fetches the existing one or creates it, so
a rotation gets the same instance back. `collectAsStateWithLifecycle()` turns the
`StateFlow` into Compose state and stops listening while the app is in the background
([`TonightScreen.kt:55`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L55)). It then passes plain values and function references
(`viewModel::toggle`) to `TonightScreen`. *Android idea here: see [learning.md](../learning.md#state-hoisting).*

**`TonightScreen(state, onToggle, onBalance, onReroll)`** ([`TonightScreen.kt:74-130`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L74-L130))
is the whole screen as one scrolling list (`LazyColumn`, which only builds the rows on
screen). Top to bottom: the "Tonight" title, the balance bar, the result, the "Who is
playing?" heading, then one row per player. *Android idea here: see [learning.md](../learning.md#composable-functions-and-recomposition).*

- **Insets** ([`TonightScreen.kt:86-88`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L86-L88)). Because the app draws edge to edge,
  `Scaffold` is told to measure `safeDrawing`: the system bars, the keyboard and the
  camera cutout. The list uses that as `contentPadding`, so the first and last rows sit
  clear of the bars but can still scroll underneath them.
- **Headings** ([`TonightScreen.kt:98`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L98), [`TonightScreen.kt:117`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L117)).
  `semantics { heading() }` marks both titles as headings, so TalkBack users can jump
  between sections. TalkBack is Android's screen reader.
- **Row keys** ([`TonightScreen.kt:120-121`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L120-L121)). `key = { it.id }` lets Compose track each
  row by player, not by position, so a reordered list later won't mix up which row is
  which.

**`BalanceBar`** ([`TonightScreen.kt:133-150`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L133-L150)) is "8 playing" with the Balance button
beside it. The count uses a *plural* resource, so a language with different words for
one and many can get it right. The button is disabled while balancing. **Note:** it is
*not* disabled with three players; tapping then shows the engine's "Tick at least 4
players", so the rule lives in one place. The button has no separate
`contentDescription`, so people using Voice Access can say the word they see
([`TonightScreen.kt:144-146`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L144-L146)).

**`PlayerRow`** ([`TonightScreen.kt:160-176`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L160-L176)) is a checkbox and a name. **Surprise:**
the tap handling is on the *row*, not the box. `toggleable(role = Role.Checkbox)`
([`TonightScreen.kt:166`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L166)) makes the whole 48 dp row the touch target and merges the row
into one item that TalkBack reads as a checkbox with a name and a state. The `Checkbox`
itself gets `onCheckedChange = null` ([`TonightScreen.kt:169`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L169)), so it only draws, and
one tap is never handled twice. *Android idea here: see [learning.md](../learning.md#semantics-for-talkback).*

**`ResultSection`** ([`TonightScreen.kt:179-195`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L179-L195)) picks what to show with a `when` over
the four results: nothing, "Balancing…", a problem in the theme's error colour, or the
teams. **Surprise:** the waiting text and the problem are **live regions**
(`LiveRegionMode.Polite`, [`TonightScreen.kt:185`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L185), [`TonightScreen.kt:191`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L191)). TalkBack
announces them when they appear, after it finishes what it's saying, so a blind user
hears "Tick at least 4 players" without hunting for it.

**`TeamsSection`** ([`TonightScreen.kt:198-228`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L198-L228)) draws the two cards, the win chances,
and the "Option 2 of 5" line with **Try another**.

- `remember(players) { ... }` ([`TonightScreen.kt:200`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L200)) builds the id-to-name map once
  and keeps it across redraws, rebuilding only if the player list changes.
- **Win chances always add to 100** ([`TonightScreen.kt:201-202`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L201-L202)). It rounds A and
  shows B as `100 - A`. Rounding both could give 51% and 50%.
- The option line is a live region too ([`TonightScreen.kt:219-220`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L219-L220)), so a re-roll is
  announced.
- **Try another** is disabled when there's only one split ([`TonightScreen.kt:223`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L223)).
  Four players have only three splits, so you see "of 3", not "of 5".

**`TeamCard(title, names)`** ([`TonightScreen.kt:231-240`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L231-L240)) is a card with a title and
one line per name. **Surprise:** `semantics(mergeDescendants = true) { heading() }`
([`TonightScreen.kt:234`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L234)) makes the whole card one TalkBack stop that reads the title
and every name, and marks that stop as a heading, so heading-to-heading navigation lands
on each team.

**The previews** ([`TonightScreen.kt:242-287`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L242-L287)) draw the screen in Android Studio
without running the app: idle, balancing, teams, teams in dark mode, and a problem.
`PreviewOf` ([`TonightScreen.kt:257-267`](../../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt#L257-L267)) wraps `TonightScreen` in the theme with eight
players ticked and do-nothing callbacks. The preview teams are one hand-written split
repeated five times, shown at index 1 ("Option 2 of 5"). They're possible only because
`TonightScreen` takes plain values.

### The resources

**What they're for:** everything that isn't Kotlin: words, the icon, and the colour the
window shows before Compose draws.

- **[`strings.xml`](../../app/src/main/res/values/strings.xml)**: every word on screen. `%1$d` is "the first argument, as a whole
  number"; `%%` is a literal percent sign. The `playing_count` plural
  ([`strings.xml:22-25`](../../app/src/main/res/values/strings.xml#L22-L25)) has the same text for both forms in English.
- **`themes.xml` and its `values-night` twin** ([`themes.xml:7-9`](../../app/src/main/res/values/themes.xml#L7-L9)): the window's
  background before the first Compose frame, set to the same colour as the Compose
  background, so there's no white flash at launch. Android picks the `values-night` file
  in dark mode.
- **The launcher icon** ([`ic_launcher.xml:4-8`](../../app/src/main/res/mipmap-anydpi/ic_launcher.xml#L4-L8)): an adaptive icon with a deep green
  background, a disc split into two halves (one per team), and a single-colour version
  for Android 13's themed icons. The disc's radius of 26 sits inside the middle 66 dp
  that launchers can crop to ([`ic_launcher_foreground.xml:2-5`](../../app/src/main/res/drawable/ic_launcher_foreground.xml#L2-L5)).
- **[`data_extraction_rules.xml`](../../app/src/main/res/xml/data_extraction_rules.xml)**: excludes every storage area from cloud backup and
  device transfer, backing up the manifest's `allowBackup="false"`.

---

## 4. The tests

Run them with `./gradlew test` (laptop) and `./gradlew :app:connectedDebugAndroidTest`
(needs a phone). *Android idea here: see [learning.md](../learning.md#jvm-tests-vs-instrumented-tests).*

**`TonightViewModelTest.kt`** runs on the laptop, no phone needed. **The setup**
([`TonightViewModelTest.kt:33-46`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L33-L46)): `viewModelScope` runs on Android's main thread,
which doesn't exist on a laptop, so `Dispatchers.setMain` stands one in. Two test
dispatchers share one clock. The "main" one is unconfined, so `launch` starts at once.
The "balancing" one is standard, so it queues work until the test calls
`advanceUntilIdle()`. That freezes the ViewModel mid-balance, where the test can look at
it.

- **Ticking** ([`TonightViewModelTest.kt:60-70`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L60-L70)): a tap ticks, a second unticks.
- **Balancing** ([`TonightViewModelTest.kt:72-86`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L72-L86)): `Balancing` first, then five splits
  of 4 v 4 that use exactly the ticked players.
- **Re-rolling** ([`TonightViewModelTest.kt:88-111`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L88-L111)): it records options 0 to 4 (five
  different splits), the fifth press wraps back to the first, and a re-roll with no
  teams does nothing.
- **Too few players** ([`TonightViewModelTest.kt:113-118`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L113-L118)): three ticked gives the "at
  least 4" problem.
- **Ticking clears teams** ([`TonightViewModelTest.kt:120-131`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L120-L131)), and a re-roll can't
  bring them back.
- **Ticking mid-balance** ([`TonightViewModelTest.kt:133-144`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L133-L144)): the queued balance is
  cancelled, and the result stays `Idle` after the work drains.
- **Balance tapped twice** ([`TonightViewModelTest.kt:146-162`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L146-L162)). This one needs **the
  `ManualDispatcher` trick**.

**`ManualDispatcher`** ([`TonightViewModelTest.kt:165-176`](../../app/src/test/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModelTest.kt#L165-L176)) is a dispatcher that does
nothing but queue work until the test calls `runNext()`, one task at a time. A standard
test dispatcher's `advanceUntilIdle()` would run both balances together, hiding the
moment that matters. Here the test checks both runs are queued (`pending == 2`), runs
only the first and checks the state is *still* `Balancing`, so the cancelled run
published nothing, then runs the second and sees `Teams`. Without cancellation, the
first run's teams would have appeared.

**`TonightScreenTest.kt`** runs on a phone and drives the real screen through Compose's
test rule ([`TonightScreenTest.kt:21-34`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/TonightScreenTest.kt#L21-L34)), starting with nobody ticked.

- **Four players** ([`TonightScreenTest.kt:46-62`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/TonightScreenTest.kt#L46-L62)): tick four by tapping their names,
  check the first row reads as on and "4 playing" appears, tap Balance, wait for "Option
  1 of 3", check both team cards, then Try another gives "Option 2 of 3". Tapping a
  *name* toggles the row only because the whole row is the toggle.
- **Three players** ([`TonightScreenTest.kt:64-72`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/TonightScreenTest.kt#L64-L72)): Balance shows "Tick at least 4
  players".
- **Why `waitForText`?** ([`TonightScreenTest.kt:40-44`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/TonightScreenTest.kt#L40-L44)) The balancer runs on a real
  background thread that Compose's test rule doesn't track, so the test polls for up to
  five seconds instead of assuming it's done.
- `debugImplementation(libs.androidx.compose.ui.test.manifest)`
  ([`build.gradle.kts:62`](../../app/build.gradle.kts#L62)) adds a blank Activity for the test rule to host the screen
  in.

**`BalancerTimingTest.kt`** times the engine on a phone. `measure`
([`BalancerTimingTest.kt:48-66`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/BalancerTimingTest.kt#L48-L66)) runs the balancer twice to warm up (Android's compiler
optimises code that's been run a few times), then seven measured times, and logs the
median and maximum to logcat under `TeamBayesTiming`. 18 players (24,310 splits) must
have a median under 200 ms ([`BalancerTimingTest.kt:28-33`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/BalancerTimingTest.kt#L28-L33)). 20 players (92,378 splits)
is logged only, with no limit ([`BalancerTimingTest.kt:35-39`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/BalancerTimingTest.kt#L35-L39)).

**Why skip the limit on emulators?** An emulator's speed depends on the laptop running
it, so a failure there would say nothing about phones. `isEmulator()`
([`BalancerTimingTest.kt:42-46`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/BalancerTimingTest.kt#L42-L46)) spots one from its build strings. **Note the order:**
the test measures and logs *first*, then calls `assumeFalse`
([`BalancerTimingTest.kt:31`](../../app/src/androidTest/kotlin/io/github/valnrys/teambayes/tonight/BalancerTimingTest.kt#L31)), so an emulator still records its numbers and the test is
reported as skipped, not passed.

---

## 5. Known gaps

These are deliberate for now. Each has a home in a later milestone.

- **State doesn't survive the system killing the app.** A ViewModel survives rotation,
  but if Android reclaims a backgrounded app's memory, ticks and teams are lost. That's
  harmless with sample players, and storage comes next.
- **No release shrinking.** R8, the tool that strips unused code from release builds,
  isn't switched on yet. It's deferred to the release-signing work, so both can be
  tested together on a signed build ([decisions.md](../decisions.md)).
- **20 players take about 270 ms.** That's fine on a background thread with a
  "Balancing…" message, but there's room to speed it up. One known cost: the model still
  makes a sorted copy of each team for every split it scores.
- **The guard doesn't check the test APK.** Instrumented tests install a second APK with
  its own manifest, which the guard never sees ([`build.gradle.kts:140-141`](../../app/build.gradle.kts#L140-L141)). It never
  ships to users, but a test library could add a permission there unnoticed.

---

## 6. Questions you might ask

**Why only one Activity?** Activities are heavyweight: Android creates and destroys
them, and passing data between them is clumsy. With Compose, screens are just functions,
so one Activity hosting them all is the modern default.

**Why does `TonightScreen` take four arguments instead of the ViewModel?** So it can be
previewed with fake data and tested without any coroutines. `TonightRoute` is the thin
adapter that connects the real ViewModel. This pattern is called state hoisting.

**What happens if I rotate the phone mid-balance?** Android destroys and rebuilds the
Activity, but the ViewModel survives, and so does the coroutine in `viewModelScope`. The
new screen collects the same `state` and sees the teams when they land.

**Why `Dispatchers.Default` and not `Dispatchers.IO`?** `Default` has about one thread
per CPU core and is meant for number crunching. `IO` is for waiting on disks and
networks. The balancer is pure arithmetic.

**If I tick someone mid-balance, does the phone keep calculating?** Yes, briefly. The
engine doesn't check for cancellation, so it finishes its sums, and the result is thrown
away. At a few hundred milliseconds at most, that isn't worth complicating the engine
for.

**Why doesn't the guard just look for INTERNET?** Because the next risky permission
might not be INTERNET. An allowlist makes every new permission a decision a person has
to make.

**Why JUnit 4 here when the engine uses JUnit 5?** AndroidX's on-phone test runner and
Compose's test rule are built on JUnit 4, so the app uses it in both test folders.

**Why such odd names?** Every name is invented, so the public repo shows no real person.
Unusual names also make it obvious on screen that this is sample data.
