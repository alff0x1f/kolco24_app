# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
./gradlew assembleDebug        # build debug APK
./gradlew assembleRelease      # build release APK
./gradlew lintDebug            # run lint (must pass before merging)
./gradlew testDebugUnitTest    # run unit tests
```

## Architecture

Single-activity Jetpack Compose app (minSdk 24, targetSdk 36). No ViewModel, no Navigation Compose — screen state lives in `MainActivity.kt` via `rememberSaveable`.

**Current structure:**
- `MainActivity.kt` — entry point; owns tab selection state; renders `Scaffold` + `NavigationBar` with three placeholder screens (Отметки, Легенда, Команда)
- `ui/theme/Color.kt` — full M3 light/dark token set seeded from brand red `#B3261E`
- `ui/theme/Theme.kt` — `Kolco24Theme`; dynamic color is **off by default** (`dynamicColor = false`) so brand palette is always used; dynamic path requires `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` gate
- `ui/theme/Type.kt` — typography (default system font for now)

**Design reference:** `assets/template.html` — hi-fi HTML mockup of all planned screens (open in browser). Screens are labelled A1b (Отметки), A2 (Легенда), A3 (Отметить КП scan dialog), A4 (Команда). The Material 3 variant is the target; the iOS-like variant is for comparison only.

**M3 color tokens used in the design** (defined in `Color.kt`):
- `primary` #B3261E · `primaryContainer` #FFDAD5 · `onPrimaryContainer` #410002
- `secondaryContainer` #FFDAD6 — used as NavigationBar pill indicator
- `tertiary` #1F7A3D — success/green (КП taken state)
- `inverseSurface` #362F2E — dark hero cards (timer, team hero)
- `surfaceContainer` #F4E7E4 — NavigationBar background

**App assets:**
- `app/src/main/assets/adi-registration.properties` — ADI registration token (tracked, do not expose value)
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — separate monochrome icon for Android 13+ themed icons (no white fills; alpha-mask only)
