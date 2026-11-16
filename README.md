# TeamBayes

An offline Android app that picks balanced 5-a-side teams and learns from results.

**Status:** the rating engine (Kotlin, checked against openskill reference fixtures) is done, and the Android app runs match day: tick who's playing, pick one of three proposed Bibs / Non-bibs splits or make teams by hand, adjust, and confirm. The app has no storage yet, so confirming doesn't save. See [docs/model.md](docs/model.md) for how it works, and the walkthroughs of the [engine](docs/walkthrough/01-engine.md) and of [match day](docs/walkthrough/02-match-day.md) for a tour of the code.

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
./gradlew :app:connectedDebugAndroidTest # needs a connected phone; includes an on-device timing check
```
