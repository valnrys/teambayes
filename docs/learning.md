# Learning log

Notes on the Kotlin and Android ideas I meet while building TeamBayes, in plain words,
with snippets from the real code. Newest entry last.

## Milestone 1: the engine (no Android yet)

This milestone is pure Kotlin: no screens, no Android yet. It is the maths that rates
players and balances teams.

### Gradle
Gradle is the build tool. It downloads libraries, compiles the code and runs the tests.
The **wrapper** (`gradlew`) is a small script that fetches the exact Gradle version the
project wants, so everyone builds the same way. See
[gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties).
The **version catalogue** keeps version numbers in one file:

```toml
kotlin = "2.4.21"
```
See [libs.versions.toml](../gradle/libs.versions.toml).

### A module, `jvmToolchain` and `-Xjdk-release`
A module is a self-contained chunk of a project. `:engine` is ours, listed in
[settings.gradle.kts](../settings.gradle.kts). Its build file says which Java to use:

```kotlin
jvmToolchain(21)
freeCompilerArgs.add("-Xjdk-release=17")
```
`jvmToolchain(21)` compiles with JDK 21. `-Xjdk-release=17` limits the code to the Java 17
library, so it can't use anything newer than Android supports. See
[engine/build.gradle.kts](../engine/build.gradle.kts).

### `object`
An `object` is a single, ready-made instance: no need to create one.

```kotlin
object EngineConfig {
    const val MU0: Double = 25.0
}
```
You just write `EngineConfig.MU0`. (Trimmed: the real object has more constants.) Good for constants and helpers. See
[Config.kt](../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Config.kt).

### `data class`
A class for holding values. Kotlin writes equality, printing and copying for you.

```kotlin
data class Rating(val mu: Double, val sigma: Double) {
```
(Trimmed: the body checks the values.)
See [Rating.kt](../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Rating.kt).

### `enum class`
A fixed list of choices, which can carry values of their own.

```kotlin
enum class Outcome(val rankA: Int, val rankB: Int) {
    A_WINS(0, 1),
    B_WINS(1, 0),
    DRAW(0, 0),
}
```
(Simplified: the KDoc on each entry is left out.) There is no way to have a fourth
result by accident. Same file as above.

### `sealed interface`
Like an enum, but each choice can carry different data. The compiler knows every
possible case, so it can check you handled them all.

```kotlin
sealed interface BalanceResult {
    data class Splits(val splits: List<Split>, val sampled: Boolean) : BalanceResult
    data class Impossible(val reason: Reason) : BalanceResult
}
```
(Simplified: the real file has doc comments between these lines.)
See [Balancer.kt](../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt).

### `require`
`require` checks an input and stops with a clear message if it's bad.

```kotlin
require(nPlayers >= 2) { "A game needs at least two players, was $nPlayers" }
```
Bad inputs fail at the door, not three steps later as a strange number. See
[Config.kt](../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Config.kt).

### `internal`
`internal` means "visible inside this module only". The normal-distribution maths is
an implementation detail, so the rest of the world can't depend on it:

```kotlin
internal object NormalDist {
```
See [NormalDist.kt](../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/NormalDist.kt).

### KDoc
KDoc is a comment written above code in `/** ... */`. Tools show it on hover, and
`[Name]` links to other code:

```kotlin
/** No splits could be made, for the given [reason]. */
data class Impossible(val reason: Reason) : BalanceResult
```
Here `[reason]` links to the parameter. Every public piece of the engine has one. See
[Balancer.kt](../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/Balancer.kt).

### Tests: `kotlin.test` and JUnit 5
`kotlin.test` gives us `@Test`, and JUnit 5 is the runner that finds and runs them
(`useJUnitPlatform()` in the build file). Run them with `./gradlew :engine:test`.

```kotlin
@Test
fun `every player's new mu and sigma match in every case`() {
```
Backticks let a test name be a sentence. See
[RateFixtureTest.kt](../engine/src/test/kotlin/io/github/valnrys/teambayes/engine/RateFixtureTest.kt).

### Why fixture tests, and mutation checks
A fixture test compares our answers with answers from the reference Python package, so
it doesn't depend on our own idea of "right". A **mutation check** is a deliberate
break: change a sign or a constant, and confirm a test fails. If nothing fails, the
tests weren't really guarding that line. Passing tests only mean something if they
can also fail.

### Why floating-point order matters
Computers round decimals, so `(a + b) + c` can differ from `a + (b + c)` in the last
digits. To match the reference we add things in the same order it does:

```kotlin
for (player in team.sortedByDescending { it.ordinal }) {
```
See [ThurstoneMostellerFull.kt](../engine/src/main/kotlin/io/github/valnrys/teambayes/engine/ThurstoneMostellerFull.kt).

## Milestone 2: match day on a phone

This milestone adds the Android app: match day, from ticking who's playing to confirming
two teams, using the engine from milestone 1. The full tour is in
[walkthrough/02-match-day.md](walkthrough/02-match-day.md).

### An app module and its build file
`:app` is a second module, built by the Android Gradle Plugin instead of plain Kotlin.
Its `build.gradle.kts` names the app and the Android versions it supports:

```kotlin
defaultConfig {
    applicationId = "io.github.valnrys.teambayes"
    minSdk = 26
    targetSdk = 37
```
(Simplified: the version and test-runner lines are left out.) The application ID is the
app's permanent name on a phone and on Google Play. See
[app/build.gradle.kts](../app/build.gradle.kts).

### The manifest and permissions
The manifest is an XML file that tells Android what the app is: its icon, its screens,
and what it may do. Anything risky, like going online, needs a `<uses-permission>`
line. Libraries bring manifests too, and the build merges them all, which is why
TeamBayes checks the *merged* one.

```xml
<application
    android:allowBackup="false"
    android:usesCleartextTraffic="false"
```
(Simplified: the other attributes are left out.) See
[AndroidManifest.xml](../app/src/main/AndroidManifest.xml).

### Activity and `setContent`
An Activity is a window Android creates and destroys for you; it's what the launcher
icon opens. `setContent` hands that window to Compose, the code-based UI toolkit:

```kotlin
setContent {
    TeamBayesTheme {
        TeamBayesApp(container)
    }
}
```
See [MainActivity.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt).

### Composable functions and recomposition
A function marked `@Composable` describes part of the screen from its arguments. It
doesn't return anything; it emits UI.

```kotlin
@Composable
private fun PlayerRow(name: String, checked: Boolean, onToggle: () -> Unit) {
```
When the state it read changes, Compose calls it again (a **recomposition**) and
updates only what changed. You never "set" a checkbox; you redraw with `checked = true`.
See [RosterStep.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt).

### State hoisting
"Hoisting" state means moving it up and out of a composable, so the composable just
takes values and reports events. `MatchDayRoute` holds the link to the ViewModel and
passes plain values down, with the ViewModel seen only as an interface of actions:

```kotlin
fun MatchDayRoute(
    container: AppContainer,
    viewModel: MatchDayViewModel = viewModel(factory = MatchDayViewModel.factory(container)),
) {
    // collectAsStateWithLifecycle stops listening while the app is in the background.
    val state by viewModel.state.collectAsStateWithLifecycle()
    MatchDayScreen(state = state, actions = viewModel)
```
(Simplified: the closing brace is left out.) So `MatchDayScreen` can be previewed with
made-up data. See
[MatchDayScreen.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt).

### `ViewModel` and `StateFlow`
A `ViewModel` holds a screen's state and survives rotation, when the Activity itself is
rebuilt. A `StateFlow` is a value you can watch: it always has a current value and tells
watchers when it changes.

```kotlin
private val _state = MutableStateFlow(MatchDayUiState(players = emptyList(), loading = true, playDate = today(), today = today()))
val state: StateFlow<MatchDayUiState> = _state.asStateFlow()
```
(Simplified: the doc comment between the lines is left out.) Private and writable
inside, read-only outside. See
[MatchDayViewModel.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt).

### A step machine in one `StateFlow`
Match day has four screens, but there's no navigation library: the state has a `step`
field, an `enum class Step { ROSTER, GENERATE, MANUAL, CONFIRM }`, and the screen draws
whichever step it says:

```kotlin
when (state.step) {
    Step.ROSTER -> RosterStep(state, actions, innerPadding)
    Step.GENERATE -> PickSplitStep(state, actions, innerPadding)
    Step.MANUAL -> ManualStep(state, actions, innerPadding)
    Step.CONFIRM -> ConfirmStep(state, actions, innerPadding)
}
```
Moving between screens is just a new state, so it's tested like any other change. See
[MatchDayScreen.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayScreen.kt).

### `viewModelScope` and `Dispatchers.Default`
A coroutine is a lightweight task that can pause without blocking a thread.
`viewModelScope` runs coroutines that are cancelled when the ViewModel is cleared, and
`withContext` moves one block of work to another thread pool. `Dispatchers.Default` is
the pool for CPU-heavy work, so the main thread stays free to draw.

```kotlin
generateJob = viewModelScope.launch {
    val result = withContext(generateDispatcher) { Balancer.balance(roster, locks, k = OPTIONS) }
```
(Simplified: the rest of the block is left out. `generateDispatcher` defaults to
`Dispatchers.Default`.) See
[MatchDayViewModel.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/MatchDayViewModel.kt).

### Material 3 theming
Material 3 is Google's design system. `MaterialTheme` hands colours, text styles and
corner shapes to everything inside it, so components pick them up without being told:

```kotlin
MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    typography = TeamBayesMaterialTypography,
    shapes = TeamBayesMaterialShapes,
    content = content,
)
```
See [Theme.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt).

### Bundled fonts and `FontFamily`
Font files placed in `res/font` ship inside the app, so nothing is downloaded. A
`FontFamily` groups the files of one typeface, and Compose picks the file that matches
the weight and style a text style asks for:

```kotlin
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)
```
`R.font.instrument_serif_regular` is the id Android generates for the file. See
[Type.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Type.kt).

### A `CompositionLocal` for extra colours
A `CompositionLocal` is a value that every composable inside a provider can read,
without passing it through every function. TeamBayes uses one for the colours Material
has no slot for, such as amber:

```kotlin
private val LocalTeamBayesColors = staticCompositionLocalOf { DarkTeamBayesColors }

val MaterialTheme.teamBayesColors: TeamBayesColors
    @Composable @ReadOnlyComposable get() = LocalTeamBayesColors.current
```
(Simplified: the doc comment is left out.) The theme provides the light or dark set, and
screens write `MaterialTheme.teamBayesColors.amber`. Same file as Material 3 theming.

### `Modifier.weight` and scrolling one part of a screen
In a `Column`, `Modifier.weight(1f)` gives a child whatever height the others leave over.
Put a scrolling list in that child and only the list scrolls; everything above and below
it stays where it is. That's how every match-day step keeps its buttons on screen:

```kotlin
Box(
    modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clipToBounds()
        .semantics { isTraversalGroup = true },
) { body() }
```
(Simplified: the footer below it is left out.) The footer is measured first, and the
body fills the rest. `clipToBounds()` stops the scrolled content drawing over the footer.
See
[TeamComponents.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/TeamComponents.kt).

### Semantics for TalkBack
TalkBack is Android's screen reader. It doesn't see pixels; it reads **semantics**, a
description of each element. Modifiers add to it: `toggleable` makes a row one checkbox,
`heading()` marks a title, and a polite `liveRegion` is announced when it changes.

```kotlin
.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
```
See [RosterStep.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/RosterStep.kt).

### `selectableGroup` and radio semantics
To TalkBack, three cards where exactly one is chosen should sound like radio buttons.
`selectable(role = Role.RadioButton)` makes each card one choice, and `selectableGroup()`
on their parent says they belong together, so TalkBack can say "1 of 3":

```kotlin
.selectable(
    selected = chosen,
    enabled = enabled,
    role = Role.RadioButton,
    onClick = { actions.selectSplit(index) },
),
```
(Simplified: the card around it is left out.) See
[PickSplitStep.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/PickSplitStep.kt).

### JVM tests vs instrumented tests
`app/src/test` holds **JVM tests**: they run on the laptop in seconds, with no phone,
but they have no real Android. `app/src/androidTest` holds **instrumented tests**: they
install on a phone or emulator and can drive the real screen.

```kotlin
// JVM unit tests (app/src/test).
testImplementation(libs.junit)
// Instrumented tests, which run on a phone (app/src/androidTest).
androidTestImplementation(libs.androidx.test.runner)
```
(Simplified: other test libraries are left out.) Run them with `./gradlew test` and
`./gradlew :app:connectedDebugAndroidTest`. See
[app/build.gradle.kts](../app/build.gradle.kts).

## Milestone 3: saving games

This milestone stores players and games on the phone, so the app remembers the group,
records results and survives being killed. The full tour is in
[walkthrough/03-saving-games.md](walkthrough/03-saving-games.md).

### Room: entities, DAOs and the database
Room is Google's library over SQLite. An **entity** is a class that describes a table, a
**DAO** (data access object) is an interface of queries, and a `@Database` class ties
them together. Room writes the SQL plumbing for you and checks every query when the app
builds.

```kotlin
@Entity(
    tableName = "players",
    indices = [Index(value = ["name"], unique = true)],
)
data class PlayerEntity(
    @PrimaryKey val id: String,
```
(Simplified: the other columns are left out.) See
[Entities.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Entities.kt) and
[Daos.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/data/db/Daos.kt).

### KSP: code that writes code
KSP (Kotlin Symbol Processing) is a compiler plugin that reads annotations such as
`@Entity` and `@Dao` and generates Kotlin from them while the app builds. Room's code
generator runs through it, which is why it's added with `ksp(...)` rather than
`implementation(...)`:

```kotlin
ksp(libs.androidx.room.compiler)
```
The generated code never appears in the source tree, but it's real Kotlin, compiled with
the rest. See [app/build.gradle.kts](../app/build.gradle.kts).

### `Flow` from a database
A DAO query can return a `Flow`. Room then re-runs it whenever a table it reads changes,
and sends the new answer to whoever is collecting. Screens never ask "has anything
changed?"; the change arrives.

```kotlin
override val players: Flow<List<StoredPlayer>> =
    dao.observeAll().map { rows -> rows.map { it.toStoredPlayer() } }
```
`map` turns each answer from database rows into the app's own type on the way past. See
[Players.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/data/Players.kt).

### Transactions
A transaction is a group of database statements that happens completely or not at all.
If the phone dies half-way, the database rolls back to before it started:

```kotlin
database.withWriteTransaction {
    // The appearances go with it: the foreign key cascades the delete.
    games.getPending()?.let { games.deletePendingGame(it.id) }
}
```
Every write in TeamBayes's repositories runs inside one, so a game is never saved without
its players. See [Games.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/data/Games.kt).

### The `Application` class
Android creates one `Application` object per app process, before any screen, and keeps it
until the process ends. It's the natural home for things the whole app shares, such as the
database. The manifest names it with `android:name`.

```kotlin
override fun onCreate() {
    super.onCreate()
    container = createContainer()
}
```
(Simplified: the class around it is left out.) See
[TeamBayesApplication.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/TeamBayesApplication.kt).

### `SavedStateHandle` in depth
A ViewModel survives rotation, but not **process death**: Android killing a backgrounded
app to free memory, then restarting it when the user comes back. A `SavedStateHandle` is
a small bundle of plain values (strings, numbers, booleans, lists of strings) that Android
keeps across that. Write the state into it on every change, and read it back when the
ViewModel is created:

```kotlin
private fun change(transform: (FirstRunUiState) -> FirstRunUiState) {
    val new = transform(_state.value)
    _state.value = new
    savedStateHandle[KEY_STEP] = new.step.name
    savedStateHandle[KEY_NAMES] = ArrayList(new.names) // A Bundle can hold an ArrayList, not any List.
```
(Simplified: two more keys and the closing brace are left out.) Keep it small: ids and
choices, not data that storage can give back. The ViewModel gets its handle from a factory
calling `createSavedStateHandle()`. See
[FirstRunViewModel.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/firstrun/FirstRunViewModel.kt).

### `rememberSaveable`
`remember` keeps a value across redraws. `rememberSaveable` also keeps it across rotation
and process death, by putting it in the same kind of bundle. It suits small UI-only state
that has no ViewModel, such as whether a date picker is open:

```kotlin
var picking by rememberSaveable { mutableStateOf(false) }
```
See [ConfirmStep.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/matchday/ConfirmStep.kt).

### A custom test runner
Instrumented tests are started by a **test runner**, named in the build file. A custom one
can change how the app starts under test. TeamBayes's swaps in a test `Application` whose
database lives in memory, so no test can touch the phone's real data:

```kotlin
class TeamBayesTestRunner : AndroidJUnitRunner() {
    override fun newApplication(classLoader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(classLoader, TestTeamBayesApplication::class.java.name, context)
}
```
See [TeamBayesTestRunner.kt](../app/src/androidTest/kotlin/io/github/valnrys/teambayes/TeamBayesTestRunner.kt).

### Which tests run where
Room and SQLite need Android, so tests of the real database are **instrumented tests**: they
run on a phone or emulator, each on a fresh in-memory database. Everything above storage,
such as the ViewModels and the result rules, runs as **JVM tests** on the laptop against
in-memory fakes, in seconds.

```kotlin
db = Room.inMemoryDatabaseBuilder(context, TeamBayesDatabase::class.java)
    .setDriver(BundledSQLiteDriver())
    .build()
```
An in-memory database disappears when it's closed, so each test starts empty. See
[DatabaseTestSupport.kt](../app/src/androidTest/kotlin/io/github/valnrys/teambayes/data/DatabaseTestSupport.kt).
