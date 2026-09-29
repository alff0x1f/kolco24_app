# Repository Guidelines

## Project Structure & Module Organization

This single-module Android app uses Kotlin, Jetpack Compose, and Room. Sources live in
`app/src/main/java/ru/kolco24/kolco24/`: `ui/` contains screens, `data/` contains repositories,
API clients, persistence, NFC, and tracking logic. Resources and bundled assets live in
`app/src/main/res/` and `app/src/main/assets/`. JVM tests are in `app/src/test/`; device tests
are in `app/src/androidTest/`. Exported Room schemas are committed under `app/schemas/`.
The web server is a separate repository at `../server` (`~/src/kolco24/server`).

## Build, Test, and Development Commands

Use Android Studio and the checked-in Gradle wrapper; the daemon toolchain uses JDK 21.

- `./gradlew assembleDebug` — build the debug APK.
- `./gradlew installDebug` — install on a connected emulator or device; launch from its launcher.
- `./gradlew lintDebug` — run Android Lint; required before merging.
- `./gradlew testDebugUnitTest` — run JVM unit tests; required before merging.
- `./gradlew connectedDebugAndroidTest` — validate DAO queries and Room migrations on a device.

## Coding Style & Naming Conventions

Use four-space indentation, PascalCase for classes/files, camelCase for functions/properties,
and UPPER_SNAKE_CASE for constants. Match surrounding Kotlin and Compose conventions.
Android Lint is configured; no dedicated Kotlin formatter is configured.
Keep pure logic Android-free. State is hosted in `MainActivity.kt` through `rememberSaveable`;
follow existing overlay patterns. AGP provides Kotlin support; do not add `kotlin.android`.
Minimum SDK is 24 with no desugaring: use `SimpleDateFormat`, not `java.time`.

## Testing Guidelines

Use JUnit 4, MockWebServer, and coroutine test utilities. Mirror production packages and name
classes `*Test`; use descriptive methods such as `parse_unknownOrMissing_isOffline`.
Test pure models, mappers, crypto, and trust boundaries. Compose UI, Android adapters, and
trivial wiring are generally untested. No numerical coverage target is configured.
Room changes require a registered migration, an exported schema JSON, and migration validation.

## Commit & Pull Request Guidelines

Follow history with short messages such as `feat: add member provisioning`, `fix: correct
upload routing`, or `docs: update server path`. Describe the problem, resulting behavior,
and validation in PRs. Link relevant issues and include screenshots for visible UI changes.
Read `CLAUDE.md` and the relevant `docs/design/UI-NOTES.md` or `DATA-NOTES.md` before changing
an area; update design notes when behavior changes.

## Security & Configuration Tips

Keep credentials in ignored `local.properties`: `kolco24.apiBaseUrl`, `kolco24.appKeyId`,
and `kolco24.appSecret`, or their `KOLCO24_*` environment equivalents in `app/build.gradle.kts`.
Never commit secrets. Changing the LAN API host requires updating
`app/src/main/res/xml/network_security_config.xml` together with configuration.
