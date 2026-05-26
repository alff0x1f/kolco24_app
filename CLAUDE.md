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
- `MainActivity.kt` — entry point; owns tab selection state + `showScan: Boolean`; renders `Scaffold` + `NavigationBar` with three screens; ScanScreen overlaid as a full-screen composable when `showScan = true`
- `ui/marks/MarksScreen.kt` — Отметки tab; orange FAB triggers ScanScreen overlay via `onScanClick` callback
- `ui/legend/LegendScreen.kt` — Легенда tab; bold dark FilterChips, no trailing icon for unvisited CPs
- `ui/team/TeamScreen.kt` — Команда tab; outlined "Привязать" button, simplified avatars
- `ui/scan/ScanScreen.kt` — full-screen overlay for NFC checkpoint marking (A3 screen)
- `ui/theme/Color.kt` — full M3 light/dark token set; cool-grey light palette; `OrangeCta = #C65A2E`
- `ui/theme/Theme.kt` — `Kolco24Theme`; dynamic color is **off by default** (`dynamicColor = false`) so brand palette is always used; dynamic path requires `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` gate
- `ui/theme/Type.kt` — typography (default system font for now)

**Design reference:** `assets/template_v2.html` — hi-fi HTML mockup (M3 cool-grey variant). Screens: A1b (Отметки), A2 (Легенда), A3 (Отметить КП scan dialog), A4 (Команда).

**M3 color tokens used in the design** (defined in `Color.kt`):
- `primary` #B3261E · `primaryContainer` #FFDAD5 · `onPrimaryContainer` #410002
- `OrangeCta` #C65A2E — FABs, active NavigationBar icon/text, NFC icon tint (not a theme token — used directly)
- `tertiary` #1F7A3D — success/green (КП taken state)
- `inverseSurface` #1D242D — dark hero cards (timer card in ScanScreen)
- `surfaceContainer` #FFFFFF — NavigationBar background (cool white; mapped from `SurfaceContainerDefault` in Color.kt)
- `outlineVariant` #E2E6EB — cool divider/border color

**Scan overlay pattern:**
`MainActivity` wraps `Scaffold` in a `Box(Modifier.fillMaxSize())`. The `ScanScreen` is rendered after the Scaffold inside the same Box — when `showScan = true` it covers the entire screen. Dismiss via the close button or system back (handled by `BackHandler`) sets `showScan = false`. No navigation component used.

**App assets:**
- `app/src/main/assets/adi-registration.properties` — ADI registration token (tracked, do not expose value)
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — separate monochrome icon for Android 13+ themed icons (no white fills; alpha-mask only)
