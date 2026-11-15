# TeamBayes

An offline Android app that picks balanced 5-a-side teams and learns from results.

**Status:** the rating engine (Kotlin, checked against openskill reference fixtures) is done; the Android app is next. See [docs/model.md](docs/model.md) for how it works and [docs/walkthrough/01-engine.md](docs/walkthrough/01-engine.md) for a tour of the code.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

## Run the engine tests

```sh
./gradlew :engine:test
```
