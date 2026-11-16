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

## Milestone 2: the first screen on a phone

This milestone adds the Android app: one screen that ticks who's playing and balances
them with the engine from milestone 1. The full tour is in
[walkthrough/02-first-screen.md](walkthrough/02-first-screen.md).

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
        TonightRoute()
    }
}
```
See [MainActivity.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/MainActivity.kt).

### Composable functions and recomposition
A function marked `@Composable` describes part of the screen from its arguments. It
doesn't return anything; it emits UI.

```kotlin
@Composable
private fun PlayerRow(name: String, ticked: Boolean, onToggle: () -> Unit) {
```
When the state it read changes, Compose calls it again (a **recomposition**) and
updates only what changed. You never "set" a checkbox; you redraw with `ticked = true`.
See [TonightScreen.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt).

### State hoisting
"Hoisting" state means moving it up and out of a composable, so the composable just
takes values and reports events. `TonightRoute` holds the link to the ViewModel and
passes plain values down:

```kotlin
TonightScreen(
    state = state,
    onToggle = viewModel::toggle,
    onBalance = viewModel::balance,
    onReroll = viewModel::reroll,
)
```
So `TonightScreen` can be previewed and tested with made-up data. Same file as above.

### `ViewModel` and `StateFlow`
A `ViewModel` holds a screen's state and survives rotation, when the Activity itself is
rebuilt. A `StateFlow` is a value you can watch: it always has a current value and tells
watchers when it changes.

```kotlin
private val _state = MutableStateFlow(TonightUiState(players, initiallyTicked))
val state: StateFlow<TonightUiState> = _state.asStateFlow()
```
(Simplified: the doc comment between the lines is left out.) Private and writable
inside, read-only outside. See
[TonightViewModel.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightViewModel.kt).

### `viewModelScope` and `Dispatchers.Default`
A coroutine is a lightweight task that can pause without blocking a thread.
`viewModelScope` runs coroutines that are cancelled when the ViewModel is cleared, and
`withContext` moves one block of work to another thread pool. `Dispatchers.Default` is
the pool for CPU-heavy work, so the main thread stays free to draw.

```kotlin
balanceJob = viewModelScope.launch {
    val outcome = withContext(balanceDispatcher) { Balancer.balance(roster, k = KEPT_SPLITS) }
```
(Simplified: the rest of the block is left out. `balanceDispatcher` defaults to
`Dispatchers.Default`.) Same file as above.

### Material 3 theming
Material 3 is Google's design system. `MaterialTheme` hands a colour scheme and text
styles to everything inside it, so components pick them up without being told:

```kotlin
MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    content = content,
)
```
See [Theme.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/ui/theme/Theme.kt).

### Semantics for TalkBack
TalkBack is Android's screen reader. It doesn't see pixels; it reads **semantics**, a
description of each element. Modifiers add to it: `toggleable` makes a row one checkbox,
`heading()` marks a title, and a polite `liveRegion` is announced when it changes.

```kotlin
.toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle() }),
```
See [TonightScreen.kt](../app/src/main/kotlin/io/github/valnrys/teambayes/tonight/TonightScreen.kt).

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
