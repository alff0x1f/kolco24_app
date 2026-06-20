# Theme Switching (Light / Dark / System) in Settings

## Overview
- Add a user-controlled app theme with three modes: **Системная** (follow OS), **Светлая**, **Тёмная**.
- Solves: the app currently hardcodes `darkTheme = isSystemInDarkTheme()` in `Kolco24Theme`, so users cannot override the system setting.
- Integrates via a new «Внешний вид» card in the existing `SettingsScreen` overlay; the choice is persisted in SharedPreferences and applied at `MainActivity.setContent` through the already-parameterized `Kolco24Theme(darkTheme: Boolean)`.

## Context (from discovery)
- Files/components involved:
  - `app/src/main/java/ru/kolco24/kolco24/ui/theme/Theme.kt` — `Kolco24Theme(darkTheme: Boolean = isSystemInDarkTheme(), content)`. Already parameterized; no signature change.
  - `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt` — `onCreate` `setContent { Kolco24Theme { Kolco24AppRoot() } }` (~L137-141); `Kolco24AppRoot()` (~L276) already does `val container = remember { (context.applicationContext as Kolco24App).container }` (~L287); `SettingsScreen` is rendered from inside `Kolco24AppRoot`'s overlay `Box`.
  - `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt` — stateless full-screen `Column` overlay; row-based cards (`ChangeTeamRow`, `DebugRow` patterns).
  - `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt` — manual DI; constructor is `class AppContainer(private val context: Context)`; members are `by lazy`.
  - `app/src/main/java/ru/kolco24/kolco24/data/InstallId.kt` — reference pattern: pure `getOrCreate(load, save)` + `fromSharedPreferences(context)` adapter using `getSharedPreferences(MODE_PRIVATE)`.
- Related patterns found:
  - Pure, Android-free, JVM-testable helpers (`ui/legend/CheckpointColor.kt`, `data/NfcUid.kt`) with co-located unit tests in `app/src/test`.
  - Stateless Compose screens with hoisted state + callbacks; transient UI state via local `remember`.
  - Inline Russian string literals (no `strings.xml` for these screens).
- Dependencies identified: none new. Uses `kotlinx.coroutines` `StateFlow` (already present) and Compose `collectAsState` (already present).

## Development Approach
- **Testing approach**: Regular (code first, then tests) — chosen because the design and signatures are already validated in the brainstorm; pure logic is small and well-defined.
- Complete each task fully before moving to the next.
- Make small, focused changes.
- **Every task includes new/updated tests** for the code it changes (success + edge cases).
- **All tests must pass before starting the next task.**
- Update this plan file if scope changes during implementation.
- Maintain backward compatibility: default mode is `SYSTEM`, preserving today's behavior for existing installs.

## Testing Strategy
- **Unit tests** (`app/src/test`, pure JVM):
  - `ThemeModeTest` — `isDark` across the 3 modes × `systemDark` true/false; `parseThemeMode` null→SYSTEM, unknown→SYSTEM, round-trip of each enum name.
  - `ThemePreferenceTest` — fake in-memory `load`/`save`: defaults to SYSTEM when store empty; `setMode` calls `save` with the enum name and emits the new value on the `StateFlow`.
- **E2E tests**: project has no Playwright/Cypress-style UI e2e harness; the Compose UI (`SettingsScreen` card/dialog, `MainActivity` wiring) is verified manually (see Post-Completion), consistent with the existing untested-host-UI convention.

## Progress Tracking
- Mark completed items with `[x]` immediately when done.
- Add newly discovered tasks with ➕ prefix.
- Document issues/blockers with ⚠️ prefix.
- Keep the plan in sync with actual work.

## Solution Overview
- **Theme model** (`ui/theme/ThemeMode.kt`, pure): `enum ThemeMode { SYSTEM, LIGHT, DARK }`, `ThemeMode.isDark(systemDark)`, `parseThemeMode(raw)`.
- **Persistence** (`data/ThemePreference.kt`): holds a `MutableStateFlow<ThemeMode>` seeded by a **synchronous** `load()` at construction (no theme flash on cold start); `setMode` updates the flow and persists via `save`. `fromSharedPreferences(context)` adapter backs it with a `"kolco24.settings"` prefs file, key `"theme_mode"` — mirroring `InstallId`.
- **DI**: `AppContainer` exposes `val themePreference by lazy { ThemePreference.fromSharedPreferences(context) }`.
- **Application (single collection point)**: inside the `setContent { }` lambda, resolve the container via the existing idiom `val container = remember { (applicationContext as Kolco24App).container }`, collect the flow once `val mode by container.themePreference.mode.collectAsState()`, and wrap with `Kolco24Theme(darkTheme = mode.isDark(isSystemInDarkTheme())) { Kolco24AppRoot(themeMode = mode, onThemeModeChange = { container.themePreference.setMode(it) }) }`. This is the **only** place the flow is subscribed — `themeMode`/`onThemeModeChange` are threaded down as params, avoiding a second `collectAsState` and the scope mismatch with the container reference already resolved inside `Kolco24AppRoot`.
- **UI**: `Kolco24AppRoot` gains `themeMode: ThemeMode` + `onThemeModeChange: (ThemeMode) -> Unit` params and forwards them to `SettingsScreen`, which gains the same two params and renders a «Внешний вид» card whose `ThemeRow` opens a private `ThemeDialog` (3 `RadioButton` rows).

## Technical Details
- `parseThemeMode(raw: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM`.
- `ThemeMode.isDark(systemDark)`: SYSTEM→`systemDark`, LIGHT→`false`, DARK→`true`.
- `ThemePreference(load: () -> String?, save: (String) -> Unit)`: `private val _mode = MutableStateFlow(parseThemeMode(load()))`; `val mode: StateFlow<ThemeMode> = _mode.asStateFlow()`; `fun setMode(m) { _mode.value = m; save(m.name) }`.
- Reactivity: `MutableStateFlow` current value is read synchronously at construction → `collectAsState()` initial frame is correct (no flash). The `setContent` lambda accesses the container via the established idiom `applicationContext as Kolco24App` (same as `Kolco24AppRoot`), not `application as`.
- Label mapping (inline RU): SYSTEM→«Системная», LIGHT→«Светлая», DARK→«Тёмная»; subtitle of `ThemeRow` shows the current label.
- minSdk 24: no `java.time`, no new APIs. `ThemeMode.entries` is available (project is on Kotlin 2.2.10).

## What Goes Where
- **Implementation Steps** (`[ ]`): all code + unit tests in this repo.
- **Post-Completion** (no checkboxes): manual on-device verification of theme switching + persistence across restarts.

## Implementation Steps

### Task 1: Pure theme model

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/theme/ThemeMode.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/ui/theme/ThemeModeTest.kt`

- [x] create `ThemeMode.kt` with `enum class ThemeMode { SYSTEM, LIGHT, DARK }` (no Android imports)
- [x] add `fun ThemeMode.isDark(systemDark: Boolean): Boolean` (SYSTEM→systemDark, LIGHT→false, DARK→true)
- [x] add `fun parseThemeMode(raw: String?): ThemeMode` defaulting to SYSTEM for null/unknown
- [x] write `ThemeModeTest`: `isDark` across 3 modes × systemDark true/false (6 cases)
- [x] write `ThemeModeTest`: `parseThemeMode` null→SYSTEM, unknown→SYSTEM, round-trip each enum name
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 2: ThemePreference (SharedPreferences-backed reactive store)

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/data/ThemePreference.kt`
- Create: `app/src/test/java/ru/kolco24/kolco24/data/ThemePreferenceTest.kt`

- [x] create `ThemePreference(load: () -> String?, save: (String) -> Unit)` holding `MutableStateFlow(parseThemeMode(load()))` exposed as `val mode: StateFlow<ThemeMode>`
- [x] add `fun setMode(m: ThemeMode) { _mode.value = m; save(m.name) }`
- [x] add `companion object { fun fromSharedPreferences(context: Context): ThemePreference }` using prefs file `"kolco24.settings"`, key `"theme_mode"`, `MODE_PRIVATE`, `apply()` (mirror `InstallId`)
- [x] write `ThemePreferenceTest` with a fake in-memory store: defaults to SYSTEM when empty; pre-seeded value parses on init
- [x] write `ThemePreferenceTest`: `setMode` calls `save` with the enum name and emits the new value on `mode`
- [x] run `./gradlew testDebugUnitTest` — must pass before next task

### Task 3: Expose ThemePreference via AppContainer + apply in MainActivity

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/AppContainer.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] add `val themePreference: ThemePreference by lazy { ThemePreference.fromSharedPreferences(context) }` to `AppContainer`
- [x] in the `setContent { }` lambda, add `val container = remember { (applicationContext as Kolco24App).container }` and `val mode by container.themePreference.mode.collectAsState()` (single subscription point)
- [x] pass `darkTheme = mode.isDark(isSystemInDarkTheme())` to `Kolco24Theme` (no change to `Theme.kt`)
- [x] add `themeMode: ThemeMode` + `onThemeModeChange: (ThemeMode) -> Unit` params to `Kolco24AppRoot` and pass `themeMode = mode`, `onThemeModeChange = { container.themePreference.setMode(it) }` from `setContent`
- [x] verify no behavior change at default (SYSTEM still follows the OS) — default mode is SYSTEM, `isDark(systemDark)` returns `systemDark`, matching the prior `isSystemInDarkTheme()` behavior
- [x] (no new unit tests — wiring only; covered by Task 1/2 logic tests and manual verification) — note rationale inline
- [x] run `./gradlew testDebugUnitTest` and `./gradlew lintDebug` — must pass before next task

### Task 4: Settings UI — «Внешний вид» card, ThemeRow, ThemeDialog

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] add params `themeMode: ThemeMode` and `onThemeModeChange: (ThemeMode) -> Unit` to `SettingsScreen`
- [x] add «Внешний вид» `Surface` card with a `ThemeRow` (`Icons.Filled.Palette` avatar, title «Тема», subtitle = current mode label, chevron), mirroring `ChangeTeamRow` styling
- [x] add a private `ThemeDialog` (`AlertDialog`, 3 `RadioButton` rows: Системная/Светлая/Тёмная) toggled by a local `var showThemeDialog by remember { mutableStateOf(false) }`; selecting an option calls `onThemeModeChange` and closes the dialog
- [x] forward `Kolco24AppRoot`'s `themeMode`/`onThemeModeChange` params (added in Task 3) into the `SettingsScreen(...)` call — no new `collectAsState` here (single subscription stays at `setContent`)
- [x] (no unit tests — Compose host UI is untested per project convention; the `themeModeLabel` helper is a trivial inline `when`, not extracted to the pure model)
- [x] run `./gradlew testDebugUnitTest` and `./gradlew lintDebug` — must pass before next task

### Task 5: Verify acceptance criteria
- [x] verify all three modes apply correctly and override the system setting (manual on-device test — skipped, not automatable; logic covered by ThemeModeTest)
- [x] verify default (fresh install / empty prefs) is SYSTEM — no behavior regression (manual on-device test — skipped, not automatable; covered by ThemePreferenceTest default-empty case)
- [x] verify chosen mode survives process death / app restart (synchronous read, no flash) (manual on-device test — skipped, not automatable; covered by ThemePreferenceTest pre-seeded case)
- [x] run full unit suite: `./gradlew testDebugUnitTest` — passed
- [x] run lint: `./gradlew lintDebug` — passed

### Task 6: [Final] Update documentation
- [x] update `CLAUDE.md`: document `ThemeMode.kt`, `ThemePreference.kt`, the `AppContainer.themePreference` member, the `MainActivity` theme application, and the new `SettingsScreen` «Внешний вид» card/params
- [x] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems — informational only*

**Manual verification:**
- On a device/emulator: open Настройки → Внешний вид → Тема, pick each of Системная/Светлая/Тёмная and confirm the UI re-themes immediately.
- Toggle the OS dark mode while in «Системная» and confirm the app follows it.
- Set «Тёмная», kill and relaunch the app — confirm it reopens dark with no light-theme flash on the first frame.
