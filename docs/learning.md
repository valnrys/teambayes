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
