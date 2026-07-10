# Repository Guidelines

## Project Structure & Module Organization
Modules are `app`, `utils`, and `buildSrc`. `app/src/main/kotlin` is the entry point, `utils/src/main/kotlin` shared code, and `utils/src/test/kotlin` tests. Build logic lives in `buildSrc/src/main/kotlin`; versions in `gradle/libs.versions.toml`. As mission-recorder grows, split capture core, audio, settings, Compose UI, and native interop into modules or KMP source sets.

## Build, Test, and Development Commands
Use the Gradle Wrapper.

- `./gradlew run` runs the CLI app.
- `./gradlew build` compiles modules.
- `./gradlew check` runs tests.
- `./gradlew clean` removes outputs.

On Windows, use `.\gradlew.bat`.

## Kotlin Style & Concurrency
Follow official Kotlin conventions: 4-space indentation, packages under `io.aequicor`, `PascalCase` classes, and `camelCase` members. Keep structured concurrency, make suspend work cancellable, expose continuous state as `Flow`, and use `StateFlow` or `SharedFlow` for UI state/events.

## Constraints & Prohibitions
Do not use `GlobalScope`. Do not hardcode `Dispatchers.IO`, `Dispatchers.Default`, or `Dispatchers.Main` in business logic; inject `CoroutineDispatcher` or `CoroutineScope` through constructors and wire production values at module boundaries. Do not use `Thread.sleep`. Do not leak native handles or device APIs into shared domain code.

## Kotlin Coroutines Tests
Use `kotlinx-coroutines-test` for suspend, `Flow`, and timing behavior. Write `fun recordsFrames() = runTest { ... }`. Prefer `StandardTestDispatcher`; use `UnconfinedTestDispatcher` only for eager execution. Use `advanceUntilIdle`, `runCurrent`, or `advanceTimeBy`, not sleeps. Override/reset `Dispatchers.Main` with a shared UI test rule.

## Compose & GUI Guidelines
Use lapis lazuli GUI tone: primary `#26619C`, neutral surfaces, restrained accents. Build stateless Compose screens, hoist state, add semantics, avoid expensive recomposition, and use stable list keys. Use Google Fonts Material Symbols for icons. Store strings, images, and fonts in Compose Multiplatform resources.

## Native Interop
Keep JNI and Kotlin/Native interop behind small platform adapters. Use JNI for JVM/Android calls and Kotlin/Native `cinterop` for native targets. Document ownership, threading, permissions, cleanup, and error mapping for capture APIs.

## Testing Guidelines
Use `kotlin.test` on JUnit Platform. Place tests under each module's `src/test/kotlin` tree and name classes with a `Test` suffix. Cover settings, cancellation, buffers, and platform-independent capture state. Prefer fakes over real devices.

## Commit & Pull Request Guidelines
Git history contains only an initial commit, so use short imperative messages such as `Add capture settings model`. Pull requests need a summary, tests run, affected platforms, and screenshots or recordings for Compose or capture-flow changes.

## Security & Configuration Tips
Do not commit recordings, screenshots, secrets, IDE files, or machine paths. Document screen, microphone, and system-audio permissions.
