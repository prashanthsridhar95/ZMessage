# Contributing

## Setup

JDK 17 and Android Studio (or the Android SDK with `ANDROID_HOME` set). Then:

```bash
./gradlew :domain:test           # pure Kotlin; no Android SDK needed beyond configuration
./gradlew :app:testDebugUnitTest
./gradlew detekt                 # must report zero issues
./gradlew :app:installDebug      # on a connected device
```

## Layout and rules

- `:domain` is pure Kotlin and must stay free of Android types. The compiler enforces it.
- Presentation follows MVI: a `Contract` (State, Intent, Effect), a `ViewModel` that reduces intents, and
  stateless composables that take `(State, (Intent) -> Unit)`.
- Keep Compose screens stateless and previewable; side effects live in the `*Route` composables.
- See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Teaching the parsers a new SMS format

1. Take a real message and mask it (Settings → Parsing health → Copy report does this).
2. Add it as a test in `GenericTransactionParserTest` (or the matching parser test) first.
3. Make the smallest change in `domain/classification` that passes it, then run the whole suite. The
   fuzz tests in `ClassifierRobustnessTest` guard against crashes and slow patterns.

## Pull requests

Small, focused, with tests. CI runs unit tests, detekt, lint and builds a debug APK.
Never commit real SMS content, account numbers, keystores or `local.properties`.
