# TeamBayes

An offline Android app that picks balanced 5-a-side teams and learns from results.

**Status:** the rating engine (Kotlin, checked against openskill reference fixtures) is done, and the Android app stores its players and games on the phone. First run asks for the players and a rough ranking; match day picks one of three proposed Bibs / Non-bibs splits or makes teams by hand, saves the game, records goals as they go in, and shows how the result moved everyone's Skill. An unfinished game survives the app being closed. See [docs/model.md](docs/model.md) for how it works, and the walkthroughs of the [engine](docs/walkthrough/01-engine.md), [match day](docs/walkthrough/02-match-day.md) and [saving games](docs/walkthrough/03-saving-games.md) for a tour of the code.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

## Modules

- `:engine`: the pure Kotlin rating and team-balancing engine. No Android, no runtime dependencies.
- `:app`: the Android app (Jetpack Compose, Material 3). It depends on `:engine`.

## Build and install the app

You need the Android SDK with platform 37 (`compileSdk 37`), either through Android Studio or the command-line tools, and JDK 21. Point `local.properties` at the SDK (`sdk.dir=/path/to/sdk`), or set `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug   # builds the APK
./gradlew :app:installDebug    # installs it on a connected phone or emulator
```

TeamBayes is offline by design. Every app build (debug and release) checks the final merged manifest with `verifyAllowedPermissions` and fails if the app asks for any permission that isn't on a short allowlist. INTERNET is not on it. The instrumented-test APK has its own manifest, which isn't checked.

## Run the tests

```sh
./gradlew test                          # engine and app unit tests
./gradlew :app:connectedDebugAndroidTest # needs an emulator or a spare phone; includes an on-device timing check
```

Run the instrumented tests on an emulator or a spare phone, never on the phone that holds your group's games. They use an in-memory database and leave the app installed, but installing a debug build over your real app could still cost data.
