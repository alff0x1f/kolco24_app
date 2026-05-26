# Template v2 M3 Design — All 4 Screens

## Overview

Update the Kolco24 Android Compose app to match the Material 3 "cool grey" variant from `assets/template_v2.html`.
The current app uses warm red-tonal surface colors and has placeholder-style CTAs; template_v2 switches to
cool-grey neutrals, orange CTAs, and adds a full-screen Scan dialog for marking checkpoints.

Changes:
- Replace warm palette with cool-grey neutrals in `Color.kt` / `Theme.kt`
- Restyle Navigation bar active indicator (orange, no pill)
- Update Отметки: orange FAB, white "Фото" button, transparent NFC banner
- Update Легенда: bold dark FilterChips, remove trailing icon for open CPs
- Update Команда: white "Привязать" button + orange NFC icon, simplify avatars
- Add new Scan screen (A3) as full-screen overlay in `MainActivity`

## Context (from discovery)

- **Language/framework**: Kotlin, Jetpack Compose, Material 3, minSdk 24
- **Key files**: `Color.kt`, `Theme.kt`, `MainActivity.kt`, `MarksScreen.kt`, `LegendScreen.kt`, `TeamScreen.kt`
- **Architecture**: single-activity, no Navigation Compose, no ViewModel — screen state in `MainActivity` via `rememberSaveable`
- **Reference**: M3 variant in `assets/template_v2.html` — `MarksTilesM3`, `LegendM3`, `ScanM3`, `TeamM3` components
- **New file**: `ui/scan/ScanScreen.kt` (no existing counterpart)

## Development Approach

- **Testing approach**: Regular (code first — this is pure UI, no business logic to unit-test)
- Complete each task fully before moving to the next
- Make small, focused changes; verify build compiles after each task
- Run `./gradlew lintDebug` after all tasks; run `./gradlew testDebugUnitTest` for any touched logic

## Solution Overview

1. Color palette updated in `Color.kt`; `Theme.kt` wire-up updated to match
2. `OrangeCta` added as a named color used in NavigationBarItemColors, FAB, and action buttons
3. Scan screen built as a standalone composable; shown via `var showScan` boolean state in `Kolco24App`
4. Each screen file updated independently — no shared components introduced (fits YAGNI)

## Implementation Steps

---

### Task 1: Update color palette (Color.kt)

Replace warm red-tonal surface tokens with cool-grey neutrals from `kolco24.com` reference.
Add `OrangeCta` for FABs and navigation.

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/theme/Color.kt`

- [x] Replace `SurfaceLight` with `Color(0xFFEEF0F3)` (cool grey, was `#FFFBFF`)
- [x] Replace `OnSurfaceLight` with `Color(0xFF161A1F)` (cool dark ink, was `#201A19`)
- [x] Replace `OnSurfaceVariantLight` with `Color(0xFF6B7785)` (cool muted, was `#534341`)
- [x] Replace `SurfaceContainerLow` with `Color(0xFFFFFFFF)` (white cards, was warm `#FAEAEB`)
- [x] Replace `SurfaceContainerDefault` with `Color(0xFFFFFFFF)` (nav bar white, was warm `#F4E7E4`)
- [x] Replace `SurfaceContainerHigh` with `Color(0xFFF4F6F9)` (was `#EEE1DE`)
- [x] Replace `SurfaceContainerHighest` with `Color(0xFFE8ECF1)` (was `#E8DCD9`)
- [x] Replace `OutlineLight` with `Color(0xFF6B7785)` (was `#857370`)
- [x] Replace `OutlineVariantLight` with `Color(0xFFE2E6EB)` (cool divider, was `#D8C2BE`)
- [x] Replace `InverseSurface` with `Color(0xFF1D242D)` (dark charcoal, was `#362F2E`)
- [x] Replace `InverseOnSurface` with `Color(0xFFF4F6F9)` (was `#FBEEBB`)
- [x] Add `val OrangeCta = Color(0xFFC65A2E)` — used for FABs and active nav
- [x] Verify `SurfaceContainerLowest` stays `Color(0xFFFFFFFF)` (already white, no change needed)
- [x] Keep primary (`#B3261E`), tertiary (`#1F7A3D`) and dark-scheme tokens unchanged

---

### Task 2: Update Theme.kt wire-up

`Theme.kt` references named color constants; verify nothing references renamed/removed constants and
the light scheme compiles cleanly.

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/theme/Theme.kt`

- [x] Confirm all `LightColorScheme` assignments still resolve (no compile errors after Task 1)
- [x] Remove `secondary`/`onSecondary`/`secondaryContainer`/`onSecondaryContainer` from `LightColorScheme`
  if unused (they are not referenced by any screen; keep if lint requires) — kept, still used by MarksScreen and TeamScreen
- [x] Run `./gradlew assembleDebug` — must succeed with zero errors before Task 3

---

### Task 3: Update MainActivity — NavigationBar + Scan overlay

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [x] Import `OrangeCta` from theme, plus `NavigationBarItemDefaults`, `Color`
- [x] Add `var showScan by rememberSaveable { mutableStateOf(false) }` in `Kolco24App`
- [x] Add custom `NavigationBarItemColors` helper (or inline): `indicatorColor = Color.Transparent`,
  `selectedIconColor = OrangeCta`, `selectedTextColor = OrangeCta`,
  `unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant`,
  `unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant`
- [x] Apply custom colors to all three `NavigationBarItem` calls via `colors =` parameter
- [x] Add `onScanClick: () -> Unit` parameter to `MarksScreen(...)` call — wire to `{ showScan = true }`
- [x] After the `Scaffold { ... }` block, add: `if (showScan) { ScanScreen(onClose = { showScan = false }) }`
  using `Box(Modifier.fillMaxSize())` overlay so it covers the scaffold
- [x] Run `./gradlew assembleDebug` — must compile (ScanScreen stub needed first, see Task 7)

---

### Task 4: Update MarksScreen

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/marks/MarksScreen.kt`

- [ ] Add `onScanClick: () -> Unit` parameter to `MarksScreen` composable
- [ ] Change `ExtendedFloatingActionButton` `containerColor` from `MaterialTheme.colorScheme.primary`
  to `OrangeCta`; set `contentColor = Color.White`
- [ ] Replace the `FilledTonalButton` "Фото" with an `OutlinedButton`-style button:
  `containerColor = MaterialTheme.colorScheme.surfaceContainerLowest`,
  `contentColor = MaterialTheme.colorScheme.onSurface`,
  `border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)`,
  icon tint = `OrangeCta`
- [ ] Wire `ExtendedFloatingActionButton` `onClick` to `onScanClick`
- [ ] Update `NfcBanner`: remove `Surface` wrapper with `tertiaryContainer` fill — change to a plain
  `Row` with transparent background; remove trailing NFC icon; keep animated green dot + text only
- [ ] Run `./gradlew assembleDebug` — must compile

---

### Task 5: Update LegendScreen

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/legend/LegendScreen.kt`

- [ ] Replace `FilterChip` for "Все" with a custom `Button`/`Surface`-based chip:
  - when selected: `background = MaterialTheme.colorScheme.onSurface` (dark fill), `contentColor = Color.White`,
    leading check icon (`Icons.Filled.Check`, 18dp), height 40dp, bold text
  - when unselected: transparent background, `border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)`,
    `contentColor = MaterialTheme.colorScheme.onSurface`
- [ ] Apply same custom chip style to "Не взятые" chip
- [ ] In `CheckpointRow`: remove the `else` branch that renders `RadioButtonUnchecked` for non-taken CPs
  (trailing icon should only appear when `cp.taken == true`)
- [ ] Run `./gradlew assembleDebug` — must compile

---

### Task 6: Update TeamScreen

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/ui/team/TeamScreen.kt`

- [ ] In `MemberRow`: replace `FilledTonalButton` "Привязать" with a button using
  `containerColor = MaterialTheme.colorScheme.surfaceContainerLowest`,
  `contentColor = MaterialTheme.colorScheme.onSurface`,
  `border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)`,
  NFC icon tint = `OrangeCta`
- [ ] In `MonogramAvatar`: remove the `isMe` parameter and all `isMe`-conditional logic;
  all bound members get `surfaceContainerHighest` background with `onSurface` text
- [ ] In `MemberRow` bound sub-row: replace the filled dot `Box` with `Icon(Icons.Filled.CheckCircle)`
  tinted `MaterialTheme.colorScheme.tertiary`, size 14dp
- [ ] Remove `isMe: Boolean = false` and `role: String? = null` from `TeamMember` data class
  (and all references in MOCK_MEMBERS and `MemberRow`)
- [ ] Run `./gradlew assembleDebug` — must compile

---

### Task 7: Add ScanScreen (A3 — Отметить КП)

New full-screen composable. Uses the same mock chip data as TeamScreen.

**Files:**
- Create: `app/src/main/java/ru/kolco24/kolco24/ui/scan/ScanScreen.kt`

- [ ] Create package `ru.kolco24.kolco24.ui.scan`
- [ ] Define `ScanScreen(onClose: () -> Unit, modifier: Modifier = Modifier)` composable
- [ ] Top bar: `Row` with close `IconButton` (Icons.Filled.Close), title "Отметить КП" (bodyLarge),
  disabled text "Готово" (labelLarge, `onSurfaceVariant` color)
- [ ] `CpWaitingCard`: `Surface(shape=large, color=surfaceContainerLowest, border=outlineVariant)` with
  large CP badge (white box, red top/bottom stripes, "?" text, `onSurfaceVariant` color) + text block
  ("КП не отсканирован", "Поднесите телефон к чипу на КП")
- [ ] Section header "Чипы команды" + counter chip (e.g. "3 / 6")
- [ ] `ChipGrid`: 2-column `LazyVerticalGrid` or manual 2-col `Column` of chip slots:
  - filled slot: `CheckCircle` icon + name + "Чип NNN" monospace sub-text
  - waiting slot: dashed circle outline + "Ожидание" + "NFC · scan" label
  - inset dividers between slots (match template's `outlineVariant` grid lines)
- [ ] `HeroTimerCard`: dark `Surface(color=inverseSurface)` card:
  - Circular progress drawn with `Canvas` (or `drawWithContent`) — ring at 87 % (17 s / 20 s),
    amber ring color `Color(0xFFFFC98A)`, dark track `rgba(255,255,255,0.12)`
  - Center: seconds number (28sp monospace) + "сек" label
  - Right: "Сканируйте" overline, "КП и ещё 4 чипа" body, countdown-reset hint text
- [ ] NFC banner (reuse same transparent-row pattern from MarksScreen)
- [ ] Wrap everything in a `LazyColumn` inside a `Box(Modifier.fillMaxSize().background(surface))`
- [ ] Run `./gradlew assembleDebug` — must succeed

---

### Task 8: Wire ScanScreen into MainActivity overlay

**Files:**
- Modify: `app/src/main/java/ru/kolco24/kolco24/MainActivity.kt`

- [ ] Import `ScanScreen`
- [ ] In `Kolco24App`, wrap existing `Scaffold` in a `Box(Modifier.fillMaxSize())`
- [ ] After the scaffold, add `if (showScan) { ScanScreen(onClose = { showScan = false }, modifier = Modifier.fillMaxSize()) }`
- [ ] Run `./gradlew assembleDebug` — full build must succeed

---

### Task 9: Verify acceptance criteria

**Files:** none (verification only)

- [ ] Run `./gradlew assembleDebug` — zero errors, zero warnings in changed files
- [ ] Run `./gradlew lintDebug` — must pass (no new lint errors)
- [ ] Run `./gradlew testDebugUnitTest` — all tests pass (no logic changed, but confirm)
- [ ] Visually verify each screen against template_v2.html reference:
  - NavigationBar: no pill, orange active text/icon
  - Отметки: orange FAB + white "Фото" + transparent NFC banner
  - Легенда: bold dark chips + no trailing icon for open CPs
  - Команда: white "Привязать" button + simplified avatars
  - Scan: top bar, CP card, chip grid, dark timer, NFC banner

---

### Task 10: [Final] Tidy up

- [ ] Update `CLAUDE.md` if new patterns introduced (OrangeCta usage, Scan overlay pattern)
- [ ] Move this plan to `docs/plans/completed/`

## Post-Completion

**Manual visual testing:**
- Install debug APK on device / emulator and swipe through all three tabs
- Tap "Отметить КП" FAB — confirm ScanScreen overlays correctly and close button dismisses it
- Confirm dark hero cards (Team hero, Scan timer) look correct against the light surface
- Test in both light and dark system themes (dark theme uses existing dark scheme, verify it still looks reasonable)
